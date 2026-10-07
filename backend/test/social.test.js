'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { makeCtx, register, linkGuardian, freshArea, north } = require('./helpers');

let ctx;
before(() => { ctx = makeCtx(); });
after(() => ctx.close());

const get = (u, path) => ctx.api().get(`/api/v1${path}`).set(u.auth);
const post = (u, path, json) => ctx.api().post(`/api/v1${path}`).set(u.auth).send(json);
const put = (u, path, json) => ctx.api().put(`/api/v1${path}`).set(u.auth).send(json);
const del = (u, path) => ctx.api().delete(`/api/v1${path}`).set(u.auth);

// ------------------------------------------------------------------ family circle

test('circle: linked people see a location only from those who share it', async () => {
  const ward = await register(ctx.app, 'Asha');
  const mum = await register(ctx.app, 'Mum');
  const stranger = await register(ctx.app, 'Stranger');
  await linkGuardian(ctx.app, ward, mum);

  let c = await get(mum, '/circle');
  assert.equal(c.status, 200);
  assert.equal(c.body.sharing, false);
  assert.deepEqual(c.body.members, [{ userId: ward.userId, name: 'Asha', relation: 'ward', location: null }]);

  assert.equal((await put(ward, '/circle/me', { sharing: true, lat: 28.61, lng: 77.2, accuracy: 12.5, battery: 64 })).status, 204);
  c = await get(mum, '/circle');
  const loc = c.body.members[0].location;
  assert.equal(loc.lat, 28.61);
  assert.equal(loc.lng, 77.2);
  assert.equal(loc.battery, 64);
  assert.equal(typeof loc.updatedAt, 'number');

  // the ward sees her guardian, who is not sharing
  c = await get(ward, '/circle');
  assert.equal(c.body.sharing, true);
  assert.deepEqual(c.body.members, [{ userId: mum.userId, name: 'Mum', relation: 'guardian', location: null }]);

  // nobody outside the links sees anything
  assert.deepEqual((await get(stranger, '/circle')).body, { sharing: false, members: [] });

  // switching sharing off removes the position
  assert.equal((await put(ward, '/circle/me', { sharing: false })).status, 204);
  assert.equal((await get(mum, '/circle')).body.members[0].location, null);
});

test('circle: links in both directions are one member; unlinking removes her', async () => {
  const a = await register(ctx.app, 'A');
  const b = await register(ctx.app, 'B');
  await linkGuardian(ctx.app, a, b);
  await linkGuardian(ctx.app, b, a);
  const c = await get(a, '/circle');
  assert.equal(c.body.members.length, 1);
  assert.equal(c.body.members[0].relation, 'both');
  await del(a, `/guardians/${b.userId}`);
  assert.deepEqual((await get(a, '/circle')).body.members, []);
});

test('circle: validation', async () => {
  const u = await register(ctx.app);
  assert.equal((await put(u, '/circle/me', {})).status, 400);
  assert.equal((await put(u, '/circle/me', { sharing: true, lat: 91, lng: 0 })).body.error, 'invalid_location');
  assert.equal((await put(u, '/circle/me', { sharing: true, lat: 1, lng: 1, battery: 101 })).status, 400);
  assert.equal((await put(u, '/circle/me', { sharing: true, lat: 1, lng: 1, accuracy: -1 })).status, 400);
  assert.equal((await ctx.api().get('/api/v1/circle')).status, 401);
});

// ------------------------------------------------------------------ safety map

