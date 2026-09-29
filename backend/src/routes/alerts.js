'use strict';

const express = require('express');
const { tx } = require('../db');
const { ApiError, ah, body, badRequest, notFound, isUuid, isLat, isLng } = require('../util');
const { haversineM } = require('../geo');
const { trackUrl, guardianIds, userName } = require('../incidents');
const { notifyAll } = require('../notify');

function alertsRouter({ pool, config }) {
  const r = express.Router();

  r.post('/alerts/:incidentId/respond', ah(async (req, res) => {
    const { incidentId } = req.params;
    if (!isUuid(incidentId)) throw notFound('alert');
    const b = body(req);
    if (!isLat(b.lat) || !isLng(b.lng)) throw badRequest('invalid_location', 'lat/lng out of range');

    const { rows } = await pool.query(
      `SELECT i.id, i.user_id, i.track_token, i.status, i.duress, i.last_lat, i.last_lng
         FROM incident_helpers h JOIN incidents i ON i.id = h.incident_id
        WHERE h.incident_id = $1 AND h.helper_id = $2`, [incidentId, req.userId]);
    if (!rows.length) throw notFound('alert'); // only helpers who actually got a helper_alert
    const inc = rows[0];
    if (inc.status === 'ended' && !inc.duress) throw new ApiError(409, 'incident_ended', 'she is safe now, thank you');

    const distanceM = Math.round(haversineM(b.lat, b.lng, inc.last_lat, inc.last_lng));
    await tx(pool, async (c) => {
      const upd = await c.query(
        `UPDATE incident_helpers SET responded_at = now(), respond_lat = $3, respond_lng = $4
          WHERE incident_id = $1 AND helper_id = $2 AND responded_at IS NULL`, [inc.id, req.userId, b.lat, b.lng]);
      if (!upd.rowCount) return; // repeated tap: tell the owner only once
      const helperName = await userName(c, req.userId);
      const recipients = [inc.user_id, ...(await guardianIds(c, inc.user_id))].filter((id) => id !== req.userId);
      await notifyAll(c, 'responder', inc.id, recipients, { incidentId: inc.id, helperName, distanceM });
    });
    res.json({ ok: true, distanceM, trackUrl: trackUrl(config, inc.track_token) });
  }));

  return r;
}

module.exports = { alertsRouter };
