'use strict';

const crypto = require('crypto');
const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const {
  makeCtx, register, createIncident, uploadPhoto, fakeGraph, freshArea,
} = require('./helpers');
const { normalizeNumber } = require('../src/whatsapp');

const WA_ENV = {
  WHATSAPP_TOKEN: 'test-token',
  WHATSAPP_PHONE_NUMBER_ID: 'PNID123',
  WHATSAPP_VERIFY_TOKEN: 'verify-me',
  WHATSAPP_APP_SECRET: 'app-secret',
};

let ctx;
let graph;
before(() => {
  graph = fakeGraph();
  ctx = makeCtx({ env: WA_ENV, whatsappFetch: graph });
});
after(() => ctx.close());
beforeEach(() => graph.reset());

const sign = (raw) => `sha256=${crypto.createHmac('sha256', 'app-secret').update(raw).digest('hex')}`;
const inbound = (from, body) => JSON.stringify({
  object: 'whatsapp_business_account',
  entry: [{ id: 'WABA', changes: [{ field: 'messages', value: { messaging_product: 'whatsapp', messages: [{ from, id: `wamid.in.${Math.random()}`, type: 'text', text: { body } }] } }] }],
});
async function webhook(raw, signature = sign(raw)) {
  const req = ctx.api().post('/api/v1/whatsapp/webhook').set('Content-Type', 'application/json');
  if (signature) req.set('X-Hub-Signature-256', signature);
  const res = await req.send(raw);
  await ctx.whatsapp.idle();
  return res;
}
const setContacts = (u, contacts, enabled = true) => ctx.api().put('/api/v1/me/whatsapp').set(u.auth).send({ enabled, contacts });
const randIndian = () => `9${String(crypto.randomInt(0, 1e9)).padStart(9, '0')}`;
const locate = (u, id, p) => ctx.api().post(`/api/v1/incidents/${id}/locations`).set(u.auth).send({ points: [{ lat: p.lat, lng: p.lng, at: Date.now() }] });
const logRows = async (incidentId, kind) => (await ctx.pool.query(
  'SELECT to_number, status, attempts, template FROM whatsapp_messages WHERE incident_id = $1 AND kind = $2 ORDER BY created_at', [incidentId, kind])).rows;

/** A user with one opted-in contact (a) and one not opted in (b). */
async function userWithContacts(name = 'Asha') {
  const u = await register(ctx.app, name);
  const a = `+91${randIndian()}`;
  const b = `+91${randIndian()}`;
  await setContacts(u, [{ name: 'A', number: a }, { name: 'B', number: b }]);
  await webhook(inbound(a.slice(1), 'JOIN'));
  graph.reset();
  return { u, a, b };
}

test('number normalisation', () => {
  assert.equal(normalizeNumber('98123 45678'), '+919812345678');
  assert.equal(normalizeNumber('098123-45678'), '+919812345678');
  assert.equal(normalizeNumber('919812345678'), '+919812345678');
  assert.equal(normalizeNumber('+44 20 7946 0958'), '+442079460958');
  assert.equal(normalizeNumber('0044 20 7946 0958'), '+442079460958');
  assert.equal(normalizeNumber('12345'), null);
  assert.equal(normalizeNumber('call me'), null);
  assert.equal(normalizeNumber(null), null);
});

test('PUT/GET /me/whatsapp: normalise, drop invalid, max 5, joinUrl, disable deletes', async () => {
  const u = await register(ctx.app);
  const res = await setContacts(u, [
    { name: 'Riya', number: '98123 45678' },
    { name: 'Dup', number: '+919812345678' },
    { name: 'Bad', number: 'not a number' },
    { name: 'UK', number: '+44 20 7946 0958' },
  ]);
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, {
    enabled: true,
    contacts: [
      { name: 'Riya', number: '+919812345678', optedIn: false },
      { name: 'UK', number: '+442079460958', optedIn: false },
    ],
    joinUrl: 'https://wa.me/919000011111?text=JOIN',
    configured: true,
  });
  assert.deepEqual((await ctx.api().get('/api/v1/me/whatsapp').set(u.auth)).body, res.body);
  const six = Array.from({ length: 6 }, () => ({ name: 'x', number: randIndian() }));
  const tooMany = await setContacts(u, six);
  assert.equal(tooMany.status, 400);
  assert.equal(tooMany.body.error, 'too_many_contacts');
  const off = await ctx.api().put('/api/v1/me/whatsapp').set(u.auth).send({ enabled: false });
  assert.equal(off.status, 200);
  assert.equal(off.body.enabled, false);
  assert.deepEqual(off.body.contacts, []);
  assert.equal((await ctx.api().get('/api/v1/me/whatsapp')).status, 401);
});

