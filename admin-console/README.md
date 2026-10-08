# Naari Shakti website and admin console

One Next.js app serving two things:

- **Public site** at `/` — what the app does, how it keeps users safe, and the APK download (`/download`).
- **Admin console** at `/admin` — password-protected, for support staff.

The console lets support staff browse every user and their full activity —
devices, guardian links, SOS incidents (with location trail, evidence metadata,
alerted helpers and WhatsApp messages), check-ins, and volunteer helpers.

It reads the **same Postgres database** as the API server (`backend/`); nothing in the
backend needs to change and the console never writes to the database.

## Run locally

```bash
cd admin-console
npm install
npm run dev        # http://localhost:3100
```

Configuration lives in `.env.local` (see `.env.example`):

| var | purpose |
|---|---|
| `DATABASE_URL` | same value as `backend/.env` |
| `ADMIN_PASSWORD` | password for the /admin/login page (required) |
| `ADMIN_SESSION_SECRET` | optional cookie-signing secret (defaults to one derived from the password) |
| `PUBLIC_BASE_URL` | API origin, used to build `…/t/<token>` tracking links |
| `ADMIN_TZ` | timezone for displayed timestamps (default `Asia/Kolkata`) |
| `APK_URL` | optional: where `/download` redirects (see *Publishing a new APK*) |

## Pages

Public:

- `/` — landing page: features, privacy, install steps (copy lives in `src/app/page.js`)
- `/download` — the APK

Admin (login required):

- `/admin` — dashboard: user/incident/check-in/helper counts and a live activity feed
- `/admin/users` — searchable user list (name, guardian code or id)
- `/admin/users/<id>` — profile, devices & push status, guardians/wards, WhatsApp contacts,
  incidents, check-ins, alerts received
- `/admin/devices` — installs per day (device registrations), recent registrations, and per-user
  last-seen with a dormant flag (the closest signal to an uninstall; the app never reports
  uninstalls — use Play Console / Firebase Analytics for exact numbers)
- `/admin/incidents` — filterable by status/duress, searchable
- `/admin/incidents/<id>` — full incident: map of last location, location trail, evidence,
  helpers alerted/responding, alert fan-out, WhatsApp delivery
- `/admin/checkins` — all timed check-ins, filter by status
- `/admin/helpers` — volunteer helpers with freshness and response stats

## Publishing a new APK

The APK (about 150 MB) is too large for git, so it is published as a GitHub release asset
and the site links to it.

1. Build the signed release APK in Android Studio (`app/build/outputs/apk/release/app-release.apk`).
2. `npm run apk` — copies it to `downloads/naari-shakti.apk` (git-ignored) and writes its
   version, size and SHA-256 to `src/lib/release.json`.
3. On GitHub: Releases → Draft a new release → attach `downloads/naari-shakti.apk`
   (keep the name `naari-shakti.apk`) → Publish.
4. Add the new version's "What's new" entries to `RELEASE_NOTES` in `src/lib/release.js`
   (a version with no entry shows no "What's new" section).
5. Commit `src/lib/release.json` and `src/lib/release.js`, then deploy.

`/download` resolves in this order: `APK_URL` if set → `downloads/naari-shakti.apk` if it
exists on the server (streamed directly, with resume) → the `naari-shakti.apk` asset on the
latest GitHub release. On Render only the first and last apply, since `downloads/` is not in git.

## Deploy (Render, same as the backend)

Create a Web Service from this folder: build `npm install && npm run build`,
start `npm start`, add the env vars above. Use a strong `ADMIN_PASSWORD` —
this console can see every user's data.

## Security notes

- Session is an HttpOnly, HMAC-signed cookie valid 7 days; middleware guards everything under `/admin`; the public site needs no login.
- The console is read-only by construction (only `SELECT` statements).
- Evidence *files* are intentionally not proxied here; the metadata table links to the
  public tracking page instead, which already enforces retention/expiry rules.
