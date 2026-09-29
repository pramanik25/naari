# Naari Shakti API server

Node.js 20+ and PostgreSQL 13+ backend for the Naari Shakti Android app. It covers device registration, guardians, SOS incidents with live location, nearby-helper alerts, tamper-evident evidence uploads, timed check-ins, real-time alerts over WebSocket, and a public live-tracking page.

`CONTRACT.md` is the API specification shared with the Android client. It is the source of truth.

- **Stack:** express 4, node-postgres, ws, helmet, pino. SQL is written by hand with no ORM, and there is no build step.
- **Postgres:** plain Postgres with no PostGIS. Nearby-helper search uses a lat/lng bounding-box index scan followed by an exact haversine check. Everyone within 2 km is alerted, widened to 5 km when fewer than 10 helpers are found, capped at 50 and nearest first. The phone can opt an incident out with `broadcast: false`.
- **Multi-instance:** every alert is stored in `notifications` and then announced with `NOTIFY naari_events`. Every instance `LISTEN`s and pushes the alert to its own open sockets.

## Local run

```powershell
cd backend
npm install
copy .env.example .env      # then edit DATABASE_URL / SIGNING_SECRET
npm start                   # runs migrations, then listens on PORT (default 8080)
```

- **Health check:** `http://127.0.0.1:8080/healthz` returns `{"ok":true,"db":true}`.
- **Android emulator:** reaches the server at `http://10.0.2.2:8080`.
- **Physical phone on the same Wi-Fi:** use the PC's LAN IP, and set `PUBLIC_BASE_URL` to that address so the tracking links work from the phone.

### Tests

```powershell
npm test
```

`npm test` does three things:

1. Recreates the schema of the test database. It refuses to run unless the database name ends in `_test`. Override the database with `TEST_DATABASE_URL`; the default is `postgres://naari@127.0.0.1:55432/naarishakti_test`.
2. Applies the migrations.
3. Runs the integration tests. They use `node:test`, supertest and a real `ws` client against a real server, and Twilio is mocked through an injected `fetch`.

### Migrations

- Migration files are `migrations/NNN_name.sql`.
- They are applied in order at startup, or manually with `npm run migrate`.
- Applied migrations are recorded in `schema_migrations`.
- A Postgres advisory lock ensures only one instance migrates at a time.
- Never edit a migration that has been applied. Add a new file instead.

## Environment variables

| var | default | purpose |
|---|---|---|
| `DATABASE_URL` | required | Postgres connection string. Add `?sslmode=require` for managed providers that need it. |
| `PORT` | `8080` | HTTP and WebSocket port. |
| `PUBLIC_BASE_URL` | `http://127.0.0.1:8080` | Public origin used in tracking links (`${PUBLIC_BASE_URL}/t/<token>`), which are sent in SMS and in alerts. In production this **must** be your HTTPS URL. When it starts with `https://`, HSTS and `upgrade-insecure-requests` are also enabled. |
| `EVIDENCE_DIR` | `./data/evidence` | Evidence storage: `<userId>/<incidentId>/<evidenceId>.<jpg\|m4a\|mp4>`. Uploads land in `.tmp/` first. |
| `SIGNING_SECRET` | random per boot (with a warning) | HMAC-SHA256 key for 15-minute evidence URLs. Set it to 32+ random bytes, and use the same value on every instance. |
| `MAX_EVIDENCE_BYTES` | `52428800` | Upload size limit (50 MB). |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` | unset | Optional. When an overdue check-in escalates, its contacts get an SMS with the tracking link. Twilio is called over its REST API, with no SDK. |
| `WHATSAPP_*` | unset | Optional WhatsApp alerts to her emergency contacts; see [WhatsApp alerts setup](#whatsapp-alerts-setup). |
| `FIREBASE_SERVICE_ACCOUNT` or `FIREBASE_SERVICE_ACCOUNT_FILE` | unset | Strongly recommended: FCM push so helper/guardian alerts reach phones whose app is closed; see [Push notifications setup](#push-notifications-setup). |
| `TRUST_PROXY` | `false` | `true` trusts one proxy hop for `X-Forwarded-For`, so per-IP rate limits see real clients. It also accepts a hop count or an express trust-proxy string. |
| `LOG_LEVEL` | `info` | pino log level. Logs are JSON on stdout. Tracking tokens, signatures and `?token=` values are scrubbed from logged URLs. |

## Deploying

The server is a single Node process with no build step. It needs three things:

- A Postgres database.
- A persistent disk for `EVIDENCE_DIR`.
- HTTPS in front of it: a reverse proxy or the platform's load balancer.

### Any managed Postgres (Neon, Render, Railway, Supabase, RDS…)

1. Create the database and copy its connection string into `DATABASE_URL`. Neon and most others need `?sslmode=require`.
2. Set `PUBLIC_BASE_URL=https://your-domain`, a strong `SIGNING_SECRET`, and `TRUST_PROXY=true`.
3. Run `npm ci --omit=dev && npm start`. Migrations run automatically.

