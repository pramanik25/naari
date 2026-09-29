'use strict';

const { tx } = require('./db');
const { haversineM, boundingBox } = require('./geo');
const { notifyAll, notifyEach } = require('./notify');

const { signedEvidenceUrl } = require('./signing');

const HELPER_RADIUS_M = 2000;
const HELPER_OUTER_RADIUS_M = 5000;
const HELPER_MIN_BEFORE_EXPAND = 10;
const HELPER_CAP = 50;

const trackUrl = (config, token) => `${config.publicBaseUrl}/t/${token}`;

async function guardianIds(client, wardId) {
  const { rows } = await client.query('SELECT guardian_id FROM guardian_links WHERE ward_id = $1', [wardId]);
  return rows.map((r) => r.guardian_id);
}

async function userName(client, userId) {
  const { rows } = await client.query('SELECT name FROM users WHERE id = $1', [userId]);
  return rows.length ? rows[0].name : '';
}

/**
 * Inserts location points and moves `last_*` forward to the newest point.
 * points: [{ lat, lng, accuracy|null, at: Date }]
 */
async function insertLocations(client, incidentId, points) {
  if (!points.length) return;
  await client.query(
    `INSERT INTO incident_locations (incident_id, lat, lng, accuracy, at)
     SELECT $1, lat, lng, acc, at
       FROM unnest($2::float8[], $3::float8[], $4::real[], $5::timestamptz[]) AS p(lat, lng, acc, at)`,
    [incidentId, points.map((p) => p.lat), points.map((p) => p.lng), points.map((p) => p.accuracy), points.map((p) => p.at)],
  );
  const newest = points.reduce((a, b) => (b.at >= a.at ? b : a));
  await client.query(
    `UPDATE incidents SET last_lat = $2, last_lng = $3, last_accuracy = $4, last_at = $5, updated_at = now()
      WHERE id = $1 AND (last_at IS NULL OR last_at <= $5)`,
    [incidentId, newest.lat, newest.lng, newest.accuracy, newest.at],
  );
}

/** { evidenceCount (verified), photoId (newest verified photo) | null } */
async function evidenceSummary(client, incidentId) {
  const { rows: [r] } = await client.query(
    `SELECT count(*)::int AS n,
            (SELECT id FROM evidence WHERE incident_id = $1 AND verified AND kind = 'photo'
              ORDER BY received_at DESC LIMIT 1) AS photo_id
       FROM evidence WHERE incident_id = $1 AND verified`, [incidentId]);
  return { evidenceCount: r.n, photoId: r.photo_id };
}

/** Re-run the helper search when she has moved this far since the last search… */
const REFAN_MOVE_M = 250;
/** …but not more often than this… */
const REFAN_MIN_INTERVAL_MS = 60_000;
/** …and in any case this often, to reach helpers who came into range or opted in meanwhile. */
const REFAN_MAX_INTERVAL_MS = 5 * 60_000;

/**
 * Alerts opted-in helpers near the incident's last location: everyone within 2 km, or within 5 km
 * when fewer than 10 were found in 2 km (cap 50 per search, nearest first).
 *
 * Called on every location update. The first search runs on her first location; it repeats when
 * she has moved ≥250 m (at most once a minute) and every 5 minutes regardless, because the first
 * fix is often a stale cached location and she may be moving. A helper is alerted at most once
 * per incident. Skipped entirely when broadcast = false. Excludes the owner, her guardians and
 * helpers whose location is older than 24 h. Returns the number of helpers newly alerted.
 */
