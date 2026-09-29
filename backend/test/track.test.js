'use strict';

const crypto = require('crypto');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const request = require('supertest');
const { makeCtx, register, createIncident, sha256, freshArea, setHelper, north } = require('./helpers');

let ctx;
before(() => { ctx = makeCtx({ limits: { trackPerMinute: 10_000, pagePerMinute: 10_000 } }); });
after(() => ctx.close());

async function seeded() {
  const area = freshArea();
  const u = await register(ctx.app, 'Asha');
  const helper = await register(ctx.app, 'Helper');
  await setHelper(ctx.app, helper, north(area, 300));
  const inc = await createIncident(ctx.app, u, { battery: 64 });
  const t0 = Date.now() - 60_000;
  const points = Array.from({ length: 5 }, (_, i) => ({ lat: area.lat + i * 0.0001, lng: area.lng, accuracy: 5, at: t0 + i * 1000 }));
  await ctx.api().post(`/api/v1/incidents/${inc.id}/locations`).set(u.auth).send({ points });
  const photo = crypto.randomBytes(5000);
  const photoId = crypto.randomUUID();
  await ctx.api().put(`/api/v1/incidents/${inc.id}/evidence/${photoId}`).set(u.auth)
    .set('Content-Type', 'image/jpeg').set('X-Evidence-Kind', 'photo').set('X-Sha256', sha256(photo))
    .set('X-Captured-At', String(Date.now())).send(photo);
  const bad = crypto.randomBytes(100);
  await ctx.api().put(`/api/v1/incidents/${inc.id}/evidence/${crypto.randomUUID()}`).set(u.auth)
    .set('Content-Type', 'audio/mp4').set('X-Evidence-Kind', 'audio').set('X-Sha256', sha256(Buffer.from('x')))
    .send(bad);
  return { u, helper, inc, area, points, photo, photoId };
}

test('track JSON shape, no-store, only verified evidence', async () => {
  const s = await seeded();
  const res = await ctx.api().get(`/api/v1/track/${s.inc.token}`);
  assert.equal(res.status, 200);
  assert.equal(res.headers['cache-control'], 'no-store');
  const b = res.body;
  assert.deepEqual(Object.keys(b).sort(), ['battery', 'duress', 'endedAt', 'evidence', 'helpersNotified', 'lastLocation',
    'ownerName', 'path', 'respondersCount', 'serverTime', 'source', 'startedAt', 'status'].sort());
  assert.equal(b.ownerName, 'Asha');
  assert.equal(b.status, 'active');
  assert.equal(b.duress, false);
  assert.equal(b.source, 'voice');
  assert.equal(b.endedAt, null);
  assert.equal(b.battery, 64);
  assert.equal(b.helpersNotified, 1);
  assert.equal(b.respondersCount, 0);
  const last = s.points[4];
  assert.deepEqual(b.lastLocation, { lat: last.lat, lng: last.lng, accuracy: 5, at: last.at });
  assert.deepEqual(b.path, s.points.map((p) => ({ lat: p.lat, lng: p.lng, at: p.at })));
  assert.equal(b.evidence.length, 1);
  const e = b.evidence[0];
  assert.equal(e.evidenceId, s.photoId);
  assert.equal(e.kind, 'photo');
  assert.equal(e.contentType, 'image/jpeg');
  assert.equal(e.verified, true);
  assert.match(e.url, new RegExp(`^/api/v1/track/${s.inc.token}/evidence/${s.photoId}\\?exp=\\d+&sig=[0-9a-f]{64}$`));
  assert.equal(typeof b.serverTime, 'number');
});

