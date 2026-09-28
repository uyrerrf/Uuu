function relTime(ts) {
  if (!ts) return 'never';
  const d = Date.now() - new Date(ts);
  if (d < 60000) return 'now';
  if (d < 3600000) return Math.floor(d/60000) + 'm ago';
  if (d < 86400000) return Math.floor(d/3600000) + 'h ago';
  return Math.floor(d/86400000) + 'd ago';
}

function BattIcon({ level }) {
  if (level < 0) return null;
  const col = level < 20 ? '#ff4444' : level < 50 ? '#f90' : '#00d26a';
  return <span style={{ fontSize: 10, color: col, marginLeft: 'auto', flexShrink: 0 }}>{level}%</span>;
}

export default function Sidebar({ devices, selected, onSelect, onLogout, wsStatus, open, onClose }) {
  const online = devices.filter(d => d.online).length;

  const statusColor = { connected: '#00d26a', connecting: '#f90', disconnected: '#555' }[wsStatus] || '#555';

  return (
    <>
      <div style={{
        width: 240, flexShrink: 0,
        background: '#0f0f14', borderRight: '1px solid #1e1e2a',
        display: 'flex', flexDirection: 'column',
        transition: 'transform .2s',
        // Mobile: fixed overlay
        position: 'var(--sb-pos, relative)',
        top: 0, left: 0, bottom: 0,
        zIndex: 50,
        transform: 'var(--sb-transform, none)',
      }} className={`sidebar ${open ? 'open' : ''}`}>

        {/* Header */}
        <div style={{ padding: '16px 14px 12px', borderBottom: '1px solid #1e1e2a', flexShrink: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ width: 7, height: 7, borderRadius: '50%', background: statusColor, boxShadow: wsStatus === 'connected' ? `0 0 6px ${statusColor}` : 'none', flexShrink: 0 }} />
            <span style={{ fontSize: 12, fontWeight: 700, color: '#aaa', letterSpacing: 2, textTransform: 'uppercase' }}>Devices</span>
            <button onClick={onClose} className="hide-desktop" style={{ marginLeft: 'auto', background: 'none', border: 'none', color: '#555', cursor: 'pointer', fontSize: 18, lineHeight: 1 }}>×</button>
          </div>
          <div style={{ fontSize: 11, color: '#444', marginTop: 4 }}>{online} online · {devices.length} total</div>
        </div>

        {/* Device list */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '6px 0' }}>
          {devices.length === 0 && (
            <div style={{ padding: '20px 14px', fontSize: 12, color: '#333' }}>waiting for devices...</div>
          )}
          {devices.map(d => {
            const label = d.alias || d.model || d.id.slice(0, 14);
            const sub = d.alias
              ? (d.model || d.id.slice(0, 10)) + ' · ' + relTime(d.last_seen)
              : (d.manufacturer ? d.manufacturer + ' · ' : '') + relTime(d.last_seen);
            const isSelected = selected === d.id;
            return (
              <div
                key={d.id}
                onClick={() => onSelect(d.id)}
                style={{
                  padding: '9px 14px', cursor: 'pointer',
                  background: isSelected ? '#16162a' : 'transparent',
                  borderLeft: `2px solid ${isSelected ? '#00d26a' : 'transparent'}`,
                  transition: 'background .1s',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                  <div style={{ width: 6, height: 6, borderRadius: '50%', flexShrink: 0, background: d.online ? '#00d26a' : '#333', boxShadow: d.online ? '0 0 5px #00d26a' : 'none' }} />
                  <span style={{ fontSize: 13, color: isSelected ? '#fff' : '#bbb', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{label}</span>
                  <BattIcon level={d.battery_level ?? -1} />
                </div>
                <div style={{ fontSize: 11, color: '#444', marginTop: 2, paddingLeft: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{sub}</div>
              </div>
            );
          })}
        </div>

        {/* Footer */}
        <div style={{ padding: '10px 14px', borderTop: '1px solid #1e1e2a', flexShrink: 0 }}>
          <button onClick={onLogout}
            style={{ width: '100%', padding: '7px 0', background: 'none', border: '1px solid #1e1e2a', borderRadius: 5, color: '#555', fontSize: 11, cursor: 'pointer', letterSpacing: 1 }}>
            SIGN OUT
          </button>
        </div>
      </div>

      <style>{`
        @media (max-width: 768px) {
          .sidebar {
            --sb-pos: fixed !important;
            --sb-transform: translateX(-100%) !important;
          }
          .sidebar.open {
            --sb-transform: translateX(0) !important;
          }
          .hide-desktop { display: block !important; }
        }
        @media (min-width: 769px) {
          .hide-desktop { display: none !important; }
        }
      `}</style>
    </>
  );
}
