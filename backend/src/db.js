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

/**
 * Runs fn(client) inside BEGIN/COMMIT, rolling back on any throw. Callbacks pushed onto
 * `client.afterCommit` during fn run once the COMMIT succeeded (never after a rollback).
 */
async function tx(pool, fn) {
  const client = await pool.connect();
  const after = [];
  try {
    await client.query('BEGIN');
    client.afterCommit = after;
    const result = await fn(client);
    await client.query('COMMIT');
    for (const cb of after) {
      try { cb(); } catch (_) { /* a hook must never fail a committed transaction */ }
    }
    return result;
  } catch (err) {
    try { await client.query('ROLLBACK'); } catch (_) { /* connection already broken */ }
    throw err;
  } finally {
    client.afterCommit = null;
    client.release();
  }
}

/**
 * LISTEN needs a session-level connection: through a transaction-mode pooler (Neon's "-pooler"
 * host, PgBouncer) the LISTEN succeeds but no notification is ever delivered. Returns the direct
 * form of a Neon pooled URL; any other URL is returned unchanged.
 */
function directUrl(databaseUrl) {
  try {
    const u = new URL(databaseUrl);
    if (!/\.neon\.tech$/i.test(u.hostname) || !/-pooler(?=\.)/.test(u.hostname)) return databaseUrl;
    u.hostname = u.hostname.replace(/-pooler(?=\.)/, '');
    return u.toString();
  } catch (_) {
    return databaseUrl;
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

module.exports = { createPool, tx, createListener, directUrl };
