'use strict';

const express = require('express');
const { ah, body, badRequest, isLat, isLng } = require('../util');

function helperRouter({ pool }) {
  const r = express.Router();

  r.put('/helper', ah(async (req, res) => {
    const b = body(req);
    if (typeof b.enabled !== 'boolean') throw badRequest('invalid_request', 'enabled must be a boolean');
    if (!b.enabled) {
      await pool.query('DELETE FROM helpers WHERE user_id = $1', [req.userId]);
      return res.status(204).end();
    }
    if (!isLat(b.lat) || !isLng(b.lng)) throw badRequest('invalid_location', 'lat/lng out of range');
    await pool.query(
      `INSERT INTO helpers (user_id, lat, lng, updated_at) VALUES ($1, $2, $3, now())
       ON CONFLICT (user_id) DO UPDATE SET lat = EXCLUDED.lat, lng = EXCLUDED.lng, updated_at = now()`,
      [req.userId, b.lat, b.lng]);
    return res.status(204).end();
  }));

  /** How often this volunteer was alerted and how often she responded (for her badge). */
  r.get('/helper/stats', ah(async (req, res) => {
    const { rows: [s] } = await pool.query(
      `SELECT count(*)::int AS alerted, count(responded_at)::int AS responded
         FROM incident_helpers WHERE helper_id = $1`, [req.userId]);
    res.json({ alerted: s.alerted, responded: s.responded });
  }));

  return r;
}

module.exports = { helperRouter };
