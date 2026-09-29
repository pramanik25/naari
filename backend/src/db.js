'use strict';

const { Pool, Client } = require('pg');

function createPool(databaseUrl, log) {
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 10,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 10_000,
    application_name: 'naari-api',
  });
  pool.on('error', (err) => {
    // Idle client errors (e.g. server restart) must not crash the process.
    if (log) log.error({ err }, 'postgres pool error');
  });
  return pool;
}

/** Runs fn(client) inside BEGIN/COMMIT, rolling back on any throw. */
async function tx(pool, fn) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    try { await client.query('ROLLBACK'); } catch (_) { /* connection already broken */ }
    throw err;
  } finally {
    client.release();
  }
}

/**
 * A dedicated connection that LISTENs on `channel` and reconnects with backoff.
 * onMessage(payload) is called for every NOTIFY. Returns { ready, close }.
 */
function createListener(databaseUrl, channel, onMessage, log) {
  let client = null;
  let closed = false;
  let retryMs = 500;
  let timer = null;
  let resolveReady;
  const ready = new Promise((r) => { resolveReady = r; });

  async function connect() {
    if (closed) return;
    const c = new Client({ connectionString: databaseUrl, application_name: 'naari-listener' });
    c.on('notification', (msg) => {
      if (msg.channel !== channel) return;
      try { onMessage(msg.payload); } catch (err) { if (log) log.error({ err }, 'listener handler failed'); }
    });
    c.on('error', (err) => {
      if (log) log.warn({ err }, 'listener connection error');
      scheduleReconnect(c);
    });
    c.on('end', () => scheduleReconnect(c));
    try {
      await c.connect();
      await c.query(`LISTEN ${channel}`);
      client = c;
      retryMs = 500;
      resolveReady();
      if (log) log.info({ channel }, 'listening for events');
    } catch (err) {
      if (log) log.warn({ err }, 'listener connect failed');
      scheduleReconnect(c);
    }
  }

  function scheduleReconnect(c) {
    if (closed || timer) return;
    if (client === c) client = null;
    c.removeAllListeners('end');
    c.end().catch(() => {});
    timer = setTimeout(() => { timer = null; connect(); }, retryMs);
    timer.unref();
    retryMs = Math.min(retryMs * 2, 15_000);
  }

  connect();

  return {
    ready,
    async close() {
      closed = true;
      if (timer) clearTimeout(timer);
      if (client) {
        const c = client;
        client = null;
        c.removeAllListeners('end');
        await c.end().catch(() => {});
      }
    },
  };
}

module.exports = { createPool, tx, createListener };
