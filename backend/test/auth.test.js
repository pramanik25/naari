'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const request = require('supertest');
const { makeCtx, register, sha256 } = require('./helpers');

let ctx;
before(() => { ctx = makeCtx(); });
after(() => ctx.close());

test('healthz reports db ok', async () => {
  const res = await ctx.api().get('/healthz');
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { ok: true, db: true });
});

test('register returns userId + opaque token; only its SHA-256 is stored', async () => {
  const res = await ctx.api().post('/api/v1/devices/register').send({ deviceName: 'Pixel 7', name: 'Asha' });
  assert.equal(res.status, 201);
  assert.match(res.body.userId, /^[0-9a-f-]{36}$/);
  assert.ok(res.body.token.length >= 43);
  const { rows } = await ctx.pool.query('SELECT token_hash FROM device_tokens WHERE user_id = $1', [res.body.userId]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].token_hash, sha256(res.body.token));
  assert.notEqual(rows[0].token_hash, res.body.token);
});

test('bad, missing and malformed tokens are rejected with 401', async () => {
  const u = await register(ctx.app, 'Asha');
  assert.equal((await ctx.api().get('/api/v1/me')).status, 401);
  const bad = await ctx.api().get('/api/v1/me').set('Authorization', `Bearer ${u.token.slice(0, -2)}xx`);
  assert.equal(bad.status, 401);
  assert.equal(bad.body.error, 'unauthorized');
  assert.equal((await ctx.api().get('/api/v1/me').set('Authorization', 'Basic abc')).status, 401);
  assert.equal((await ctx.api().get('/api/v1/me').set(u.auth)).status, 200);
});

test('profile: guardian code created on first read, stable, well-formed', async () => {
  const u = await register(ctx.app, 'Meera');
  const a = await ctx.api().get('/api/v1/me').set(u.auth);
  assert.equal(a.status, 200);
  assert.equal(a.body.userId, u.userId);
  assert.equal(a.body.name, 'Meera');
  assert.match(a.body.guardianCode, /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/);
  const b = await ctx.api().get('/api/v1/me').set(u.auth);
  assert.equal(b.body.guardianCode, a.body.guardianCode);
  const p = await ctx.api().patch('/api/v1/me').set(u.auth).send({ name: '  Meera   K ' });
  assert.equal(p.status, 200);
  assert.deepEqual(p.body, { userId: u.userId, name: 'Meera K', guardianCode: a.body.guardianCode });
  assert.equal((await ctx.api().patch('/api/v1/me').set(u.auth).send({ name: 42 })).status, 400);
});

test('guardian codes are unique across users', async () => {
  const users = await Promise.all(Array.from({ length: 25 }, (_, i) => register(ctx.app, `U${i}`)));
  const codes = await Promise.all(users.map(async (u) => (await ctx.api().get('/api/v1/me').set(u.auth)).body.guardianCode));
  assert.equal(new Set(codes).size, codes.length);
});

test('errors use { error, message } and unknown routes 404; bad JSON is 400', async () => {
  const u = await register(ctx.app);
  const r = await ctx.api().get('/api/v1/nope').set(u.auth);
  assert.equal(r.status, 404);
  assert.equal(r.body.error, 'not_found');
  assert.equal(typeof r.body.message, 'string');
  const j = await ctx.api().patch('/api/v1/me').set(u.auth).set('Content-Type', 'application/json').send('{"name":');
  assert.equal(j.status, 400);
  assert.equal(j.body.error, 'invalid_json');
  assert.ok(!JSON.stringify(j.body).includes('at '), 'no stack traces');
});

test('JSON bodies over 100 kb are rejected', async () => {
  const u = await register(ctx.app);
  const res = await ctx.api().patch('/api/v1/me').set(u.auth).send({ name: 'x'.repeat(120 * 1024) });
  assert.equal(res.status, 413);
});

test('register is rate limited per IP', async () => {
  const small = makeCtx({ limits: { registerPerHour: 3 } });
  try {
    for (let i = 0; i < 3; i++) assert.equal((await request(small.app).post('/api/v1/devices/register').send({})).status, 201);
    const res = await request(small.app).post('/api/v1/devices/register').send({});
    assert.equal(res.status, 429);
    assert.ok(res.headers['retry-after']);
  } finally {
    await small.close();
  }
});
