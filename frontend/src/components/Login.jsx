import { useState } from 'react';

export default function Login({ onLogin }) {
  const [u, setU] = useState('');
  const [p, setP] = useState('');
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setLoading(true); setErr('');
    try { await onLogin(u, p); }
    catch (er) { setErr(er.message || 'Login failed'); }
    finally { setLoading(false); }
  }

  return (
    <div style={{ minHeight: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#0a0a0f', padding: 20 }}>
      <div style={{ width: '100%', maxWidth: 360, background: '#111118', border: '1px solid #1e1e2a', borderRadius: 10, padding: '36px 32px' }}>
        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 14 }}>
          <svg width="52" height="52" viewBox="0 0 64 64" fill="none">
            <circle cx="32" cy="32" r="30" fill="#111118" stroke="#1e1e2a" strokeWidth="2"/>
            <path d="M18 40 Q14 30 22 24 Q26 20 32 20 Q40 20 44 26 Q48 32 44 40 Q40 46 32 46 Q24 46 20 42 Z" fill="#00d26a" opacity="0.9"/>
            <circle cx="26" cy="33" r="2.4" fill="#0a0a0f"/>
            <circle cx="38" cy="33" r="2.4" fill="#0a0a0f"/>
            <path d="M46 22 Q52 16 56 18" stroke="#00d26a" strokeWidth="2.5" strokeLinecap="round" fill="none"/>
            <circle cx="56" cy="18" r="2.5" fill="#00d26a"/>
            <path d="M20 44 Q16 50 12 50 M26 46 Q24 52 20 54" stroke="#00d26a" strokeWidth="2" strokeLinecap="round" fill="none" opacity="0.6"/>
          </svg>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
          <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#00d26a', boxShadow: '0 0 8px #00d26a' }} />
          <span style={{ fontSize: 16, fontWeight: 700, color: '#e0e0e0', letterSpacing: 2, textTransform: 'uppercase' }}>C2 Panel</span>
        </div>
        <div style={{ fontSize: 11, color: '#444', marginBottom: 28, letterSpacing: 1 }}>COMMAND & CONTROL</div>

        {err && <div style={{ fontSize: 12, color: '#ff4444', background: '#1a0f0f', border: '1px solid #3a1515', borderRadius: 5, padding: '8px 12px', marginBottom: 16 }}>{err}</div>}

        <form onSubmit={submit}>
          <label style={{ display: 'block', fontSize: 10, color: '#555', letterSpacing: 1.5, textTransform: 'uppercase', marginBottom: 6 }}>Username</label>
          <input value={u} onChange={e => setU(e.target.value)} autoComplete="username" spellCheck={false}
            style={{ width: '100%', padding: '10px 12px', background: '#0a0a0f', border: '1px solid #1e1e2a', borderRadius: 5, color: '#ddd', fontSize: 14, outline: 'none', marginBottom: 18, transition: 'border-color .15s' }}
            onFocus={e => e.target.style.borderColor = '#00d26a'}
            onBlur={e => e.target.style.borderColor = '#1e1e2a'}
          />
          <label style={{ display: 'block', fontSize: 10, color: '#555', letterSpacing: 1.5, textTransform: 'uppercase', marginBottom: 6 }}>Password</label>
          <input value={p} onChange={e => setP(e.target.value)} type="password" autoComplete="current-password"
            style={{ width: '100%', padding: '10px 12px', background: '#0a0a0f', border: '1px solid #1e1e2a', borderRadius: 5, color: '#ddd', fontSize: 14, outline: 'none', marginBottom: 24, transition: 'border-color .15s' }}
            onFocus={e => e.target.style.borderColor = '#00d26a'}
            onBlur={e => e.target.style.borderColor = '#1e1e2a'}
          />
          <button type="submit" disabled={loading || !u || !p}
            style={{ width: '100%', padding: '11px 0', background: loading ? '#0d3a20' : '#00613a', border: 'none', borderRadius: 5, color: '#fff', fontSize: 13, fontWeight: 600, cursor: loading ? 'wait' : 'pointer', letterSpacing: 1, transition: 'background .15s' }}>
            {loading ? 'Authenticating...' : 'Access Panel'}
          </button>
        </form>
      </div>
    </div>
  );
}