test('places: report, find nearby, delete', async () => {
  const u = await register(ctx.app);
  const other = await register(ctx.app);
  const here = freshArea();
  const created = await post(u, '/places/reports', { category: 'poorly_lit', ...here, note: ' Street lights are off  after 9 ' });
  assert.equal(created.status, 201);
  await post(u, '/places/reports', { category: 'safe_spot', ...north(here, 5000) });

  const near = await get(other, `/places/reports?lat=${here.lat}&lng=${here.lng}`);
  assert.equal(near.status, 200);
  assert.equal(near.body.reports.length, 1); // the one 5 km away is outside the default 2 km
  const r = near.body.reports[0];
  assert.equal(r.id, created.body.id);
  assert.equal(r.category, 'poorly_lit');
  assert.equal(r.note, 'Street lights are off after 9');
  assert.equal(r.mine, false);
  assert.ok(r.distanceM < 20);
  assert.equal(r.userId, undefined);
  assert.equal((await get(u, `/places/reports?lat=${here.lat}&lng=${here.lng}`)).body.reports[0].mine, true);
  assert.equal((await get(other, `/places/reports?lat=${here.lat}&lng=${here.lng}&radius=6000`)).body.reports.length, 2);

  assert.equal((await del(other, `/places/reports/${r.id}`)).status, 404); // not hers
  assert.equal((await del(u, `/places/reports/${r.id}`)).status, 204);
  assert.deepEqual((await get(other, `/places/reports?lat=${here.lat}&lng=${here.lng}`)).body.reports, []);
});

test('places: three flags hide a report; a flagger stops seeing it at once', async () => {
  const author = await register(ctx.app);
  const here = freshArea();
  const { body: { id } } = await post(author, '/places/reports', { category: 'harassment', ...here });
  const q = `/places/reports?lat=${here.lat}&lng=${here.lng}`;
  assert.equal((await post(author, `/places/reports/${id}/flag`)).body.error, 'own_content');

  const flaggers = [await register(ctx.app), await register(ctx.app), await register(ctx.app)];
  assert.equal((await post(flaggers[0], `/places/reports/${id}/flag`)).status, 204);
  assert.equal((await post(flaggers[0], `/places/reports/${id}/flag`)).status, 204); // counts once
  assert.deepEqual((await get(flaggers[0], q)).body.reports, []);
  assert.equal((await post(flaggers[1], `/places/reports/${id}/flag`)).status, 204);
  assert.equal((await get(author, q)).body.reports.length, 1);
  assert.equal((await post(flaggers[2], `/places/reports/${id}/flag`)).status, 204);
  assert.deepEqual((await get(author, q)).body.reports, []);
});

test('places: validation and the daily limit', async () => {
  const u = await register(ctx.app);
  const here = freshArea();
  assert.equal((await post(u, '/places/reports', { category: 'nope', ...here })).body.error, 'invalid_category');
  assert.equal((await post(u, '/places/reports', { category: 'isolated', lat: 'x', lng: 1 })).body.error, 'invalid_location');
  assert.equal((await post(u, '/places/reports', { category: 'isolated', ...here, note: 'call 98765 43210' })).body.error, 'contact_info');
  assert.equal((await get(u, '/places/reports')).status, 400);
  assert.equal((await get(u, `/places/reports?lat=1&lng=1&radius=999999`)).status, 400);
  for (let i = 0; i < 10; i++) {
    assert.equal((await post(u, '/places/reports', { category: 'isolated', ...here })).status, 201);
  }
  const blocked = await post(u, '/places/reports', { category: 'isolated', ...here });
  assert.equal(blocked.status, 429);
  assert.equal(blocked.body.error, 'daily_limit');
});

// ------------------------------------------------------------------ community

