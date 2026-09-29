'use strict';

const { WebSocketServer, WebSocket } = require('ws');
const { bearerFromHeader, userIdForToken } = require('./auth');
const { toMessage } = require('./notify');
const { isUuid } = require('./util');

const WS_PATH = '/api/v1/ws';

function reject(socket, status, text) {
  if (socket.destroyed) return;
  socket.end(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Type: application/json\r\n\r\n`
    + JSON.stringify({ error: status === 401 ? 'unauthorized' : 'not_found', message: text }));
}

/**
 * WebSocket hub: authenticates upgrades, keeps every open socket per user (a user may have several
 * devices/sockets), answers pings, records acks and pushes notifications that arrive through
 * Postgres NOTIFY (see createListener in db.js).
 */
function createHub({ pool, log, pingIntervalMs = 25_000 }) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024, clientTracking: true });
  const byUser = new Map(); // userId -> Set<WebSocket>

  function add(ws, userId) {
    let set = byUser.get(userId);
    if (!set) { set = new Set(); byUser.set(userId, set); }
    set.add(ws);
  }

  function remove(ws) {
    const set = byUser.get(ws.userId);
    if (!set) return;
    set.delete(ws);
    if (!set.size) byUser.delete(ws.userId);
  }

  async function onMessage(ws, data, isBinary) {
    ws.isAlive = true;
    if (isBinary) return;
    let msg;
    try { msg = JSON.parse(data.toString('utf8')); } catch (_) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
    } else if (msg.type === 'ack' && isUuid(msg.id)) {
      try {
        await pool.query(
          'UPDATE notifications SET acked_at = now() WHERE id = $1 AND recipient_id = $2 AND acked_at IS NULL',
          [msg.id, ws.userId]);
      } catch (err) {
        log.error({ err }, 'ack failed');
      }
    }
  }

  function register(ws, userId) {
    ws.userId = userId;
    ws.isAlive = true;
    add(ws, userId);
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('message', (data, isBinary) => { onMessage(ws, data, isBinary); });
    ws.on('close', () => remove(ws));
    ws.on('error', () => { /* 'close' follows */ });
  }

  async function onUpgrade(req, socket, head) {
    socket.on('error', () => {});
    let url;
    try { url = new URL(req.url, 'http://localhost'); } catch (_) { return reject(socket, 404, 'Not Found'); }
    if (url.pathname !== WS_PATH) return reject(socket, 404, 'Not Found');
    const token = bearerFromHeader(req.headers.authorization) || url.searchParams.get('token');
    let userId = null;
    try { userId = await userIdForToken(pool, token); } catch (err) { log.error({ err }, 'ws auth failed'); }
    if (!userId) return reject(socket, 401, 'Unauthorized');
    if (socket.destroyed) return undefined;
    return wss.handleUpgrade(req, socket, head, (ws) => register(ws, userId));
  }

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) { ws.terminate(); continue; }
      ws.isAlive = false;
      try { ws.ping(); } catch (_) { ws.terminate(); }
    }
  }, pingIntervalMs);
  heartbeat.unref();

  /** Called with a notification id from NOTIFY naari_events. */
  async function onNotify(id) {
    if (!isUuid(id) || byUser.size === 0) return;
    let row;
    try {
      ({ rows: [row] } = await pool.query(
        'SELECT id, recipient_id, type, payload, created_at, acked_at FROM notifications WHERE id = $1', [id]));
    } catch (err) {
      log.error({ err }, 'failed to load notification');
      return;
    }
    if (!row || row.acked_at) return;
    const set = byUser.get(row.recipient_id);
    if (!set) return;
    const text = JSON.stringify(toMessage(row));
    for (const ws of set) if (ws.readyState === WebSocket.OPEN) ws.send(text);
  }

  function disconnectUser(userId) {
    const set = byUser.get(userId);
    if (!set) return;
    for (const ws of set) ws.close(4001, 'account deleted');
  }

  function close() {
    clearInterval(heartbeat);
    for (const ws of wss.clients) ws.close(1001, 'server shutting down');
    // give close frames a moment, then drop whatever is left
    setTimeout(() => { for (const ws of wss.clients) ws.terminate(); }, 1000).unref();
    wss.close();
  }

  return {
    attach(server) { server.on('upgrade', (req, socket, head) => { onUpgrade(req, socket, head); }); },
    onNotify,
    disconnectUser,
    close,
    connectedUsers: () => byUser.size,
  };
}

module.exports = { createHub, WS_PATH };
