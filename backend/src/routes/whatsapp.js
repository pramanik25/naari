'use strict';

const crypto = require('crypto');
const express = require('express');
const { tx } = require('../db');
const { ah, body, badRequest, sendError, safeEqualHex } = require('../util');
const { normalizeNumber, MAX_CONTACTS } = require('../whatsapp');

async function settings(pool, whatsapp, userId) {
  const [{ rows: [u] }, { rows }] = await Promise.all([
    pool.query('SELECT whatsapp_enabled FROM users WHERE id = $1', [userId]),
    pool.query('SELECT name, number, opted_in FROM whatsapp_contacts WHERE user_id = $1 ORDER BY position, created_at', [userId]),
  ]);
  const business = whatsapp.enabled ? await whatsapp.getBusinessNumber() : null;
  return {
    enabled: Boolean(u && u.whatsapp_enabled),
    contacts: rows.map((c) => ({ name: c.name, number: c.number, optedIn: c.opted_in })),
    joinUrl: business ? `https://wa.me/${business.replace(/^\+/, '')}?text=JOIN` : null,
    configured: whatsapp.enabled,
  };
}

/** PUT/GET /api/v1/me/whatsapp (authenticated). */
function whatsappSettingsRouter({ pool, whatsapp }) {
  const r = express.Router();

  r.get('/me/whatsapp', ah(async (req, res) => {
    res.json(await settings(pool, whatsapp, req.userId));
  }));

  r.put('/me/whatsapp', ah(async (req, res) => {
    const b = body(req);
    if (typeof b.enabled !== 'boolean') throw badRequest('invalid_request', 'enabled must be a boolean');
    let contacts = null;
    if (b.enabled && b.contacts !== undefined) {
      if (!Array.isArray(b.contacts)) throw badRequest('invalid_contacts', 'contacts must be an array');
      if (b.contacts.length > MAX_CONTACTS) throw badRequest('too_many_contacts', `at most ${MAX_CONTACTS} contacts`);
      const seen = new Map();
      for (const c of b.contacts) {
        if (!c || typeof c !== 'object') continue;
        const number = normalizeNumber(c.number);
        if (!number || seen.has(number)) continue; // non-E.164 numbers are dropped
        const name = typeof c.name === 'string' ? c.name.trim().slice(0, 60) : '';
        seen.set(number, name);
      }
      contacts = [...seen].map(([number, name]) => ({ number, name }));
    }
    await tx(pool, async (c) => {
      await c.query('UPDATE users SET whatsapp_enabled = $2 WHERE id = $1', [req.userId, b.enabled]);
      if (!b.enabled) {
        await c.query('DELETE FROM whatsapp_contacts WHERE user_id = $1', [req.userId]);
        return;
      }
      if (!contacts) return; // enabled without a list keeps the current contacts
      await c.query('DELETE FROM whatsapp_contacts WHERE user_id = $1 AND NOT (number = ANY($2::text[]))',
        [req.userId, contacts.map((x) => x.number)]);
      // Upsert keeps the opt-in state of numbers that were already listed.
      await c.query(
        `INSERT INTO whatsapp_contacts (user_id, number, name, position)
         SELECT $1, n, nm, pos FROM unnest($2::text[], $3::text[]) WITH ORDINALITY AS x(n, nm, pos)
         ON CONFLICT (user_id, number) DO UPDATE SET name = EXCLUDED.name, position = EXCLUDED.position`,
        [req.userId, contacts.map((x) => x.number), contacts.map((x) => x.name)]);
    });
    res.json(await settings(pool, whatsapp, req.userId));
  }));

  return r;
}

/** Public Meta webhook: GET verification + POST inbound events (raw body for the signature). */
function whatsappWebhookRouter({ whatsapp, log }) {
  const r = express.Router();
  const wa = whatsapp.config;

  r.get('/whatsapp/webhook', (req, res) => {
    const mode = req.query['hub.mode'];
    const token = String(req.query['hub.verify_token'] || '');
    const challenge = req.query['hub.challenge'];
    const expected = wa && wa.verifyToken;
    const ok = expected && mode === 'subscribe' && typeof challenge === 'string'
      && token.length === expected.length && crypto.timingSafeEqual(Buffer.from(token), Buffer.from(expected));
    if (!ok) return sendError(res, 403, 'forbidden', 'verification failed');
    return res.status(200).type('text/plain').send(challenge);
  });

  r.post('/whatsapp/webhook', (req, res) => {
    if (!wa) return sendError(res, 404, 'not_configured', 'WhatsApp is not configured');
    const raw = Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0);
    if (wa.appSecret) {
      const header = String(req.headers['x-hub-signature-256'] || '');
      const given = header.startsWith('sha256=') ? header.slice(7).toLowerCase() : '';
      const expected = crypto.createHmac('sha256', wa.appSecret).update(raw).digest('hex');
      if (!safeEqualHex(given, expected)) return sendError(res, 401, 'invalid_signature', 'bad X-Hub-Signature-256');
    }
    let payload;
    try { payload = JSON.parse(raw.toString('utf8')); } catch (_) { return sendError(res, 400, 'invalid_json', 'body is not valid JSON'); }
    for (const entry of (payload && Array.isArray(payload.entry) ? payload.entry : [])) {
      for (const change of (Array.isArray(entry.changes) ? entry.changes : [])) {
        const value = change && change.value;
        if (!value) continue;
        for (const m of (Array.isArray(value.messages) ? value.messages : [])) {
          const text = m && m.type === 'text' && m.text ? m.text.body
            : m && m.type === 'button' && m.button ? m.button.text : null;
          if (m && m.from && text) whatsapp.handleInbound(String(m.from), String(text).slice(0, 1000));
        }
        for (const s of (Array.isArray(value.statuses) ? value.statuses : [])) {
          if (s && s.id && s.status) whatsapp.handleStatus(String(s.id), String(s.status));
        }
      }
    }
    if (log) log.debug('whatsapp webhook processed');
    // Always acknowledge quickly; processing continues in the background.
    return res.status(200).json({ ok: true });
  });

  return r;
}

module.exports = { whatsappSettingsRouter, whatsappWebhookRouter };