test('webhook verification (GET)', async () => {
  const ok = await ctx.api().get('/api/v1/whatsapp/webhook')
    .query({ 'hub.mode': 'subscribe', 'hub.verify_token': 'verify-me', 'hub.challenge': '12345' });
  assert.equal(ok.status, 200);
  assert.equal(ok.text, '12345');
  const bad = await ctx.api().get('/api/v1/whatsapp/webhook')
    .query({ 'hub.mode': 'subscribe', 'hub.verify_token': 'nope', 'hub.challenge': '12345' });
  assert.equal(bad.status, 403);
});

test('webhook rejects missing or wrong X-Hub-Signature-256', async () => {
  const u = await register(ctx.app);
  const n = `+91${randIndian()}`;
  await setContacts(u, [{ name: 'A', number: n }]);
  const raw = inbound(n.slice(1), 'JOIN');
  assert.equal((await webhook(raw, null)).status, 401);
  assert.equal((await webhook(raw, sign(`${raw} `))).status, 401);
  assert.equal((await webhook(raw, 'sha256=zz')).status, 401);
  assert.equal((await ctx.api().get('/api/v1/me/whatsapp').set(u.auth)).body.contacts[0].optedIn, false);
  assert.equal(graph.calls.length, 0);
});

test('JOIN opts a number in for every user who listed it (+ confirmation); STOP opts out', async () => {
  const n = `+91${randIndian()}`;
  const u1 = await register(ctx.app, 'Asha');
  const u2 = await register(ctx.app, 'Meera');
  const u3 = await register(ctx.app, 'Other');
  await setContacts(u1, [{ name: 'Mum', number: n }]);
  await setContacts(u2, [{ name: 'Aunty', number: n.slice(3) }]);
  await setContacts(u3, [{ name: 'X', number: `+91${randIndian()}` }]);

  const res = await webhook(inbound(n.slice(1), 'नमस्ते join'));
  assert.equal(res.status, 200);
  for (const u of [u1, u2]) {
    assert.equal((await ctx.api().get('/api/v1/me/whatsapp').set(u.auth)).body.contacts[0].optedIn, true);
  }
  assert.equal((await ctx.api().get('/api/v1/me/whatsapp').set(u3.auth)).body.contacts[0].optedIn, false);
  const [reply] = graph.messages();
  assert.equal(reply.to, n.slice(1));
  assert.equal(reply.type, 'text');
  assert.match(reply.text.body, /Asha/);
  assert.match(reply.text.body, /Meera/);
  assert.match(reply.text.body, /STOP/);

  graph.reset();
  await webhook(inbound(n.slice(1), 'STOP'));
  for (const u of [u1, u2]) {
    assert.equal((await ctx.api().get('/api/v1/me/whatsapp').set(u.auth)).body.contacts[0].optedIn, false);
  }
  assert.equal(graph.messages()[0].type, 'text');

  // re-saving the list keeps opt-in state of numbers already listed
  await webhook(inbound(n.slice(1), 'JOIN'));
  await setContacts(u1, [{ name: 'Mum (renamed)', number: n }, { name: 'New', number: randIndian() }]);
  const c = (await ctx.api().get('/api/v1/me/whatsapp').set(u1.auth)).body.contacts;
  assert.deepEqual(c.map((x) => [x.name, x.optedIn]), [['Mum (renamed)', true], ['New', false]]);
});

