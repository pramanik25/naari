'use strict';

const express = require('express');
const { tx } = require('../db');
const { ApiError, ah, body, badRequest, notFound, isUuid, isMs, ms, isLat, isLng, TRACK_TOKEN_RE } = require('../util');
const {
  trackUrl, guardianIds, userName, insertLocations, fanOutHelpers, endIncident,
} = require('../incidents');
const { notifyAll } = require('../notify');

const SOURCE_RE = /^[a-z0-9_]{1,32}$/;
const MAX_POINTS = 100;

/** Loads an incident owned by userId or throws 404 (never 403: other users' ids look nonexistent). */
async function ownedIncident(db, id, userId, forUpdate = false) {
  if (!isUuid(id)) throw notFound('incident');
  const { rows } = await db.query(
    `SELECT * FROM incidents WHERE id = $1 AND user_id = $2${forUpdate ? ' FOR UPDATE' : ''}`, [id, userId]);
  if (!rows.length) throw notFound('incident');
  return rows[0];
}

const optInt = (v, min, max, field) => {
  if (v === undefined || v === null) return undefined;
  if (!Number.isInteger(v) || v < min || v > max) throw badRequest('invalid_request', `${field} must be an integer ${min}..${max}`);
  return v;
};

const optMessage = (v) => {
  if (v === undefined || v === null) return undefined;
  if (typeof v !== 'string') throw badRequest('invalid_request', 'message must be a string');
  return v.trim().slice(0, 500);
};

/** Validates one location point -> { lat, lng, accuracy, at: Date }. */
function parsePoint(p) {
  if (!p || typeof p !== 'object' || !isLat(p.lat) || !isLng(p.lng)) {
    throw badRequest('invalid_location', 'each point needs numeric lat/lng in range');
  }
  let accuracy = null;
  if (p.accuracy !== undefined && p.accuracy !== null) {
    if (typeof p.accuracy !== 'number' || !Number.isFinite(p.accuracy) || p.accuracy < 0 || p.accuracy > 1e6) {
      throw badRequest('invalid_location', 'accuracy must be a non-negative number');
    }
    accuracy = p.accuracy;
  }
  let at = new Date();
  if (p.at !== undefined && p.at !== null) {
    if (!isMs(p.at)) throw badRequest('invalid_location', 'at must be epoch milliseconds');
    at = new Date(p.at);
  }
  return { lat: p.lat, lng: p.lng, accuracy, at };
}

