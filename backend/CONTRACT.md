# Naari Shakti API contract (v1)

Single source of truth shared by the Node.js + PostgreSQL server (`backend/`) and the Android
client (`app/src/main/java/com/example/naarishakti/cloud/`). Change both together.

- Base URL: `API_BASE_URL` (dev: `http://10.0.2.2:8080` from the Android emulator,
  `http://127.0.0.1:8080` on the PC). All JSON is UTF-8; times are **epoch milliseconds** (numbers).
- Errors: HTTP status + `{ "error": "<machine_code>", "message": "<human text>" }`.
- Auth: `Authorization: Bearer <deviceToken>` on every `/api/v1/**` route except
  `POST /api/v1/devices/register` and the public tracking routes.
- IDs: incident, evidence and check-in ids are UUIDs generated on the phone (idempotent PUTs).

## Server configuration (env)
| var | default | purpose |
|---|---|---|
| `DATABASE_URL` | — (required) | Postgres connection string |
| `PORT` | 8080 | |
| `PUBLIC_BASE_URL` | `http://127.0.0.1:8080` | used in tracking links (`${PUBLIC_BASE_URL}/t/${token}`) |
| `EVIDENCE_DIR` | `./data/evidence` | where uploaded evidence files are stored |
| `SIGNING_SECRET` | random per boot (warn) | HMAC key for short-lived evidence URLs |
| `MAX_EVIDENCE_BYTES` | 52428800 | 50 MB per file |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` | unset | optional server SMS for overdue check-ins |
| `FIREBASE_SERVICE_ACCOUNT` (or `..._FILE`) | unset | Firebase service-account JSON (inline or file path); enables FCM push so alerts reach closed apps |
| `TRUST_PROXY` | false | honour X-Forwarded-For behind a proxy |

## Devices & profile
- `POST /api/v1/devices/register` `{ "deviceName": "Pixel 7", "name": "Asha" }`
  → `201 { "userId": "<uuid>", "token": "<opaque 43+ chars>" }`. Server stores only a SHA-256 of
  the token. Rate-limited per IP.
- `GET /api/v1/me` → `{ "userId", "name", "guardianCode" }` (guardian code created on first read:
  6 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, unique).
- `PATCH /api/v1/me` `{ "name"?: string }` → same shape as GET.
- `PUT /api/v1/push-token` `{ "token": "<FCM registration token>" }` → `204`. Stored per device
  (keyed by the bearer token); `{ "token": null }` clears it. The server pushes every alert to it
  (see Real-time alerts), so phones get alerts with the app closed.
- `DELETE /api/v1/me` → deletes the account and all its data (incidents, evidence files, links).

## Guardians (people who get this user's alerts)
- `POST /api/v1/guardians/link` `{ "code": "K7P2QX" }` → `{ "wardId", "wardName" }`. The caller
  becomes a guardian of the code's owner. Errors: `invalid_code`, `self_link`, `too_many_attempts`
  (10 wrong codes / hour / user).
- `GET /api/v1/guardians` → `{ "guardians": [{ "userId", "name", "linkedAt" }], "guarding": [ ...same ] }`
- `DELETE /api/v1/guardians/{otherUserId}` → removes the link in either direction.

## Nearby helpers (opt-in volunteers)
- `PUT /api/v1/helper` `{ "enabled": true, "lat": 28.61, "lng": 77.20 }` → `204`.
  `{ "enabled": false }` removes the helper location. Locations older than 24 h are ignored.

## Incidents (one fired SOS)
- `PUT /api/v1/incidents/{id}` create-or-update:
  `{ "token": "<22 chars url-safe>", "source": "voice", "silent": false, "startedAt": 1700000000000,
     "contactsCount": 3, "battery": 64 }` → `200 { "id", "trackUrl" }`.
  `token` and `source` are immutable after create. On create the server notifies guardians and,
  once a location exists, helpers within 2 km.
- `POST /api/v1/incidents/{id}/locations` `{ "points": [{ "lat", "lng", "accuracy", "at" }] }` → `204`
  (max 100 points per call). Updates `lastLocation`; the first location triggers helper fan-out.
- `POST /api/v1/incidents/{id}/battery` `{ "battery": 12 }` → `204`.
- `POST /api/v1/incidents/{id}/duress` → `204`. Irreversible; guardians are told she may be
  forced to cancel. After duress, guardians never receive the "safe" message for this incident.
- `POST /api/v1/incidents/{id}/end` `{ "userInitiated": true }` → `204`. Status `ended`;
  guardians and alerted helpers receive `ended` (unless duress).
- `GET /api/v1/incidents` → `{ "incidents": [{ "id", "source", "status", "startedAt", "endedAt",
  "evidenceCount", "verifiedCount", "trackUrl" }] }` (owner's own, newest first, max 50).

## Evidence (tamper-evident)
- `PUT /api/v1/incidents/{id}/evidence/{evidenceId}` — raw body upload (not multipart).
  Headers: `Content-Type` (`image/jpeg` | `audio/mp4` | `video/mp4`), `X-Evidence-Kind`
  (`photo` | `audio` | `video`), `X-Sha256` (lowercase hex of the body, computed on the phone),
  `X-Captured-At` (ms). The server streams to disk while hashing and responds
  `201 { "evidenceId", "serverSha256", "verified": true|false, "receivedAt" }`.
  Re-uploading the same id with the same hash → `200` with the stored record (idempotent);
  a different hash → `409 evidence_exists`. Files are never overwritten.
- `GET /api/v1/incidents/{id}/evidence` → `{ "evidence": [{ "evidenceId", "kind", "size",
  "sha256", "serverSha256", "verified", "capturedAt", "receivedAt" }] }`.

## Check-ins (timed "I'm safe" check)
- `PUT /api/v1/checkins/{id}` `{ "deadline": ms, "note": "Walking home", "contacts": ["+9198..."] }`
  → `200 { "id", "status": "active" }`. Deadline must be 1 min – 24 h ahead.
- `POST /api/v1/checkins/{id}/location` `{ "lat", "lng", "accuracy", "at" }` → `204`.
- `POST /api/v1/checkins/{id}/extend` `{ "deadline": ms }` → `204`.
- `POST /api/v1/checkins/{id}/complete` → `204` (she is safe). `POST /api/v1/checkins/{id}/cancel` → `204`.
- Server sweeper (every 30 s): an `active` check-in past `deadline + 60 s` becomes `overdue`, the server
  creates an incident on her behalf (`source = "checkin"`, last check-in location) so guardians,
  helpers and the tracking page work even if her phone is dead; guardians get `checkin_overdue`;
  if Twilio is configured the check-in `contacts` get an SMS with the tracking link.

## Helper response
- `POST /api/v1/alerts/{incidentId}/respond` `{ "lat", "lng" }` → `{ "ok": true, "distanceM", "trackUrl" }`.
  Only users who actually received a `helper_alert` for that incident. Owner + guardians get `responder`.

## Real-time alerts
- WebSocket `GET /api/v1/ws` with header `Authorization: Bearer <token>` (or `?token=` query for
  clients that can't set headers). Server → client JSON messages `{ "id": "<notification uuid>",
  "type": "...", "at": ms, ...fields }`. Client → server: `{ "type": "ack", "id": "..." }`,
  `{ "type": "ping" }` (server answers `{ "type": "pong" }`). Server pings every 25 s.
- Every alert is also stored; `GET /api/v1/notifications?since=<ms>` returns unacked ones from the last
  24 h (`{ "notifications": [ ... ] }`) so a phone that was offline catches up on reconnect.
  `POST /api/v1/notifications/{id}/ack` → `204` acks over HTTP (used by the push path).
- Push: when `FIREBASE_SERVICE_ACCOUNT` is set, every unacked notification is also sent as a
  high-priority FCM **data** message `{ "n": "<the same JSON string>" }` to each of the recipient's
  registered push tokens (TTL 15 min for `helper_alert`, 24 h otherwise), so alerts ring on the
  lock screen with the app closed. Clients de-duplicate socket + push by notification `id` and ack
  pushed alerts over HTTP. Dead FCM tokens (404/UNREGISTERED) are dropped server-side.
- Multi-instance safe: fan-out goes through Postgres `LISTEN/NOTIFY` (channel `naari_events`).

| `type` | fields | client behaviour |
|---|---|---|
| `sos` | `incidentId, ownerName, lat?, lng?, trackUrl` | full-screen alert "Asha needs help" → tracking |
| `duress` | `incidentId, ownerName, trackUrl` | alert "Asha may be forced to cancel — still in danger" |
| `ended` | `incidentId, ownerName` | notification "Asha is safe now" |
| `helper_alert` | `incidentId, lat, lng, distanceM, trackUrl` | full-screen HelperAlertActivity with "I'm going" (no owner name) |
| `responder` | `incidentId, helperName, distanceM` | "A nearby helper is on the way" |
| `checkin_overdue` | `incidentId, ownerName, note, trackUrl` | full-screen alert to guardians |

## Public live tracking (no login; the unguessable token is the secret)
- `GET /t/{token}` → HTML page (premium dark "Midnight Rose" style, mobile first) that polls
  `GET /api/v1/track/{token}` every 10 s: status banner (active / duress / ended), name, elapsed
  time, battery, Leaflet + OpenStreetMap map with path, verified evidence (photos inline, audio and
  video players), responders count.
- `GET /api/v1/track/{token}` →
  `{ "ownerName", "status", "duress", "source", "startedAt", "endedAt", "battery",
     "lastLocation": { "lat", "lng", "accuracy", "at" } | null, "path": [ { "lat", "lng", "at" } ],
     "evidence": [ { "evidenceId", "kind", "contentType", "capturedAt", "verified", "url" } ],
     "respondersCount", "helpersNotified", "serverTime" }`
  - `path` = last 200 points; `evidence` = newest 20 verified items with 15-minute HMAC-signed URLs
    `GET /api/v1/track/{token}/evidence/{evidenceId}?exp=<ms>&sig=<hex>`.
  - 24 h after an incident ended, location and evidence are no longer returned.
  - 404 unknown token; 429 after 60 requests/min/IP; `Cache-Control: no-store`.

## Operations
- `GET /healthz` → `{ "ok": true, "db": true, "push": true }` (`push`: FCM configured).
- Schema migrations run automatically on start (`backend/migrations/*.sql`, tracked in a
  `schema_migrations` table).

---

# v1.1 additions: nearby broadcast + WhatsApp alerts

## Nearby broadcast (everyone nearby who agreed to help)
- "Helpers" are now every user who said yes to **"Help women near you"** on first launch (the app
  asks once; it is `Prefs.HELPER_OPT_IN`). Same `PUT /api/v1/helper` endpoint; the phone refreshes
  its coarse location every 30 min and whenever the app opens.
- `PUT /api/v1/incidents/{id}` accepts `"broadcast": true|false` (default **true**). When false the
  server never alerts nearby users for that incident (guardians still get `sos`).
- **Tiered radius:** alert helpers within 2 km; if fewer than 10 were found, extend to 5 km in the
  same fan-out (each helper alerted once). `helper_alert` gains `radiusKm` (2 or 5).
- **Repeated search:** the first search runs on her first location, then repeats whenever she has
  moved ≥250 m from the last search centre (at most once a minute) and every 5 minutes regardless —
  the first fix is often a stale cached location and she may be moving. A helper is alerted at most
  once per incident.
- **Evidence for helpers:** `helper_alert` gains `evidenceCount` and `photoUrl` — an absolute,
  15-minute signed URL of the newest verified photo at fan-out time, or `null`.
- **Evidence updates:** when a new *photo* is verified during an active (or duress) incident, the
  server sends `evidence_update` `{ incidentId, photoUrl, evidenceCount, trackUrl }` to already-alerted
  helpers **and** guardians, at most once per 60 s per incident.
- Safety copy stays on the helper screen: call 112 first; don't put yourself at risk.

## WhatsApp alerts to her emergency contacts (WhatsApp Business Cloud API)
Messages come from the Naari Shakti WhatsApp Business number (not from her personal WhatsApp — no
official API can send from a personal account). WhatsApp requires recipients to opt in, so each
contact joins once by sending `JOIN` to the business number (the app shares a `wa.me` link).

Server env (all optional; the feature is dormant until set):
| var | purpose |
|---|---|
| `WHATSAPP_TOKEN` | Cloud API access token |
| `WHATSAPP_PHONE_NUMBER_ID` | sender phone-number id |
| `WHATSAPP_VERIFY_TOKEN` | webhook verification token |
| `WHATSAPP_APP_SECRET` | verifies `X-Hub-Signature-256` on webhook posts |
| `WHATSAPP_API_VERSION` | default `v20.0` |
| `WHATSAPP_TEMPLATE_SOS` | default `naari_sos_alert` — body `{{1}} needs help. Live location and evidence: {{2}}`, LOCATION header |
| `WHATSAPP_TEMPLATE_EVIDENCE` | default `naari_sos_evidence` — IMAGE header, body `New photo from {{1}}'s SOS. Live: {{2}}` |
| `WHATSAPP_TEMPLATE_SAFE` | default `naari_sos_safe` — body `{{1}} has marked herself safe.` |
| `WHATSAPP_TEMPLATE_LANG` | default `en` |

Endpoints:
- `PUT /api/v1/me/whatsapp` `{ "enabled": true, "contacts": [{ "name": "Riya", "number": "+919812345678" }] }`
  → `200 { "enabled", "contacts": [{ "name", "number", "optedIn": bool }] , "joinUrl": "https://wa.me/<business>?text=JOIN" }`.
  Max 5 contacts, E.164 normalised (Indian 10-digit numbers get `+91`). `{ "enabled": false }` deletes them.
- `GET /api/v1/me/whatsapp` → same shape. `joinUrl` is null when WhatsApp isn't configured;
  `"configured": bool` is included.
- `GET /api/v1/whatsapp/webhook` — Meta verification (`hub.mode`, `hub.verify_token`, `hub.challenge`).
- `POST /api/v1/whatsapp/webhook` — inbound messages; text `JOIN` (case-insensitive, any language
  prefix allowed) marks that sender number opted in for every user who listed it and replies with a
  short confirmation; `STOP` opts out. Signature verified when `WHATSAPP_APP_SECRET` is set.

Sending (server, only to opted-in numbers of the incident owner, never for other users):
- **Incident created** → SOS template with her last location as the LOCATION header (sent as soon as a
  location exists; if none yet, a text-only fallback is sent) and the tracking link.
- **Photo verified** → evidence template with the photo (uploaded to the Cloud API media endpoint),
  at most one per contact per 60 s and 10 per incident.
- **Incident ended** (not duress) → safe template.
- **Duress** → nothing that says "safe"; the tracking link keeps working.
- Failures are logged and retried up to 3 times with backoff; never blocks the API response.

# v1.2 additions: family circle, safety map, community

## Family circle (location between linked people)
People linked as guardian/ward see each other's latest position, but only from those who switched
sharing on. Sharing is per user and off by default.
- `PUT /api/v1/circle/me` `{ "sharing": true, "lat", "lng", "accuracy"?, "battery"?: 0-100 }` → `204`.
  `{ "sharing": false }` deletes the stored position.
- `GET /api/v1/circle` → `{ "sharing": bool, "members": [{ "userId", "name",
  "relation": "guardian"|"ward"|"both", "location": { "lat", "lng", "accuracy", "battery", "updatedAt" } | null }] }`.
  `relation` is what the other person is to the caller; `location` is null while she is not sharing.

## Community safety map
Anonymous reports about places. The author is never returned; coordinates are rounded to 4 decimals.
- `POST /api/v1/places/reports` `{ "category", "lat", "lng", "note"?: ≤200 }` → `201 { "id", "createdAt" }`.
  `category`: `poorly_lit` | `isolated` | `harassment` | `unsafe_transport` | `safe_spot`.
  Errors: `invalid_category`, `invalid_location`, `contact_info`, `daily_limit` (10 / 24 h / user, 429).
- `GET /api/v1/places/reports?lat&lng&radius=2000` (radius in metres, max 10000) →
  `{ "reports": [{ "id", "category", "lat", "lng", "note", "createdAt", "mine", "distanceM" }] }`,
  newest first, at most 200, no older than 180 days, without the ones the caller flagged.
- `DELETE /api/v1/places/reports/{id}` → `204` (own reports only).
- `POST /api/v1/places/reports/{id}/flag` → `204`. Hidden for everyone after 3 different users flag it.

## Community (anonymous text posts)
`alias` is a per-thread name like `Sakhi K7P2`: the same person keeps it inside one thread and gets
another in every other thread. User ids are never returned. Links and phone numbers (9+ digits) are
refused with `contact_info`.
- `GET /api/v1/community/posts?topic=&before=<ms>` → `{ "posts": [Post], "nextBefore": <ms>|null }`, 30 per
  page, newest first. `Post` = `{ "id", "topic", "body", "alias", "replyCount", "createdAt", "mine" }`.
  `topic`: `advice` | `experience` | `legal` | `health` | `support`.
- `POST /api/v1/community/posts` `{ "topic", "body": 1-1000 chars }` → `201 Post`. `daily_limit`: 5 / 24 h.
- `GET /api/v1/community/posts/{id}` → `{ "post": Post, "replies": [{ "id", "body", "alias", "createdAt",
  "mine", "byAuthor" }] }` (oldest first, at most 300).
- `POST /api/v1/community/posts/{id}/replies` `{ "body": 1-500 chars }` → `201` reply. `daily_limit`: 40 / 24 h.
- `DELETE /api/v1/community/posts/{id}`, `DELETE /api/v1/community/replies/{id}` → `204` (own only).
- `POST /api/v1/community/posts/{id}/flag`, `POST /api/v1/community/replies/{id}/flag` → `204`. Hidden for
  everyone but the author after 3 different users flag it. `own_content` on your own.
- `POST /api/v1/community/posts/{id}/block`, `POST /api/v1/community/replies/{id}/block` → `204`. The caller
  no longer sees anything written by that author; the author is not told.

## Helper stats
- `GET /api/v1/helper/stats` → `{ "alerted": n, "responded": n }` for the caller as a volunteer helper.
