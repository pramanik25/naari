'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { makeCtx, register, createIncident, sha256 } = require('./helpers');
const { createEvidenceCleaner } = require('../src/jobs/evidenceCleaner');

let ctx;
before(() => { ctx = makeCtx(); });
after(() => ctx.close());

function upload(user, incidentId, evidenceId, buf) {
  return ctx.api().put(`/api/v1/incidents/${incidentId}/evidence/${evidenceId}`)
    .set(user.auth)
    .set('Content-Type', 'image/jpeg')
    .set('X-Evidence-Kind', 'photo')
    .set('X-Sha256', sha256(buf))
    .set('X-Captured-At', String(Date.now()))
    .send(buf);
}

function cleaner(config = ctx.config) {
  return createEvidenceCleaner({ pool: ctx.pool, config, log: ctx.log });
}

async function evidenceRows(incidentId) {
  return (await ctx.pool.query('SELECT id FROM evidence WHERE incident_id = $1', [incidentId])).rows;
}

test('evidence is deleted (file + row) once the incident ended past retention', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const evId = crypto.randomUUID();
  assert.equal((await upload(u, inc.id, evId, crypto.randomBytes(2000))).status, 201);
  const file = path.join(ctx.config.evidenceDir, u.userId, inc.id, `${evId}.jpg`);
  assert.ok(fs.existsSync(file));

  // Active incident, fresh evidence: nothing to clean.
  await cleaner().runOnce();
  assert.ok(fs.existsSync(file));
  assert.equal((await evidenceRows(inc.id)).length, 1);

  // Ended, but within retention: still kept.
  assert.equal((await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(u.auth).send({})).status, 204);
  await cleaner().runOnce();
  assert.ok(fs.existsSync(file));
  assert.equal((await evidenceRows(inc.id)).length, 1);

  // Ended 25 h ago (default retention 24 h): deleted.
  await ctx.pool.query(`UPDATE incidents SET ended_at = now() - interval '25 hours' WHERE id = $1`, [inc.id]);
  await cleaner().runOnce();
  assert.ok(!fs.existsSync(file));
  assert.equal((await evidenceRows(inc.id)).length, 0);
});

test('evidence on a never-ended incident is deleted after 7 days', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const evId = crypto.randomUUID();
  assert.equal((await upload(u, inc.id, evId, crypto.randomBytes(1000))).status, 201);
  await ctx.pool.query(`UPDATE evidence SET received_at = now() - interval '8 days' WHERE id = $1`, [evId]);
  await cleaner().runOnce();
  assert.equal((await evidenceRows(inc.id)).length, 0);
});

test('EVIDENCE_RETENTION_HOURS=0 keeps everything', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const evId = crypto.randomUUID();
  assert.equal((await upload(u, inc.id, evId, crypto.randomBytes(1000))).status, 201);
  assert.equal((await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(u.auth).send({})).status, 204);
  await ctx.pool.query(`UPDATE incidents SET ended_at = now() - interval '30 days' WHERE id = $1`, [inc.id]);
  await ctx.pool.query(`UPDATE evidence SET received_at = now() - interval '30 days' WHERE id = $1`, [evId]);
  await cleaner({ ...ctx.config, evidenceRetentionHours: 0 }).runOnce();
  assert.equal((await evidenceRows(inc.id)).length, 1);
  // Clean up so later tests with the default retention are not affected.
  await ctx.pool.query('DELETE FROM evidence WHERE id = $1', [evId]);
});

test('a row whose file is already gone is still removed', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const evId = crypto.randomUUID();
  assert.equal((await upload(u, inc.id, evId, crypto.randomBytes(1000))).status, 201);
  fs.rmSync(path.join(ctx.config.evidenceDir, u.userId, inc.id, `${evId}.jpg`));
  assert.equal((await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(u.auth).send({})).status, 204);
  await ctx.pool.query(`UPDATE incidents SET ended_at = now() - interval '25 hours' WHERE id = $1`, [inc.id]);
  await cleaner().runOnce();
  assert.equal((await evidenceRows(inc.id)).length, 0);
});