function incidentsRouter({ pool, config, log, whatsapp }) {
  const r = express.Router();

  r.put('/incidents/:id', ah(async (req, res) => {
    const { id } = req.params;
    if (!isUuid(id)) throw notFound('incident');
    const b = body(req);
    if (b.silent !== undefined && typeof b.silent !== 'boolean') throw badRequest('invalid_request', 'silent must be a boolean');
    if (b.broadcast !== undefined && b.broadcast !== null && typeof b.broadcast !== 'boolean') {
      throw badRequest('invalid_request', 'broadcast must be a boolean');
    }
    const contactsCount = optInt(b.contactsCount, 0, 1000, 'contactsCount');
    const battery = optInt(b.battery, 0, 100, 'battery');
    const message = optMessage(b.message);

    const existing = (await pool.query('SELECT user_id, track_token FROM incidents WHERE id = $1', [id])).rows[0];
    if (existing) {
      if (existing.user_id !== req.userId) throw notFound('incident');
      // token and source are immutable: silently keep the stored values.
      await pool.query(
        `UPDATE incidents SET silent = COALESCE($2, silent), contacts_count = COALESCE($3, contacts_count),
                battery = COALESCE($4, battery), broadcast = COALESCE($5, broadcast),
                message = COALESCE($6, message), updated_at = now()
          WHERE id = $1`,
        [id, b.silent ?? null, contactsCount ?? null, battery ?? null, b.broadcast ?? null, message ?? null]);
      if (b.broadcast === true) {
        // turned on after a location arrived: fan out now (no-op if already done)
        await fanOutHelpers(pool, config, id).catch((err) => log && log.error({ err }, 'helper fan-out failed'));
      }
      return res.json({ id, trackUrl: trackUrl(config, existing.track_token) });
    }

    if (typeof b.token !== 'string' || !TRACK_TOKEN_RE.test(b.token)) {
      throw badRequest('invalid_token', 'token must be 22-64 url-safe characters');
    }
    if (typeof b.source !== 'string' || !SOURCE_RE.test(b.source)) {
      throw badRequest('invalid_source', 'source must match [a-z0-9_]{1,32}');
    }
    if (b.startedAt !== undefined && b.startedAt !== null && !isMs(b.startedAt)) {
      throw badRequest('invalid_request', 'startedAt must be epoch milliseconds');
    }
    const startedAt = b.startedAt ? new Date(b.startedAt) : new Date();

    const created = await tx(pool, async (c) => {
      let ins;
      try {
        ins = await c.query(
          `INSERT INTO incidents (id, user_id, track_token, source, silent, started_at, contacts_count, battery, broadcast, message)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
           ON CONFLICT (id) DO NOTHING
           RETURNING id, track_token`,
          [id, req.userId, b.token, b.source, b.silent ?? false, startedAt, contactsCount ?? 0, battery ?? null,
            b.broadcast ?? true, message ?? '']);
      } catch (err) {
        if (err.code === '23505') throw new ApiError(409, 'token_conflict', 'tracking token already in use');
        throw err;
      }
      if (!ins.rows.length) return null; // lost a race with a concurrent create of the same id
      const ownerName = await userName(c, req.userId);
      const url = trackUrl(config, b.token);
      await notifyAll(c, 'sos', id, await guardianIds(c, req.userId), { incidentId: id, ownerName, trackUrl: url });
      return url;
    });
    if (created) {
      whatsapp.onIncidentCreated(id);
      return res.json({ id, trackUrl: created });
    }
    const again = await ownedIncident(pool, id, req.userId);
    return res.json({ id, trackUrl: trackUrl(config, again.track_token) });
  }));

  r.post('/incidents/:id/locations', ah(async (req, res) => {
    const inc = await ownedIncident(pool, req.params.id, req.userId);
    const pts = body(req).points;
    if (!Array.isArray(pts) || pts.length === 0) throw badRequest('invalid_request', 'points must be a non-empty array');
    if (pts.length > MAX_POINTS) throw badRequest('too_many_points', `at most ${MAX_POINTS} points per call`);
    const points = pts.map(parsePoint);
    await tx(pool, (c) => insertLocations(c, inc.id, points));
    try {
      await fanOutHelpers(pool, config, inc.id);
    } catch (err) {
      if (log) log.error({ err }, 'helper fan-out failed');
    }
    whatsapp.onLocation(inc.id);
    res.status(204).end();
  }));

  r.post('/incidents/:id/battery', ah(async (req, res) => {
    const inc = await ownedIncident(pool, req.params.id, req.userId);
    const battery = optInt(body(req).battery, 0, 100, 'battery');
    if (battery === undefined) throw badRequest('invalid_request', 'battery is required');
    await pool.query('UPDATE incidents SET battery = $2, updated_at = now() WHERE id = $1', [inc.id, battery]);
    res.status(204).end();
  }));

  r.post('/incidents/:id/duress', ah(async (req, res) => {
    const inc = await ownedIncident(pool, req.params.id, req.userId);
    await tx(pool, async (c) => {
      const { rows } = await c.query(
        `UPDATE incidents SET duress = true, duress_at = now(), updated_at = now()
          WHERE id = $1 AND NOT duress RETURNING id, track_token`, [inc.id]);
      if (!rows.length) return; // already under duress: idempotent
      const ownerName = await userName(c, req.userId);
      await notifyAll(c, 'duress', inc.id, await guardianIds(c, req.userId),
        { incidentId: inc.id, ownerName, trackUrl: trackUrl(config, rows[0].track_token) });
    });
    res.status(204).end();
  }));

  r.post('/incidents/:id/end', ah(async (req, res) => {
    const inc = await ownedIncident(pool, req.params.id, req.userId);
    const b = body(req);
    if (b.userInitiated !== undefined && typeof b.userInitiated !== 'boolean') {
      throw badRequest('invalid_request', 'userInitiated must be a boolean');
    }
    await endIncident(pool, inc.id, b.userInitiated ?? true);
    whatsapp.onIncidentEnded(inc.id);
    res.status(204).end();
  }));

  r.get('/incidents', ah(async (req, res) => {
    const { rows } = await pool.query(
      `SELECT i.id, i.source, i.status, i.started_at, i.ended_at, i.track_token,
              (SELECT count(*)::int FROM evidence e WHERE e.incident_id = i.id) AS evidence_count,
              (SELECT count(*)::int FROM evidence e WHERE e.incident_id = i.id AND e.verified) AS verified_count
         FROM incidents i WHERE i.user_id = $1
        ORDER BY i.started_at DESC LIMIT 50`, [req.userId]);
    res.json({
      incidents: rows.map((i) => ({
        id: i.id,
        source: i.source,
        status: i.status,
        startedAt: ms(i.started_at),
        endedAt: ms(i.ended_at),
        evidenceCount: i.evidence_count,
        verifiedCount: i.verified_count,
        trackUrl: trackUrl(config, i.track_token),
      })),
    });
  }));

  return r;
}

module.exports = { incidentsRouter, ownedIncident, parsePoint };