**Render / Railway:**

- Create a Node web service with root directory `backend`, build command `npm ci --omit=dev` and start command `npm start`.
- Attach a **persistent disk or volume** and point `EVIDENCE_DIR` at it, for example `/data/evidence`. Without one, evidence is lost on every deploy.
- Their HTTPS edge supports WebSockets out of the box.

#### Render free plan specifically

`render.yaml` in the repo root is a ready-made Blueprint (Render dashboard → New → Blueprint →
pick this repo). Two things about the free plan to know before using it for real users, not just
testing:

1. **No persistent disk.** The free plan has nowhere durable to write files, so `render.yaml` sets
   `EVIDENCE_DIR=/tmp/evidence`, which is wiped on every restart and every deploy — uploaded photos
   and audio will disappear unpredictably. This is a stopgap so the server runs, not a fix. To keep
   evidence for real, either upgrade to a paid Render plan with a disk, or change `EVIDENCE_DIR`'s
   storage in code to an S3-compatible bucket (Cloudflare R2's free tier is large enough for this).
2. **It sleeps after 15 minutes of no traffic**, and the first request after that takes 30–60 s to
   answer — too slow for an SOS. `.github/workflows/keep-alive.yml` pings `/healthz` every 10
   minutes with a GitHub Actions cron job to prevent that. One-time setup: GitHub repo → **Settings
   → Secrets and variables → Actions → Variables** → add a variable named `BACKEND_URL` set to your
   Render service's URL (e.g. `https://naari-shakti-api.onrender.com`, no trailing slash). GitHub
   only runs scheduled workflows on the default branch and can run them a few minutes late, which is
   why the job runs every 10 minutes against Render's 15-minute sleep timer, not every 13–15. This
   only works while the repo has recent activity — GitHub disables a workflow's schedule after 60
   days with no commits to the repo, so it needs a commit at least that often to keep firing.
   A paid Render plan removes the sleep entirely and makes this workflow unnecessary.

### VPS with systemd and a reverse proxy

`/etc/systemd/system/naari.service`:

```ini
[Unit]
Description=Naari Shakti API
After=network-online.target

[Service]
User=naari
WorkingDirectory=/opt/naari/backend
EnvironmentFile=/opt/naari/backend/.env
ExecStart=/usr/bin/node src/server.js
Restart=always
RestartSec=3
# the process handles SIGTERM: stops the sweeper, closes sockets, drains HTTP, closes the pool
KillSignal=SIGTERM
TimeoutStopSec=15
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/naari

[Install]
WantedBy=multi-user.target
```

With `EVIDENCE_DIR=/var/lib/naari/evidence`, start it with `systemctl enable --now naari`.

**Caddy** handles automatic HTTPS and passes WebSockets through as-is:

```
naari.example.com {
    reverse_proxy 127.0.0.1:8080
    request_body {
        max_size 60MB
    }
}
```

**nginx** needs these settings, plus a certbot certificate:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;       # WebSocket /api/v1/ws
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 75s;                       # > 25 s server ping interval
    client_max_body_size 60m;                     # > MAX_EVIDENCE_BYTES
    proxy_request_buffering off;                  # stream evidence straight through
}
```

**Several instances:**

- Point every instance at the same database, and give them the same `SIGNING_SECRET`.
- Give them a shared `EVIDENCE_DIR`, for example an NFS mount, or pin uploads and evidence reads to one instance.
- Alerts fan out across instances through Postgres `LISTEN/NOTIFY`.
- The check-in sweeper uses `FOR UPDATE SKIP LOCKED`, so each overdue check-in escalates exactly once.
- Rate limits are in-memory, so they apply per instance.

## Push notifications setup

Without push, alerts only arrive while the app is open or its protection engine is running (they
travel over the API WebSocket). With push, nearby-helper and guardian alerts ring full-screen on
the lock screen even when the app was never opened that day. One Firebase project serves both
sides:

1. [console.firebase.google.com](https://console.firebase.google.com) → create a project (say
   `naari-shakti`). Analytics can stay off.
2. **Android app**: in the project, *Add app → Android*, package name `com.example.naarishakti`,
   download `google-services.json` and put it at `app/google-services.json`, then rebuild the app.
   This file must come from the same Firebase project as the server service account; without it,
   the Android app cannot obtain an FCM token and push will not work.
3. **Server**: *Project settings → Service accounts → Generate new private key*. For local runs,
   set `FIREBASE_SERVICE_ACCOUNT_FILE` to the downloaded JSON path. For Render, either set the
   `FIREBASE_SERVICE_ACCOUNT` environment variable to the full JSON value, or add the JSON as a
   Render Secret File and set `FIREBASE_SERVICE_ACCOUNT_FILE` to its mounted path (for example,
   `/etc/secrets/firebase-service-account.json`). Redeploy after changing the environment. Keep
   the private key out of Git. The server's boot warning about push disappears when configured.

The server sends data-only, high-priority FCM messages (the phone builds the alarm-style
notification itself), retires dead tokens automatically, and phones de-duplicate socket + push by
notification id — no double alerts. No Firebase SDK is used server-side, just the FCM HTTP v1 API.

## WhatsApp alerts setup

When configured, the server sends WhatsApp messages from **your Naari Shakti WhatsApp Business number** to the emergency contacts she lists in the app. No official API can send from her personal WhatsApp.

The feature stays dormant until both `WHATSAPP_TOKEN` and `WHATSAPP_PHONE_NUMBER_ID` are set.

### 1. Meta account and number

1. Create a **Meta Business account** (business.facebook.com), then a Meta developer app of type *Business* with the **WhatsApp** product added.
2. Add a phone number for the **WhatsApp Business** sender and verify it. The number must not already be registered on the WhatsApp or WhatsApp Business app.
3. Create a **permanent access token**:
   - Go to Business settings, then *System users*, and add a system user with Admin role.
   - Assign it your app and your WhatsApp account with full control.
   - Click *Generate token* with the `whatsapp_business_messaging` and `whatsapp_business_management` permissions.
   - Temporary 24-hour tokens from the API setup page are fine for testing only.
4. Copy these into `.env`:
   - The *Phone number ID*, as `WHATSAPP_PHONE_NUMBER_ID`.
   - The token, as `WHATSAPP_TOKEN`.
   - The app secret from *App settings*, then *Basic*, as `WHATSAPP_APP_SECRET`.
   - The number in E.164, as `WHATSAPP_BUSINESS_NUMBER`. This is optional; if unset it is looked up from the Graph API.

### 2. Message templates (submit for approval, category **UTILITY**, language **English (en)**)

Business-initiated messages must use approved templates. Submit these four in WhatsApp Manager, under *Message templates*. The names are the defaults and can be overridden with `WHATSAPP_TEMPLATE_*`.

| name | header | body |
|---|---|---|
| `naari_sos_alert` | **Location** | `{{1}} needs help. Live location and evidence: {{2}}` |
| `naari_sos_alert_text` | none | `{{1}} needs help. Live location and evidence: {{2}}` |
| `naari_sos_evidence` | **Image** | `New photo from {{1}}'s SOS. Live: {{2}}` |
| `naari_sos_safe` | none | `{{1}} has marked herself safe.` |

