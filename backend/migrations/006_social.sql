-- v1.2: family circle, community safety map, anonymous community.

-- Family circle: the latest position of a user who chose to share it with the people she is
-- linked to as guardian or ward. A row exists only while sharing is on.
CREATE TABLE circle_presence (
  user_id     uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  lat         double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lng         double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
  accuracy    real CHECK (accuracy >= 0),
  battery     smallint CHECK (battery BETWEEN 0 AND 100),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Community safety map: places users marked as unsafe (or safe). The author is never returned.
CREATE TABLE place_reports (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  category    text NOT NULL CHECK (category IN ('poorly_lit', 'isolated', 'harassment', 'unsafe_transport', 'safe_spot')),
  lat         double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lng         double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
  note        text NOT NULL DEFAULT '' CHECK (char_length(note) <= 200),
  hidden      boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX place_reports_lat_lng_idx ON place_reports (lat, lng) WHERE NOT hidden;
CREATE INDEX place_reports_user_idx ON place_reports (user_id, created_at);

-- Anonymous community. `alias` is derived per thread, so one person cannot be followed from
-- thread to thread.
CREATE TABLE community_posts (
  id           uuid PRIMARY KEY,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  topic        text NOT NULL CHECK (topic IN ('advice', 'experience', 'legal', 'health', 'support')),
  body         text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 1000),
  alias        text NOT NULL,
  reply_count  integer NOT NULL DEFAULT 0,
  hidden       boolean NOT NULL DEFAULT false,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX community_posts_feed_idx ON community_posts (created_at DESC) WHERE NOT hidden;
CREATE INDEX community_posts_user_idx ON community_posts (user_id, created_at);

CREATE TABLE community_replies (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id     uuid NOT NULL REFERENCES community_posts(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body        text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 500),
  alias       text NOT NULL,
  hidden      boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX community_replies_post_idx ON community_replies (post_id, created_at);
CREATE INDEX community_replies_user_idx ON community_replies (user_id, created_at);

-- "Report" by a user on a map report, a post or a reply; content hides itself after enough of them.
CREATE TABLE content_flags (
  target_type  text NOT NULL CHECK (target_type IN ('place', 'post', 'reply')),
  target_id    uuid NOT NULL,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (target_type, target_id, user_id)
);

-- She never sees posts or replies written by someone she blocked.
CREATE TABLE community_blocks (
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, blocked_id),
  CHECK (user_id <> blocked_id)
);
