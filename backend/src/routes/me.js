'use strict';

const crypto = require('crypto');
const fsp = require('fs/promises');
const path = require('path');
const express = require('express');
const { generateDeviceToken, hashToken, bearerFromHeader } = require('../auth');
const { ApiError, ah, body, badRequest, notFound } = require('../util');
const { tx } = require('../db');
const { normalizeNumber } = require('../whatsapp');

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function randomCode() {
  let s = '';
  for (let i = 0; i < 6; i++) s += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
  return s;
}

function optionalName(v, field) {
  if (v === undefined || v === null) return undefined;
  if (typeof v !== 'string') throw badRequest('invalid_request', `${field} must be a string`);
  const s = v.trim().replace(/\s+/g, ' ');
  if (s.length > 80) throw badRequest('invalid_request', `${field} is too long (max 80)`);
  return s;
}

/** undefined = not sent; null or "" = clear; otherwise the E.164 form, or 400 when it isn't a phone number. */
function optionalPhone(v) {
  if (v === undefined) return undefined;
  if (v === null || v === '') return null;
  const phone = normalizeNumber(v);
  if (!phone) throw badRequest('invalid_phone', 'phone must be a valid phone number');
  return phone;
}

/** Returns { userId, name, phone, guardianCode }, creating the guardian code on first read. */
async function profile(pool, userId) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const { rows } = await pool.query('SELECT id, name, phone, guardian_code FROM users WHERE id = $1', [userId]);
    if (!rows.length) throw notFound('user');
    const u = rows[0];
    if (u.guardian_code) return { userId: u.id, name: u.name, phone: u.phone, guardianCode: u.guardian_code };
    try {
      await pool.query('UPDATE users SET guardian_code = $2 WHERE id = $1 AND guardian_code IS NULL', [userId, randomCode()]);
    } catch (err) {
      if (err.code !== '23505') throw err; // collision with another user's code: try another
    }
  }
  throw new ApiError(503, 'unavailable', 'could not allocate a guardian code, retry');
}

/** POST /api/v1/devices/register (public, rate limited by the caller). */
function registerRouter({ pool }) {
  const r = express.Router();
  r.post('/devices/register', ah(async (req, res) => {
    const b = body(req);
    const deviceName = optionalName(b.deviceName, 'deviceName') ?? null;
    const name = optionalName(b.name, 'name') ?? '';
    const token = generateDeviceToken();
    const userId = await tx(pool, async (c) => {
      const { rows } = await c.query('INSERT INTO users (name) VALUES ($1) RETURNING id', [name]);
      await c.query('INSERT INTO device_tokens (token_hash, user_id, device_name) VALUES ($1, $2, $3)',
        [hashToken(token), rows[0].id, deviceName]);
      return rows[0].id;
    });
    res.status(201).json({ userId, token });
  }));
  return r;
}

function meRouter({ pool, config, hub, log }) {
  const r = express.Router();

  r.get('/me', ah(async (req, res) => {
    res.json(await profile(pool, req.userId));
  }));

  r.patch('/me', ah(async (req, res) => {
    const b = body(req);
    const name = optionalName(b.name, 'name');
    const phone = optionalPhone(b.phone);
    if (name !== undefined) await pool.query('UPDATE users SET name = $2 WHERE id = $1', [req.userId, name]);
    if (phone !== undefined) await pool.query('UPDATE users SET phone = $2 WHERE id = $1', [req.userId, phone]);
    res.json(await profile(pool, req.userId));
  }));

  /**
   * Saves this device's FCM registration token so the server can push alerts while the app is
   * closed; `{ "token": null }` clears it. Per device (keyed by the bearer token's hash), not per
   * user: each phone of an account pushes to its own FCM token.
   */
  r.put('/push-token', ah(async (req, res) => {
    const b = body(req);
    let pushToken = b.token === undefined ? null : b.token;
    if (pushToken !== null) {
      if (typeof pushToken !== 'string' || !pushToken.trim() || pushToken.length > 4096) {
        throw badRequest('invalid_request', 'token must be a non-empty string (max 4096 chars) or null');
      }
      pushToken = pushToken.trim();
    }
    await pool.query(
      'UPDATE device_tokens SET push_token = $2, push_updated_at = now() WHERE token_hash = $1',
      [hashToken(bearerFromHeader(req.headers.authorization)), pushToken]);
    res.status(204).end();
  }));

  r.delete('/me', ah(async (req, res) => {
    const { rowCount } = await pool.query('DELETE FROM users WHERE id = $1', [req.userId]);
    if (!rowCount) throw notFound('user');
    // Rows are gone (ON DELETE CASCADE); now remove the evidence files on disk.
    const dir = path.join(config.evidenceDir, req.userId);
    try {
      await fsp.rm(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
    } catch (err) {
      if (log) log.error({ err }, 'failed to delete evidence directory of deleted user');
    }
    if (hub) hub.disconnectUser(req.userId);
    res.status(204).end();
  }));

  return r;
}

module.exports = { registerRouter, meRouter, profile, CODE_ALPHABET };