test('SOS: LOCATION header template once a location exists, only to opted-in contacts of the owner', async () => {
  const { u, a } = await userWithContacts('Asha');
  const other = await userWithContacts('Someone else');
  const inc = await createIncident(ctx.app, u);
  await ctx.whatsapp.idle();
  assert.equal(graph.messages().length, 0, 'no location yet');

  const p = freshArea();
  await locate(u, inc.id, p);
  await ctx.whatsapp.idle();
  const msgs = graph.messages();
  assert.equal(msgs.length, 1);
  const m = msgs[0];
  assert.equal(m.messaging_product, 'whatsapp');
  assert.equal(m.to, a.slice(1));
  assert.equal(m.type, 'template');
  assert.equal(m.template.name, 'naari_sos_alert');
  assert.deepEqual(m.template.language, { code: 'en' });
  const [header, bodyC] = m.template.components;
  assert.equal(header.type, 'header');
  assert.equal(header.parameters[0].type, 'location');
  assert.equal(header.parameters[0].location.latitude, String(p.lat));
  assert.equal(header.parameters[0].location.longitude, String(p.lng));
  assert.ok(header.parameters[0].location.name);
  assert.ok(header.parameters[0].location.address);
  assert.deepEqual(bodyC, { type: 'body', parameters: [{ type: 'text', text: 'Asha' }, { type: 'text', text: inc.trackUrl }] });
  const call = graph.calls.find((c) => c.path.endsWith('/messages'));
  assert.equal(call.path, '/v20.0/PNID123/messages');
  assert.equal(call.headers.Authorization, 'Bearer test-token');
  assert.ok(!graph.messages().some((x) => x.to === other.a.slice(1)));

  graph.reset();
  await locate(u, inc.id, p);
  await ctx.whatsapp.idle();
  assert.equal(graph.messages().length, 0, 'SOS is sent once');
  const log = await logRows(inc.id, 'sos');
  assert.deepEqual(log.map((r) => [r.to_number, r.status, r.template]), [[a, 'sent', 'naari_sos_alert']]);
});

test('no location after the delay: text-only fallback exactly once; a later location still sends the pin', async () => {
  const { u } = await userWithContacts('Asha');
  const inc = await createIncident(ctx.app, u);
  await ctx.whatsapp.idle();
  assert.equal(await ctx.whatsapp.runFallbackSweep(), 0, 'too early');
  await ctx.pool.query(`UPDATE incidents SET created_at = now() - interval '2 minutes' WHERE id = $1`, [inc.id]);
  const [first, second] = await Promise.all([ctx.whatsapp.runFallbackSweep(), ctx.whatsapp.runFallbackSweep()]);
  assert.equal(first + second, 1);
  assert.equal(await ctx.whatsapp.runFallbackSweep(), 0);
  const [m] = graph.messages();
  assert.equal(m.template.name, 'naari_sos_alert_text');
  assert.deepEqual(m.template.components, [{ type: 'body', parameters: [{ type: 'text', text: 'Asha' }, { type: 'text', text: inc.trackUrl }] }]);

  graph.reset();
  await locate(u, inc.id, freshArea());
  await ctx.whatsapp.idle();
  assert.equal(graph.messages().length, 1);
  assert.equal(graph.messages()[0].template.name, 'naari_sos_alert');
  assert.equal((await logRows(inc.id, 'sos_text')).length, 1);
});

test('fallback is not sent for an incident that already ended', async () => {
  const { u } = await userWithContacts();
  const inc = await createIncident(ctx.app, u);
  await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(u.auth).send({});
  await ctx.pool.query(`UPDATE incidents SET created_at = now() - interval '2 minutes' WHERE id = $1`, [inc.id]);
  await ctx.whatsapp.runFallbackSweep();
  await ctx.whatsapp.idle();
  assert.equal(graph.messages().length, 0, 'no SOS, and therefore no safe message either');
});

