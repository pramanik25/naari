'use strict';

const crypto = require('crypto');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const {
  makeCtx, register, linkGuardian, createIncident, notifications, ofType, freshArea, north, setHelper, trackToken,
} = require('./helpers');

let ctx;
before(() => { ctx = makeCtx(); });
after(() => ctx.close());

const post = (path, user, body) => ctx.api().post(path).set(user.auth).send(body);

test('create notifies guardians with sos; re-PUT is idempotent', async () => {
  const ward = await register(ctx.app, 'Asha');
  const g1 = await register(ctx.app, 'Mum');
  const g2 = await register(ctx.app, 'Dad');
  await linkGuardian(ctx.app, ward, g1);
  await linkGuardian(ctx.app, ward, g2);
  const inc = await createIncident(ctx.app, ward);
  assert.equal(inc.trackUrl, `https://naari.test/t/${inc.token}`);

  for (const g of [g1, g2]) {
    const sos = ofType(await notifications(ctx.app, g), 'sos', inc.id);
    assert.equal(sos.length, 1);
    assert.equal(sos[0].ownerName, 'Asha');
    assert.equal(sos[0].trackUrl, inc.trackUrl);
    assert.equal(typeof sos[0].at, 'number');
    assert.match(sos[0].id, /^[0-9a-f-]{36}$/);
  }
  // owner is not notified about her own SOS
  assert.equal((await notifications(ctx.app, ward)).length, 0);

  const again = await ctx.api().put(`/api/v1/incidents/${inc.id}`).set(ward.auth)
    .send({ token: inc.token, source: 'voice', battery: 55 });
  assert.equal(again.status, 200);
  assert.deepEqual(again.body, { id: inc.id, trackUrl: inc.trackUrl });
  assert.equal(ofType(await notifications(ctx.app, g1), 'sos', inc.id).length, 1, 'no duplicate sos');
});

test('token and source are immutable after create', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u, { source: 'power' });
  const res = await ctx.api().put(`/api/v1/incidents/${inc.id}`).set(u.auth)
    .send({ token: trackToken(), source: 'voice', silent: true });
  assert.equal(res.status, 200);
  assert.equal(res.body.trackUrl, inc.trackUrl);
  const { rows } = await ctx.pool.query('SELECT track_token, source, silent FROM incidents WHERE id = $1', [inc.id]);
  assert.deepEqual(rows[0], { track_token: inc.token, source: 'power', silent: true });
});

test('validation and ownership: other users get 404', async () => {
  const u = await register(ctx.app);
  const other = await register(ctx.app);
  const id = crypto.randomUUID();
  assert.equal((await ctx.api().put(`/api/v1/incidents/${id}`).set(u.auth).send({ token: 'short', source: 'voice' })).status, 400);
  assert.equal((await ctx.api().put(`/api/v1/incidents/${id}`).set(u.auth).send({ token: trackToken(), source: 'Bad Source' })).status, 400);
  const inc = await createIncident(ctx.app, u);
  assert.equal((await ctx.api().put(`/api/v1/incidents/${inc.id}`).set(other.auth).send({ token: trackToken(), source: 'voice' })).status, 404);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/locations`, other, { points: [{ lat: 1, lng: 1 }] })).status, 404);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/battery`, other, { battery: 5 })).status, 404);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/duress`, other, {})).status, 404);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/end`, other, {})).status, 404);
  assert.equal((await post('/api/v1/incidents/not-a-uuid/end', u, {})).status, 404);
  // reusing someone else's tracking token
  const dup = await ctx.api().put(`/api/v1/incidents/${crypto.randomUUID()}`).set(other.auth).send({ token: inc.token, source: 'voice' });
  assert.equal(dup.status, 409);
  assert.equal(dup.body.error, 'token_conflict');
});

