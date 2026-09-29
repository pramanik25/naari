'use strict';

const crypto = require('crypto');
const { tx } = require('../db');
const { randomToken } = require('../util');
const { trackUrl, guardianIds, userName, fanOutHelpers } = require('../incidents');
const { notifyAll } = require('../notify');

const GRACE_SECONDS = 60;
const MAX_PER_RUN = 200;

function smsText(ownerName, note, url) {
  const who = ownerName || 'Your contact';
  const what = note ? ` (${note.slice(0, 80)})` : '';
  return `Naari Shakti: ${who} missed a safety check-in${what}. Live location: ${url} . If you cannot reach her, call 112.`;
}

/**
 * Escalates active check-ins that are past deadline + 60 s. Each check-in is claimed with
 * FOR UPDATE SKIP LOCKED and flipped to `overdue` in the same transaction that creates the incident,
 * so escalation happens exactly once even with several server instances.
 */
function createCheckinSweeper({ pool, config, sms, whatsapp = null, log, intervalMs = 30_000 }) {
  let timer = null;
  let running = null;

  async function escalateOne() {
    return tx(pool, async (c) => {
      const { rows } = await c.query(
        `SELECT * FROM checkins
          WHERE status = 'active' AND deadline < now() - make_interval(secs => $1)
          ORDER BY deadline LIMIT 1 FOR UPDATE SKIP LOCKED`, [GRACE_SECONDS]);
      if (!rows.length) return null;
      const ck = rows[0];
      const incidentId = crypto.randomUUID();
      const token = randomToken(16);
      const contacts = Array.isArray(ck.contacts) ? ck.contacts : [];
      const hasLocation = ck.last_lat != null;
      await c.query(
        `INSERT INTO incidents (id, user_id, track_token, source, silent, started_at, contacts_count,
                                last_lat, last_lng, last_accuracy, last_at)
         VALUES ($1, $2, $3, 'checkin', false, now(), $4, $5, $6, $7, $8)`,
        [incidentId, ck.user_id, token, Math.min(contacts.length, 1000), ck.last_lat, ck.last_lng,
          ck.last_accuracy, ck.last_at]);
      if (hasLocation) {
        await c.query(
          'INSERT INTO incident_locations (incident_id, lat, lng, accuracy, at) VALUES ($1, $2, $3, $4, $5)',
          [incidentId, ck.last_lat, ck.last_lng, ck.last_accuracy, ck.last_at]);
      }
      await c.query(
        `UPDATE checkins SET status = 'overdue', incident_id = $2, escalated_at = now(), updated_at = now()
          WHERE id = $1`, [ck.id, incidentId]);
      const ownerName = await userName(c, ck.user_id);
      const url = trackUrl(config, token);
      await notifyAll(c, 'checkin_overdue', incidentId, await guardianIds(c, ck.user_id),
        { incidentId, ownerName, note: ck.note, trackUrl: url });
      return { checkinId: ck.id, incidentId, userId: ck.user_id, contacts, ownerName, note: ck.note, url, hasLocation };
    });
  }

  async function sweep() {
    const escalated = [];
    for (let i = 0; i < MAX_PER_RUN; i++) {
      const e = await escalateOne();
      if (!e) break;
      escalated.push({ checkinId: e.checkinId, incidentId: e.incidentId });
      log.warn({ checkinId: e.checkinId, incidentId: e.incidentId }, 'check-in overdue: escalated');
      if (whatsapp) whatsapp.onIncidentCreated(e.incidentId);
      if (e.hasLocation) {
        await fanOutHelpers(pool, config, e.incidentId).catch((err) => log.error({ err }, 'fan-out failed'));
      }
      if (sms && sms.enabled && e.contacts.length) {
        const text = smsText(e.ownerName, e.note, e.url);
        for (const to of e.contacts) {
          // eslint-disable-next-line no-await-in-loop
          await sms.send(to, text).catch((err) => log.error({ err: err.message }, 'overdue sms failed'));
        }
      }
    }
    return escalated;
  }

  /** One sweep; concurrent calls share the in-flight run. Resolves to [{ checkinId, incidentId }]. */
  function runOnce() {
    if (!running) running = sweep().finally(() => { running = null; });
    return running;
  }

  return {
    runOnce,
    start() {
      if (timer) return;
      timer = setInterval(() => { runOnce().catch((err) => log.error({ err }, 'check-in sweep failed')); }, intervalMs);
      timer.unref();
    },
    async stop() {
      if (timer) clearInterval(timer);
      timer = null;
      if (running) await running.catch(() => {});
    },
  };
}

module.exports = { createCheckinSweeper, smsText };