test('evidence: media upload then IMAGE header; 1 per contact per 60 s; max 10 per contact per incident', async () => {
  const { u, a } = await userWithContacts('Asha');
  const inc = await createIncident(ctx.app, u);
  await locate(u, inc.id, freshArea());
  await ctx.whatsapp.idle();
  graph.reset();

  const photo = await uploadPhoto(ctx.app, u, inc.id);
  await ctx.whatsapp.idle();
  const media = graph.calls.filter((c) => c.path.endsWith('/media'));
  assert.equal(media.length, 1);
  assert.equal(media[0].path, '/v20.0/PNID123/media');
  assert.equal(media[0].form.get('messaging_product'), 'whatsapp');
  assert.equal(media[0].form.get('type'), 'image/jpeg');
  const file = media[0].form.get('file');
  assert.equal(file.size, photo.body.length);
  const mediaId = `media-${graph.calls.indexOf(media[0]) + 1}`;
  const [m] = graph.messages();
  assert.equal(m.to, a.slice(1));
  assert.equal(m.template.name, 'naari_sos_evidence');
  assert.deepEqual(m.template.components[0], { type: 'header', parameters: [{ type: 'image', image: { id: mediaId } }] });
  assert.deepEqual(m.template.components[1], { type: 'body', parameters: [{ type: 'text', text: 'Asha' }, { type: 'text', text: inc.trackUrl }] });

  graph.reset();
  await uploadPhoto(ctx.app, u, inc.id);
  await ctx.whatsapp.idle();
  assert.equal(graph.calls.length, 0, 'second photo within 60 s is throttled (no upload either)');

  await ctx.pool.query(`UPDATE whatsapp_messages SET created_at = now() - interval '61 seconds' WHERE incident_id = $1 AND kind = 'evidence'`, [inc.id]);
  await uploadPhoto(ctx.app, u, inc.id);
  await ctx.whatsapp.idle();
  assert.equal(graph.messages().length, 1, 'allowed again after 60 s');

  // pad the log to 10 per contact, all older than 60 s -> the cap stops further sends
  await ctx.pool.query(
    `INSERT INTO whatsapp_messages (user_id, incident_id, to_number, kind, status, created_at)
     SELECT $1, $2, $3, 'evidence', 'sent', now() - interval '5 minutes' FROM generate_series(1, 8)`, [u.userId, inc.id, a]);
  await ctx.pool.query(`UPDATE whatsapp_messages SET created_at = now() - interval '61 seconds' WHERE incident_id = $1 AND kind = 'evidence'`, [inc.id]);
  graph.reset();
  await uploadPhoto(ctx.app, u, inc.id);
  await ctx.whatsapp.idle();
  assert.equal(graph.calls.length, 0, 'max 10 per contact per incident');
});

test('safe template on a normal end; nothing "safe" under duress', async () => {
  const { u, a } = await userWithContacts('Asha');
  const inc = await createIncident(ctx.app, u);
  await locate(u, inc.id, freshArea());
  await ctx.whatsapp.idle();
  graph.reset();
  await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(u.auth).send({ userInitiated: true });
  await ctx.api().post(`/api/v1/incidents/${inc.id}/end`).set(u.auth).send({});
  await ctx.whatsapp.idle();
  const msgs = graph.messages();
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].to, a.slice(1));
  assert.equal(msgs[0].template.name, 'naari_sos_safe');
  assert.deepEqual(msgs[0].template.components, [{ type: 'body', parameters: [{ type: 'text', text: 'Asha' }] }]);

  const d = await createIncident(ctx.app, u);
  await locate(u, d.id, freshArea());
  await ctx.api().post(`/api/v1/incidents/${d.id}/duress`).set(u.auth).send({});
  await ctx.whatsapp.idle();
  graph.reset();
  await ctx.api().post(`/api/v1/incidents/${d.id}/end`).set(u.auth).send({ userInitiated: true });
  await ctx.whatsapp.idle();
  assert.equal(graph.messages().length, 0);
  assert.equal((await logRows(d.id, 'safe')).length, 0);
});

test('WhatsApp disabled by the owner: nothing is sent even to opted-in numbers', async () => {
  const { u } = await userWithContacts();
  await ctx.pool.query('UPDATE users SET whatsapp_enabled = false WHERE id = $1', [u.userId]);
  const inc = await createIncident(ctx.app, u);
  await locate(u, inc.id, freshArea());
  await ctx.whatsapp.idle();
  assert.equal(graph.messages().length, 0);
});