test('locations: validation and 100-point cap', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const p = { lat: 12.9, lng: 77.6, accuracy: 10, at: Date.now() };
  assert.equal((await post(`/api/v1/incidents/${inc.id}/locations`, u, { points: [] })).status, 400);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/locations`, u, { points: [{ lat: 91, lng: 0 }] })).status, 400);
  const many = await post(`/api/v1/incidents/${inc.id}/locations`, u, { points: Array(101).fill(p) });
  assert.equal(many.status, 400);
  assert.equal(many.body.error, 'too_many_points');
  assert.equal((await post(`/api/v1/incidents/${inc.id}/locations`, u, { points: Array(100).fill(p) })).status, 204);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/battery`, u, { battery: 12 })).status, 204);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/battery`, u, { battery: 101 })).status, 400);
});

test('helper fan-out: <10 within 2 km extends to 5 km; stale/owner/guardians excluded; exactly once', async () => {
  const area = freshArea();
  const ward = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  await linkGuardian(ctx.app, ward, guardian);
  const near = await register(ctx.app, 'Near');
  const edge = await register(ctx.app, 'Edge');
  const far = await register(ctx.app, 'Far');
  const tooFar = await register(ctx.app, 'Too far');
  const stale = await register(ctx.app, 'Stale');
  await setHelper(ctx.app, near, north(area, 800));
  await setHelper(ctx.app, edge, north(area, 1950));
  await setHelper(ctx.app, far, north(area, 2600));
  await setHelper(ctx.app, tooFar, north(area, 5600));
  await setHelper(ctx.app, stale, north(area, 300));
  await setHelper(ctx.app, guardian, north(area, 100));
  await setHelper(ctx.app, ward, area);
  await ctx.pool.query(`UPDATE helpers SET updated_at = now() - interval '25 hours' WHERE user_id = $1`, [stale.userId]);

  const inc = await createIncident(ctx.app, ward);
  assert.equal(ofType(await notifications(ctx.app, near), 'helper_alert').length, 0, 'no location yet');

  const loc = { points: [{ lat: area.lat, lng: area.lng, accuracy: 8, at: Date.now() }] };
  assert.equal((await post(`/api/v1/incidents/${inc.id}/locations`, ward, loc)).status, 204);

  const nearAlerts = ofType(await notifications(ctx.app, near), 'helper_alert', inc.id);
  assert.equal(nearAlerts.length, 1);
  const a = nearAlerts[0];
  assert.ok(Math.abs(a.distanceM - 800) <= 5, `distance ${a.distanceM}`);
  assert.equal(a.lat, area.lat);
  assert.equal(a.lng, area.lng);
  assert.equal(a.trackUrl, inc.trackUrl);
  assert.equal(a.ownerName, undefined, 'helpers never get the owner name');
  assert.equal(a.radiusKm, 5, 'fewer than 10 helpers within 2 km -> 5 km tier');
  assert.equal(a.evidenceCount, 0);
  assert.equal(a.photoUrl, null);
  assert.equal(ofType(await notifications(ctx.app, edge), 'helper_alert', inc.id).length, 1);
  const farAlert = ofType(await notifications(ctx.app, far), 'helper_alert', inc.id);
  assert.equal(farAlert.length, 1, '2.6 km helper reached via the 5 km tier');
  assert.equal(farAlert[0].radiusKm, 5);
  assert.equal(ofType(await notifications(ctx.app, tooFar), 'helper_alert').length, 0);
  assert.equal(ofType(await notifications(ctx.app, stale), 'helper_alert').length, 0);
  assert.equal(ofType(await notifications(ctx.app, guardian), 'helper_alert').length, 0);
  assert.equal(ofType(await notifications(ctx.app, ward), 'helper_alert').length, 0);

  // more locations never fan out again
  await post(`/api/v1/incidents/${inc.id}/locations`, ward, { points: [{ lat: area.lat + 0.001, lng: area.lng }] });
  assert.equal(ofType(await notifications(ctx.app, near), 'helper_alert', inc.id).length, 1);
  const { rows } = await ctx.pool.query('SELECT count(*)::int AS n FROM incident_helpers WHERE incident_id = $1', [inc.id]);
  assert.equal(rows[0].n, 3);
});

test('helper opt-out removes the helper', async () => {
  const u = await register(ctx.app);
  await setHelper(ctx.app, u, freshArea());
  assert.equal((await ctx.api().put('/api/v1/helper').set(u.auth).send({ enabled: false })).status, 204);
  const { rows } = await ctx.pool.query('SELECT 1 FROM helpers WHERE user_id = $1', [u.userId]);
  assert.equal(rows.length, 0);
  assert.equal((await ctx.api().put('/api/v1/helper').set(u.auth).send({ enabled: true, lat: 200, lng: 0 })).status, 400);
});

test('duress alerts guardians once; ending afterwards never sends "ended"', async () => {
  const area = freshArea();
  const ward = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  const helper = await register(ctx.app, 'Helper');
  await linkGuardian(ctx.app, ward, guardian);
  await setHelper(ctx.app, helper, north(area, 500));
  const inc = await createIncident(ctx.app, ward);
  await post(`/api/v1/incidents/${inc.id}/locations`, ward, { points: [{ lat: area.lat, lng: area.lng }] });

  assert.equal((await post(`/api/v1/incidents/${inc.id}/duress`, ward, {})).status, 204);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/duress`, ward, {})).status, 204);
  const d = ofType(await notifications(ctx.app, guardian), 'duress', inc.id);
  assert.equal(d.length, 1);
  assert.equal(d[0].ownerName, 'Asha');
  assert.equal(d[0].trackUrl, inc.trackUrl);

  assert.equal((await post(`/api/v1/incidents/${inc.id}/end`, ward, { userInitiated: true })).status, 204);
  assert.equal(ofType(await notifications(ctx.app, guardian), 'ended').length, 0);
  assert.equal(ofType(await notifications(ctx.app, helper), 'ended').length, 0);
  const { rows } = await ctx.pool.query('SELECT status, duress FROM incidents WHERE id = $1', [inc.id]);
  assert.deepEqual(rows[0], { status: 'ended', duress: true });
});

