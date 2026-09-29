'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const {
  makeCtx, register, linkGuardian, createIncident, notifications, ofType, freshArea, north, setHelper, uploadPhoto,
} = require('./helpers');

let ctx;
before(() => { ctx = makeCtx({ limits: { pagePerMinute: 10_000, trackPerMinute: 10_000 } }); });
after(() => ctx.close());

const locate = (u, id, p) => ctx.api().post(`/api/v1/incidents/${id}/locations`).set(u.auth).send({ points: [{ lat: p.lat, lng: p.lng, at: Date.now() }] });

test('broadcast=false: no helper fan-out, guardians still get sos; turning it on fans out', async () => {
  const area = freshArea();
  const ward = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  const helper = await register(ctx.app, 'Near');
  await linkGuardian(ctx.app, ward, guardian);
  await setHelper(ctx.app, helper, north(area, 300));
  const inc = await createIncident(ctx.app, ward, { broadcast: false });
  assert.equal((await locate(ward, inc.id, area)).status, 204);
  assert.equal(ofType(await notifications(ctx.app, helper), 'helper_alert').length, 0);
  assert.equal(ofType(await notifications(ctx.app, guardian), 'sos', inc.id).length, 1);
  const { rows } = await ctx.pool.query('SELECT broadcast, helpers_fanned_out_at FROM incidents WHERE id = $1', [inc.id]);
  assert.deepEqual(rows[0], { broadcast: false, helpers_fanned_out_at: null });

  const bad = await ctx.api().put(`/api/v1/incidents/${inc.id}`).set(ward.auth).send({ broadcast: 'yes' });
  assert.equal(bad.status, 400);
  const on = await ctx.api().put(`/api/v1/incidents/${inc.id}`).set(ward.auth).send({ token: inc.token, source: 'voice', broadcast: true });
  assert.equal(on.status, 200);
  assert.equal(ofType(await notifications(ctx.app, helper), 'helper_alert', inc.id).length, 1);
});

test('fan-out repeats as she moves: a stale first fix far away does not block alerting helpers near her', async () => {
  const area = freshArea();
  const ward = await register(ctx.app, 'Asha');
  const helper = await register(ctx.app, 'Near');
  await setHelper(ctx.app, helper, north(area, 300));
  const inc = await createIncident(ctx.app, ward);
  const back = (ms) => ctx.pool.query(
    `UPDATE incidents SET helpers_fanned_out_at = now() - ($2 || ' milliseconds')::interval WHERE id = $1`, [inc.id, ms]);

  // First point: a stale cached location ~50 km away. Nobody nearby.
  await locate(ward, inc.id, north(area, 50_000));
  assert.equal(ofType(await notifications(ctx.app, helper), 'helper_alert', inc.id).length, 0);

  // Her real location arrives within the same minute: throttled, still nobody.
  await locate(ward, inc.id, area);
  assert.equal(ofType(await notifications(ctx.app, helper), 'helper_alert', inc.id).length, 0);

  // A minute later she has moved ≥250 m from the last search centre: the helper is alerted.
  await back(61_000);
  await locate(ward, inc.id, area);
  assert.equal(ofType(await notifications(ctx.app, helper), 'helper_alert', inc.id).length, 1);

  // Later searches never alert the same helper twice.
  await back(5 * 60_000 + 1000);
  await locate(ward, inc.id, area);
  assert.equal(ofType(await notifications(ctx.app, helper), 'helper_alert', inc.id).length, 1);
});

test('fan-out every 5 min reaches a helper who opted in after the first search', async () => {
  const area = freshArea();
  const ward = await register(ctx.app, 'Asha');
  const inc = await createIncident(ctx.app, ward);
  await locate(ward, inc.id, area);
  const late = await register(ctx.app, 'Late');
  await setHelper(ctx.app, late, north(area, 500));

  // Not moved, under 5 minutes: no new search.
  await ctx.pool.query(`UPDATE incidents SET helpers_fanned_out_at = now() - interval '2 minutes' WHERE id = $1`, [inc.id]);
  await locate(ward, inc.id, area);
  assert.equal(ofType(await notifications(ctx.app, late), 'helper_alert', inc.id).length, 0);

  // Five minutes on: the periodic search alerts her.
  await ctx.pool.query(`UPDATE incidents SET helpers_fanned_out_at = now() - interval '301 seconds' WHERE id = $1`, [inc.id]);
  await locate(ward, inc.id, area);
  assert.equal(ofType(await notifications(ctx.app, late), 'helper_alert', inc.id).length, 1);
});

test('broadcast defaults to true', async () => {
  const u = await register(ctx.app);
  const inc = await createIncident(ctx.app, u);
  const { rows } = await ctx.pool.query('SELECT broadcast FROM incidents WHERE id = $1', [inc.id]);
  assert.equal(rows[0].broadcast, true);
});

