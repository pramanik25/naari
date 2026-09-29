'use strict';

const crypto = require('crypto');
const fsp = require('fs/promises');
const path = require('path');
const express = require('express');
const { generateDeviceToken, hashToken } = require('../auth');
const { ApiError, ah, body, badRequest, notFound } = require('../util');
const { tx } = require('../db');

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

/** Returns { userId, name, guardianCode }, creating the guardian code on first read. */
async function profile(pool, userId) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const { rows } = await pool.query('SELECT id, name, guardian_code FROM users WHERE id = $1', [userId]);
    if (!rows.length) throw notFound('user');
    const u = rows[0];
    if (u.guardian_code) return { userId: u.id, name: u.name, guardianCode: u.guardian_code };
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
    const name = optionalName(body(req).name, 'name');
    if (name !== undefined) await pool.query('UPDATE users SET name = $2 WHERE id = $1', [req.userId, name]);
    res.json(await profile(pool, req.userId));
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
