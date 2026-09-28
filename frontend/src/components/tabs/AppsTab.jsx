import { useState, useEffect } from 'react';
import { api } from '../../lib/api';
import { Card, CmdBtn, Empty, C, useData } from './shared';

export default function AppsTab({ device }) {
  const { data, loading, load } = useData(device.id, 'apps');
  const [filter, setFilter] = useState('');
  useEffect(() => { load(); }, [device.id]);

  // CCS sends INSTALLED_APPS as direct JSON: {type:'INSTALLED_APPS', apps:[...], total:N}
  const raw = data?.[0]?.data;
  const apps = raw?.apps || (Array.isArray(raw) ? raw : []);
  const filtered = filter
    ? apps.filter(a => `${a.name}${a.package}`.toLowerCase().includes(filter.toLowerCase()))
    : apps;

  async function openApp(pkg) {
    await api.sendCommand(device.id, 'OPEN_APP', { package: pkg }).catch(() => {});
  }
  async function blockApp(pkg) {
    await api.sendCommand(device.id, 'TOGGLE_BLOCK_APP', { package: pkg, blocked: true }).catch(() => {});
  }
  async function dupApp(pkg) {
    await api.sendCommand(device.id, 'DUPLICATE_APP', { package: pkg }).catch(() => {});
  }
  async function delApp(pkg) {
    if (!confirm(`Delete ${pkg}?`)) return;
    await api.sendCommand(device.id, 'DELETE_APP', { package: pkg }).catch(() => {});
  }
  async function stopApp(pkg) {
    await api.sendCommand(device.id, 'FORCE_STOP_APP', { package: pkg }).catch(() => {});
  }

  return (
    <Card title={`Apps (${apps.length}${filter ? ` / ${filtered.length} shown` : ''})`}
      action={<CmdBtn deviceId={device.id} action="GET_INSTALLED_APPS" label="📥 Fetch" onDone={load} />}>
      {apps.length > 0 && (
        <input value={filter} onChange={e => setFilter(e.target.value)} placeholder="Filter by name or package..."
          style={{ width: '100%', padding: '7px 11px', background: C.bg, border: `1px solid ${C.border}`,
            borderRadius: 5, color: C.text, fontSize: 12, outline: 'none', marginBottom: 10 }} />
      )}
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      {!loading && !apps.length && <Empty text="No apps — click Fetch" />}
      <div style={{ fontSize: 12 }}>
        {filtered.map((a, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8,
            padding: '5px 0', borderBottom: '1px solid #0d0d12' }}>
            <span style={{ flex: 1, color: C.text, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {a.name || a.packageName || a.package}
            </span>
            <span style={{ color: C.dim, fontSize: 10, flexShrink: 0, maxWidth: 130,
              overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {a.packageName || a.package}
            </span>
            <button onClick={() => openApp(a.packageName || a.package)}
              title="Open" style={iconBtn(C.green)}>▶</button>
            <button onClick={() => stopApp(a.packageName || a.package)}
              title="Force stop" style={iconBtn('#f90')}>⏹</button>
            <button onClick={() => blockApp(a.packageName || a.package)}
              title="Block app" style={iconBtn('#888')}>🚫</button>
            <button onClick={() => dupApp(a.packageName || a.package)}
              title="Clone app" style={iconBtn('#888')}>⎘</button>
            <button onClick={() => delApp(a.packageName || a.package)}
              title="Uninstall" style={iconBtn(C.red)}>✕</button>
          </div>
        ))}
      </div>
    </Card>
  );
}

const iconBtn = (color) => ({
  background: 'none', border: `1px solid ${color}44`, borderRadius: 4,
  color, fontSize: 11, padding: '2px 7px', cursor: 'pointer', flexShrink: 0,
  fontFamily: 'inherit',
});