async function fanOutHelpers(pool, config, incidentId) {
  return tx(pool, async (c) => {
    const { rows } = await c.query(
      `SELECT id, user_id, track_token, last_lat, last_lng, helpers_fanned_out_at, fanout_lat, fanout_lng, message
         FROM incidents
        WHERE id = $1 AND last_lat IS NOT NULL AND broadcast AND (status = 'active' OR duress)
        FOR UPDATE`,
      [incidentId],
    );
    if (!rows.length) return 0;
    const inc = rows[0];
    if (inc.helpers_fanned_out_at) {
      const age = Date.now() - new Date(inc.helpers_fanned_out_at).getTime();
      const moved = inc.fanout_lat == null
        ? Infinity
        : haversineM(inc.fanout_lat, inc.fanout_lng, inc.last_lat, inc.last_lng);
      const due = (age >= REFAN_MIN_INTERVAL_MS && moved >= REFAN_MOVE_M) || age >= REFAN_MAX_INTERVAL_MS;
      if (!due) return 0;
    }
    await c.query(
      `UPDATE incidents SET helpers_fanned_out_at = now(), fanout_lat = $2, fanout_lng = $3 WHERE id = $1`,
      [inc.id, inc.last_lat, inc.last_lng],
    );
    const box = boundingBox(inc.last_lat, inc.last_lng, HELPER_OUTER_RADIUS_M);
    const lngClause = box.lngRanges.map((_, i) => `(h.lng BETWEEN $${4 + i * 2} AND $${5 + i * 2})`).join(' OR ');
    const params = [box.minLat, box.maxLat, inc.user_id, ...box.lngRanges.flat()];
    const cand = await c.query(
      `SELECT h.user_id, h.lat, h.lng FROM helpers h
        WHERE h.lat BETWEEN $1 AND $2 AND (${lngClause})
          AND h.updated_at > now() - interval '24 hours'
          AND h.user_id <> $3
          AND NOT EXISTS (SELECT 1 FROM guardian_links g WHERE g.ward_id = $3 AND g.guardian_id = h.user_id)
        LIMIT 5000`,
      params,
    );
    const all = cand.rows
      .map((h) => ({ id: h.user_id, d: Math.round(haversineM(inc.last_lat, inc.last_lng, h.lat, h.lng)) }))
      .filter((h) => h.d <= HELPER_OUTER_RADIUS_M)
      .sort((a, b) => a.d - b.d);
    const inner = all.filter((h) => h.d <= HELPER_RADIUS_M);
    const radiusKm = inner.length >= HELPER_MIN_BEFORE_EXPAND ? 2 : 5;
    const found = (radiusKm === 2 ? inner : all).slice(0, HELPER_CAP);
    if (!found.length) return 0;
    // Only helpers not alerted by an earlier search for this incident.
    const ins = await c.query(
      `INSERT INTO incident_helpers (incident_id, helper_id, distance_m, radius_km)
       SELECT $1, h, d, $4 FROM unnest($2::uuid[], $3::int[]) AS x(h, d)
       ON CONFLICT DO NOTHING
       RETURNING helper_id`,
      [inc.id, found.map((h) => h.id), found.map((h) => h.d), radiusKm],
    );
    const fresh = new Set(ins.rows.map((r) => r.helper_id));
    const near = found.filter((h) => fresh.has(h.id));
    if (!near.length) return 0;
    const url = trackUrl(config, inc.track_token);
    const ev = await evidenceSummary(c, inc.id);
    const photoUrl = ev.photoId ? signedEvidenceUrl(config, inc.track_token, ev.photoId) : null;
    const ownerName = await userName(c, inc.user_id);
    await notifyEach(c, 'helper_alert', inc.id, near.map((h) => ({
      recipientId: h.id,
      payload: {
        incidentId: inc.id, lat: inc.last_lat, lng: inc.last_lng, distanceM: h.d, trackUrl: url,
        radiusKm, evidenceCount: ev.evidenceCount, photoUrl,
        ownerName, message: inc.message || '',
      },
    })));
    return near.length;
  });
}

/**
 * After a photo is verified on a live (active or duress) incident: `evidence_update` to alerted
 * helpers and guardians, at most once per 60 s per incident. The throttle lives in
 * incidents.evidence_update_at, so it holds across instances. Returns the number of recipients.
 */
async function notifyEvidenceUpdate(pool, config, incidentId) {
  return tx(pool, async (c) => {
    const { rows } = await c.query(
      `UPDATE incidents SET evidence_update_at = now()
        WHERE id = $1 AND (status = 'active' OR duress)
          AND (evidence_update_at IS NULL OR evidence_update_at <= now() - interval '60 seconds')
        RETURNING id, user_id, track_token`, [incidentId]);
    if (!rows.length) return 0;
    const inc = rows[0];
    const ev = await evidenceSummary(c, inc.id);
    if (!ev.photoId) return 0;
    const helpers = await c.query('SELECT helper_id FROM incident_helpers WHERE incident_id = $1', [inc.id]);
    const recipients = [...(await guardianIds(c, inc.user_id)), ...helpers.rows.map((r) => r.helper_id)]
      .filter((id) => id !== inc.user_id);
    const ids = await notifyAll(c, 'evidence_update', inc.id, recipients, {
      incidentId: inc.id,
      photoUrl: signedEvidenceUrl(config, inc.track_token, ev.photoId),
      evidenceCount: ev.evidenceCount,
      trackUrl: trackUrl(config, inc.track_token),
    });
    return ids.length;
  });
}

/**
 * Ends an active incident once. Guardians get `ended` (with ownerName) and alerted helpers get
 * `ended` (without ownerName), unless the incident is under duress. Returns true if it changed.
 */
async function endIncident(pool, incidentId, userInitiated) {
  return tx(pool, async (c) => {
    const { rows } = await c.query(
      `UPDATE incidents SET status = 'ended', ended_at = now(), user_initiated_end = $2, updated_at = now()
        WHERE id = $1 AND status = 'active'
        RETURNING id, user_id, duress`,
      [incidentId, userInitiated],
    );
    if (!rows.length) return false;
    const inc = rows[0];
    if (inc.duress) return true;
    const ownerName = await userName(c, inc.user_id);
    const guardians = await guardianIds(c, inc.user_id);
    await notifyAll(c, 'ended', inc.id, guardians, { incidentId: inc.id, ownerName });
    const helpers = await c.query('SELECT helper_id FROM incident_helpers WHERE incident_id = $1', [inc.id]);
    const guardianSet = new Set(guardians);
    const helperIds = helpers.rows.map((r) => r.helper_id).filter((id) => !guardianSet.has(id) && id !== inc.user_id);
    await notifyAll(c, 'ended', inc.id, helperIds, { incidentId: inc.id });
    return true;
  });
}

module.exports = {
  HELPER_RADIUS_M, HELPER_OUTER_RADIUS_M, HELPER_CAP, trackUrl, guardianIds, userName, insertLocations,
  fanOutHelpers, endIncident, notifyEvidenceUpdate, evidenceSummary,
};
