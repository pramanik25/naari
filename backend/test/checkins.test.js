'use strict';

const crypto = require('crypto');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const {
  makeCtx, register, linkGuardian, notifications, ofType, freshArea, north, setHelper,
} = require('./helpers');
const { createSms } = require('../src/sms');
const { createCheckinSweeper } = require('../src/jobs/checkinSweeper');

let ctx;
let sweeper;
const smsCalls = [];
before(() => {
  ctx = makeCtx();
  const twilioConfig = { ...ctx.config, twilio: { accountSid: 'AC123', authToken: 'secret', from: '+15550001111' } };
  const fetchImpl = async (url, init) => {
    smsCalls.push({ url, init, params: new URLSearchParams(init.body) });
    return new Response(JSON.stringify({ sid: `SM${smsCalls.length}` }), { status: 201 });
  };
  sweeper = createCheckinSweeper({ pool: ctx.pool, config: ctx.config, sms: createSms({ config: twilioConfig, fetchImpl }), log: ctx.log });
});
after(async () => { await sweeper.stop(); await ctx.close(); });

const put = (u, id, body) => ctx.api().put(`/api/v1/checkins/${id}`).set(u.auth).send(body);
const post = (u, path, body = {}) => ctx.api().post(path).set(u.auth).send(body);
const overdue = (id) => ctx.pool.query(`UPDATE checkins SET deadline = now() - interval '2 minutes' WHERE id = $1`, [id]);

test('deadline must be 1 min .. 24 h ahead', async () => {
  const u = await register(ctx.app);
  assert.equal((await put(u, crypto.randomUUID(), { deadline: Date.now() + 10_000 })).status, 400);
  assert.equal((await put(u, crypto.randomUUID(), { deadline: Date.now() + 25 * 3600_000 })).status, 400);
  assert.equal((await put(u, crypto.randomUUID(), { deadline: 'soon' })).status, 400);
  assert.equal((await put(u, crypto.randomUUID(), { deadline: Date.now() + 120_000, contacts: ['not a phone'] })).status, 400);
});

test('lifecycle: create, location, extend, complete (idempotent), ownership', async () => {
  const u = await register(ctx.app);
  const other = await register(ctx.app);
  const id = crypto.randomUUID();
  const res = await put(u, id, { deadline: Date.now() + 30 * 60_000, note: 'Walking home', contacts: ['+91 98765 43210'] });
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { id, status: 'active' });
  assert.equal((await put(u, id, { deadline: Date.now() + 40 * 60_000, note: 'Walking home' })).status, 200);
  assert.equal((await post(u, `/api/v1/checkins/${id}/location`, { lat: 28.61, lng: 77.2, accuracy: 12, at: Date.now() })).status, 204);
  assert.equal((await post(u, `/api/v1/checkins/${id}/extend`, { deadline: Date.now() + 60 * 60_000 })).status, 204);
  assert.equal((await post(other, `/api/v1/checkins/${id}/extend`, { deadline: Date.now() + 60 * 60_000 })).status, 404);
  assert.equal((await post(other, `/api/v1/checkins/${id}/complete`)).status, 404);
  assert.equal((await post(u, `/api/v1/checkins/${id}/complete`)).status, 204);
  assert.equal((await post(u, `/api/v1/checkins/${id}/complete`)).status, 204);
  const ext = await post(u, `/api/v1/checkins/${id}/extend`, { deadline: Date.now() + 60 * 60_000 });
  assert.equal(ext.status, 409);
  assert.equal(ext.body.error, 'checkin_not_active');
  const { rows } = await ctx.pool.query('SELECT status, last_lat, contacts FROM checkins WHERE id = $1', [id]);
  assert.deepEqual(rows[0], { status: 'completed', last_lat: 28.61, contacts: [] });

  const c2 = crypto.randomUUID();
  await put(u, c2, { deadline: Date.now() + 5 * 60_000 });
  assert.equal((await post(u, `/api/v1/checkins/${c2}/cancel`)).status, 204);
  await overdue(c2);
  const escalated = await sweeper.runOnce();
  assert.ok(!escalated.some((e) => e.checkinId === c2), 'cancelled check-ins are never escalated');
});