test('signed evidence URL serves the file; tampering and expiry are rejected; Range gives 206', async () => {
  const s = await seeded();
  const { url } = (await ctx.api().get(`/api/v1/track/${s.inc.token}`)).body.evidence[0];

  const ok = await ctx.api().get(url).buffer(true).parse((res, cb) => {
    const chunks = []; res.on('data', (c) => chunks.push(c)); res.on('end', () => cb(null, Buffer.concat(chunks)));
  });
  assert.equal(ok.status, 200);
  assert.equal(ok.headers['content-type'], 'image/jpeg');
  assert.equal(ok.headers['accept-ranges'], 'bytes');
  assert.equal(sha256(ok.body), sha256(s.photo));

  const range = await ctx.api().get(url).set('Range', 'bytes=100-199').buffer(true).parse((res, cb) => {
    const chunks = []; res.on('data', (c) => chunks.push(c)); res.on('end', () => cb(null, Buffer.concat(chunks)));
  });
  assert.equal(range.status, 206);
  assert.equal(range.headers['content-range'], `bytes 100-199/${s.photo.length}`);
  assert.equal(range.headers['content-length'], '100');
  assert.ok(range.body.equals(s.photo.subarray(100, 200)));
  assert.equal((await ctx.api().get(url).set('Range', 'bytes=999999-')).status, 416);

  const tampered = url.replace(/sig=([0-9a-f])/, (m, c) => `sig=${c === '0' ? '1' : '0'}`);
  const t = await ctx.api().get(tampered);
  assert.equal(t.status, 403);
  assert.equal(t.body.error, 'invalid_signature');
  const laterExp = url.replace(/exp=(\d+)/, (m, n) => `exp=${Number(n) + 60_000}`);
  assert.equal((await ctx.api().get(laterExp)).status, 403);
  // valid signature but from another incident's token -> 404
  const other = await seeded();
  const cross = url.replace(s.inc.token, other.inc.token);
  assert.equal((await ctx.api().get(cross)).status, 404);
  // expired, correctly signed link
  const { signEvidence } = require('../src/routes/track');
  const exp = Date.now() - 1000;
  const expired = `/api/v1/track/${s.inc.token}/evidence/${s.photoId}?exp=${exp}&sig=${signEvidence(ctx.config.signingSecret, s.photoId, exp)}`;
  const ex = await ctx.api().get(expired);
  assert.equal(ex.status, 403);
  assert.equal(ex.body.error, 'link_expired');
});

test('24 h after end: location and evidence are no longer returned', async () => {
  const s = await seeded();
  const { url } = (await ctx.api().get(`/api/v1/track/${s.inc.token}`)).body.evidence[0];
  await ctx.api().post(`/api/v1/incidents/${s.inc.id}/end`).set(s.u.auth).send({ userInitiated: true });
  const recent = (await ctx.api().get(`/api/v1/track/${s.inc.token}`)).body;
  assert.equal(recent.status, 'ended');
  assert.ok(recent.lastLocation);
  assert.equal(recent.evidence.length, 1);

  await ctx.pool.query(`UPDATE incidents SET ended_at = now() - interval '25 hours' WHERE id = $1`, [s.inc.id]);
  const old = (await ctx.api().get(`/api/v1/track/${s.inc.token}`)).body;
  assert.equal(old.status, 'ended');
  assert.equal(old.lastLocation, null);
  assert.deepEqual(old.path, []);
  assert.deepEqual(old.evidence, []);
  assert.equal(old.ownerName, 'Asha');
  assert.equal((await ctx.api().get(url)).status, 404, 'old signed links stop working too');
});

test('unknown token -> 404; tracking page is served for a real token', async () => {
  assert.equal((await ctx.api().get('/api/v1/track/AAAAAAAAAAAAAAAAAAAAAA')).status, 404);
  assert.equal((await ctx.api().get('/api/v1/track/x')).status, 404);
  const s = await seeded();
  const page = await ctx.api().get(`/t/${s.inc.token}`);
  assert.equal(page.status, 200);
  assert.match(page.headers['content-type'], /text\/html/);
  assert.match(page.text, /track\.js/);
  assert.doesNotMatch(page.text, /<script>(?!<\/script>)/, 'no inline scripts');
  assert.match(page.headers['content-security-policy'], /script-src 'self' https:\/\/unpkg\.com/);
  assert.equal(page.headers['referrer-policy'], 'strict-origin');
  assert.equal((await ctx.api().get('/t/AAAAAAAAAAAAAAAAAAAAAA')).status, 404);
  for (const f of ['track.js', 'track.css', 'i18n.js']) {
    assert.equal((await ctx.api().get(`/static/${f}`)).status, 200, f);
  }
});

test('track JSON is rate limited to 60/min/IP', async () => {
  const limited = makeCtx();
  try {
    const u = await register(limited.app);
    const inc = await createIncident(limited.app, u);
    for (let i = 0; i < 60; i++) {
      assert.equal((await request(limited.app).get(`/api/v1/track/${inc.token}`)).status, 200);
    }
    const res = await request(limited.app).get(`/api/v1/track/${inc.token}`);
    assert.equal(res.status, 429);
    assert.equal(res.body.error, 'rate_limited');
  } finally {
    await limited.close();
  }
});
