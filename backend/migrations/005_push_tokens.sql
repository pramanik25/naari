-- FCM push delivery: a device may register its Firebase Cloud Messaging token so alerts reach
-- the phone even when the app process is dead (lock screen / app closed / battery-killed).
ALTER TABLE device_tokens
  ADD COLUMN push_token text CHECK (char_length(push_token) BETWEEN 1 AND 4096),
  ADD COLUMN push_updated_at timestamptz;

-- Fan-out looks up every push-capable device of a recipient.
CREATE INDEX device_tokens_push_idx ON device_tokens (user_id) WHERE push_token IS NOT NULL;
