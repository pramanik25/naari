'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { makeCtx, register, linkGuardian, createIncident, sha256, setHelper, freshArea } = require('./helpers');

let ctx;
before(() => { ctx = makeCtx(); });
after(() => ctx.close());

test('DELETE /me removes the account, its rows and its evidence files', async () => {
  const u = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  const other = await register(ctx.app, 'Other');
  await linkGuardian(ctx.app, u, guardian);
  await setHelper(ctx.app, u, freshArea());
  const inc = await createIncident(ctx.app, u);
  const buf = crypto.randomBytes(2048);
  const evId = crypto.randomUUID();
  const up = await ctx.api().put(`/api/v1/incidents/${inc.id}/evidence/${evId}`).set(u.auth)
    .set('Content-Type', 'image/jpeg').set('X-Evidence-Kind', 'photo').set('X-Sha256', sha256(buf)).send(buf);
  assert.equal(up.status, 201);
  await ctx.api().put(`/api/v1/checkins/${crypto.randomUUID()}`).set(u.auth).send({ deadline: Date.now() + 600_000 });
  const otherInc = await createIncident(ctx.app, other);

  const userDir = path.join(ctx.config.evidenceDir, u.userId);
  assert.ok(fs.existsSync(path.join(userDir, inc.id, `${evId}.jpg`)));

  const del = await ctx.api().delete('/api/v1/me').set(u.auth);
  assert.equal(del.status, 204);
  assert.ok(!fs.existsSync(userDir), 'evidence directory removed');
  assert.equal((await ctx.api().get('/api/v1/me').set(u.auth)).status, 401, 'token no longer works');
  assert.equal((await ctx.api().get(`/api/v1/track/${inc.token}`)).status, 404, 'tracking link dead');

  const counts = await ctx.pool.query(
    `SELECT (SELECT count(*) FROM users WHERE id = $1)::int AS users,
            (SELECT count(*) FROM device_tokens WHERE user_id = $1)::int AS tokens,
            (SELECT count(*) FROM incidents WHERE user_id = $1)::int AS incidents,
            (SELECT count(*) FROM evidence WHERE user_id = $1)::int AS evidence,
            (SELECT count(*) FROM checkins WHERE user_id = $1)::int AS checkins,
            (SELECT count(*) FROM helpers WHERE user_id = $1)::int AS helpers,
            (SELECT count(*) FROM guardian_links WHERE ward_id = $1 OR guardian_id = $1)::int AS links,
            (SELECT count(*) FROM notifications WHERE incident_id = $2)::int AS notifications`,
    [u.userId, inc.id]);
  assert.deepEqual(counts.rows[0], { users: 0, tokens: 0, incidents: 0, evidence: 0, checkins: 0, helpers: 0, links: 0, notifications: 0 });

  // other users are untouched
  assert.equal((await ctx.api().get(`/api/v1/track/${otherInc.token}`)).status, 200);
  assert.deepEqual((await ctx.api().get('/api/v1/guardians').set(guardian.auth)).body.guarding, []);
});
