'use strict';

const { randomToken, sha256Hex, sendError } = require('./util');

const TOKEN_RE = /^[A-Za-z0-9_-]{32,128}$/;

/** New opaque device token (32 random bytes -> 43 url-safe chars). */
const generateDeviceToken = () => randomToken(32);

/** Only this hex digest is ever stored. */
const hashToken = (token) => sha256Hex(token);

function bearerFromHeader(header) {
  if (typeof header !== 'string') return null;
  const m = /^Bearer\s+(\S+)\s*$/i.exec(header);
  return m ? m[1] : null;
}

/**
 * Resolves a raw device token to a user id, or null. Lookup is by the SHA-256 of the token,
 * so the stored value never has to be compared against the secret itself.
 */
async function userIdForToken(pool, token) {
  if (typeof token !== 'string' || !TOKEN_RE.test(token)) return null;
  const { rows } = await pool.query('SELECT user_id FROM device_tokens WHERE token_hash = $1', [hashToken(token)]);
  return rows.length ? rows[0].user_id : null;
}

function requireAuth(pool) {
  return async (req, res, next) => {
    try {
      const userId = await userIdForToken(pool, bearerFromHeader(req.headers.authorization));
      if (!userId) return sendError(res, 401, 'unauthorized', 'missing or invalid device token');
      req.userId = userId;
      return next();
    } catch (err) {
      return next(err);
    }
  };
}

module.exports = { generateDeviceToken, hashToken, bearerFromHeader, userIdForToken, requireAuth };
