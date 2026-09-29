'use strict';

const { sendError } = require('./util');

const MAX_KEYS = 100_000;

/**
 * In-memory fixed-window limiter (per process). Good enough for a single instance; behind several
 * instances each gets its own budget.
 */
function createRateLimiter({ windowMs, max }) {
  const hits = new Map();
  const sweep = setInterval(() => {
    const now = Date.now();
    for (const [k, v] of hits) if (v.reset <= now) hits.delete(k);
  }, Math.max(windowMs, 10_000));
  sweep.unref();

  function hit(key) {
    const now = Date.now();
    let e = hits.get(key);
    if (!e || e.reset <= now) {
      if (hits.size >= MAX_KEYS) hits.clear(); // memory guard under key-flooding
      e = { count: 0, reset: now + windowMs };
      hits.set(key, e);
    }
    e.count += 1;
    return { ok: e.count <= max, retryAfterS: Math.max(1, Math.ceil((e.reset - now) / 1000)) };
  }

  function middleware(keyFn = (req) => req.ip) {
    return (req, res, next) => {
      const r = hit(String(keyFn(req)));
      if (!r.ok) {
        res.set('Retry-After', String(r.retryAfterS));
        res.set('Cache-Control', 'no-store');
        return sendError(res, 429, 'rate_limited', 'too many requests, slow down');
      }
      return next();
    };
  }

  return { hit, middleware, stop: () => clearInterval(sweep) };
}

module.exports = { createRateLimiter };
