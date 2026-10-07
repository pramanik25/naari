'use strict';

const { ApiError, badRequest } = require('./util');

/** Content disappears for everyone once this many different users reported it. */
const FLAGS_TO_HIDE = 3;

const TABLES = { place: 'place_reports', post: 'community_posts', reply: 'community_replies' };

/**
 * Records one user's report of a piece of content and hides it at FLAGS_TO_HIDE reports.
 * Reporting twice counts once. Returns true when this call hid the content.
 */
async function flag(pool, targetType, targetId, userId) {
  const table = TABLES[targetType];
  await pool.query(
    `INSERT INTO content_flags (target_type, target_id, user_id) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
    [targetType, targetId, userId]);
  const { rowCount } = await pool.query(
    `UPDATE ${table} SET hidden = true
      WHERE id = $2 AND NOT hidden
        AND (SELECT count(*) FROM content_flags WHERE target_type = $1 AND target_id = $2) >= $3`,
    [targetType, targetId, FLAGS_TO_HIDE]);
  return rowCount > 0;
}

/** Throws 429 when the user already wrote `max` rows in `table` during the last 24 hours. */
async function dailyLimit(pool, table, userId, max, what) {
  const { rows: [{ n }] } = await pool.query(
    `SELECT count(*)::int AS n FROM ${table} WHERE user_id = $1 AND created_at > now() - interval '24 hours'`,
    [userId]);
  if (n >= max) throw new ApiError(429, 'daily_limit', `you can add at most ${max} ${what} a day`);
}

const URL_RE = /(https?:\/\/|www\.)\S+|\b[a-z0-9-]+\.(com|in|net|org|me|ly|co|io|app|xyz)\b/i;
// Nine or more digits, allowing the usual separators: a phone number, but not a helpline (112,
// 1091) or a span of years (2019-2020).
const PHONE_RE = /(?:\d[\s().-]?){9,}/;

/**
 * Trimmed public text of 1..max characters. Links and phone numbers are refused: an anonymous
 * space must not become a way to pull someone into a private chat.
 */
function publicText(v, field, max, { required = true } = {}) {
  if (v === undefined || v === null) v = '';
  if (typeof v !== 'string') throw badRequest('invalid_request', `${field} must be a string`);
  const s = v.replace(/\r\n?/g, '\n').replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
  if (!s && required) throw badRequest('invalid_request', `${field} is required`);
  if (s.length > max) throw badRequest('invalid_request', `${field} is too long (max ${max})`);
  if (URL_RE.test(s) || PHONE_RE.test(s)) {
    throw badRequest('contact_info', 'links and phone numbers are not allowed here');
  }
  return s;
}

module.exports = { FLAGS_TO_HIDE, flag, dailyLimit, publicText };