test('10+ helpers within 2 km: stays at the 2 km tier', async () => {
  const area = freshArea();
  const ward = await register(ctx.app);
  const inner = [];
  for (let i = 0; i < 10; i++) {
    const h = await register(ctx.app, `H${i}`);
    await setHelper(ctx.app, h, north(area, 100 + i * 150));
    inner.push(h);
  }
  const outer = await register(ctx.app, 'Outer');
  await setHelper(ctx.app, outer, north(area, 3000));
  const inc = await createIncident(ctx.app, ward);
  await locate(ward, inc.id, area);
  for (const h of inner) {
    const a = ofType(await notifications(ctx.app, h), 'helper_alert', inc.id);
    assert.equal(a.length, 1);
    assert.equal(a[0].radiusKm, 2);
  }
  assert.equal(ofType(await notifications(ctx.app, outer), 'helper_alert').length, 0);
  const { rows } = await ctx.pool.query('SELECT DISTINCT radius_km FROM incident_helpers WHERE incident_id = $1', [inc.id]);
  assert.deepEqual(rows, [{ radius_km: 2 }]);
});

test('helper_alert carries evidenceCount and an absolute signed photoUrl that works', async () => {
  const area = freshArea();
  const ward = await register(ctx.app);
  const helper = await register(ctx.app);
  await setHelper(ctx.app, helper, north(area, 500));
  const inc = await createIncident(ctx.app, ward);
  await uploadPhoto(ctx.app, ward, inc.id);
  const newest = await uploadPhoto(ctx.app, ward, inc.id);
  await uploadPhoto(ctx.app, ward, inc.id, { verified: false });
  await locate(ward, inc.id, area);
  const [a] = ofType(await notifications(ctx.app, helper), 'helper_alert', inc.id);
  assert.equal(a.evidenceCount, 2, 'verified items only');
  assert.match(a.photoUrl, new RegExp(`^https://naari\\.test/api/v1/track/${inc.token}/evidence/${newest.evidenceId}\\?exp=\\d+&sig=[0-9a-f]{64}$`));
  const img = await ctx.api().get(a.photoUrl.replace('https://naari.test', ''));
  assert.equal(img.status, 200);
  assert.equal(img.headers['content-type'], 'image/jpeg');
});

test('evidence_update: guardians + alerted helpers, at most once per 60 s per incident', async () => {
  const area = freshArea();
  const ward = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  const helper = await register(ctx.app, 'Near');
  const stranger = await register(ctx.app, 'Stranger');
  await linkGuardian(ctx.app, ward, guardian);
  await setHelper(ctx.app, helper, north(area, 400));
  const inc = await createIncident(ctx.app, ward);
  await locate(ward, inc.id, area);

  await uploadPhoto(ctx.app, ward, inc.id, { verified: false });
  assert.equal(ofType(await notifications(ctx.app, guardian), 'evidence_update').length, 0, 'unverified photos never notify');

  const p1 = await uploadPhoto(ctx.app, ward, inc.id);
  for (const u of [guardian, helper]) {
    const n = ofType(await notifications(ctx.app, u), 'evidence_update', inc.id);
    assert.equal(n.length, 1);
    assert.equal(n[0].incidentId, inc.id);
    assert.equal(n[0].evidenceCount, 1);
    assert.equal(n[0].trackUrl, inc.trackUrl);
    assert.ok(n[0].photoUrl.startsWith(`https://naari.test/api/v1/track/${inc.token}/evidence/${p1.evidenceId}?`));
    assert.deepEqual(Object.keys(n[0]).sort(), ['at', 'evidenceCount', 'id', 'incidentId', 'photoUrl', 'trackUrl', 'type']);
  }
  assert.equal(ofType(await notifications(ctx.app, stranger), 'evidence_update').length, 0);
  assert.equal(ofType(await notifications(ctx.app, ward), 'evidence_update').length, 0);

  await uploadPhoto(ctx.app, ward, inc.id); // within 60 s: throttled
  assert.equal(ofType(await notifications(ctx.app, guardian), 'evidence_update', inc.id).length, 1);

  await ctx.pool.query(`UPDATE incidents SET evidence_update_at = now() - interval '61 seconds' WHERE id = $1`, [inc.id]);
  const p3 = await uploadPhoto(ctx.app, ward, inc.id);
  const after = ofType(await notifications(ctx.app, guardian), 'evidence_update', inc.id);
  assert.equal(after.length, 2);
  assert.equal(after[1].evidenceCount, 3);
  assert.ok(after[1].photoUrl.includes(p3.evidenceId));

  // ended incident: no more updates
  await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(ward.auth).send({});
  await ctx.pool.query(`UPDATE incidents SET evidence_update_at = now() - interval '61 seconds' WHERE id = $1`, [inc.id]);
  await uploadPhoto(ctx.app, ward, inc.id);
  assert.equal(ofType(await notifications(ctx.app, guardian), 'evidence_update', inc.id).length, 2);
});

test('evidence_update still flows under duress after a forced end', async () => {
  const ward = await register(ctx.app);
  const guardian = await register(ctx.app);
  await linkGuardian(ctx.app, ward, guardian);
  const inc = await createIncident(ctx.app, ward);
  await ctx.api().post(`/api/v1/incidents/${inc.id}/duress`).set(ward.auth).send({});
  await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(ward.auth).send({});
  await uploadPhoto(ctx.app, ward, inc.id);
  assert.equal(ofType(await notifications(ctx.app, guardian), 'evidence_update', inc.id).length, 1);
});