test('community: post, reply, read; authors stay anonymous', async () => {
  const asha = await register(ctx.app, 'Asha');
  const riya = await register(ctx.app, 'Riya');
  const created = await post(asha, '/community/posts', { topic: 'advice', body: 'How do I talk to HR about a colleague?' });
  assert.equal(created.status, 201);
  const p = created.body;
  assert.match(p.alias, /^Sakhi [A-Z2-9]{4}$/);
  assert.equal(p.mine, true);
  assert.equal(p.userId, undefined);

  const r1 = await post(riya, `/community/posts/${p.id}/replies`, { body: 'Write it down with dates first.' });
  assert.equal(r1.status, 201);
  assert.equal(r1.body.byAuthor, false);
  const r2 = await post(asha, `/community/posts/${p.id}/replies`, { body: 'Thank you.' });
  assert.equal(r2.body.byAuthor, true);
  assert.equal(r2.body.alias, p.alias); // same person, same thread, same name
  assert.notEqual(r1.body.alias, p.alias);

  const thread = await get(riya, `/community/posts/${p.id}`);
  assert.equal(thread.body.post.replyCount, 2);
  assert.equal(thread.body.post.mine, false);
  assert.deepEqual(thread.body.replies.map((x) => [x.body, x.mine, x.byAuthor]),
    [['Write it down with dates first.', true, false], ['Thank you.', false, true]]);
  assert.ok(!JSON.stringify(thread.body).includes(asha.userId));

  // a second thread gives the same person another name
  const again = await post(asha, '/community/posts', { topic: 'legal', body: 'What is a Zero FIR?' });
  assert.notEqual(again.body.alias, p.alias);

  const feed = await get(riya, '/community/posts?topic=advice');
  assert.ok(feed.body.posts.some((x) => x.id === p.id));
  assert.ok(feed.body.posts.every((x) => x.topic === 'advice'));

  assert.equal((await del(riya, `/community/replies/${r2.body.id}`)).status, 404); // not hers
  assert.equal((await del(riya, `/community/replies/${r1.body.id}`)).status, 204);
  assert.equal((await get(asha, `/community/posts/${p.id}`)).body.post.replyCount, 1);
  assert.equal((await del(riya, `/community/posts/${p.id}`)).status, 404);
  assert.equal((await del(asha, `/community/posts/${p.id}`)).status, 204);
  assert.equal((await get(riya, `/community/posts/${p.id}`)).status, 404);
});

test('community: links, phone numbers and bad input are refused', async () => {
  const u = await register(ctx.app);
  const bad = async (json) => (await post(u, '/community/posts', json)).body.error;
  assert.equal(await bad({ topic: 'advice', body: 'message me on wa.me/919876543210' }), 'contact_info');
  assert.equal(await bad({ topic: 'advice', body: 'my number is 98765-43210' }), 'contact_info');
  assert.equal(await bad({ topic: 'advice', body: 'see https://example.com/x' }), 'contact_info');
  assert.equal(await bad({ topic: 'gossip', body: 'hello' }), 'invalid_topic');
  assert.equal(await bad({ topic: 'advice', body: '   ' }), 'invalid_request');
  assert.equal(await bad({ topic: 'advice', body: 'x'.repeat(1001) }), 'invalid_request');
  // helplines and years are fine
  assert.equal((await post(u, '/community/posts', { topic: 'legal', body: 'Call 112 or 1091. This happened in 2019-2020.' })).status, 201);
  assert.equal((await get(u, '/community/posts?topic=nope')).status, 400);
  assert.equal((await get(u, '/community/posts/not-a-uuid')).status, 404);
});

test('community: three flags hide a post from everyone but its author', async () => {
  const author = await register(ctx.app);
  const { body: p } = await post(author, '/community/posts', { topic: 'support', body: 'Something unkind' });
  assert.equal((await post(author, `/community/posts/${p.id}/flag`)).body.error, 'own_content');
  const readers = [await register(ctx.app), await register(ctx.app), await register(ctx.app)];
  for (const reader of readers) assert.equal((await post(reader, `/community/posts/${p.id}/flag`)).status, 204);
  assert.equal((await get(readers[0], `/community/posts/${p.id}`)).status, 404);
  assert.ok(!(await get(readers[0], '/community/posts')).body.posts.some((x) => x.id === p.id));
  assert.equal((await get(author, `/community/posts/${p.id}`)).status, 200);
  // nobody can reply to a hidden post, not even the author
  assert.equal((await post(author, `/community/posts/${p.id}/replies`, { body: 'hello' })).status, 404);
});

