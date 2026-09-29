'use strict';

const crypto = require('crypto');
const { toMessage } = require('./notify');

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const TOKEN_SLACK_MS = 60_000; // refresh the OAuth token a minute before it expires

// A helper_alert is useless after ~15 min (the client drops stale ones anyway); everything else
// stays deliverable for a day, matching the GET /notifications catch-up window.
const TTL_S = { helper_alert: 900 };
const DEFAULT_TTL_S = 24 * 3600;

/**
 * FCM HTTP v1 sender: a service-account JWT exchanged for an OAuth token, then plain fetch — no
 * firebase-admin. Dormant when config.firebase is unset. Messages are data-only and high priority
 * so the phone's CloudMessagingService builds the same full-screen alert the WebSocket path shows
 * (a "notification" payload would be flattened into a silent tray entry by the system); a phone
 * that also holds an open socket de-duplicates by notification id.
 */
function createPush({ config, pool, log, fetchImpl = globalThis.fetch }) {
  const fb = config.firebase;
  let cached = null; // { token, expiresAt }
  let inflight = null;

  async function accessToken() {
    if (cached && Date.now() < cached.expiresAt - TOKEN_SLACK_MS) return cached.token;
    if (!inflight) {
      inflight = (async () => {
        const iat = Math.floor(Date.now() / 1000);
        const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
        const claims = Buffer.from(JSON.stringify({
          iss: fb.clientEmail, scope: SCOPE, aud: fb.tokenUri, iat, exp: iat + 3600,
        })).toString('base64url');
        const unsigned = `${header}.${claims}`;
        const signature = crypto.sign('RSA-SHA256', Buffer.from(unsigned), fb.privateKey).toString('base64url');
        const res = await fetchImpl(fb.tokenUri, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({
            grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            assertion: `${unsigned}.${signature}`,
          }).toString(),
        });
        if (!res.ok) throw new Error(`FCM OAuth exchange failed: HTTP ${res.status}`);
        const bodyJson = await res.json();
        if (!bodyJson.access_token) throw new Error('FCM OAuth exchange returned no access_token');
        cached = { token: bodyJson.access_token, expiresAt: Date.now() + (bodyJson.expires_in || 3600) * 1000 };
        return cached.token;
      })().finally(() => { inflight = null; });
    }
    return inflight;
  }

  async function deliver(device, data, ttl) {
    let bearer;
    try {
      bearer = await accessToken();
    } catch (err) {
      log.error({ err }, 'push auth failed');
      return;
    }
    let res;
    try {
      res = await fetchImpl(`https://fcm.googleapis.com/v1/projects/${fb.projectId}/messages:send`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: { token: device.push_token, data, android: { priority: 'HIGH', ttl } },
        }),
      });
    } catch (err) {
      log.warn({ err }, 'push send failed'); // offline notifications are caught up over HTTP later
      return;
    }
    if (res.ok) return;
    const text = await res.text().catch(() => '');
    if (res.status === 404 || text.includes('UNREGISTERED')) {
      // App uninstalled or the token rotated: forget it so we stop sending.
      await pool.query(
        'UPDATE device_tokens SET push_token = NULL, push_updated_at = now() WHERE token_hash = $1',
        [device.token_hash]).catch((err) => log.error({ err }, 'push token cleanup failed'));
      return;
    }
    if (res.status === 401) cached = null; // stale OAuth token: the next notification retries fresh
    log.warn({ status: res.status, body: text.slice(0, 300) }, 'push rejected by FCM');
  }

  /** Called with a notification id from NOTIFY naari_events (same feed as the WebSocket hub). */
  async function onNotify(id) {
    if (!fb) return;
    try {
      const { rows: [row] } = await pool.query(
        'SELECT id, recipient_id, type, payload, created_at, acked_at FROM notifications WHERE id = $1', [id]);
      if (!row || row.acked_at) return;
      const { rows: devices } = await pool.query(
        'SELECT token_hash, push_token FROM device_tokens WHERE user_id = $1 AND push_token IS NOT NULL',
        [row.recipient_id]);
      if (!devices.length) return;
      const data = { n: JSON.stringify(toMessage(row)) };
      const ttl = `${TTL_S[row.type] || DEFAULT_TTL_S}s`;
      await Promise.all(devices.map((d) => deliver(d, data, ttl)));
    } catch (err) {
      log.error({ err, id }, 'push fan-out failed');
    }
  }

  return { enabled: !!fb, onNotify };
}

module.exports = { createPush };
