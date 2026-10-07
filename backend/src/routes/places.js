'use strict';

const express = require('express');
const { ah, body, badRequest, notFound, isUuid, isLat, isLng, ms } = require('../util');
const { haversineM, boundingBox } = require('../geo');
const { flag, dailyLimit, publicText } = require('../moderation');

const CATEGORIES = ['poorly_lit', 'isolated', 'harassment', 'unsafe_transport', 'safe_spot'];
const MAX_REPORTS_PER_DAY = 10;
const DEFAULT_RADIUS_M = 2000;
const MAX_RADIUS_M = 10_000;
const MAX_RESULTS = 200;
/** Reports stop showing after this long: streets change. */
const REPORT_TTL_DAYS = 180;

/** ~11 m grid: precise enough for a street corner, too coarse to single out a doorway. */
const round4 = (v) => Math.round(v * 1e4) / 1e4;

/** Community safety map: anonymous reports about places, hidden once enough users flag them. */
function placesRouter({ pool }) {
  const r = express.Router();

  r.post('/places/reports', ah(async (req, res) => {
    const b = body(req);
    if (!CATEGORIES.includes(b.category)) {
      throw badRequest('invalid_category', `category must be one of ${CATEGORIES.join(', ')}`);
    }
    if (!isLat(b.lat) || !isLng(b.lng)) throw badRequest('invalid_location', 'lat/lng out of range');
    const note = publicText(b.note, 'note', 200, { required: false });
    await dailyLimit(pool, 'place_reports', req.userId, MAX_REPORTS_PER_DAY, 'reports');
    const { rows: [row] } = await pool.query(
      `INSERT INTO place_reports (user_id, category, lat, lng, note) VALUES ($1, $2, $3, $4, $5)
       RETURNING id, created_at`, [req.userId, b.category, round4(b.lat), round4(b.lng), note]);
    res.status(201).json({ id: row.id, createdAt: ms(row.created_at) });
  }));

  r.get('/places/reports', ah(async (req, res) => {
    const lat = Number(req.query.lat);
    const lng = Number(req.query.lng);
    if (!isLat(lat) || !isLng(lng)) throw badRequest('invalid_location', 'lat/lng query parameters are required');
    let radius = DEFAULT_RADIUS_M;
    if (req.query.radius !== undefined) {
      radius = Number(req.query.radius);
      if (!Number.isFinite(radius) || radius < 1 || radius > MAX_RADIUS_M) {
        throw badRequest('invalid_request', `radius must be 1-${MAX_RADIUS_M} metres`);
      }
    }
    const box = boundingBox(lat, lng, radius);
    const lngSql = box.lngRanges.map((_, i) => `lng BETWEEN $${4 + i * 2} AND $${5 + i * 2}`).join(' OR ');
    const { rows } = await pool.query(
      `SELECT id, user_id, category, lat, lng, note, created_at FROM place_reports
        WHERE NOT hidden AND created_at > now() - interval '${REPORT_TTL_DAYS} days'
          AND lat BETWEEN $2 AND $3 AND (${lngSql})
          AND NOT EXISTS (SELECT 1 FROM content_flags f
                           WHERE f.target_type = 'place' AND f.target_id = place_reports.id AND f.user_id = $1)
        ORDER BY created_at DESC LIMIT 2000`,
      [req.userId, box.minLat, box.maxLat, ...box.lngRanges.flat()]);
    const reports = [];
    for (const p of rows) {
      const distance = haversineM(lat, lng, p.lat, p.lng);
      if (distance > radius) continue;
      reports.push({
        id: p.id, category: p.category, lat: p.lat, lng: p.lng, note: p.note,
        createdAt: ms(p.created_at), mine: p.user_id === req.userId, distanceM: Math.round(distance),
      });
      if (reports.length >= MAX_RESULTS) break;
    }
    res.json({ reports });
  }));

  r.delete('/places/reports/:id', ah(async (req, res) => {
    if (!isUuid(req.params.id)) throw notFound('report');
    const { rowCount } = await pool.query(
      'DELETE FROM place_reports WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    if (!rowCount) throw notFound('report');
    res.status(204).end();
  }));

  r.post('/places/reports/:id/flag', ah(async (req, res) => {
    if (!isUuid(req.params.id)) throw notFound('report');
    const { rows } = await pool.query('SELECT user_id FROM place_reports WHERE id = $1', [req.params.id]);
    if (!rows.length) throw notFound('report');
    if (rows[0].user_id === req.userId) throw badRequest('own_content', 'delete your own report instead');
    await flag(pool, 'place', req.params.id, req.userId);
    res.status(204).end();
  }));

  return r;
}

module.exports = { placesRouter, CATEGORIES, MAX_REPORTS_PER_DAY };