test('end: guardians and alerted helpers get ended exactly once', async () => {
  const area = freshArea();
  const ward = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  const helper = await register(ctx.app, 'Helper');
  const bystander = await register(ctx.app, 'Far away');
  await linkGuardian(ctx.app, ward, guardian);
  await setHelper(ctx.app, helper, north(area, 400));
  await setHelper(ctx.app, bystander, north(area, 7000));
  const inc = await createIncident(ctx.app, ward);
  await post(`/api/v1/incidents/${inc.id}/locations`, ward, { points: [{ lat: area.lat, lng: area.lng }] });

  assert.equal((await post(`/api/v1/incidents/${inc.id}/end`, ward, { userInitiated: true })).status, 204);
  assert.equal((await post(`/api/v1/incidents/${inc.id}/end`, ward, {})).status, 204);
  const ge = ofType(await notifications(ctx.app, guardian), 'ended', inc.id);
  assert.equal(ge.length, 1);
  assert.equal(ge[0].ownerName, 'Asha');
  const he = ofType(await notifications(ctx.app, helper), 'ended', inc.id);
  assert.equal(he.length, 1);
  assert.equal(he[0].ownerName, undefined);
  assert.equal(ofType(await notifications(ctx.app, bystander), 'ended').length, 0);

  const list = await ctx.api().get('/api/v1/incidents').set(ward.auth);
  assert.equal(list.status, 200);
  const item = list.body.incidents.find((i) => i.id === inc.id);
  assert.equal(item.status, 'ended');
  assert.equal(item.source, 'voice');
  assert.equal(typeof item.startedAt, 'number');
  assert.equal(typeof item.endedAt, 'number');
  assert.equal(item.evidenceCount, 0);
  assert.equal(item.verifiedCount, 0);
  assert.equal(item.trackUrl, inc.trackUrl);
  assert.deepEqual(Object.keys(item).sort(),
    ['endedAt', 'evidenceCount', 'id', 'source', 'startedAt', 'status', 'trackUrl', 'verifiedCount']);
});

test('incident list is newest first and only the owner\'s', async () => {
  const u = await register(ctx.app);
  const other = await register(ctx.app);
  await createIncident(ctx.app, other);
  const older = await createIncident(ctx.app, u, { startedAt: Date.now() - 60_000 });
  const newer = await createIncident(ctx.app, u, { startedAt: Date.now() });
  const list = (await ctx.api().get('/api/v1/incidents').set(u.auth)).body.incidents;
  assert.deepEqual(list.map((i) => i.id), [newer.id, older.id]);
});
