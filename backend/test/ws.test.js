'use strict';

const fs = require('fs');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const { testConfig, silent, register, linkGuardian, createIncident, notifications, ofType } = require('./helpers');
const { start } = require('../src/server');

let srv;
let base;
before(async () => {
  srv = await start({ config: testConfig(), log: silent, sweeper: false, host: '127.0.0.1', limits: { registerPerHour: 10_000 } });
  base = `127.0.0.1:${srv.port}`;
});
after(async () => {
  await srv.close();
  fs.rmSync(srv.config.evidenceDir, { recursive: true, force: true });
});

function connect(user, { query = false } = {}) {
  const url = query ? `ws://${base}/api/v1/ws?token=${user.token}` : `ws://${base}/api/v1/ws`;
  const ws = new WebSocket(url, query ? {} : { headers: { Authorization: `Bearer ${user.token}` } });
  const inbox = [];
  const waiters = [];
  ws.on('message', (d) => {
    const m = JSON.parse(d.toString());
    const i = waiters.findIndex((w) => w.pred(m));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m); else inbox.push(m);
  });
  ws.next = (pred, ms = 5000) => {
    const i = inbox.findIndex(pred);
    if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { pred, resolve };
      waiters.push(w);
      setTimeout(() => { const k = waiters.indexOf(w); if (k >= 0) { waiters.splice(k, 1); reject(new Error('timeout waiting for message')); } }, ms).unref();
    });
  };
  ws.inbox = inbox;
  return new Promise((resolve, reject) => { ws.once('open', () => resolve(ws)); ws.once('error', reject); });
}

const closeWs = (ws) => new Promise((r) => { if (ws.readyState === WebSocket.CLOSED) r(); else { ws.once('close', r); ws.close(); } });
const app = () => srv.app;

test('bad token is refused with 401', async () => {
  const ws = new WebSocket(`ws://${base}/api/v1/ws`, { headers: { Authorization: 'Bearer nope-nope-nope-nope-nope-nope-nope-nope' } });
  const status = await new Promise((resolve) => {
    ws.on('unexpected-response', (req, res) => resolve(res.statusCode));
    ws.on('error', () => {});
  });
  assert.equal(status, 401);
});

test('guardian receives sos live on every open socket, acks it, ping/pong works', async () => {
  const ward = await register(app(), 'Asha');
  const guardian = await register(app(), 'Mum');
  await linkGuardian(app(), ward, guardian);
  const phone = await connect(guardian);
  const tablet = await connect(guardian, { query: true });

  phone.send(JSON.stringify({ type: 'ping' }));
  assert.deepEqual(await phone.next((m) => m.type === 'pong'), { type: 'pong' });

  const inc = await createIncident(app(), ward);
  const [a, b] = await Promise.all([
    phone.next((m) => m.type === 'sos' && m.incidentId === inc.id),
    tablet.next((m) => m.type === 'sos' && m.incidentId === inc.id),
  ]);
  assert.equal(a.id, b.id);
  assert.equal(a.ownerName, 'Asha');
  assert.equal(a.trackUrl, inc.trackUrl);
  assert.equal(typeof a.at, 'number');

  phone.send(JSON.stringify({ type: 'ack', id: a.id }));
  let acked = null;
  for (let i = 0; i < 50 && !acked; i++) {
    // eslint-disable-next-line no-await-in-loop
    acked = (await srv.pool.query('SELECT acked_at FROM notifications WHERE id = $1', [a.id])).rows[0].acked_at;
    // eslint-disable-next-line no-await-in-loop
    if (!acked) await new Promise((r) => setTimeout(r, 20));
  }
  assert.ok(acked, 'ack recorded');
  assert.equal(ofType(await notifications(app(), guardian), 'sos', inc.id).length, 0, 'acked ones are not replayed');
  await closeWs(phone);
  await closeWs(tablet);
});

test('offline guardian catches up from /notifications after reconnect', async () => {
  const ward = await register(app(), 'Asha');
  const guardian = await register(app(), 'Mum');
  await linkGuardian(app(), ward, guardian);
  const ws = await connect(guardian);
  const first = await createIncident(app(), ward);
  const live = await ws.next((m) => m.type === 'sos' && m.incidentId === first.id);
  ws.send(JSON.stringify({ type: 'ack', id: live.id }));
  await closeWs(ws);

  // while offline
  await new Promise((r) => setTimeout(r, 50));
  const second = await createIncident(app(), ward);
  const ws2 = await connect(guardian);
  const pending = await notifications(app(), guardian, live.at);
  const missed = ofType(pending, 'sos', second.id);
  assert.equal(missed.length, 1);
  assert.equal(ofType(pending, 'sos', first.id).length, 0);
  ws2.send(JSON.stringify({ type: 'ack', id: missed[0].id }));
  // the reconnected socket still gets new live events
  await ctxDuress(ward, second.id);
  const d = await ws2.next((m) => m.type === 'duress' && m.incidentId === second.id);
  assert.equal(d.ownerName, 'Asha');
  await closeWs(ws2);
});

async function ctxDuress(ward, incidentId) {
  const request = require('supertest');
  const r = await request(app()).post(`/api/v1/incidents/${incidentId}/duress`).set(ward.auth).send({});
  assert.equal(r.status, 204);
}

test('deleting the account closes its sockets', async () => {
  const u = await register(app(), 'Gone');
  const ws = await connect(u);
  const closed = new Promise((r) => ws.once('close', (code) => r(code)));
  const request = require('supertest');
  assert.equal((await request(app()).delete('/api/v1/me').set(u.auth)).status, 204);
  assert.equal(await closed, 4001);
});