- In each body, `{{1}}` is her name and `{{2}}` is the tracking link (`PUBLIC_BASE_URL/t/<token>`).
- `naari_sos_alert_text` is the fallback for an SOS that has no location yet. A template with a LOCATION header cannot be sent without a location, so the fallback needs its own template.
- Meta asks for sample values when you submit: use a name such as `Asha` and a sample link.
- To support another language, approve translations under the same names and set `WHATSAPP_TEMPLATE_LANG`. The setting is global.

### 3. Webhook

- In the app's *WhatsApp*, then *Configuration* page, set the callback URL to `https://<your-domain>/api/v1/whatsapp/webhook`.
- Set the verify token to the value of `WHATSAPP_VERIFY_TOKEN`.
- Subscribe to the **messages** field.
- Meta verifies the URL with a GET request. POSTs are checked against `X-Hub-Signature-256` using `WHATSAPP_APP_SECRET`.
- Always set the app secret. Without it, anyone could forge JOIN or STOP messages. The server logs a warning at startup when it is missing.

### 4. Recipient opt-in (required by WhatsApp)

1. She adds up to 5 contacts in the app with `PUT /api/v1/me/whatsapp`. The response includes `joinUrl` (`https://wa.me/<business>?text=JOIN`), which the app shares with her contacts.
2. Each contact opens that link and sends **JOIN**. That opts the number in for every user who currently lists it, and they get a short confirmation.
3. **STOP** opts the number out everywhere.
4. Only opted-in numbers of the incident owner, whose WhatsApp alerts are enabled, ever receive messages. A number that is listed later has to send JOIN again.

### What gets sent

| When | What |
|---|---|
| SOS, once a location exists | `naari_sos_alert` with her last location as the Location header. |
| SOS with still no location after 20 s | `naari_sos_alert_text`, sent once. If a location arrives later, the location version is sent too. |
| A photo is verified while the incident is active or under duress | `naari_sos_evidence` with the photo (images up to 5 MB). The photo is uploaded to the Cloud API `/media` endpoint first. |
| The incident ends without duress | `naari_sos_safe`, but only if an SOS was actually sent. |
| The incident ends under duress | Nothing that says "safe". |

