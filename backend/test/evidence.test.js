'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { makeCtx, register, createIncident, sha256 } = require('./helpers');

let ctx;
before(() => { ctx = makeCtx({ env: { MAX_EVIDENCE_BYTES: String(64 * 1024) } }); });
after(() => ctx.close());

function upload(user, incidentId, evidenceId, buf, { hash = sha256(buf), type = 'image/jpeg', kind = 'photo', capturedAt = Date.now() } = {}) {
  return ctx.api().put(`/api/v1/incidents/${incidentId}/evidence/${evidenceId}`)
    .set(user.auth)
    .set('Content-Type', type)
    .set('X-Evidence-Kind', kind)
    .set('X-Sha256', hash)
    .set('X-Captured-At', String(capturedAt))
    .send(buf);
}

test('upload streams to disk, verifies hash, lists evidence', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const buf = crypto.randomBytes(20_000);
  const evId = crypto.randomUUID();
  const captured = Date.now() - 5000;
  const res = await upload(u, inc.id, evId, buf, { capturedAt: captured });
  assert.equal(res.status, 201);
  assert.equal(res.body.evidenceId, evId);
  assert.equal(res.body.serverSha256, sha256(buf));
  assert.equal(res.body.verified, true);
  assert.equal(typeof res.body.receivedAt, 'number');

  const file = path.join(ctx.config.evidenceDir, u.userId, inc.id, `${evId}.jpg`);
  assert.ok(fs.existsSync(file));
  assert.equal(sha256(fs.readFileSync(file)), sha256(buf));
  assert.deepEqual(fs.readdirSync(path.join(ctx.config.evidenceDir, '.tmp')), [], 'no temp files left');

  const list = await ctx.api().get(`/api/v1/incidents/${inc.id}/evidence`).set(u.auth);
  assert.equal(list.status, 200);
  assert.deepEqual(list.body.evidence, [{
    evidenceId: evId, kind: 'photo', size: buf.length, sha256: sha256(buf), serverSha256: sha256(buf),
    verified: true, capturedAt: captured, receivedAt: res.body.receivedAt,
  }]);

  const inc2 = (await ctx.api().get('/api/v1/incidents').set(u.auth)).body.incidents.find((i) => i.id === inc.id);
  assert.equal(inc2.evidenceCount, 1);
  assert.equal(inc2.verifiedCount, 1);
});

test('wrong client hash is stored but verified=false', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const buf = crypto.randomBytes(1000);
  const claimed = sha256(Buffer.from('something else'));
  const res = await upload(u, inc.id, crypto.randomUUID(), buf, { hash: claimed, type: 'audio/mp4', kind: 'audio' });
  assert.equal(res.status, 201);
  assert.equal(res.body.verified, false);
  assert.equal(res.body.serverSha256, sha256(buf));
});

test('same id: same hash -> 200 idempotent, different hash -> 409; file never overwritten', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const buf = crypto.randomBytes(3000);
  const evId = crypto.randomUUID();
  const first = await upload(u, inc.id, evId, buf, { type: 'video/mp4', kind: 'video' });
  assert.equal(first.status, 201);
  const again = await upload(u, inc.id, evId, buf, { type: 'video/mp4', kind: 'video' });
  assert.equal(again.status, 200);
  assert.deepEqual(again.body, first.body);
  const other = crypto.randomBytes(3000);
  const conflict = await upload(u, inc.id, evId, other, { type: 'video/mp4', kind: 'video' });
  assert.equal(conflict.status, 409);
  assert.equal(conflict.body.error, 'evidence_exists');
  const file = path.join(ctx.config.evidenceDir, u.userId, inc.id, `${evId}.mp4`);
  assert.equal(sha256(fs.readFileSync(file)), sha256(buf));
});

test('size limit -> 413 and nothing stored', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const evId = crypto.randomUUID();
  const res = await upload(u, inc.id, evId, crypto.randomBytes(64 * 1024 + 1));
  assert.equal(res.status, 413);
  assert.equal(res.body.error, 'too_large');
  const { rows } = await ctx.pool.query('SELECT 1 FROM evidence WHERE id = $1', [evId]);
  assert.equal(rows.length, 0);
  // exactly at the limit is fine
  assert.equal((await upload(u, inc.id, crypto.randomUUID(), crypto.randomBytes(64 * 1024))).status, 201);
});

test("another user's incident -> 404; bad headers rejected", async () => {
  const owner = await register(ctx.app);
  const intruder = await register(ctx.app);
  const inc = await createIncident(ctx.app, owner);
  const buf = crypto.randomBytes(100);
  const res = await upload(intruder, inc.id, crypto.randomUUID(), buf);
  assert.equal(res.status, 404);
  assert.equal((await ctx.api().get(`/api/v1/incidents/${inc.id}/evidence`).set(intruder.auth)).status, 404);
  assert.equal((await upload(owner, inc.id, crypto.randomUUID(), buf, { type: 'text/html' })).status, 415);
  assert.equal((await upload(owner, inc.id, crypto.randomUUID(), buf, { kind: 'doc' })).status, 400);
  assert.equal((await upload(owner, inc.id, crypto.randomUUID(), buf, { hash: 'abc' })).status, 400);
});
