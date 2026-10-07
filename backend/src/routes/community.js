'use strict';

const crypto = require('crypto');
const express = require('express');
const { ah, body, badRequest, notFound, isUuid, isMs, ms } = require('../util');
const { tx } = require('../db');
const { flag, dailyLimit, publicText } = require('../moderation');

const TOPICS = ['advice', 'experience', 'legal', 'health', 'support'];
const MAX_POSTS_PER_DAY = 5;
const MAX_REPLIES_PER_DAY = 40;
const PAGE = 30;
const MAX_REPLIES_SHOWN = 300;
const ALIAS_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/**
 * The name shown for a user inside one thread, e.g. "Sakhi K7P2". Stable within the thread (so a
 * conversation can be followed) and different in every other thread (so she cannot be tracked).
 */
function aliasFor(secret, userId, postId) {
  const mac = crypto.createHmac('sha256', secret).update(`community-alias:${postId}:${userId}`).digest();
  let tag = '';
  for (let i = 0; i < 4; i++) tag += ALIAS_ALPHABET[mac[i] % ALIAS_ALPHABET.length];
  return `Sakhi ${tag}`;
}

const NOT_BLOCKED = `NOT EXISTS (SELECT 1 FROM community_blocks b WHERE b.user_id = $1 AND b.blocked_id = t.user_id)`;

