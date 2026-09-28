import { useEffect } from 'react';
import { Card, CmdBtn, C, useData, parseCcsData } from './shared';

export default function LocationTab({ device }) {
  const { data, loading, load } = useData(device.id, 'location');
  useEffect(() => { load(50); }, [device.id]);

  // CCS sends: {"type":"LOCATION","data":"{lat:...,lng:...,accuracy:...}"}
  const locs = parseCcsData(data);
  const loc  = locs[0];
  const lat  = loc?.lat ?? loc?.latitude;
  const lng  = loc?.lng ?? loc?.longitude;
  const mapUrl = lat && lng
    ? `https://www.openstreetmap.org/export/embed.html?bbox=${lng-.01},${lat-.01},${lng+.01},${lat+.01}&layer=mapnik&marker=${lat},${lng}`
    : null;

  return (
    <Card title="GPS Location"
      action={<CmdBtn deviceId={device.id} action="GET_LOCATION" label="📍 Get Now" onDone={load} />}>
      {!loc && !loading && <div style={{ color: C.dim, fontSize: 12 }}>No location — click Get Now</div>}
      {loading && <div style={{ color: '#444', fontSize: 12 }}>loading…</div>}
      {loc && (
        <>
          <div style={{ display: 'flex', gap: 20, marginBottom: 14, fontSize: 12, flexWrap: 'wrap' }}>
            <span style={{ color: C.dim }}>Lat: <span style={{ color: C.text }}>{lat?.toFixed(6)}</span></span>
            <span style={{ color: C.dim }}>Lng: <span style={{ color: C.text }}>{lng?.toFixed(6)}</span></span>
            {loc.accuracy && <span style={{ color: C.dim }}>±<span style={{ color: C.text }}>{loc.accuracy}m</span></span>}
            {loc.provider && <span style={{ color: C.dim }}>via <span style={{ color: C.text }}>{loc.provider}</span></span>}
          </div>
          {mapUrl && <iframe src={mapUrl} width="100%" height="260"
            style={{ border: `1px solid ${C.border}`, borderRadius: 5, display: 'block' }} title="map" />}
          <a href={`https://www.google.com/maps?q=${lat},${lng}`} target="_blank" rel="noreferrer"
            style={{ display: 'inline-block', marginTop: 8, fontSize: 12, color: C.green }}>
            Open in Google Maps ↗
          </a>
        </>
      )}
    </Card>
  );
}
