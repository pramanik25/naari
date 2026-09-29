'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const { makeCtx, register, testConfig, silent } = require('./helpers');
const { createPush } = require('../src/push');
const { notifyAll } = require('../src/notify');
const { tx } = require('../src/db');

let ctx;
before(() => { ctx = makeCtx(); });
after(() => ctx.close());

function serviceAccountJson() {
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  return JSON.stringify({
    project_id: 'naari-test',
    client_email: 'push@naari-test.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
  });
}

async function pushTokenOf(user) {
  const { rows } = await ctx.pool.query('SELECT push_token FROM device_tokens WHERE user_id = $1', [user.userId]);
  return rows[0].push_token;
}

test('PUT /push-token stores per device, validates, clears with null', async () => {
  const user = await register(ctx.app, 'Asha');
  assert.equal((await ctx.api().put('/api/v1/push-token').set(user.auth).send({ token: 'fcm-token-1' })).status, 204);
  assert.equal(await pushTokenOf(user), 'fcm-token-1');

  assert.equal((await ctx.api().put('/api/v1/push-token').set(user.auth).send({ token: '  ' })).status, 400);
  assert.equal((await ctx.api().put('/api/v1/push-token').set(user.auth).send({ token: 42 })).status, 400);
  assert.equal((await ctx.api().put('/api/v1/push-token').send({ token: 'x' })).status, 401);
  assert.equal(await pushTokenOf(user), 'fcm-token-1');

  assert.equal((await ctx.api().put('/api/v1/push-token').set(user.auth).send({ token: null })).status, 204);
  assert.equal(await pushTokenOf(user), null);
});

test('push is dormant without a service account', async () => {
  const push = createPush({
    config: testConfig(),
    pool: ctx.pool,
    log: silent,
    fetchImpl: async () => { throw new Error('unexpected network call'); },
  });
  assert.equal(push.enabled, false);
  await push.onNotify(crypto.randomUUID()); // must not throw or call fetch
});

test('fan-out sends one high-priority data message per device and forgets dead tokens', async () => {
  const config = testConfig({ FIREBASE_SERVICE_ACCOUNT: serviceAccountJson() });
  const calls = [];
  const fcmResponses = [];
  const fetchImpl = async (url, init = {}) => {
    calls.push({ url, init });
    if (url.includes('oauth2.googleapis.com')) {
      assert.match(String(init.body), /jwt-bearer/);
      return Response.json({ access_token: 'at-1', expires_in: 3600 });
    }
    return fcmResponses.shift() || Response.json({ name: 'projects/naari-test/messages/1' });
  };
  const push = createPush({ config, pool: ctx.pool, log: silent, fetchImpl });
  assert.equal(push.enabled, true);

  const user = await register(ctx.app, 'Mum');
  await ctx.api().put('/api/v1/push-token').set(user.auth).send({ token: 'fcm-abc' });

  const [id] = await tx(ctx.pool, (c) => notifyAll(c, 'sos', null, [user.userId], { ownerName: 'Asha' }));
  await push.onNotify(id);

  const sends = calls.filter((c) => c.url.includes('fcm.googleapis.com'));
  assert.equal(sends.length, 1);
  assert.match(sends[0].init.headers.Authorization, /^Bearer at-1$/);
  const msg = JSON.parse(sends[0].init.body).message;
  assert.equal(msg.token, 'fcm-abc');
  assert.equal(msg.android.priority, 'HIGH');
  assert.equal(msg.android.ttl, '86400s');
  const wire = JSON.parse(msg.data.n);
  assert.equal(wire.id, id);
  assert.equal(wire.type, 'sos');
  assert.equal(wire.ownerName, 'Asha');
  assert.ok(wire.at > 0);

  // helper_alert gets the short TTL
  const [helperId] = await tx(ctx.pool, (c) => notifyAll(c, 'helper_alert', null, [user.userId], {}));
  await push.onNotify(helperId);
  const helperSend = calls.filter((c) => c.url.includes('fcm.googleapis.com')).pop();
  assert.equal(JSON.parse(helperSend.init.body).message.android.ttl, '900s');

  // FCM says the token is gone: it is dropped, later notifications send nothing
  fcmResponses.push(new Response('{"error":{"status":"UNREGISTERED"}}', { status: 404 }));
  const [deadId] = await tx(ctx.pool, (c) => notifyAll(c, 'ended', null, [user.userId], {}));
  await push.onNotify(deadId);
  assert.equal(await pushTokenOf(user), null);

  const before = calls.length;
  const [quietId] = await tx(ctx.pool, (c) => notifyAll(c, 'ended', null, [user.userId], {}));
  await push.onNotify(quietId);
  assert.equal(calls.length, before);
});

test('acked notifications are not pushed', async () => {
  const config = testConfig({ FIREBASE_SERVICE_ACCOUNT: serviceAccountJson() });
  const calls = [];
  const push = createPush({
    config,
    pool: ctx.pool,
    log: silent,
    fetchImpl: async (url, init) => { calls.push({ url, init }); return Response.json({ access_token: 'at', expires_in: 3600 }); },
  });
  const user = await register(ctx.app, 'Riya');
  await ctx.api().put('/api/v1/push-token').set(user.auth).send({ token: 'fcm-riya' });
  const [id] = await tx(ctx.pool, (c) => notifyAll(c, 'ended', null, [user.userId], {}));
  assert.equal((await ctx.api().post(`/api/v1/notifications/${id}/ack`).set(user.auth)).status, 204);
  await push.onNotify(id);
  assert.equal(calls.length, 0);
});
