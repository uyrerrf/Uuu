'use strict';
const express = require('express');
const jwt = require('jsonwebtoken');

const router = express.Router();

// POST /api/auth/login
// Body: { username, password }
router.post('/login', async (req, res) => {
  try {
    const { username, password } = req.body;

    if (!username || !password) {
      return res.status(400).json({ error: 'Username and password required' });
    }

    const adminUser = process.env.ADMIN_USERNAME;
    const adminPass = process.env.ADMIN_PASSWORD;

    if (!adminUser || !adminPass) {
      console.error('[AUTH] ADMIN_USERNAME / ADMIN_PASSWORD not set in env');
      return res.status(500).json({ error: 'Server auth not configured' });
    }

    if (username !== adminUser || password !== adminPass) {
      return res.status(401).json({ error: 'Invalid credentials' });
    }

    const token = jwt.sign(
      { username, role: 'admin' },
      process.env.JWT_SECRET,
      { expiresIn: '24h' }
    );

    res.json({ token, expiresIn: 86400 });
  } catch (err) {
    console.error('[AUTH]', err.message);
    res.status(500).json({ error: 'Login failed' });
  }
});

// POST /api/auth/refresh
router.post('/refresh', (req, res) => {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'No token' });

  try {
    const payload = jwt.verify(token, process.env.JWT_SECRET, { ignoreExpiration: true });
    const now = Math.floor(Date.now() / 1000);
    if (now - payload.exp > 3600) {
      return res.status(401).json({ error: 'Token expired too long ago' });
    }
    const newToken = jwt.sign(
      { username: payload.username, role: payload.role },
      process.env.JWT_SECRET,
      { expiresIn: '24h' }
    );
    res.json({ token: newToken, expiresIn: 86400 });
  } catch {
    res.status(401).json({ error: 'Invalid token' });
  }
});

module.exports = router;
    
