'use strict';

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const { pipeline } = require('stream/promises');
const express = require('express');
const { ApiError, ah, notFound, isUuid, ms, safeEqualHex, TRACK_TOKEN_RE } = require('../util');

const { signEvidence, signedEvidencePath } = require('../signing');

const REDACT_AFTER_END_MS = 24 * 60 * 60 * 1000;
const PUBLIC_DIR = path.join(__dirname, '..', '..', 'public');

const isRedacted = (inc, now = Date.now()) => inc.ended_at != null && now - inc.ended_at.getTime() > REDACT_AFTER_END_MS;

async function incidentByToken(pool, token) {
  if (typeof token !== 'string' || !TRACK_TOKEN_RE.test(token)) return null;
  const { rows } = await pool.query(
    `SELECT i.*, u.name AS owner_name FROM incidents i JOIN users u ON u.id = i.user_id WHERE i.track_token = $1`,
    [token]);
  return rows[0] || null;
}

/** Sends a file with single-range support (206 / 416) so media can seek. */
async function sendFileWithRange(req, res, absPath, contentType) {
  let stat;
  try { stat = await fsp.stat(absPath); } catch (_) { throw notFound('file'); }
  const size = stat.size;
  res.set({
    'Content-Type': contentType,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'private, max-age=600',
    'Content-Disposition': 'inline',
  });
  let start = 0;
  let end = size - 1;
  const range = req.headers.range;
  const m = typeof range === 'string' ? /^bytes=(\d*)-(\d*)$/.exec(range.trim()) : null;
  if (m && (m[1] !== '' || m[2] !== '')) {
    if (m[1] === '') {
      const suffix = Number(m[2]);
      start = Math.max(0, size - suffix);
      if (suffix === 0) start = size; // unsatisfiable
    } else {
      start = Number(m[1]);
      if (m[2] !== '') end = Math.min(Number(m[2]), size - 1);
    }
    if (start >= size || start > end) {
      res.status(416).set('Content-Range', `bytes */${size}`).end();
      return;
    }
    res.status(206).set('Content-Range', `bytes ${start}-${end}/${size}`);
  } else {
    res.status(200);
  }
  res.set('Content-Length', String(size === 0 ? 0 : end - start + 1));
  if (req.method === 'HEAD' || size === 0) { res.end(); return; }
  await pipeline(fs.createReadStream(absPath, { start, end }), res).catch(() => { res.destroy(); });
}

/** Public JSON + evidence routes under /api/v1/track. `limiter` guards the JSON endpoint. */
function trackApiRouter({ pool, config, jsonLimiter, fileLimiter }) {
  const r = express.Router();

  r.get('/:token', jsonLimiter, ah(async (req, res) => {
    res.set('Cache-Control', 'no-store');
    res.set('X-Robots-Tag', 'noindex, nofollow');
    const inc = await incidentByToken(pool, req.params.token);
    if (!inc) throw notFound('tracking link');
    const now = Date.now();
    const redacted = isRedacted(inc, now);

    const [path_, ev, resp] = await Promise.all([
      redacted ? { rows: [] } : pool.query(
        `SELECT lat, lng, at FROM (
           SELECT lat, lng, at, id FROM incident_locations WHERE incident_id = $1 ORDER BY at DESC, id DESC LIMIT 200
         ) p ORDER BY at, id`, [inc.id]),
      redacted ? { rows: [] } : pool.query(
        `SELECT id, kind, content_type, captured_at, verified FROM evidence
          WHERE incident_id = $1 AND verified ORDER BY received_at DESC LIMIT 20`, [inc.id]),
      pool.query(
        `SELECT count(*)::int AS notified, count(responded_at)::int AS responders
           FROM incident_helpers WHERE incident_id = $1`, [inc.id]),
    ]);

    res.json({
      ownerName: inc.owner_name,
      status: inc.status,
      duress: inc.duress,
      source: inc.source,
      startedAt: ms(inc.started_at),
      endedAt: ms(inc.ended_at),
      battery: inc.battery,
      lastLocation: redacted || inc.last_lat == null ? null : {
        lat: inc.last_lat, lng: inc.last_lng, accuracy: inc.last_accuracy, at: ms(inc.last_at),
      },
      path: path_.rows.map((p) => ({ lat: p.lat, lng: p.lng, at: ms(p.at) })),
      evidence: ev.rows.map((e) => ({
        evidenceId: e.id,
        kind: e.kind,
        contentType: e.content_type,
        capturedAt: ms(e.captured_at),
        verified: e.verified,
        url: signedEvidencePath(config.signingSecret, inc.track_token, e.id, now),
      })),
      respondersCount: resp.rows[0].responders,
      helpersNotified: resp.rows[0].notified,
      serverTime: now,
    });
  }));

  r.get('/:token/evidence/:evidenceId', fileLimiter, ah(async (req, res) => {
    const { token, evidenceId } = req.params;
    const exp = String(req.query.exp || '');
    const sig = String(req.query.sig || '').toLowerCase();
    if (!isUuid(evidenceId) || !/^\d{10,16}$/.test(exp) || !/^[0-9a-f]{64}$/.test(sig)) {
      throw new ApiError(403, 'invalid_signature', 'missing or malformed signature');
    }
    if (!safeEqualHex(sig, signEvidence(config.signingSecret, evidenceId.toLowerCase(), exp))) {
      throw new ApiError(403, 'invalid_signature', 'bad signature');
    }
    if (Number(exp) < Date.now()) throw new ApiError(403, 'link_expired', 'evidence link expired, reload the page');

    const inc = await incidentByToken(pool, token);
    if (!inc || isRedacted(inc)) throw notFound('evidence');
    const { rows } = await pool.query(
      'SELECT file_path, content_type FROM evidence WHERE id = $1 AND incident_id = $2 AND verified', [evidenceId, inc.id]);
    if (!rows.length) throw notFound('evidence');
    const abs = path.join(config.evidenceDir, ...rows[0].file_path.split('/'));
    await sendFileWithRange(req, res, abs, rows[0].content_type);
  }));

  return r;
}

/** GET /t/:token -> the static tracking page (it reads the token from its own URL). */
function trackPageRouter({ pool, pageLimiter }) {
  const r = express.Router();
  r.get('/t/:token', pageLimiter, ah(async (req, res) => {
    res.set('Cache-Control', 'no-store');
    res.set('X-Robots-Tag', 'noindex, nofollow');
    const inc = await incidentByToken(pool, req.params.token);
    if (!inc) {
      res.status(404).type('html').send('<!doctype html><html lang="en"><meta charset="utf-8">'
        + '<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">'
        + '<title>Link not found · Naari Kavach</title><link rel="stylesheet" href="/static/track.css">'
        + '<body class="notfound"><main class="card"><p class="eyebrow">Naari Kavach</p>'
        + '<h1>This tracking link is not valid</h1><p class="muted">Check the link in the message you received.'
        + ' If someone is in danger, call <a href="tel:112">112</a>.</p></main></body></html>');
      return;
    }
    res.sendFile(path.join(PUBLIC_DIR, 'track.html'), { headers: { 'Cache-Control': 'no-store' } });
  }));
  return r;
}

module.exports = {
  trackApiRouter, trackPageRouter, signEvidence, signedEvidencePath, sendFileWithRange, PUBLIC_DIR,
  REDACT_AFTER_END_MS,
};
