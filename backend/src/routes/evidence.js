'use strict';

const crypto = require('crypto');
const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const express = require('express');
const { tx } = require('../db');
const { ApiError, ah, badRequest, notFound, isUuid, isMs, ms } = require('../util');
const { ownedIncident } = require('./incidents');
const { notifyEvidenceUpdate } = require('../incidents');

const EXT = { 'image/jpeg': 'jpg', 'audio/mp4': 'm4a', 'video/mp4': 'mp4' };
const KINDS = new Set(['photo', 'audio', 'video']);
const SHA_RE = /^[0-9a-f]{64}$/;

const tooLarge = (max) => new ApiError(413, 'too_large', `evidence exceeds ${max} bytes`);

/**
 * Streams the request body to `tmp` while hashing it. Resolves { size, sha256 } only when the whole
 * body arrived; rejects (and leaves cleanup to the caller) on abort, write error or size overflow.
 */
function receiveBody(req, tmp, maxBytes) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const out = fs.createWriteStream(tmp, { flags: 'wx' });
    let size = 0;
    let settled = false;
    const fail = (err) => {
      if (settled) return;
      settled = true;
      req.pause();
      out.destroy();
      out.once('close', () => reject(err));
    };
    req.on('data', (chunk) => {
      if (settled) return;
      size += chunk.length;
      if (size > maxBytes) return fail(tooLarge(maxBytes));
      hash.update(chunk);
      if (!out.write(chunk)) {
        req.pause();
        out.once('drain', () => { if (!settled) req.resume(); });
      }
      return undefined;
    });
    req.on('end', () => {
      if (settled) return;
      const declared = req.headers['content-length'];
      if (declared !== undefined && Number(declared) !== size) {
        fail(new ApiError(400, 'incomplete_upload', 'body shorter than Content-Length'));
        return;
      }
      out.end();
    });
    req.on('close', () => { if (!req.complete) fail(new ApiError(400, 'incomplete_upload', 'upload aborted')); });
    req.on('error', fail);
    out.on('error', fail);
    out.on('close', () => {
      if (settled) return;
      settled = true;
      resolve({ size, sha256: hash.digest('hex') });
    });
  });
}

const receipt = (row) => ({
  evidenceId: row.id,
  serverSha256: row.server_sha256,
  verified: row.verified,
  receivedAt: ms(row.received_at),
});

function evidenceRouter({ pool, config, log, whatsapp }) {
  const r = express.Router();

  /** Same id: same claimed hash -> 200 with the stored record; otherwise 409. */
  function replay(res, existing, incidentId, claimed) {
    if (existing.incident_id === incidentId && existing.sha256 === claimed) return res.status(200).json(receipt(existing));
    throw new ApiError(409, 'evidence_exists', 'evidence id already used with different content');
  }

  r.put('/incidents/:id/evidence/:evidenceId', ah(async (req, res) => {
    const { id } = req.params;
    const evidenceId = String(req.params.evidenceId).toLowerCase();
    if (!isUuid(id) || !isUuid(evidenceId)) throw notFound('evidence');

    const contentType = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
    if (!EXT[contentType]) {
      throw new ApiError(415, 'unsupported_media_type', 'Content-Type must be image/jpeg, audio/mp4 or video/mp4');
    }
    const kind = String(req.headers['x-evidence-kind'] || '').trim().toLowerCase();
    if (!KINDS.has(kind)) throw badRequest('invalid_kind', 'X-Evidence-Kind must be photo, audio or video');
    const claimed = String(req.headers['x-sha256'] || '').trim().toLowerCase();
    if (!SHA_RE.test(claimed)) throw badRequest('invalid_sha256', 'X-Sha256 must be 64 hex chars');
    let capturedAt = new Date();
    const capHeader = req.headers['x-captured-at'];
    if (capHeader !== undefined && capHeader !== '') {
      const n = Number(capHeader);
      if (!isMs(n)) throw badRequest('invalid_captured_at', 'X-Captured-At must be epoch milliseconds');
      capturedAt = new Date(n);
    }

    const inc = await ownedIncident(pool, id, req.userId);

    const prior = (await pool.query('SELECT * FROM evidence WHERE id = $1', [evidenceId])).rows[0];
    if (prior) return replay(res, prior, inc.id, claimed);

    const declared = req.headers['content-length'];
    if (declared !== undefined && Number(declared) > config.maxEvidenceBytes) {
      res.set('Connection', 'close');
      throw tooLarge(config.maxEvidenceBytes);
    }

    const tmpDir = path.join(config.evidenceDir, '.tmp');
    await fsp.mkdir(tmpDir, { recursive: true });
    const tmp = path.join(tmpDir, `${evidenceId}.${crypto.randomBytes(6).toString('hex')}.part`);
    let received;
    try {
      received = await receiveBody(req, tmp, config.maxEvidenceBytes);
    } catch (err) {
      await fsp.rm(tmp, { force: true }).catch(() => {});
      if (err.status === 413) res.set('Connection', 'close');
      throw err;
    }

    const rel = `${req.userId}/${inc.id}/${evidenceId}.${EXT[contentType]}`;
    const finalPath = path.join(config.evidenceDir, ...rel.split('/'));
    const verified = received.sha256 === claimed;
    let row;
    try {
      row = await tx(pool, async (c) => {
        const ins = await c.query(
          `INSERT INTO evidence (id, incident_id, user_id, kind, content_type, size, sha256, server_sha256,
                                 verified, captured_at, file_path)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
           ON CONFLICT (id) DO NOTHING RETURNING *`,
          [evidenceId, inc.id, req.userId, kind, contentType, received.size, claimed, received.sha256,
            verified, capturedAt, rel]);
        if (!ins.rows.length) return null;
        // Files are never overwritten: the row lock on this id serialises concurrent uploads.
        await fsp.mkdir(path.dirname(finalPath), { recursive: true });
        if (fs.existsSync(finalPath)) throw new Error(`evidence file already exists: ${rel}`);
        await fsp.rename(tmp, finalPath);
        return ins.rows[0];
      });
    } finally {
      await fsp.rm(tmp, { force: true }).catch(() => {});
    }
    if (!row) {
      const existing = (await pool.query('SELECT * FROM evidence WHERE id = $1', [evidenceId])).rows[0];
      return replay(res, existing, inc.id, claimed);
    }
    if (!verified && log) log.warn({ evidenceId }, 'evidence hash mismatch');
    if (verified && kind === 'photo') {
      await notifyEvidenceUpdate(pool, config, inc.id).catch((err) => log && log.error({ err }, 'evidence_update failed'));
      whatsapp.onPhotoVerified(inc.id, row.id);
    }
    return res.status(201).json(receipt(row));
  }));

  r.get('/incidents/:id/evidence', ah(async (req, res) => {
    const inc = await ownedIncident(pool, req.params.id, req.userId);
    const { rows } = await pool.query(
      'SELECT * FROM evidence WHERE incident_id = $1 ORDER BY captured_at, received_at', [inc.id]);
    res.json({
      evidence: rows.map((e) => ({
        evidenceId: e.id,
        kind: e.kind,
        size: Number(e.size),
        sha256: e.sha256,
        serverSha256: e.server_sha256,
        verified: e.verified,
        capturedAt: ms(e.captured_at),
        receivedAt: ms(e.received_at),
      })),
    });
  }));

  return r;
}

module.exports = { evidenceRouter };
