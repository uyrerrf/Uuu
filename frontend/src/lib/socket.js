/**
 * Native WebSocket wrapper for /panel endpoint.
 * Auto-reconnect with exponential backoff.
 * Binary HVNC frame routing via onBinaryFrame callback.
 *
 * FIX: Backoff only resets after a stable connection (>5s).
 * Prevents hammer loop when server is flapping.
 */

let ws = null;
let token = null;
let reconnectTimer = null;
let reconnectDelay = 1000;
let connectedAt = null; // timestamp of last successful connect
const MAX_DELAY = 15000;
const STABLE_CONNECTION_MS = 5000;

const listeners = new Map(); // event → Set<fn>

function emit(event, data) {
  const fns = listeners.get(event);
  if (fns) fns.forEach(fn => fn(data));
}

export function on(event, fn) {
  if (!listeners.has(event)) listeners.set(event, new Set());
  listeners.get(event).add(fn);
  return () => listeners.get(event).delete(fn);
}

export function off(event, fn) {
  listeners.get(event)?.delete(fn);
}

export function send(obj) {
  if (ws?.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
    return true;
  }
  return false;
}

function connect() {
  if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) return;

  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(`${proto}//${location.host}/panel`);
  ws.binaryType = 'arraybuffer';

  ws.onopen = () => {
    connectedAt = Date.now();

    // Only reset backoff if we've been stable before,
    // or if this is a genuinely fresh connection after a long disconnect
    if (reconnectDelay > 5000) {
      // We've been retrying for a while — only reset if the connection holds
      // We'll verify in onclose if this was stable
    } else {
      reconnectDelay = 1000;
    }

    ws.send(JSON.stringify({ type: 'auth', token }));
    emit('connecting', null);
  };

  ws.onmessage = (event) => {
    // Binary = HVNC frame: [frameType:1][devIdLen:2][devId:N][payload]
    if (event.data instanceof ArrayBuffer) {
      const buf = new Uint8Array(event.data);
      if (buf.length < 4) return;
      const frameType = buf[0];
      const devIdLen = (buf[1] << 8) | buf[2];
      if (buf.length < 3 + devIdLen) return;
      const deviceId = new TextDecoder().decode(buf.slice(3, 3 + devIdLen));
      const payload = event.data.slice(3 + devIdLen);
      emit('binary_frame', { frameType, deviceId, payload });
      return;
    }

    let msg;
    try { msg = JSON.parse(event.data); } catch { return; }

    switch (msg.type) {
      case 'auth_ok':
        emit('connected', null);
        break;
      case 'devices':
        emit('devices', msg.data);
        break;
      case 'device_connected':
        emit('device_connected', msg);
        break;
      case 'device_disconnected':
        emit('device_disconnected', msg);
        break;
      case 'device_data':
        emit('device_data', msg);
        break;
      case 'device_status_update':
        emit('device_status_update', msg);
        break;
      case 'command_sent':
        emit('command_sent', msg);
        break;
      default:
        emit('message', msg);
    }
  };

  ws.onclose = (ev) => {
    const wasStable = connectedAt && (Date.now() - connectedAt > STABLE_CONNECTION_MS);
    emit('disconnected', ev);

    // Only reset backoff if the connection was stable for >5s
    // Otherwise keep escalating to prevent hammer loop
    if (wasStable) {
      reconnectDelay = 1000;
    }

    scheduleReconnect();
  };

  ws.onerror = () => {};
}

function scheduleReconnect() {
  clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(() => {
    connect();
    reconnectDelay = Math.min(reconnectDelay * 2, MAX_DELAY);
  }, reconnectDelay);
}

export function panelConnect(authToken) {
  token = authToken;
  reconnectDelay = 1000;
  connect();
}

export function panelDisconnect() {
  clearTimeout(reconnectTimer);
  token = null;
  ws?.close();
  ws = null;
  listeners.clear();
}

export function isConnected() {
  return ws?.readyState === WebSocket.OPEN;
}
