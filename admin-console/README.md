# Naari Shakti Admin Console

Next.js web console for support staff: browse every user and their full activity —
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
| `ADMIN_PASSWORD` | password for the /login page (required) |
| `ADMIN_SESSION_SECRET` | optional cookie-signing secret (defaults to one derived from the password) |
| `PUBLIC_BASE_URL` | API origin, used to build `…/t/<token>` tracking links |
| `ADMIN_TZ` | timezone for displayed timestamps (default `Asia/Kolkata`) |

## Pages

- `/` — dashboard: user/incident/check-in/helper counts and a live activity feed
- `/users` — searchable user list (name, guardian code or id)
- `/users/<id>` — profile, devices & push status, guardians/wards, WhatsApp contacts,
  incidents, check-ins, alerts received
- `/incidents` — filterable by status/duress, searchable
- `/incidents/<id>` — full incident: map of last location, location trail, evidence,
  helpers alerted/responding, alert fan-out, WhatsApp delivery
- `/checkins` — all timed check-ins, filter by status
- `/helpers` — volunteer helpers with freshness and response stats

## Deploy (Render, same as the backend)

Create a Web Service from this folder: build `npm install && npm run build`,
start `npm start`, add the env vars above. Use a strong `ADMIN_PASSWORD` —
this console can see every user's data.

## Security notes

- Session is an HttpOnly, HMAC-signed cookie valid 7 days; middleware guards every route.
- The console is read-only by construction (only `SELECT` statements).
- Evidence *files* are intentionally not proxied here; the metadata table links to the
  public tracking page instead, which already enforces retention/expiry rules.
