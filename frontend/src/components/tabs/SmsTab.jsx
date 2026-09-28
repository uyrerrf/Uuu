import { useEffect } from 'react';
import { Card, CmdBtn, Table, Empty, useData, parseCcsData } from './shared';

export default function SmsTab({ device }) {
  const { data, loading, load } = useData(device.id, 'sms');
  useEffect(() => { load(); }, [device.id]);

  // CCS sends: {"type":"SMS","data":"[{address,body,date,type}]"}
  // parseCcsData handles the unwrapping of the JSON string
  const msgs = parseCcsData(data);
  const rows = msgs.map(m => ({
    '':       m.type === 1 ? '↙ In' : '↗ Out',
    'Number': m.address || '—',
    'Message':String(m.body || '').slice(0, 120),
    'Date':   m.date ? new Date(Number(m.date) || m.date).toLocaleString() : '—',
  }));

  return (
    <Card title={`SMS (${rows.length})`}
      action={
        <>
          <CmdBtn deviceId={device.id} action="GET_SMS" label="📥 Fetch" onDone={load} />
          <button onClick={() => load()} style={{ padding: '5px 10px', background: 'none',
            border: '1px solid #1e1e2a', borderRadius: 5, color: '#555', fontSize: 11,
            cursor: 'pointer' }}>↻</button>
        </>
      }>
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      {!loading && !rows.length && <Empty text="No SMS — click Fetch" />}
      {rows.length > 0 && <Table headers={['', 'Number', 'Message', 'Date']} rows={rows} />}
    </Card>
  );
}
