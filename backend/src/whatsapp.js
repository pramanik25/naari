'use strict';

const fsp = require('fs/promises');
const path = require('path');
const { tx } = require('./db');
const { trackUrl } = require('./incidents');

const GRAPH = 'https://graph.facebook.com';
const MAX_ATTEMPTS = 4; // first try + 3 retries
const EVIDENCE_PER_CONTACT_WINDOW_S = 60;
const EVIDENCE_MAX_PER_CONTACT_PER_INCIDENT = 10;
const WA_IMAGE_MAX_BYTES = 5 * 1024 * 1024; // Cloud API image limit
const MAX_CONTACTS = 5;

/**
 * E.164 normalisation: "+<8-15 digits>" kept; Indian 10-digit numbers (also with a leading 0 or a
 * bare 91 prefix) get +91; "00" international prefix becomes "+". Anything else -> null (dropped).
 */
function normalizeNumber(raw) {
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  let s = String(raw).trim().replace(/[\s().-]/g, '');
  if (s.startsWith('00')) s = `+${s.slice(2)}`;
  if (/^\+[1-9]\d{7,14}$/.test(s)) return s;
  if (/^[1-9]\d{9}$/.test(s)) return `+91${s}`;
  if (/^0[1-9]\d{9}$/.test(s)) return `+91${s.slice(1)}`;
  if (/^91[6-9]\d{9}$/.test(s)) return `+${s}`;
  return null;
}

// ---------- payload builders (pure; exported for tests) ----------
const bodyParams = (...texts) => ({ type: 'body', parameters: texts.map((t) => ({ type: 'text', text: String(t) })) });

function template(name, lang, components) {
  return { type: 'template', template: { name, language: { code: lang }, components } };
}

function sosLocationPayload(wa, ownerName, url, loc) {
  return template(wa.templates.sos, wa.lang, [
    {
      type: 'header',
      parameters: [{
        type: 'location',
        location: {
          latitude: String(loc.lat),
          longitude: String(loc.lng),
          name: `${ownerName} · last known location`.slice(0, 100),
          address: `${loc.lat.toFixed(5)}, ${loc.lng.toFixed(5)}`,
        },
      }],
    },
    bodyParams(ownerName, url),
  ]);
}

const sosTextPayload = (wa, ownerName, url) => template(wa.templates.sosText, wa.lang, [bodyParams(ownerName, url)]);

const evidencePayload = (wa, ownerName, url, mediaId) => template(wa.templates.evidence, wa.lang, [
  { type: 'header', parameters: [{ type: 'image', image: { id: mediaId } }] },
  bodyParams(ownerName, url),
]);

const safePayload = (wa, ownerName) => template(wa.templates.safe, wa.lang, [bodyParams(ownerName)]);

const textPayload = (body) => ({ type: 'text', text: { preview_url: false, body } });

/**
 * WhatsApp Business Cloud API integration. Every hook is a no-op when config.whatsapp is null.
 * Hooks never block API responses: they run in the background (tracked so tests can await idle()).
 */