test('community: flagged replies disappear and the count follows', async () => {
  const author = await register(ctx.app);
  const troll = await register(ctx.app);
  const { body: p } = await post(author, '/community/posts', { topic: 'support', body: 'A hard week' });
  const { body: reply } = await post(troll, `/community/posts/${p.id}/replies`, { body: 'Something unkind' });
  for (const reader of [author, await register(ctx.app), await register(ctx.app)]) {
    assert.equal((await post(reader, `/community/replies/${reply.id}/flag`)).status, 204);
  }
  const thread = await get(author, `/community/posts/${p.id}`);
  assert.deepEqual(thread.body.replies, []);
  assert.equal(thread.body.post.replyCount, 0);
});

test('community: blocking hides everything that person wrote, in both feeds and threads', async () => {
  const me = await register(ctx.app);
  const them = await register(ctx.app);
  const { body: theirs } = await post(them, '/community/posts', { topic: 'advice', body: 'Their post' });
  const { body: mine } = await post(me, '/community/posts', { topic: 'advice', body: 'My post' });
  await post(them, `/community/posts/${mine.id}/replies`, { body: 'Their reply' });

  assert.equal((await post(me, `/community/posts/${mine.id}/block`)).body.error, 'own_content');
  assert.equal((await post(me, `/community/posts/${theirs.id}/block`)).status, 204);
  assert.equal((await get(me, `/community/posts/${theirs.id}`)).status, 404);
  assert.ok(!(await get(me, '/community/posts')).body.posts.some((x) => x.id === theirs.id));
  assert.deepEqual((await get(me, `/community/posts/${mine.id}`)).body.replies, []);
  // the blocked person is not told and still sees her own post
  assert.equal((await get(them, `/community/posts/${theirs.id}`)).status, 200);
});

test('community: feed pages backwards and posts are limited per day', async () => {
  const u = await register(ctx.app);
  for (let i = 0; i < 5; i++) {
    assert.equal((await post(u, '/community/posts', { topic: 'health', body: `Post ${i}` })).status, 201);
  }
  const blocked = await post(u, '/community/posts', { topic: 'health', body: 'One too many' });
  assert.equal(blocked.status, 429);
  assert.equal(blocked.body.error, 'daily_limit');

  // Spread 35 posts over time, then walk the feed.
  await ctx.pool.query(`DELETE FROM community_posts`);
  for (let i = 0; i < 35; i++) {
    await ctx.pool.query(
      `INSERT INTO community_posts (id, user_id, topic, body, alias, created_at)
       VALUES (gen_random_uuid(), $1, 'health', $2, 'Sakhi TEST', now() - ($3 || ' minutes')::interval)`,
      [u.userId, `Old ${i}`, String(i)]);
  }
  const first = await get(u, '/community/posts');
  assert.equal(first.body.posts.length, 30);
  assert.equal(first.body.posts[0].body, 'Old 0');
  assert.equal(typeof first.body.nextBefore, 'number');
  const second = await get(u, `/community/posts?before=${first.body.nextBefore}`);
  assert.deepEqual(second.body.posts.map((x) => x.body), ['Old 30', 'Old 31', 'Old 32', 'Old 33', 'Old 34']);
  assert.equal(second.body.nextBefore, null);
});

// ------------------------------------------------------------------ helper stats

test('helper stats count alerts and responses', async () => {
  const helper = await register(ctx.app);
  assert.deepEqual((await get(helper, '/helper/stats')).body, { alerted: 0, responded: 0 });
  const victim = await register(ctx.app);
  for (const responded of [true, false]) {
    const id = require('crypto').randomUUID();
    await ctx.pool.query(
      `INSERT INTO incidents (id, user_id, track_token, source, started_at) VALUES ($1, $2, $3, 'voice', now())`,
      [id, victim.userId, require('crypto').randomBytes(16).toString('base64url')]);
    await ctx.pool.query(
      `INSERT INTO incident_helpers (incident_id, helper_id, distance_m, responded_at) VALUES ($1, $2, 100, $3)`,
      [id, helper.userId, responded ? new Date() : null]);
  }
  assert.deepEqual((await get(helper, '/helper/stats')).body, { alerted: 2, responded: 1 });
});
