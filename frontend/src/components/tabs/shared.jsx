import { useState, useCallback } from 'react';
import { api } from '../../lib/api';

export const C = {
  bg: '#0a0a0f', card: '#111118', border: '#1e1e2a',
  green: '#00d26a', dim: '#555', text: '#ddd', muted: '#888', red: '#ff4444',
};

export function Card({ title, action, children }) {
  return (
    <div style={{ background: C.card, border: `1px solid ${C.border}`, borderRadius: 7, marginBottom: 14 }}>
      <div style={{ padding: '9px 14px', borderBottom: `1px solid ${C.border}`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <span style={{ fontSize: 11, color: C.muted, textTransform: 'uppercase', letterSpacing: 1, fontWeight: 600 }}>
          {title}
        </span>
        {action && <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>{action}</div>}
      </div>
      <div style={{ padding: 14 }}>{children}</div>
    </div>
  );
}

export function Btn({ label, onClick, variant = 'default', disabled, style: s = {} }) {
  const v = { default: { bg: '#1a1a26', border: C.border, color: C.muted },
               primary: { bg: '#00613a', border: 'transparent', color: '#fff' },
               danger:  { bg: '#2a1010', border: '#55000044', color: C.red } }[variant] || {};
  return (
    <button onClick={onClick} disabled={disabled}
      style={{ padding: '6px 13px', background: disabled ? '#111' : v.bg,
        border: `1px solid ${v.border}`, borderRadius: 5,
        color: disabled ? '#333' : v.color, fontSize: 12,
        cursor: disabled ? 'not-allowed' : 'pointer',
        whiteSpace: 'nowrap', fontFamily: 'inherit', ...s }}>
      {label}
    </button>
  );
}

// CmdBtn: sends command via REST. params are spread into request body so
// backend merges them → device receives {"action":"X", param1: v1, ...}
export function CmdBtn({ deviceId, action, params = {}, label, onDone, variant = 'default' }) {
  const [loading, setLoading] = useState(false);
  const [ok, setOk] = useState(false);

  async function fire() {
    setLoading(true); setOk(false);
    try {
      // Send flat: {action, ...params} — backend strips action and spreads to device
      await api.sendCommand(deviceId, action, params);
      setOk(true);
      onDone?.();
      setTimeout(() => setOk(false), 2000);
    } catch { /* swallow */ }
    finally { setLoading(false); }
  }

  return (
    <Btn label={loading ? '…' : ok ? `✓` : label}
      onClick={fire} disabled={loading} variant={ok ? 'primary' : variant} />
  );
}

export function Table({ headers, rows }) {
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
        <thead>
          <tr>
            {headers.map(h => (
              <th key={h} style={{ textAlign: 'left', padding: '5px 10px',
                color: C.dim, borderBottom: `1px solid ${C.border}`,
                fontWeight: 400, whiteSpace: 'nowrap' }}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} style={{ borderBottom: '1px solid #0d0d12' }}>
              {Object.values(row).map((v, j) => (
                <td key={j} style={{ padding: '6px 10px', color: C.text,
                  verticalAlign: 'top', wordBreak: 'break-word', maxWidth: 260 }}>
                  {v ?? '—'}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Empty({ text = 'No data yet' }) {
  return <div style={{ color: C.dim, fontSize: 12, padding: '10px 0' }}>{text}</div>;
}

export function useData(deviceId, dataType) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async (limit = 200) => {
    setLoading(true);
    try { setData(await api.getData(deviceId, dataType, limit)); }
    catch { setData([]); }
    finally { setLoading(false); }
  }, [deviceId, dataType]);

  return { data, loading, load };
}

// Parse CCS sendResponse("SMS", jsonString) → the data field is a JSON string
export function parseCcsData(rows) {
  return (rows || []).flatMap(row => {
    const d = row.data;
    // If data is wrapped sendResponse format: {type:'SMS', data:'[...]'}
    if (d && typeof d.data === 'string') {
      try { return JSON.parse(d.data); } catch { return [d]; }
    }
    // Direct JSON object
    if (Array.isArray(d)) return d;
    if (d && typeof d === 'object') return [d];
    return [];
  });
}
