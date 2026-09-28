'use strict';
const express = require('express');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');

const router = express.Router();

// POST /api/auth/login
// Body: { username, password }
router.post('/login', async (req, res) => {
  const { username, password } = req.body;

  if (!username || !password) {
    return res.status(400).json({ error: 'Username and password required' });
  }

  const adminUser = process.env.ADMIN_USERNAME;
  const adminHash = process.env.ADMIN_PASSWORD_HASH; // bcrypt hash stored in env

  if (username !== adminUser) {
    // Timing-safe: still run bcrypt even on wrong username
    await bcrypt.compare(password, '$2b$12$invalid.hash.for.timing.safety.only');
    return res.status(401).json({ error: 'Invalid credentials' });
  }

  const valid = await bcrypt.compare(password, adminHash);
  if (!valid) {
    return res.status(401).json({ error: 'Invalid credentials' });
  }

  const token = jwt.sign(
    { username, role: 'admin' },
    process.env.JWT_SECRET,
    { expiresIn: '24h' }
  );

  res.json({ token, expiresIn: 86400 });
});

// POST /api/auth/refresh
router.post('/refresh', (req, res) => {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'No token' });

  try {
    const payload = jwt.verify(token, process.env.JWT_SECRET, { ignoreExpiration: true });
    // Only refresh if expired within last hour
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
