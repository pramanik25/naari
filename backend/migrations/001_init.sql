-- Naari Shakti schema v1. Plain Postgres 13+ (gen_random_uuid is built in); no extensions required.

CREATE TABLE users (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name           text NOT NULL DEFAULT '' CHECK (char_length(name) <= 80),
  guardian_code  text UNIQUE CHECK (guardian_code ~ '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$'),
  created_at     timestamptz NOT NULL DEFAULT now()
);

-- Device bearer tokens: only the SHA-256 (hex) is stored.
CREATE TABLE device_tokens (
  token_hash   text PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_name  text CHECK (char_length(device_name) <= 80),
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX device_tokens_user_idx ON device_tokens (user_id);

-- guardian_id receives alerts about ward_id.
CREATE TABLE guardian_links (
  ward_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  guardian_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  linked_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ward_id, guardian_id),
  CHECK (ward_id <> guardian_id)
);
CREATE INDEX guardian_links_guardian_idx ON guardian_links (guardian_id);

-- Wrong guardian-code attempts, for the 10 / hour / user throttle (shared across instances).
CREATE TABLE guardian_link_failures (
  id          bigserial PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  failed_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX guardian_link_failures_user_idx ON guardian_link_failures (user_id, failed_at);

-- Opt-in volunteer helpers (latest location only).
CREATE TABLE helpers (
  user_id     uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  lat         double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lng         double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX helpers_lat_lng_idx ON helpers (lat, lng);
CREATE INDEX helpers_updated_idx ON helpers (updated_at);

CREATE TABLE incidents (
  id                    uuid PRIMARY KEY,
  user_id               uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  track_token           text NOT NULL UNIQUE CHECK (track_token ~ '^[A-Za-z0-9_-]{22,64}$'),
  source                text NOT NULL CHECK (source ~ '^[a-z0-9_]{1,32}$'),
  silent                boolean NOT NULL DEFAULT false,
  status                text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'ended')),
  duress                boolean NOT NULL DEFAULT false,
  duress_at             timestamptz,
  started_at            timestamptz NOT NULL,
  ended_at              timestamptz,
  user_initiated_end    boolean,
  contacts_count        integer NOT NULL DEFAULT 0 CHECK (contacts_count BETWEEN 0 AND 1000),
  battery               smallint CHECK (battery BETWEEN 0 AND 100),
  last_lat              double precision CHECK (last_lat BETWEEN -90 AND 90),
  last_lng              double precision CHECK (last_lng BETWEEN -180 AND 180),
  last_accuracy         real,
  last_at               timestamptz,
  helpers_fanned_out_at timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'ended') = (ended_at IS NOT NULL))
);
CREATE INDEX incidents_user_started_idx ON incidents (user_id, started_at DESC);

CREATE TABLE incident_locations (
  id           bigserial PRIMARY KEY,
  incident_id  uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  lat          double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lng          double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
  accuracy     real CHECK (accuracy >= 0),
  at           timestamptz NOT NULL
);
CREATE INDEX incident_locations_incident_at_idx ON incident_locations (incident_id, at DESC);

-- Helpers who were sent a helper_alert for an incident (and whether they responded).
CREATE TABLE incident_helpers (
  incident_id   uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  helper_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  distance_m    integer NOT NULL CHECK (distance_m >= 0),
  alerted_at    timestamptz NOT NULL DEFAULT now(),
  responded_at  timestamptz,
  respond_lat   double precision,
  respond_lng   double precision,
  PRIMARY KEY (incident_id, helper_id)
);
CREATE INDEX incident_helpers_helper_idx ON incident_helpers (helper_id);

CREATE TABLE evidence (
  id             uuid PRIMARY KEY,
  incident_id    uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind           text NOT NULL CHECK (kind IN ('photo', 'audio', 'video')),
  content_type   text NOT NULL CHECK (content_type IN ('image/jpeg', 'audio/mp4', 'video/mp4')),
  size           bigint NOT NULL CHECK (size >= 0),
  sha256         text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  server_sha256  text NOT NULL CHECK (server_sha256 ~ '^[0-9a-f]{64}$'),
  verified       boolean NOT NULL,
  captured_at    timestamptz NOT NULL,
  received_at    timestamptz NOT NULL DEFAULT now(),
  file_path      text NOT NULL
);
CREATE INDEX evidence_incident_idx ON evidence (incident_id, received_at DESC);
CREATE INDEX evidence_user_idx ON evidence (user_id);

CREATE TABLE checkins (
  id             uuid PRIMARY KEY,
  user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'cancelled', 'overdue')),
  deadline       timestamptz NOT NULL,
  note           text NOT NULL DEFAULT '' CHECK (char_length(note) <= 200),
  contacts       jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(contacts) = 'array'),
  last_lat       double precision CHECK (last_lat BETWEEN -90 AND 90),
  last_lng       double precision CHECK (last_lng BETWEEN -180 AND 180),
  last_accuracy  real,
  last_at        timestamptz,
  incident_id    uuid REFERENCES incidents(id) ON DELETE SET NULL,
  escalated_at   timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX checkins_user_idx ON checkins (user_id);
CREATE INDEX checkins_due_idx ON checkins (deadline) WHERE status = 'active';

CREATE TABLE notifications (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  incident_id   uuid REFERENCES incidents(id) ON DELETE CASCADE,
  type          text NOT NULL CHECK (type IN ('sos', 'duress', 'ended', 'helper_alert', 'responder', 'checkin_overdue')),
  payload       jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz NOT NULL DEFAULT now(),
  acked_at      timestamptz
);
CREATE INDEX notifications_pending_idx ON notifications (recipient_id, created_at) WHERE acked_at IS NULL;
CREATE INDEX notifications_incident_idx ON notifications (incident_id);
