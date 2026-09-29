'use strict';

const express = require('express');
const { ApiError, ah, body, badRequest, notFound, isUuid, ms } = require('../util');

const MAX_FAILURES_PER_HOUR = 10;
const CODE_RE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/;

function guardiansRouter({ pool }) {
  const r = express.Router();

  r.post('/guardians/link', ah(async (req, res) => {
    const raw = body(req).code;
    if (typeof raw !== 'string' || raw.length > 64) throw badRequest('invalid_request', 'code must be a string');
    const code = raw.replace(/[\s-]/g, '').toUpperCase();

    const { rows: [{ n }] } = await pool.query(
      `SELECT count(*)::int AS n FROM guardian_link_failures
        WHERE user_id = $1 AND failed_at > now() - interval '1 hour'`, [req.userId]);
    if (n >= MAX_FAILURES_PER_HOUR) {
      res.set('Retry-After', '3600');
      throw new ApiError(429, 'too_many_attempts', 'too many wrong codes, try again later');
    }

    const ward = CODE_RE.test(code)
      ? (await pool.query('SELECT id, name FROM users WHERE guardian_code = $1', [code])).rows[0]
      : undefined;
    if (!ward) {
      await pool.query('INSERT INTO guardian_link_failures (user_id) VALUES ($1)', [req.userId]);
      throw new ApiError(404, 'invalid_code', 'no one has that guardian code');
    }
    if (ward.id === req.userId) throw badRequest('self_link', 'you cannot be your own guardian');

    await pool.query(
      `INSERT INTO guardian_links (ward_id, guardian_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
      [ward.id, req.userId]);
    res.json({ wardId: ward.id, wardName: ward.name });
  }));

  r.get('/guardians', ah(async (req, res) => {
    const [g, w] = await Promise.all([
      pool.query(
        `SELECT u.id, u.name, l.linked_at FROM guardian_links l JOIN users u ON u.id = l.guardian_id
          WHERE l.ward_id = $1 ORDER BY l.linked_at`, [req.userId]),
      pool.query(
        `SELECT u.id, u.name, l.linked_at FROM guardian_links l JOIN users u ON u.id = l.ward_id
          WHERE l.guardian_id = $1 ORDER BY l.linked_at`, [req.userId]),
    ]);
    const shape = (row) => ({ userId: row.id, name: row.name, linkedAt: ms(row.linked_at) });
    res.json({ guardians: g.rows.map(shape), guarding: w.rows.map(shape) });
  }));

  r.delete('/guardians/:otherUserId', ah(async (req, res) => {
    const other = req.params.otherUserId;
    if (!isUuid(other)) throw notFound('link');
    const { rowCount } = await pool.query(
      `DELETE FROM guardian_links
        WHERE (ward_id = $1 AND guardian_id = $2) OR (ward_id = $2 AND guardian_id = $1)`,
      [req.userId, other]);
    if (!rowCount) throw notFound('link');
    res.status(204).end();
  }));

  return r;
}

module.exports = { guardiansRouter, MAX_FAILURES_PER_HOUR };
