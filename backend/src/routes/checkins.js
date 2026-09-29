'use strict';

const express = require('express');
const { tx } = require('../db');
const { ApiError, ah, body, badRequest, notFound, isUuid, isMs } = require('../util');
const { insertLocations, fanOutHelpers, endIncident } = require('../incidents');
const { parsePoint } = require('./incidents');

const MIN_AHEAD_MS = 60 * 1000;
const MAX_AHEAD_MS = 24 * 60 * 60 * 1000;
const SKEW_MS = 10 * 1000; // tolerate small phone/server clock differences
const MAX_CONTACTS = 10;

function parseDeadline(v) {
  if (!isMs(v)) throw badRequest('invalid_deadline', 'deadline must be epoch milliseconds');
  const ahead = v - Date.now();
  if (ahead < MIN_AHEAD_MS - SKEW_MS || ahead > MAX_AHEAD_MS + SKEW_MS) {
    throw badRequest('invalid_deadline', 'deadline must be between 1 minute and 24 hours from now');
  }
  return new Date(v);
}

function parseContacts(v) {
  if (v === undefined || v === null) return [];
  if (!Array.isArray(v) || v.length > MAX_CONTACTS) throw badRequest('invalid_contacts', `contacts must be an array of at most ${MAX_CONTACTS} phone numbers`);
  return v.map((c) => {
    if (typeof c !== 'string') throw badRequest('invalid_contacts', 'contacts must be phone number strings');
    const n = c.replace(/[\s().-]/g, '');
    if (!/^\+?[0-9]{6,16}$/.test(n)) throw badRequest('invalid_contacts', `invalid phone number: ${c.slice(0, 24)}`);
    return n;
  });
}

function parseNote(v) {
  if (v === undefined || v === null) return '';
  if (typeof v !== 'string') throw badRequest('invalid_request', 'note must be a string');
  const s = v.trim();
  if (s.length > 200) throw badRequest('invalid_request', 'note is too long (max 200)');
  return s;
}

async function ownedCheckin(db, id, userId, forUpdate = false) {
  if (!isUuid(id)) throw notFound('check-in');
  const { rows } = await db.query(
    `SELECT * FROM checkins WHERE id = $1 AND user_id = $2${forUpdate ? ' FOR UPDATE' : ''}`, [id, userId]);
  if (!rows.length) throw notFound('check-in');
  return rows[0];
}

function checkinsRouter({ pool, config, log, whatsapp }) {
  const r = express.Router();

  r.put('/checkins/:id', ah(async (req, res) => {
    const { id } = req.params;
    if (!isUuid(id)) throw notFound('check-in');
    const b = body(req);
    const note = parseNote(b.note);
    const contacts = parseContacts(b.contacts);

    const existing = (await pool.query('SELECT user_id, status FROM checkins WHERE id = $1', [id])).rows[0];
    if (existing) {
      if (existing.user_id !== req.userId) throw notFound('check-in');
      if (existing.status !== 'active') return res.json({ id, status: existing.status });
      const deadline = parseDeadline(b.deadline);
      await pool.query(
        `UPDATE checkins SET deadline = $2, note = $3, contacts = $4::jsonb, updated_at = now()
          WHERE id = $1 AND status = 'active'`, [id, deadline, note, JSON.stringify(contacts)]);
      return res.json({ id, status: 'active' });
    }
    const deadline = parseDeadline(b.deadline);
    const ins = await pool.query(
      `INSERT INTO checkins (id, user_id, deadline, note, contacts) VALUES ($1, $2, $3, $4, $5::jsonb)
       ON CONFLICT (id) DO NOTHING RETURNING status`, [id, req.userId, deadline, note, JSON.stringify(contacts)]);
    if (!ins.rows.length) {
      const again = await ownedCheckin(pool, id, req.userId);
      return res.json({ id, status: again.status });
    }
    return res.json({ id, status: 'active' });
  }));

  r.post('/checkins/:id/location', ah(async (req, res) => {
    const ck = await ownedCheckin(pool, req.params.id, req.userId);
    const p = parsePoint(body(req));
    if (ck.status === 'active' || ck.status === 'overdue') {
      await tx(pool, async (c) => {
        await c.query(
          `UPDATE checkins SET last_lat = $2, last_lng = $3, last_accuracy = $4, last_at = $5, updated_at = now()
            WHERE id = $1 AND (last_at IS NULL OR last_at <= $5)`, [ck.id, p.lat, p.lng, p.accuracy, p.at]);
        // Already escalated: keep feeding the incident so the tracking page stays live.
        if (ck.status === 'overdue' && ck.incident_id) await insertLocations(c, ck.incident_id, [p]);
      });
      if (ck.status === 'overdue' && ck.incident_id) {
        await fanOutHelpers(pool, config, ck.incident_id).catch((err) => log && log.error({ err }, 'fan-out failed'));
        whatsapp.onLocation(ck.incident_id);
      }
    }
    res.status(204).end();
  }));

  r.post('/checkins/:id/extend', ah(async (req, res) => {
    const ck = await ownedCheckin(pool, req.params.id, req.userId);
    const deadline = parseDeadline(body(req).deadline);
    const { rowCount } = await pool.query(
      `UPDATE checkins SET deadline = $2, updated_at = now() WHERE id = $1 AND status = 'active'`, [ck.id, deadline]);
    if (!rowCount) throw new ApiError(409, 'checkin_not_active', `check-in is ${ck.status}`);
    res.status(204).end();
  }));

  /** complete / cancel: idempotent; resolving an overdue check-in also ends its incident. */
  const close = (status) => ah(async (req, res) => {
    const ck = await ownedCheckin(pool, req.params.id, req.userId);
    const { rows } = await pool.query(
      `UPDATE checkins SET status = $2, updated_at = now() WHERE id = $1 AND status IN ('active', 'overdue')
       RETURNING incident_id`, [ck.id, status]);
    if (rows.length && ck.status === 'overdue' && rows[0].incident_id) {
      await endIncident(pool, rows[0].incident_id, true);
      whatsapp.onIncidentEnded(rows[0].incident_id);
    }
    res.status(204).end();
  });
  r.post('/checkins/:id/complete', close('completed'));
  r.post('/checkins/:id/cancel', close('cancelled'));

  return r;
}

module.exports = { checkinsRouter };
