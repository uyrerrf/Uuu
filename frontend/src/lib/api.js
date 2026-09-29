const BASE = '/api';
const tok = () => localStorage.getItem('c2_token') || '';

// Parse a response body safely. If the server (or a proxy, or Render's
// cold-start, or the SPA fallback) hands us HTML, never let it explode
// inside r.json() — surface a clean message instead.
async function parseBody(r) {
  const ct = r.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    try { return await r.json(); } catch { return null; }
  }
  const text = await r.text().catch(() => '');
  if (text.trimStart().startsWith('<')) {
    return { error: `Server returned HTML (${r.status}) — API route missing or service restarting` };
  }
  return { error: text.slice(0, 200) || r.statusText };
}

async function req(method, path, body) {
  let r;
  try {
    r = await fetch(`${BASE}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tok()}` },
      ...(body != null ? { body: JSON.stringify(body) } : {}),
    });
  } catch {
    throw new Error('Network error — server unreachable');
  }
  const data = await parseBody(r);
  if (!r.ok) throw new Error(data?.error || r.statusText);
  return data;
}

export const api = {
  login: async (u, p) => {
    let r;
    try {
      r = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: u, password: p }),
      });
    } catch {
      throw new Error('Network error — server unreachable');
    }
    const data = await parseBody(r);
    if (!r.ok) throw new Error(data?.error || r.statusText);
    return data;
  },

  getDevices:   ()            => req('GET',   '/devices'),
  getDevice:    (id)          => req('GET',   `/devices/${id}`),
  renameDevice: (id, alias)   => req('PATCH', `/devices/${id}`, { alias }),
  getData:      (id, t, l=100)=> req('GET',   `/devices/${id}/data/${t}?limit=${l}`),
  sendCommand:  (id, action, params = {}) =>
    req('POST', `/devices/${id}/cmd`, { action, ...params }),
  getCommands:  (id)          => req('GET',   `/devices/${id}/commands`),

  // ── APK Builder ──────────────────────────────────────────────────────────
  builderConfig: ()           => req('GET',   '/builder/config'),
  builderTrigger:(payload)    => req('POST',  '/builder/trigger', payload),
  builderRuns:   (limit = 10) => req('GET',   `/builder/runs?limit=${limit}`),
};
