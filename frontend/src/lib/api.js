const BASE = '/api';
const tok = () => localStorage.getItem('c2_token') || '';

async function req(method, path, body) {
  const r = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tok()}` },
    ...(body != null ? { body: JSON.stringify(body) } : {}),
  });
  if (!r.ok) {
    const e = await r.json().catch(() => ({ error: r.statusText }));
    throw new Error(e.error || r.statusText);
  }
  return r.json();
}

export const api = {
  login:        (u, p)        => fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: p }),
  }).then(r => r.json()),

  getDevices:   ()            => req('GET',   '/devices'),
  getDevice:    (id)          => req('GET',   `/devices/${id}`),
  renameDevice: (id, alias)   => req('PATCH', `/devices/${id}`, { alias }),
  getData:      (id, t, l=100)=> req('GET',   `/devices/${id}/data/${t}?limit=${l}`),

  // Sends {action, ...params} — backend spreads to device as {"action":"X",...params}
  sendCommand:  (id, action, params = {}) =>
    req('POST', `/devices/${id}/cmd`, { action, ...params }),

  getCommands:  (id)          => req('GET',   `/devices/${id}/commands`),
};