- **Evidence limits:** at most one photo per contact per 60 s, and at most 10 per contact per incident. The limits are tracked in the `whatsapp_messages` table, so they hold across instances.
- **Retries:** each send is retried up to 3 times with backoff (1, 2 and 4 s) on network errors, HTTP 5xx and HTTP 429.
- **Logging:** every message is recorded in `whatsapp_messages`, with its status updated from Meta's delivery callbacks.
- **Never blocking:** sending never delays the API response.

### Cost

- WhatsApp charges per delivered template message. These count as *utility* messages, and prices vary by recipient country; see Meta's pricing page. India's utility rate is a fraction of a rupee per message.
- One SOS with 5 contacts costs about 5 to 10 utility messages, plus up to 10 evidence photos per contact.
- JOIN and STOP replies are free-form messages inside the 24-hour customer-service window, which is free.
- Set a spending limit in WhatsApp Manager.

## Backups

- **Database:** use your provider's point-in-time recovery, or run `pg_dump -Fc "$DATABASE_URL" > naari-$(date +%F).dump` on a schedule.
- **Evidence files:** `EVIDENCE_DIR` is not in the database. Back it up separately, for example `restic backup /var/lib/naari/evidence` or `rclone sync` to encrypted object storage. Skip `.tmp/`, which only holds uploads in progress.
- **Restores:** files are immutable once written, and `evidence.server_sha256` holds each file's hash. After a restore you can check every file against the database, so the backup itself stays tamper-evident.
- **Storage security:** evidence is sensitive personal data. Encrypt backups at rest and restrict who can read them.

## Privacy and security notes

- **Tracking tokens are secrets.** Anyone holding `/t/<token>` can see her live location, so share the link only with trusted contacts. Tokens are 22+ random URL-safe characters generated on the phone and are never logged. The page sends `Referrer-Policy: strict-origin`, so map-tile and CDN requests never see the token. Tracking responses are `no-store` and `noindex`.
- **Evidence is only served through signed URLs.** `/api/v1/track/<token>/evidence/<id>?exp=&sig=` carries an HMAC-SHA256 over `evidenceId|exp` that expires after 15 minutes. It also needs the matching tracking token, and only hash-verified items are served. There is no public directory listing, and `EVIDENCE_DIR` is never served statically.
- **24 hours after an incident ends,** the tracking API stops returning location, path and evidence, and old evidence links stop working.
- **Device tokens** are 43-character random bearer tokens. The server stores only their SHA-256.
- **Account deletion:** `DELETE /api/v1/me` deletes the account and everything linked to it: incidents, locations, evidence rows, evidence files on disk, check-ins, links and notifications. It also closes the user's open sockets.
- **Other protections:**
  - JSON bodies are limited to 100 kb, and evidence uploads to `MAX_EVIDENCE_BYTES`.
  - Rate limits: registration 30/hour/IP, tracking JSON 60/min/IP, and the tracking page plus evidence files 300/min/IP. Wrong guardian codes are limited to 10 per hour per user (stored in the DB).
  - Error responses never include stack traces.
  - Helmet sets a strict CSP. The tracking page has no inline scripts, and Leaflet loads from unpkg with SRI.

## Layout

```
src/server.js        bootstrap: migrations, HTTP + WS, LISTEN client, sweeper, graceful shutdown
src/app.js           express app (imported by tests)
src/config.js        env parsing
src/db.js            pool, tx() helper, reconnecting LISTEN client
src/migrate.js       migration runner
src/auth.js          device tokens (SHA-256) + bearer middleware
src/incidents.js     shared incident logic: helper fan-out, end, track URLs
src/notify.js        persist notification + NOTIFY naari_events
src/realtime.js      WebSocket hub
src/push.js          FCM HTTP v1 push (service-account JWT, no SDK) for closed apps
src/geo.js           haversine + bounding box
src/sms.js           Twilio over fetch
src/whatsapp.js      WhatsApp Business Cloud API (templates, media upload, retries, JOIN/STOP)
src/signing.js       HMAC-signed evidence URLs
src/rateLimit.js     in-memory limiter
src/jobs/checkinSweeper.js
src/routes/*.js      me, guardians, helper, incidents, evidence, checkins, alerts, notifications, track
public/              track.html / track.css / track.js / i18n.js (tracking page, English + Hindi)
migrations/          SQL migrations
test/                integration tests
```

### Tracking-page languages

All of the page's visible text comes from `public/i18n.js`. The language is picked from `navigator.languages`, and you can force one with `?lang=hi` for testing.

To add a language, add a dictionary under its ISO code (for example `ta`, `bn` or `mr`) with the same keys. Any missing key falls back to English.
