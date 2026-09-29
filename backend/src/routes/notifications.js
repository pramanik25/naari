'use strict';

const express = require('express');
const { ah, badRequest, notFound, isUuid } = require('../util');
const { toMessage } = require('../notify');

function notificationsRouter({ pool }) {
  const r = express.Router();

  /** Unacked notifications from the last 24 h (and after `since`, if given), oldest first. */
  r.get('/notifications', ah(async (req, res) => {
    let since = 0;
    if (req.query.since !== undefined && req.query.since !== '') {
      since = Number(req.query.since);
      if (!Number.isFinite(since) || since < 0) throw badRequest('invalid_request', 'since must be epoch milliseconds');
    }
    const { rows } = await pool.query(
      `SELECT id, type, payload, created_at FROM notifications
        WHERE recipient_id = $1 AND acked_at IS NULL
          AND created_at > now() - interval '24 hours'
          AND date_trunc('milliseconds', created_at) > to_timestamp($2::double precision / 1000)
        ORDER BY created_at, id LIMIT 200`, [req.userId, since]);
    res.set('Cache-Control', 'no-store');
    res.json({ notifications: rows.map(toMessage) });
  }));

  /** Extension: HTTP ack for clients that caught up without a socket. */
  r.post('/notifications/:id/ack', ah(async (req, res) => {
    if (!isUuid(req.params.id)) throw notFound('notification');
    const { rowCount } = await pool.query(
      `UPDATE notifications SET acked_at = COALESCE(acked_at, now()) WHERE id = $1 AND recipient_id = $2`,
      [req.params.id, req.userId]);
    if (!rowCount) throw notFound('notification');
    res.status(204).end();
  }));

  return r;
}

module.exports = { notificationsRouter };
