'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { makeCtx, register } = require('./helpers');

let ctx;
before(() => { ctx = makeCtx(); });
after(() => ctx.close());

const codeOf = async (u) => (await ctx.api().get('/api/v1/me').set(u.auth)).body.guardianCode;

test('link, list both directions, unlink', async () => {
  const ward = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  const code = await codeOf(ward);

  const link = await ctx.api().post('/api/v1/guardians/link').set(guardian.auth).send({ code: code.toLowerCase() });
  assert.equal(link.status, 200);
  assert.deepEqual(link.body, { wardId: ward.userId, wardName: 'Asha' });
  // linking again is idempotent
  assert.equal((await ctx.api().post('/api/v1/guardians/link').set(guardian.auth).send({ code })).status, 200);

  const w = await ctx.api().get('/api/v1/guardians').set(ward.auth);
  assert.equal(w.body.guardians.length, 1);
  assert.equal(w.body.guardians[0].userId, guardian.userId);
  assert.equal(w.body.guardians[0].name, 'Mum');
  assert.equal(typeof w.body.guardians[0].linkedAt, 'number');
  assert.deepEqual(w.body.guarding, []);
  const g = await ctx.api().get('/api/v1/guardians').set(guardian.auth);
  assert.equal(g.body.guarding[0].userId, ward.userId);

  // either side can remove the link
  assert.equal((await ctx.api().delete(`/api/v1/guardians/${ward.userId}`).set(guardian.auth)).status, 204);
  assert.deepEqual((await ctx.api().get('/api/v1/guardians').set(ward.auth)).body.guardians, []);
  assert.equal((await ctx.api().delete(`/api/v1/guardians/${ward.userId}`).set(guardian.auth)).status, 404);
  assert.equal((await ctx.api().delete('/api/v1/guardians/not-a-uuid').set(guardian.auth)).status, 404);
});

test('ward can unlink a guardian too', async () => {
  const ward = await register(ctx.app, 'A');
  const guardian = await register(ctx.app, 'B');
  await ctx.api().post('/api/v1/guardians/link').set(guardian.auth).send({ code: await codeOf(ward) });
  assert.equal((await ctx.api().delete(`/api/v1/guardians/${guardian.userId}`).set(ward.auth)).status, 204);
  assert.deepEqual((await ctx.api().get('/api/v1/guardians').set(guardian.auth)).body.guarding, []);
});

test('self link is refused', async () => {
  const u = await register(ctx.app);
  const res = await ctx.api().post('/api/v1/guardians/link').set(u.auth).send({ code: await codeOf(u) });
  assert.equal(res.status, 400);
  assert.equal(res.body.error, 'self_link');
});

test('wrong codes: invalid_code, then too_many_attempts after 10 per hour (even for a right code)', async () => {
  const ward = await register(ctx.app);
  const guesser = await register(ctx.app);
  const real = await codeOf(ward);
  const wrong = real === 'AAAAAA' ? 'BBBBBB' : 'AAAAAA';
  for (let i = 0; i < 10; i++) {
    const r = await ctx.api().post('/api/v1/guardians/link').set(guesser.auth).send({ code: i === 0 ? 'nonsense!' : wrong });
    assert.equal(r.status, 404);
    assert.equal(r.body.error, 'invalid_code');
  }
  const blocked = await ctx.api().post('/api/v1/guardians/link').set(guesser.auth).send({ code: real });
  assert.equal(blocked.status, 429);
  assert.equal(blocked.body.error, 'too_many_attempts');
  // throttle is per user: someone else can still link
  const other = await register(ctx.app);
  assert.equal((await ctx.api().post('/api/v1/guardians/link').set(other.auth).send({ code: real })).status, 200);
  // an hour later the guesser may try again
  await ctx.pool.query(`UPDATE guardian_link_failures SET failed_at = now() - interval '61 minutes' WHERE user_id = $1`, [guesser.userId]);
  assert.equal((await ctx.api().post('/api/v1/guardians/link').set(guesser.auth).send({ code: real })).status, 200);
});

test('missing code is a 400', async () => {
  const u = await register(ctx.app);
  assert.equal((await ctx.api().post('/api/v1/guardians/link').set(u.auth).send({})).status, 400);
});