test('sends retry with backoff and are logged; permanent failure after 4 attempts', async () => {
  const flaky = fakeGraph({ failTimes: 2 });
  const c2 = makeCtx({ env: { ...WA_ENV, WHATSAPP_BUSINESS_NUMBER: '+919000011111' }, whatsappFetch: flaky });
  try {
    const u = await register(c2.app, 'Asha');
    const n = `+91${randIndian()}`;
    await c2.api().put('/api/v1/me/whatsapp').set(u.auth).send({ enabled: true, contacts: [{ name: 'A', number: n }] });
    await c2.pool.query('UPDATE whatsapp_contacts SET opted_in = true WHERE user_id = $1', [u.userId]);
    const inc = await createIncident(c2.app, u);
    await c2.api().post(`/api/v1/incidents/${inc.id}/locations`).set(u.auth).send({ points: [{ lat: 10, lng: 10 }] });
    await c2.whatsapp.idle();
    const { rows: [ok] } = await c2.pool.query(`SELECT status, attempts, provider_message_id FROM whatsapp_messages WHERE incident_id = $1`, [inc.id]);
    assert.equal(ok.status, 'sent');
    assert.equal(ok.attempts, 3);
    assert.match(ok.provider_message_id, /^wamid\./);
  } finally {
    await c2.close();
  }

  const dead = fakeGraph({ failTimes: 100 });
  const c3 = makeCtx({ env: { ...WA_ENV, WHATSAPP_BUSINESS_NUMBER: '+919000011111' }, whatsappFetch: dead });
  try {
    const u = await register(c3.app);
    await c3.api().put('/api/v1/me/whatsapp').set(u.auth).send({ enabled: true, contacts: [{ name: 'A', number: randIndian() }] });
    await c3.pool.query('UPDATE whatsapp_contacts SET opted_in = true WHERE user_id = $1', [u.userId]);
    const inc = await createIncident(c3.app, u);
    const t0 = Date.now();
    const res = await c3.api().post(`/api/v1/incidents/${inc.id}/locations`).set(u.auth).send({ points: [{ lat: 10, lng: 10 }] });
    assert.equal(res.status, 204, 'API never blocked by WhatsApp');
    assert.ok(Date.now() - t0 < 2000);
    await c3.whatsapp.idle();
    const { rows: [bad] } = await c3.pool.query(`SELECT status, attempts, error FROM whatsapp_messages WHERE incident_id = $1`, [inc.id]);
    assert.equal(bad.status, 'failed');
    assert.equal(bad.attempts, 4);
    assert.match(bad.error, /graph 500/);
  } finally {
    await c3.close();
  }
});

test('delivery status callbacks update the log', async () => {
  const { u } = await userWithContacts();
  const inc = await createIncident(ctx.app, u);
  await locate(u, inc.id, freshArea());
  await ctx.whatsapp.idle();
  const { rows: [r] } = await ctx.pool.query(`SELECT provider_message_id FROM whatsapp_messages WHERE incident_id = $1`, [inc.id]);
  const raw = JSON.stringify({ entry: [{ changes: [{ value: { statuses: [{ id: r.provider_message_id, status: 'delivered' }] } }] }] });
  assert.equal((await webhook(raw)).status, 200);
  const { rows: [s] } = await ctx.pool.query(`SELECT status FROM whatsapp_messages WHERE incident_id = $1`, [inc.id]);
  assert.equal(s.status, 'delivered');
});

test('not configured: dormant (GET shows configured=false, webhook 404/403)', async () => {
  const plain = makeCtx();
  try {
    const u = await register(plain.app);
    const g = await plain.api().get('/api/v1/me/whatsapp').set(u.auth);
    assert.deepEqual(g.body, { enabled: false, contacts: [], joinUrl: null, configured: false });
    assert.equal((await plain.api().post('/api/v1/whatsapp/webhook').set('Content-Type', 'application/json').send('{}')).status, 404);
    assert.equal((await plain.api().get('/api/v1/whatsapp/webhook').query({ 'hub.mode': 'subscribe', 'hub.verify_token': 'x', 'hub.challenge': '1' })).status, 403);
  } finally {
    await plain.close();
  }
});
