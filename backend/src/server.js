'use strict';

const http = require('http');
const fsp = require('fs/promises');
const pino = require('pino');
const { loadConfig } = require('./config');
const { createPool, createListener } = require('./db');
const { migrate } = require('./migrate');
const { createApp } = require('./app');
const { createHub } = require('./realtime');
const { createSms } = require('./sms');
const { createWhatsApp } = require('./whatsapp');
const { CHANNEL } = require('./notify');
const { createCheckinSweeper } = require('./jobs/checkinSweeper');

/**
 * Boots everything: migrations, HTTP + WebSocket, LISTEN client, check-in sweeper.
 * opts (tests): { config, log, fetchImpl, whatsappFetch, limits, sweeper: false, host }
 * Resolves to { server, port, app, pool, config, hub, sweeper, close }.
 */
async function start(opts = {}) {
  const log = opts.log || pino({ level: process.env.LOG_LEVEL || 'info' });
  const config = opts.config || loadConfig(process.env, log);
  const pool = createPool(config.databaseUrl, log);
  try {
    await migrate(pool, log);
    await fsp.mkdir(config.evidenceDir, { recursive: true });
  } catch (err) {
    await pool.end().catch(() => {});
    throw err;
  }

  const hub = createHub({ pool, log });
  const sms = createSms({ config, log, fetchImpl: opts.fetchImpl });
  const whatsapp = createWhatsApp({ config, pool, log, fetchImpl: opts.whatsappFetch || globalThis.fetch });
  const app = createApp({ config, pool, log, hub, whatsapp, limits: opts.limits });
  const server = http.createServer(app);
  server.requestTimeout = 30 * 60 * 1000; // 50 MB evidence over a slow mobile link
  server.headersTimeout = 60 * 1000;
  server.keepAliveTimeout = 65 * 1000; // longer than typical proxy idle timeouts
  hub.attach(server);

  const listener = createListener(config.databaseUrl, CHANNEL, (id) => { hub.onNotify(id); }, log);
  const sweeper = createCheckinSweeper({ pool, config, sms, whatsapp, log, intervalMs: config.sweepIntervalMs });
  if (opts.sweeper !== false) {
    sweeper.start();
    whatsapp.start();
  }

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.port, opts.host, resolve);
  });
  await Promise.race([listener.ready, new Promise((r) => setTimeout(r, 5000).unref())]);
  const { port } = server.address();
  log.info({ port, publicBaseUrl: config.publicBaseUrl }, 'naari api listening');

  let closing = null;
  function close() {
    if (closing) return closing;
    closing = (async () => {
      await sweeper.stop();
      await whatsapp.stop();
      hub.close();
      await new Promise((r) => {
        server.close(() => r());
        server.closeIdleConnections();
      });
      await listener.close();
      for (const l of app.locals.limiters) l.stop();
      await pool.end();
    })();
    return closing;
  }

  return { server, port, app, pool, config, hub, sweeper, whatsapp, close, log };
}

module.exports = { start };

if (require.main === module) {
  require('dotenv').config({ quiet: true });
  const log = pino({ level: process.env.LOG_LEVEL || 'info' });
  let config;
  try {
    config = loadConfig(process.env, log);
  } catch (err) {
    log.fatal(err.message);
    process.exit(1);
  }
  start({ config, log }).then((ctx) => {
    const shutdown = (signal) => {
      log.info({ signal }, 'shutting down');
      setTimeout(() => process.exit(1), 10_000).unref();
      ctx.close().then(() => process.exit(0), (err) => { log.error({ err }, 'shutdown failed'); process.exit(1); });
    };
    process.once('SIGINT', shutdown);
    process.once('SIGTERM', shutdown);
  }).catch((err) => {
    log.fatal({ err }, 'failed to start');
    process.exit(1);
  });
}
