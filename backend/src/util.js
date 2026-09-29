'use strict';

const crypto = require('crypto');

class ApiError extends Error {
  constructor(status, code, message) {
    super(message || code);
    this.status = status;
    this.code = code;
    this.expose = true;
  }
}

const notFound = (what = 'resource') => new ApiError(404, 'not_found', `${what} not found`);
const badRequest = (code, message) => new ApiError(400, code, message);

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const isUuid = (v) => typeof v === 'string' && UUID_RE.test(v);

// Epoch-ms sanity window: 2000-01-01 .. 2100-01-01.
const MIN_MS = 946684800000;
const MAX_MS = 4102444800000;
const isMs = (v) => typeof v === 'number' && Number.isFinite(v) && v >= MIN_MS && v <= MAX_MS;

/** Date|null -> epoch ms|null (the API speaks milliseconds). */
const ms = (d) => (d == null ? null : d instanceof Date ? d.getTime() : new Date(d).getTime());

const isLat = (v) => typeof v === 'number' && Number.isFinite(v) && v >= -90 && v <= 90;
const isLng = (v) => typeof v === 'number' && Number.isFinite(v) && v >= -180 && v <= 180;

/** Returns the parsed JSON object body or {} (never an array / primitive). */
function body(req) {
  const b = req.body;
  return b && typeof b === 'object' && !Array.isArray(b) ? b : {};
}

/** Wraps an async express handler so rejections reach the error middleware. */
const ah = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

function sendError(res, status, code, message) {
  res.status(status).json({ error: code, message: message || code });
}

/** URL-safe random token; 16 bytes -> 22 chars, 32 bytes -> 43 chars. */
const randomToken = (bytes) => crypto.randomBytes(bytes).toString('base64url');

function sha256Hex(data) {
  return crypto.createHash('sha256').update(data).digest('hex');
}

/** Constant-time comparison of two hex strings of equal expected length. */
function safeEqualHex(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  const ba = Buffer.from(a, 'hex');
  const bb = Buffer.from(b, 'hex');
  if (ba.length !== bb.length || ba.length * 2 !== a.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

const TRACK_TOKEN_RE = /^[A-Za-z0-9_-]{22,64}$/;

module.exports = {
  ApiError, notFound, badRequest, isUuid, isMs, ms, isLat, isLng, body, ah, sendError,
  randomToken, sha256Hex, safeEqualHex, TRACK_TOKEN_RE,
};