/** Anonymous text community: posts with replies, report-to-hide and blocking. Authors are never returned. */
function communityRouter({ pool, config }) {
  const r = express.Router();

  const shapePost = (p, userId) => ({
    id: p.id, topic: p.topic, body: p.body, alias: p.alias, replyCount: p.reply_count,
    createdAt: ms(p.created_at), mine: p.user_id === userId,
  });

  /** A post the caller may see: not hidden (unless her own) and not by someone she blocked. */
  async function visiblePost(id, userId) {
    if (!isUuid(id)) throw notFound('post');
    const { rows } = await pool.query(
      `SELECT t.* FROM community_posts t
        WHERE t.id = $2 AND (NOT t.hidden OR t.user_id = $1) AND ${NOT_BLOCKED}`, [userId, id]);
    if (!rows.length) throw notFound('post');
    return rows[0];
  }

  async function visibleReply(id, userId) {
    if (!isUuid(id)) throw notFound('reply');
    const { rows } = await pool.query(
      `SELECT t.* FROM community_replies t
        WHERE t.id = $2 AND (NOT t.hidden OR t.user_id = $1) AND ${NOT_BLOCKED}`, [userId, id]);
    if (!rows.length) throw notFound('reply');
    return rows[0];
  }

  r.get('/community/posts', ah(async (req, res) => {
    const params = [req.userId];
    let where = `NOT t.hidden AND ${NOT_BLOCKED}`;
    if (req.query.topic !== undefined) {
      if (!TOPICS.includes(req.query.topic)) throw badRequest('invalid_topic', `topic must be one of ${TOPICS.join(', ')}`);
      params.push(req.query.topic);
      where += ` AND t.topic = $${params.length}`;
    }
    if (req.query.before !== undefined) {
      const before = Number(req.query.before);
      if (!isMs(before)) throw badRequest('invalid_request', 'before must be epoch milliseconds');
      params.push(new Date(before));
      where += ` AND t.created_at < $${params.length}`;
    }
    const { rows } = await pool.query(
      `SELECT t.* FROM community_posts t WHERE ${where} ORDER BY t.created_at DESC LIMIT ${PAGE + 1}`, params);
    const page = rows.slice(0, PAGE);
    res.json({
      posts: page.map((p) => shapePost(p, req.userId)),
      // Pass as ?before= to load older posts; null when this was the last page.
      nextBefore: rows.length > PAGE ? ms(page[page.length - 1].created_at) : null,
    });
  }));

  r.post('/community/posts', ah(async (req, res) => {
    const b = body(req);
    if (!TOPICS.includes(b.topic)) throw badRequest('invalid_topic', `topic must be one of ${TOPICS.join(', ')}`);
    const text = publicText(b.body, 'body', 1000);
    await dailyLimit(pool, 'community_posts', req.userId, MAX_POSTS_PER_DAY, 'posts');
    const id = crypto.randomUUID();
    const { rows: [row] } = await pool.query(
      `INSERT INTO community_posts (id, user_id, topic, body, alias) VALUES ($1, $2, $3, $4, $5) RETURNING *`,
      [id, req.userId, b.topic, text, aliasFor(config.signingSecret, req.userId, id)]);
    res.status(201).json(shapePost(row, req.userId));
  }));

  r.get('/community/posts/:id', ah(async (req, res) => {
    const post = await visiblePost(req.params.id, req.userId);
    const { rows } = await pool.query(
      `SELECT t.* FROM community_replies t
        WHERE t.post_id = $2 AND NOT t.hidden AND ${NOT_BLOCKED}
        ORDER BY t.created_at LIMIT ${MAX_REPLIES_SHOWN}`, [req.userId, post.id]);
    res.json({
      post: shapePost(post, req.userId),
      replies: rows.map((x) => ({
        id: x.id, body: x.body, alias: x.alias, createdAt: ms(x.created_at),
        mine: x.user_id === req.userId, byAuthor: x.user_id === post.user_id,
      })),
    });
  }));

  r.post('/community/posts/:id/replies', ah(async (req, res) => {
    const post = await visiblePost(req.params.id, req.userId);
    if (post.hidden) throw notFound('post');
    const text = publicText(body(req).body, 'body', 500);
    await dailyLimit(pool, 'community_replies', req.userId, MAX_REPLIES_PER_DAY, 'replies');
    const row = await tx(pool, async (c) => {
      const ins = await c.query(
        `INSERT INTO community_replies (post_id, user_id, body, alias) VALUES ($1, $2, $3, $4) RETURNING *`,
        [post.id, req.userId, text, aliasFor(config.signingSecret, req.userId, post.id)]);
      await c.query('UPDATE community_posts SET reply_count = reply_count + 1 WHERE id = $1', [post.id]);
      return ins.rows[0];
    });
    res.status(201).json({
      id: row.id, body: row.body, alias: row.alias, createdAt: ms(row.created_at),
      mine: true, byAuthor: post.user_id === req.userId,
    });
  }));

  r.delete('/community/posts/:id', ah(async (req, res) => {
    if (!isUuid(req.params.id)) throw notFound('post');
    const { rowCount } = await pool.query(
      'DELETE FROM community_posts WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    if (!rowCount) throw notFound('post');
    res.status(204).end();
  }));

  r.delete('/community/replies/:id', ah(async (req, res) => {
    if (!isUuid(req.params.id)) throw notFound('reply');
    const deleted = await tx(pool, async (c) => {
      const { rows } = await c.query(
        'DELETE FROM community_replies WHERE id = $1 AND user_id = $2 RETURNING post_id, hidden',
        [req.params.id, req.userId]);
      if (rows.length && !rows[0].hidden) {
        await c.query('UPDATE community_posts SET reply_count = GREATEST(reply_count - 1, 0) WHERE id = $1', [rows[0].post_id]);
      }
      return rows.length;
    });
    if (!deleted) throw notFound('reply');
    res.status(204).end();
  }));

  r.post('/community/posts/:id/flag', ah(async (req, res) => {
    const post = await visiblePost(req.params.id, req.userId);
    if (post.user_id === req.userId) throw badRequest('own_content', 'delete your own post instead');
    await flag(pool, 'post', post.id, req.userId);
    res.status(204).end();
  }));

  r.post('/community/replies/:id/flag', ah(async (req, res) => {
    const reply = await visibleReply(req.params.id, req.userId);
    if (reply.user_id === req.userId) throw badRequest('own_content', 'delete your own reply instead');
    if (await flag(pool, 'reply', reply.id, req.userId)) {
      await pool.query('UPDATE community_posts SET reply_count = GREATEST(reply_count - 1, 0) WHERE id = $1', [reply.post_id]);
    }
    res.status(204).end();
  }));

  /** Blocks whoever wrote the post / reply. The caller never learns who that is. */
  const block = (lookup) => ah(async (req, res) => {
    const target = await lookup(req.params.id, req.userId);
    if (target.user_id === req.userId) throw badRequest('own_content', 'you cannot block yourself');
    await pool.query(
      'INSERT INTO community_blocks (user_id, blocked_id) VALUES ($1, $2) ON CONFLICT DO NOTHING',
      [req.userId, target.user_id]);
    res.status(204).end();
  });
  r.post('/community/posts/:id/block', block(visiblePost));
  r.post('/community/replies/:id/block', block(visibleReply));

  return r;
}

module.exports = { communityRouter, aliasFor, TOPICS, MAX_POSTS_PER_DAY, MAX_REPLIES_PER_DAY };
