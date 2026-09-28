import { useState, useEffect } from 'react';
import { Card, CmdBtn, Empty, C, useData } from './shared';

export default function AudioTab({ device }) {
  const { data, loading, load } = useData(device.id, 'audio');
  const [active, setActive] = useState(false);
  useEffect(() => { load(30); }, [device.id]);

  async function toggleMic() {
    const next = !active;
    setActive(next);
    // Via CmdBtn inline for simplicity
  }

  function toUrl(b64, mime = 'audio/mp4') {
    const bin = atob(b64), arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    return URL.createObjectURL(new Blob([arr], { type: mime }));
  }

  const recs = (data || []).filter(e => {
    const d = e.data;
    return d?.audio || d?.chunk || d?.data;
  }).reverse();

  function getB64(d) { return d.audio || d.chunk || d.data || ''; }

  return (
    <Card title={`Microphone (${recs.length} chunks)`}
      action={
        <>
          <CmdBtn deviceId={device.id} action="TOGGLE_MICROPHONE" params={{ enabled: true }}  label="🎙 Start" />
          <CmdBtn deviceId={device.id} action="TOGGLE_MICROPHONE" params={{ enabled: false }} label="⏹ Stop" />
          <button onClick={() => load(30)} style={{ padding: '5px 10px', background: 'none',
            border: `1px solid ${C.border}`, borderRadius: 5, color: '#555', fontSize: 11, cursor: 'pointer' }}>↻</button>
        </>
      }>
      <div style={{ fontSize: 11, color: '#444', marginBottom: 10 }}>
        TOGGLE_MICROPHONE streams audio chunks as AUDIO_CHUNK messages. Chunks appear here as they arrive.
      </div>
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      {!loading && !recs.length && <Empty text="No audio — click Start to begin microphone streaming" />}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {recs.map((entry, i) => {
          const b64 = getB64(entry.data);
          const url = b64 ? toUrl(b64) : null;
          return (
            <div key={i} style={{ background: C.bg, border: `1px solid ${C.border}`, borderRadius: 5, padding: '10px 12px' }}>
              <div style={{ fontSize: 11, color: C.dim, marginBottom: 6 }}>
                {new Date(entry.created_at).toLocaleString()}
              </div>
              {url && <audio controls src={url} style={{ width: '100%', height: 32 }} />}
              {url && <a href={url} download={`audio_${i}.m4a`}
                style={{ fontSize: 11, color: C.green, display: 'inline-block', marginTop: 5 }}>↓ Download</a>}
            </div>
          );
        })}
      </div>
    </Card>
  );
}
