'use strict';

const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const pino = require('pino');
const request = require('supertest');
const { loadConfig } = require('../src/config');
const { createPool } = require('../src/db');
const { createApp } = require('../src/app');
const { createWhatsApp } = require('../src/whatsapp');

const TEST_DB = process.env.TEST_DATABASE_URL || 'postgres://naari@127.0.0.1:55432/naarishakti_test';
const silent = pino({ level: 'silent' });

function testConfig(env = {}) {
  const evidenceDir = fs.mkdtempSync(path.join(os.tmpdir(), 'naari-ev-'));
  return loadConfig({
    DATABASE_URL: TEST_DB,
    EVIDENCE_DIR: evidenceDir,
    SIGNING_SECRET: 'test-signing-secret-0123456789abcdef',
    PUBLIC_BASE_URL: 'https://naari.test',
    PORT: '0',
    ...env,
  });
}

/** App + pool against the test database. Call ctx.close() in after(). */
function makeCtx({ env, limits, whatsappFetch } = {}) {
  const config = testConfig(env);
  if (config.whatsapp) {
    config.whatsapp.retryBaseMs = 1; // fast retries in tests
    config.whatsapp.fallbackDelayMs = 60_000; // tests drive the fallback via runFallbackSweep()
  }
  const pool = createPool(config.databaseUrl, silent);
  const noFetch = async () => { throw new Error('unexpected network call in test'); };
  const whatsapp = createWhatsApp({ config, pool, log: silent, fetchImpl: whatsappFetch || noFetch });
  const app = createApp({ config, pool, log: silent, whatsapp, limits: { registerPerHour: 10_000, ...limits } });
  return {
    config,
    pool,
    app,
    whatsapp,
    log: silent,
    api: () => request(app),
    async close() {
      await whatsapp.stop();
      for (const l of app.locals.limiters) l.stop();
      await pool.end();
      fs.rmSync(config.evidenceDir, { recursive: true, force: true });
    },
  };
}

async function register(app, name = 'User') {
  const res = await request(app).post('/api/v1/devices/register').send({ deviceName: 'Test phone', name });
  if (res.status !== 201) throw new Error(`register failed: ${res.status} ${JSON.stringify(res.body)}`);
  const { userId, token } = res.body;
  return { userId, token, name, auth: { Authorization: `Bearer ${token}` } };
}

async function linkGuardian(app, ward, guardian) {
  const me = await request(app).get('/api/v1/me').set(ward.auth);
  const res = await request(app).post('/api/v1/guardians/link').set(guardian.auth).send({ code: me.body.guardianCode });
  if (res.status !== 200) throw new Error(`link failed: ${res.status}`);
}

const trackToken = () => crypto.randomBytes(16).toString('base64url');

async function createIncident(app, user, extra = {}) {
  const id = crypto.randomUUID();
  const token = trackToken();
  const res = await request(app).put(`/api/v1/incidents/${id}`).set(user.auth).send({
    token, source: 'voice', silent: false, startedAt: Date.now(), contactsCount: 2, battery: 80, ...extra,
  });
  if (res.status !== 200) throw new Error(`create incident failed: ${res.status} ${JSON.stringify(res.body)}`);
  return { id, token, trackUrl: res.body.trackUrl };
}

async function notifications(app, user, since) {
  const res = await request(app).get(`/api/v1/notifications${since ? `?since=${since}` : ''}`).set(user.auth);
  if (res.status !== 200) throw new Error(`notifications failed: ${res.status}`);
  return res.body.notifications;
}

const ofType = (list, type, incidentId) => list.filter((n) => n.type === type && (!incidentId || n.incidentId === incidentId));

/** A random base coordinate per call so helper searches in different tests never overlap. */
function freshArea() {
  return { lat: -60 + Math.random() * 120, lng: -170 + Math.random() * 340 };
}
/** Point `metres` north of (lat,lng). */
const north = (p, metres) => ({ lat: p.lat + metres / 111_320, lng: p.lng });

async function setHelper(app, user, p) {
  const res = await request(app).put('/api/v1/helper').set(user.auth).send({ enabled: true, lat: p.lat, lng: p.lng });
  if (res.status !== 204) throw new Error(`helper failed: ${res.status}`);
}

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

/** Uploads a JPEG-typed random body; resolves to { evidenceId, body }. */
async function uploadPhoto(app, user, incidentId, { verified = true, size = 2048 } = {}) {
  const buf = crypto.randomBytes(size);
  const evidenceId = crypto.randomUUID();
  const res = await request(app).put(`/api/v1/incidents/${incidentId}/evidence/${evidenceId}`).set(user.auth)
    .set('Content-Type', 'image/jpeg').set('X-Evidence-Kind', 'photo')
    .set('X-Sha256', verified ? sha256(buf) : sha256(Buffer.from('nope')))
    .set('X-Captured-At', String(Date.now())).send(buf);
  if (res.status !== 201) throw new Error(`upload failed: ${res.status}`);
  return { evidenceId, body: buf };
}

/** Fake Graph API: records calls; optionally fails the first N calls with HTTP 500. */
function fakeGraph({ failTimes = 0 } = {}) {
  const calls = [];
  let fails = failTimes;
  const fn = async (url, init = {}) => {
    const u = new URL(url);
    const call = { url, path: u.pathname, method: init.method || 'GET', headers: init.headers || {} };
    if (typeof init.body === 'string') call.json = JSON.parse(init.body);
    if (init.body instanceof FormData) call.form = init.body;
    calls.push(call);
    if (fails > 0) {
      fails -= 1;
      return new Response(JSON.stringify({ error: { message: 'temporary' } }), { status: 500 });
    }
    if (u.pathname.endsWith('/media')) return Response.json({ id: `media-${calls.length}` });
    if (u.pathname.endsWith('/messages')) return Response.json({ messages: [{ id: `wamid.${calls.length}` }] });
    return Response.json({ display_phone_number: '+91 90000 11111', id: 'PNID' });
  };
  fn.calls = calls;
  fn.messages = () => calls.filter((c) => c.path.endsWith('/messages')).map((c) => c.json);
  fn.reset = () => { calls.length = 0; };
  return fn;
}

module.exports = {
  TEST_DB, silent, testConfig, makeCtx, register, linkGuardian, trackToken, createIncident, notifications, ofType,
  freshArea, north, setHelper, sha256, uploadPhoto, fakeGraph,
};