function createWhatsApp({ config, pool, log, fetchImpl = globalThis.fetch }) {
  const wa = config.whatsapp;
  const pending = new Set();
  let businessNumber = wa && wa.businessNumber ? normalizeNumber(wa.businessNumber) : null;
  let timer = null;
  const sleep = (ms) => new Promise((r) => { setTimeout(r, ms).unref(); });

  function background(fn) {
    if (!wa) return Promise.resolve();
    const p = Promise.resolve().then(fn).catch((err) => log.error({ err }, 'whatsapp task failed'));
    pending.add(p);
    p.finally(() => pending.delete(p));
    return p;
  }

  async function idle() {
    while (pending.size) await Promise.allSettled([...pending]);
  }

  async function graph(pathPart, init = {}) {
    let res;
    try {
      res = await fetchImpl(`${GRAPH}/${wa.apiVersion}/${pathPart}`, {
        ...init,
        headers: { Authorization: `Bearer ${wa.token}`, ...(init.headers || {}) },
        signal: AbortSignal.timeout(20_000),
      });
    } catch (err) {
      const e = new Error(`network: ${err.message}`);
      e.retryable = true;
      throw e;
    }
    let data = null;
    try { data = await res.json(); } catch (_) { /* empty body */ }
    if (!res.ok) {
      const e = new Error(`graph ${res.status}: ${(data && data.error && data.error.message) || 'error'}`);
      e.status = res.status;
      e.retryable = res.status >= 500 || res.status === 429;
      throw e;
    }
    return data;
  }

  async function withRetry(fn) {
    for (let attempt = 1; ; attempt++) {
      try {
        return { result: await fn(), attempts: attempt };
      } catch (err) {
        err.attempts = attempt;
        if (!err.retryable || attempt >= MAX_ATTEMPTS) throw err;
        await sleep(wa.retryBaseMs * 2 ** (attempt - 1));
      }
    }
  }

  /** Sends one message, logging it in whatsapp_messages (logId = an already reserved row). */
  async function send({ to, payload, kind, userId = null, incidentId = null, evidenceId = null, logId = null }) {
    const templateName = payload.type === 'template' ? payload.template.name : null;
    const id = logId || (await pool.query(
      `INSERT INTO whatsapp_messages (user_id, incident_id, evidence_id, to_number, kind, template)
       VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`,
      [userId, incidentId, evidenceId, to, kind, templateName])).rows[0].id;
    try {
      const { result, attempts } = await withRetry(() => graph(`${wa.phoneNumberId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messaging_product: 'whatsapp', recipient_type: 'individual', to: to.replace(/^\+/, ''), ...payload }),
      }));
      const wamid = (result && result.messages && result.messages[0] && result.messages[0].id) || null;
      await pool.query(
        `UPDATE whatsapp_messages SET status = 'sent', attempts = $2, provider_message_id = $3, updated_at = now()
          WHERE id = $1`, [id, attempts, wamid]);
      return true;
    } catch (err) {
      await pool.query(
        `UPDATE whatsapp_messages SET status = 'failed', attempts = $2, error = $3, updated_at = now() WHERE id = $1`,
        [id, err.attempts || 1, String(err.message).slice(0, 500)]);
      log.warn({ kind, err: err.message }, 'whatsapp send failed');
      return false;
    }
  }

  async function uploadMedia(buf, contentType, filename) {
    const { result } = await withRetry(() => {
      const form = new FormData();
      form.append('messaging_product', 'whatsapp');
      form.append('type', contentType);
      form.append('file', new Blob([buf], { type: contentType }), filename);
      return graph(`${wa.phoneNumberId}/media`, { method: 'POST', body: form });
    });
    if (!result || !result.id) throw new Error('media upload returned no id');
    return result.id;
  }

  /** Opted-in numbers of a user who has WhatsApp alerts enabled. */
  async function recipients(db, userId) {
    const { rows } = await db.query(
      `SELECT c.number FROM whatsapp_contacts c JOIN users u ON u.id = c.user_id
        WHERE c.user_id = $1 AND c.opted_in AND u.whatsapp_enabled ORDER BY c.created_at`, [userId]);
    return rows.map((r) => r.number);
  }

  // ---------- SOS ----------
  /** SOS with LOCATION header, once per incident, as soon as a location exists. */
  async function sendSosWithLocation(incidentId) {
    const { rows } = await pool.query(
      `UPDATE incidents i SET wa_sos_location_at = now() FROM users u
        WHERE i.id = $1 AND u.id = i.user_id AND i.wa_sos_location_at IS NULL AND i.last_lat IS NOT NULL
          AND (i.status = 'active' OR i.duress)
        RETURNING i.id, i.user_id, i.track_token, i.last_lat, i.last_lng, u.name`, [incidentId]);
    if (!rows.length) return 0;
    const inc = rows[0];
    const to = await recipients(pool, inc.user_id);
    const payload = sosLocationPayload(wa, inc.name || 'Someone', trackUrl(config, inc.track_token), { lat: inc.last_lat, lng: inc.last_lng });
    await Promise.all(to.map((n) => send({ to: n, payload, kind: 'sos', userId: inc.user_id, incidentId: inc.id })));
    return to.length;
  }

  /** Text-only SOS, once, for live incidents that still have no location after fallbackDelayMs. */
  async function runFallbackSweep() {
    if (!wa) return 0;
    const { rows } = await pool.query(
      `UPDATE incidents i SET wa_sos_text_at = now() FROM users u
        WHERE u.id = i.user_id AND i.wa_sos_text_at IS NULL AND i.wa_sos_location_at IS NULL AND i.last_lat IS NULL
          AND (i.status = 'active' OR i.duress)
          AND i.created_at <= now() - make_interval(secs => $1::double precision / 1000)
          AND i.created_at > now() - interval '6 hours'
          AND u.whatsapp_enabled
        RETURNING i.id, i.user_id, i.track_token, u.name`, [wa.fallbackDelayMs]);
    let sent = 0;
    for (const inc of rows) {
      const to = await recipients(pool, inc.user_id);
      const payload = sosTextPayload(wa, inc.name || 'Someone', trackUrl(config, inc.track_token));
      await Promise.all(to.map((n) => send({ to: n, payload, kind: 'sos_text', userId: inc.user_id, incidentId: inc.id })));
      sent += to.length;
    }
    return sent;
  }

  // ---------- evidence ----------
  async function sendEvidence(incidentId, evidenceId) {
    const { rows: [ev] } = await pool.query(
      `SELECT e.id, e.file_path, e.content_type, e.size, i.user_id, i.track_token, i.status, i.duress, u.name
         FROM evidence e JOIN incidents i ON i.id = e.incident_id JOIN users u ON u.id = i.user_id
        WHERE e.id = $1 AND e.incident_id = $2 AND e.verified AND e.kind = 'photo'`, [evidenceId, incidentId]);
    if (!ev || !(ev.status === 'active' || ev.duress)) return 0;
    if (Number(ev.size) > WA_IMAGE_MAX_BYTES) {
      log.info({ evidenceId }, 'photo too large for WhatsApp; skipped');
      return 0;
    }
    // Reserve log rows under a per-incident lock: <= 1 per contact per 60 s, <= 10 per contact per incident.
    const reserved = await tx(pool, async (c) => {
      await c.query("SELECT pg_advisory_xact_lock(hashtext('wa-evidence:' || $1::text))", [incidentId]);
      const out = [];
      for (const number of await recipients(c, ev.user_id)) {
        const { rows: [s] } = await c.query(
          `SELECT count(*)::int AS total,
                  count(*) FILTER (WHERE created_at > now() - make_interval(secs => $3))::int AS recent
             FROM whatsapp_messages
            WHERE incident_id = $1 AND to_number = $2 AND kind = 'evidence' AND status <> 'failed'`,
          [incidentId, number, EVIDENCE_PER_CONTACT_WINDOW_S]);
        if (s.recent > 0 || s.total >= EVIDENCE_MAX_PER_CONTACT_PER_INCIDENT) continue;
        const { rows: [r] } = await c.query(
          `INSERT INTO whatsapp_messages (user_id, incident_id, evidence_id, to_number, kind, template)
           VALUES ($1, $2, $3, $4, 'evidence', $5) RETURNING id`,
          [ev.user_id, incidentId, ev.id, number, wa.templates.evidence]);
        out.push({ number, logId: r.id });
      }
      return out;
    });
    if (!reserved.length) return 0;
    let mediaId;
    try {
      const buf = await fsp.readFile(path.join(config.evidenceDir, ...ev.file_path.split('/')));
      mediaId = await uploadMedia(buf, ev.content_type, `${ev.id}.jpg`);
    } catch (err) {
      await pool.query(
        `UPDATE whatsapp_messages SET status = 'failed', error = $2, updated_at = now() WHERE id = ANY($1::uuid[])`,
        [reserved.map((r) => r.logId), `media: ${String(err.message).slice(0, 480)}`]);
      log.warn({ err: err.message }, 'whatsapp media upload failed');
      return 0;
    }
    const payload = evidencePayload(wa, ev.name || 'Someone', trackUrl(config, ev.track_token), mediaId);
    await Promise.all(reserved.map((r) => send({
      to: r.number, payload, kind: 'evidence', userId: ev.user_id, incidentId, evidenceId: ev.id, logId: r.logId,
    })));
    return reserved.length;
  }

  // ---------- safe ----------
  async function sendSafe(incidentId) {
    const { rows } = await pool.query(
      `UPDATE incidents i SET wa_safe_at = now() FROM users u
        WHERE i.id = $1 AND u.id = i.user_id AND i.status = 'ended' AND NOT i.duress AND i.wa_safe_at IS NULL
          AND (i.wa_sos_location_at IS NOT NULL OR i.wa_sos_text_at IS NOT NULL)
        RETURNING i.id, i.user_id, u.name`, [incidentId]);
    if (!rows.length) return 0;
    const inc = rows[0];
    const to = await recipients(pool, inc.user_id);
    const payload = safePayload(wa, inc.name || 'Someone');
    await Promise.all(to.map((n) => send({ to: n, payload, kind: 'safe', userId: inc.user_id, incidentId: inc.id })));
    return to.length;
  }

  // ---------- inbound (webhook) ----------
  async function handleInbound(from, text) {
    const number = normalizeNumber(String(from).startsWith('+') ? from : `+${from}`);
    if (!number || typeof text !== 'string') return;
    if (/\bSTOP\b/i.test(text)) {
      await pool.query(
        `UPDATE whatsapp_contacts SET opted_in = false, opted_out_at = now() WHERE number = $1`, [number]);
      await send({ to: number, kind: 'reply', payload: textPayload('Naari Kavach: you will no longer receive SOS alerts. Send JOIN to opt in again.') });
    } else if (/\bJOIN\b/i.test(text)) {
      const { rows } = await pool.query(
        `UPDATE whatsapp_contacts c SET opted_in = true, opted_in_at = now(), opted_out_at = NULL
           FROM users u WHERE c.number = $1 AND u.id = c.user_id
         RETURNING u.name`, [number]);
      const names = rows.map((r) => r.name).filter(Boolean);
      const body = rows.length
        ? `Naari Kavach: you're now an emergency contact${names.length ? ` for ${names.join(', ')}` : ''}. If she sends an SOS you'll get her live location here. In danger? Call 112 first. Reply STOP to opt out.`
        : 'Naari Kavach: this number is not listed as an emergency contact yet. Ask her to add you in the app, then send JOIN again.';
      await send({ to: number, kind: 'reply', payload: textPayload(body) });
    }
  }

  async function handleStatus(wamid, status) {
    if (!['sent', 'delivered', 'read', 'failed'].includes(status)) return;
    await pool.query(
      `UPDATE whatsapp_messages SET status = $2, updated_at = now() WHERE provider_message_id = $1`, [wamid, status]);
  }

  /** Business number for the wa.me join link: env override, else looked up once from the Graph API. */
  async function getBusinessNumber() {
    if (!wa) return null;
    if (businessNumber) return businessNumber;
    try {
      const data = await graph(`${wa.phoneNumberId}?fields=display_phone_number`);
      businessNumber = normalizeNumber((data && data.display_phone_number) || '');
    } catch (err) {
      log.warn({ err: err.message }, 'could not look up WhatsApp business number');
    }
    return businessNumber;
  }

  return {
    enabled: Boolean(wa),
    config: wa,
    idle,
    normalizeNumber,
    getBusinessNumber,
    runFallbackSweep,
    handleInbound: (from, text) => background(() => handleInbound(from, text)),
    handleStatus: (wamid, status) => background(() => handleStatus(wamid, status)),
    /** New incident: SOS now if a location exists; otherwise the fallback timer/sweep sends text-only. */
    onIncidentCreated(incidentId) {
      if (!wa) return;
      background(() => sendSosWithLocation(incidentId));
      const t = setTimeout(() => { background(runFallbackSweep); }, wa.fallbackDelayMs + 250);
      t.unref();
    },
    onLocation: (incidentId) => background(() => sendSosWithLocation(incidentId)),
    onPhotoVerified: (incidentId, evidenceId) => background(() => sendEvidence(incidentId, evidenceId)),
    onIncidentEnded: (incidentId) => background(() => sendSafe(incidentId)),
    /** Backstop for the in-process fallback timers (restarts, other instances). */
    start(intervalMs = 10_000) {
      if (!wa || timer) return;
      timer = setInterval(() => { background(runFallbackSweep); }, intervalMs);
      timer.unref();
    },
    async stop() {
      if (timer) clearInterval(timer);
      timer = null;
      await idle();
    },
  };
}

module.exports = {
  createWhatsApp, normalizeNumber, sosLocationPayload, sosTextPayload, evidencePayload, safePayload, MAX_CONTACTS,
};
