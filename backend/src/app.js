'use strict';

const path = require('path');
const express = require('express');
const helmet = require('helmet');
const compression = require('compression');
const pinoHttp = require('pino-http');
const { requireAuth } = require('./auth');
const { sendError } = require('./util');
const { createRateLimiter } = require('./rateLimit');
const { registerRouter, meRouter } = require('./routes/me');
const { guardiansRouter } = require('./routes/guardians');
const { helperRouter } = require('./routes/helper');
const { incidentsRouter } = require('./routes/incidents');
const { evidenceRouter } = require('./routes/evidence');
const { checkinsRouter } = require('./routes/checkins');
const { alertsRouter } = require('./routes/alerts');
const { notificationsRouter } = require('./routes/notifications');
const { trackApiRouter, trackPageRouter, PUBLIC_DIR } = require('./routes/track');
const { whatsappSettingsRouter, whatsappWebhookRouter } = require('./routes/whatsapp');
const { createWhatsApp } = require('./whatsapp');

const DEFAULT_LIMITS = {
  registerPerHour: 30, // per IP
  trackPerMinute: 60, // per IP, JSON endpoint (contract)
  pagePerMinute: 300, // per IP, /t/ page + signed evidence files (media seeking makes many Range requests)
};

/** Hides secrets that live in URLs (tracking tokens, signatures, ws tokens) from logs. */
function scrubUrl(url) {
  return String(url || '')
    .replace(/\/(t|track)\/[A-Za-z0-9_-]+/g, '/$1/[token]')
    .replace(/([?&](?:sig|token)=)[^&]*/g, '$1[redacted]');
}

/**
 * Builds the express app. deps: { config, pool, log, hub?, whatsapp?, limits? }.
 * Kept free of listen()/timers-with-refs so tests can import it directly.
 */
function createApp({ config, pool, log, hub = null, push = null, whatsapp = null, limits = {} }) {
  const lim = { ...DEFAULT_LIMITS, ...limits };
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', config.trustProxy);
  app.set('etag', false);

  app.use(pinoHttp({
    logger: log,
    genReqId: (req) => req.headers['x-request-id'] || require('crypto').randomUUID(),
    serializers: {
      req: (req) => ({ id: req.id, method: req.method, url: scrubUrl(req.url) }),
      res: (res) => ({ statusCode: res.statusCode }),
    },
    customLogLevel: (req, res, err) => (err || res.statusCode >= 500 ? 'error' : res.statusCode >= 400 ? 'warn' : 'info'),
    autoLogging: { ignore: (req) => req.url === '/healthz' },
  }));

  const httpsOnly = config.publicBaseUrl.startsWith('https://');
  app.use(helmet({
    contentSecurityPolicy: {
      useDefaults: false,
      directives: {
        defaultSrc: ["'self'"],
        baseUri: ["'none'"],
        objectSrc: ["'none'"],
        frameAncestors: ["'none'"],
        formAction: ["'none'"],
        scriptSrc: ["'self'", 'https://unpkg.com', 'https://cdnjs.cloudflare.com'],
        styleSrc: ["'self'", 'https://unpkg.com', 'https://cdnjs.cloudflare.com'],
        imgSrc: ["'self'", 'data:', 'https://server.arcgisonline.com'],
        mediaSrc: ["'self'"],
        connectSrc: ["'self'"],
        fontSrc: ["'self'"],
        workerSrc: ["'none'"],
        ...(httpsOnly ? { upgradeInsecureRequests: [] } : {}),
      },
    },
    // Cross-origin requests (OSM tiles, CDN) get only the origin, never the /t/<token> path.
    referrerPolicy: { policy: 'strict-origin' },
    crossOriginEmbedderPolicy: false,
    strictTransportSecurity: httpsOnly ? { maxAge: 15552000 } : false,
  }));
  app.use(compression());

  const registerLimiter = createRateLimiter({ windowMs: 60 * 60 * 1000, max: lim.registerPerHour });
  const trackLimiter = createRateLimiter({ windowMs: 60 * 1000, max: lim.trackPerMinute });
  const pageLimiter = createRateLimiter({ windowMs: 60 * 1000, max: lim.pagePerMinute });

  // JSON for everything except the raw evidence upload body and the WhatsApp webhook (raw bytes
  // are needed to check X-Hub-Signature-256).
  const json = express.json({ limit: '100kb' });
  const raw = express.raw({ type: () => true, limit: '1mb' });
  app.use((req, res, next) => {
    if (req.method === 'PUT' && /\/evidence\/[^/]+\/?$/.test(req.path)) return next();
    if (req.method === 'POST' && req.path === '/api/v1/whatsapp/webhook') return raw(req, res, next);
    return json(req, res, next);
  });

  const wa = whatsapp || createWhatsApp({ config, pool, log });
  const deps = { config, pool, log, hub, whatsapp: wa };

  app.get('/healthz', async (req, res) => {
    res.set('Cache-Control', 'no-store');
    try {
      await pool.query('SELECT 1');
      // `push` (server only): whether FCM is configured, i.e. alerts reach closed apps.
      res.json(push ? { ok: true, db: true, push: push.enabled } : { ok: true, db: true });
    } catch (err) {
      log.error({ err }, 'health check failed');
      res.status(503).json({ ok: false, db: false });
    }
  });

  app.use('/static', express.static(PUBLIC_DIR, { index: false, maxAge: '1h', fallthrough: false }));
  app.use(trackPageRouter({ ...deps, pageLimiter: pageLimiter.middleware() }));

  // Public API routes (no bearer token).
  app.post('/api/v1/devices/register', registerLimiter.middleware());
  app.use('/api/v1', registerRouter(deps));
  app.use('/api/v1', whatsappWebhookRouter(deps));
  app.use('/api/v1/track', trackApiRouter({
    ...deps, jsonLimiter: trackLimiter.middleware(), fileLimiter: pageLimiter.middleware(),
  }));

  // Everything else under /api/v1 needs a device token.
  const authed = express.Router();
  authed.use(requireAuth(pool));
  for (const make of [meRouter, guardiansRouter, helperRouter, incidentsRouter, evidenceRouter,
    checkinsRouter, alertsRouter, notificationsRouter, whatsappSettingsRouter]) {
    authed.use(make(deps));
  }
  app.use('/api/v1', authed);

  app.use((req, res) => sendError(res, 404, 'not_found', 'no such route'));

  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    if (res.headersSent) { res.destroy(); return; }
    if (err.type === 'entity.parse.failed') return sendError(res, 400, 'invalid_json', 'body is not valid JSON');
    if (err.type === 'entity.too.large') return sendError(res, 413, 'too_large', 'body too large');
    if (err.status === 404 && err.code === 'ENOENT') return sendError(res, 404, 'not_found', 'no such file');
    if (err.expose && err.code && err.status) return sendError(res, err.status, err.code, err.message);
    if (err.expose && err.status && err.status < 500) return sendError(res, err.status, 'bad_request', err.message);
    (req.log || log).error({ err }, 'unhandled error');
    return sendError(res, 500, 'internal', 'internal server error');
  });

  app.locals.limiters = [registerLimiter, trackLimiter, pageLimiter];
  app.locals.whatsapp = wa;
  return app;
}

module.exports = { createApp, scrubUrl, DEFAULT_LIMITS };
