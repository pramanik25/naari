'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const {
  makeCtx, register, linkGuardian, createIncident, notifications, ofType, freshArea, north, setHelper,
} = require('./helpers');

let ctx;
before(() => { ctx = makeCtx(); });
after(() => ctx.close());

test('only alerted helpers can respond; owner + guardians get responder once', async () => {
  const area = freshArea();
  const ward = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  const helper = await register(ctx.app, 'Riya');
  const stranger = await register(ctx.app, 'Stranger');
  await linkGuardian(ctx.app, ward, guardian);
  await setHelper(ctx.app, helper, north(area, 700));
  const inc = await createIncident(ctx.app, ward);

  // before fan-out nobody is an alerted helper
  assert.equal((await ctx.api().post(`/api/v1/alerts/${inc.id}/respond`).set(helper.auth).send(north(area, 700))).status, 404);

  await ctx.api().post(`/api/v1/incidents/${inc.id}/locations`).set(ward.auth).send({ points: [{ lat: area.lat, lng: area.lng }] });

  for (const u of [stranger, guardian, ward]) {
    const r = await ctx.api().post(`/api/v1/alerts/${inc.id}/respond`).set(u.auth).send(area);
    assert.equal(r.status, 404, u.name);
  }
  assert.equal((await ctx.api().post(`/api/v1/alerts/${inc.id}/respond`).set(helper.auth).send({ lat: 'x' })).status, 400);

  const res = await ctx.api().post(`/api/v1/alerts/${inc.id}/respond`).set(helper.auth).send(north(area, 500));
  assert.equal(res.status, 200);
  assert.equal(res.body.ok, true);
  assert.ok(Math.abs(res.body.distanceM - 500) <= 5);
  assert.equal(res.body.trackUrl, inc.trackUrl);
  // repeated tap: 200 again, but no duplicate notifications
  assert.equal((await ctx.api().post(`/api/v1/alerts/${inc.id}/respond`).set(helper.auth).send(north(area, 400))).status, 200);

  for (const u of [ward, guardian]) {
    const r = ofType(await notifications(ctx.app, u), 'responder', inc.id);
    assert.equal(r.length, 1, u.name);
    assert.equal(r[0].helperName, 'Riya');
    assert.ok(Math.abs(r[0].distanceM - 500) <= 5);
  }
  assert.equal(ofType(await notifications(ctx.app, helper), 'responder').length, 0);

  const t = (await ctx.api().get(`/api/v1/track/${inc.token}`)).body;
  assert.equal(t.respondersCount, 1);
  assert.equal(t.helpersNotified, 1);

  await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(ward.auth).send({ userInitiated: true });
  const late = await ctx.api().post(`/api/v1/alerts/${inc.id}/respond`).set(helper.auth).send(area);
  assert.equal(late.status, 409);
  assert.equal(late.body.error, 'incident_ended');
});