test('sweeper escalates an overdue check-in exactly once: incident + checkin_overdue + SMS + helpers', async () => {
  const area = freshArea();
  const u = await register(ctx.app, 'Asha');
  const guardian = await register(ctx.app, 'Mum');
  const helper = await register(ctx.app, 'Helper');
  await linkGuardian(ctx.app, u, guardian);
  await setHelper(ctx.app, helper, north(area, 600));
  const id = crypto.randomUUID();
  await put(u, id, { deadline: Date.now() + 2 * 60_000, note: 'Walking home', contacts: ['+919876543210', '+15551234567'] });
  await post(u, `/api/v1/checkins/${id}/location`, { lat: area.lat, lng: area.lng, accuracy: 9, at: Date.now() });

  // not yet due (deadline in the future, and grace of 60 s)
  assert.ok(!(await sweeper.runOnce()).some((e) => e.checkinId === id));
  await ctx.pool.query(`UPDATE checkins SET deadline = now() - interval '30 seconds' WHERE id = $1`, [id]);
  assert.ok(!(await sweeper.runOnce()).some((e) => e.checkinId === id), 'within the 60 s grace');

  await overdue(id);
  smsCalls.length = 0;
  const [first, second] = await Promise.all([sweeper.runOnce(), sweeper.runOnce()]);
  const mine = first.filter((e) => e.checkinId === id);
  assert.equal(mine.length, 1);
  assert.equal(second.filter((e) => e.checkinId === id).length, 1, 'concurrent calls share one run');
  assert.equal((await sweeper.runOnce()).filter((e) => e.checkinId === id).length, 0, 'never twice');

  const { incidentId } = mine[0];
  const { rows: [inc] } = await ctx.pool.query('SELECT * FROM incidents WHERE id = $1', [incidentId]);
  assert.equal(inc.source, 'checkin');
  assert.equal(inc.status, 'active');
  assert.equal(inc.user_id, u.userId);
  assert.equal(inc.last_lat, area.lat);
  const { rows: [ck] } = await ctx.pool.query('SELECT status, incident_id FROM checkins WHERE id = $1', [id]);
  assert.deepEqual(ck, { status: 'overdue', incident_id: incidentId });

  const n = ofType(await notifications(ctx.app, guardian), 'checkin_overdue', incidentId);
  assert.equal(n.length, 1);
  assert.equal(n[0].ownerName, 'Asha');
  assert.equal(n[0].note, 'Walking home');
  assert.equal(n[0].trackUrl, `https://naari.test/t/${inc.track_token}`);
  assert.equal(ofType(await notifications(ctx.app, helper), 'helper_alert', incidentId).length, 1);

  assert.equal(smsCalls.length, 2);
  assert.equal(smsCalls[0].url, 'https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json');
  assert.equal(smsCalls[0].init.headers.Authorization, `Basic ${Buffer.from('AC123:secret').toString('base64')}`);
  assert.deepEqual(smsCalls.map((c) => c.params.get('To')), ['+919876543210', '+15551234567']);
  assert.equal(smsCalls[0].params.get('From'), '+15550001111');
  assert.ok(smsCalls[0].params.get('Body').includes(`/t/${inc.track_token}`));

  // tracking page works without her phone
  const t = await ctx.api().get(`/api/v1/track/${inc.track_token}`);
  assert.equal(t.status, 200);
  assert.equal(t.body.source, 'checkin');
  assert.equal(t.body.lastLocation.lat, area.lat);

  // later she marks herself safe: incident ends, guardians get "ended"
  assert.equal((await post(u, `/api/v1/checkins/${id}/complete`)).status, 204);
  assert.equal(ofType(await notifications(ctx.app, guardian), 'ended', incidentId).length, 1);
});

test('sweeper without Twilio still escalates (no SMS)', async () => {
  const plain = createCheckinSweeper({ pool: ctx.pool, config: ctx.config, sms: createSms({ config: ctx.config }), log: ctx.log });
  const u = await register(ctx.app);
  const id = crypto.randomUUID();
  await put(u, id, { deadline: Date.now() + 2 * 60_000, contacts: ['+919876543210'] });
  await overdue(id);
  smsCalls.length = 0;
  const out = await plain.runOnce();
  assert.equal(out.filter((e) => e.checkinId === id).length, 1);
  assert.equal(smsCalls.length, 0);
  const { rows } = await ctx.pool.query('SELECT i.last_lat FROM incidents i JOIN checkins c ON c.incident_id = i.id WHERE c.id = $1', [id]);
  assert.equal(rows[0].last_lat, null);
});
