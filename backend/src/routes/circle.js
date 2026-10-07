'use strict';

const express = require('express');
const { ah, body, badRequest, isLat, isLng, ms } = require('../util');

/**
 * Family circle: people linked as guardian or ward can see each other's latest position, but
 * only from those who switched sharing on. Sharing is per user and off by default.
 */
function circleRouter({ pool }) {
  const r = express.Router();

  r.put('/circle/me', ah(async (req, res) => {
    const b = body(req);
    if (typeof b.sharing !== 'boolean') throw badRequest('invalid_request', 'sharing must be a boolean');
    if (!b.sharing) {
      await pool.query('DELETE FROM circle_presence WHERE user_id = $1', [req.userId]);
      return res.status(204).end();
    }
    if (!isLat(b.lat) || !isLng(b.lng)) throw badRequest('invalid_location', 'lat/lng out of range');
    let accuracy = null;
    if (b.accuracy !== undefined && b.accuracy !== null) {
      if (typeof b.accuracy !== 'number' || !Number.isFinite(b.accuracy) || b.accuracy < 0) {
        throw badRequest('invalid_location', 'accuracy must be a non-negative number');
      }
      accuracy = b.accuracy;
    }
    let battery = null;
    if (b.battery !== undefined && b.battery !== null) {
      if (!Number.isInteger(b.battery) || b.battery < 0 || b.battery > 100) {
        throw badRequest('invalid_request', 'battery must be an integer 0-100');
      }
      battery = b.battery;
    }
    await pool.query(
      `INSERT INTO circle_presence (user_id, lat, lng, accuracy, battery, updated_at)
       VALUES ($1, $2, $3, $4, $5, now())
       ON CONFLICT (user_id) DO UPDATE SET lat = EXCLUDED.lat, lng = EXCLUDED.lng,
         accuracy = EXCLUDED.accuracy, battery = EXCLUDED.battery, updated_at = now()`,
      [req.userId, b.lat, b.lng, accuracy, battery]);
    return res.status(204).end();
  }));

  r.get('/circle', ah(async (req, res) => {
    const [mine, members] = await Promise.all([
      pool.query('SELECT 1 FROM circle_presence WHERE user_id = $1', [req.userId]),
      pool.query(
        `SELECT u.id, u.name, bool_or(l.is_guardian) AS is_guardian, bool_or(NOT l.is_guardian) AS is_ward,
                p.lat, p.lng, p.accuracy, p.battery, p.updated_at
           FROM (SELECT guardian_id AS other_id, true AS is_guardian FROM guardian_links WHERE ward_id = $1
                 UNION ALL
                 SELECT ward_id, false FROM guardian_links WHERE guardian_id = $1) l
           JOIN users u ON u.id = l.other_id
           LEFT JOIN circle_presence p ON p.user_id = u.id
          GROUP BY u.id, u.name, p.lat, p.lng, p.accuracy, p.battery, p.updated_at
          ORDER BY u.name, u.id`, [req.userId]),
    ]);
    res.json({
      sharing: mine.rows.length > 0,
      members: members.rows.map((m) => ({
        userId: m.id,
        name: m.name,
        // What the other person is to the caller.
        relation: m.is_guardian && m.is_ward ? 'both' : m.is_guardian ? 'guardian' : 'ward',
        location: m.updated_at == null ? null : {
          lat: m.lat, lng: m.lng, accuracy: m.accuracy, battery: m.battery, updatedAt: ms(m.updated_at),
        },
      })),
    });
  }));

  return r;
}

module.exports = { circleRouter };
