'use strict';

const { ms } = require('./util');

const CHANNEL = 'naari_events';

/**
 * Persists one notification per item and NOTIFYs `naari_events` with each notification id.
 * Call with a transaction client: Postgres delivers the NOTIFY only when that transaction commits,
 * so listeners never see an id whose row is not yet visible.
 *
 * items: [{ recipientId, payload }]
 */
async function notifyEach(client, type, incidentId, items) {
  const seen = new Set();
  const list = [];
  for (const it of items) {
    if (!it.recipientId || seen.has(it.recipientId)) continue;
    seen.add(it.recipientId);
    list.push({ r: it.recipientId, p: it.payload || {} });
  }
  if (!list.length) return [];
  const { rows } = await client.query(
    `WITH ins AS (
       INSERT INTO notifications (recipient_id, incident_id, type, payload)
       SELECT (e->>'r')::uuid, $2::uuid, $3, e->'p' FROM jsonb_array_elements($1::jsonb) AS e
       RETURNING id
     )
     SELECT id, pg_notify($4, id::text) FROM ins`,
    [JSON.stringify(list), incidentId || null, type, CHANNEL],
  );
  return rows.map((r) => r.id);
}

/** Same payload to many recipients. */
function notifyAll(client, type, incidentId, recipientIds, payload) {
  return notifyEach(client, type, incidentId, recipientIds.map((recipientId) => ({ recipientId, payload })));
}

/** DB row -> wire message `{ id, type, at, ...fields }`. */
function toMessage(row) {
  const payload = row.payload || {};
  const msg = { id: row.id, type: row.type, at: ms(row.created_at) };
  for (const [k, v] of Object.entries(payload)) {
    if (!(k in msg)) msg[k] = v;
  }
  return msg;
}

module.exports = { CHANNEL, notifyEach, notifyAll, toMessage };
