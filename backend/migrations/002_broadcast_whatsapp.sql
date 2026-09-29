-- v1.1: nearby broadcast flag, evidence_update throttle, WhatsApp Business alerts.

ALTER TABLE incidents
  ADD COLUMN broadcast               boolean NOT NULL DEFAULT true,
  ADD COLUMN evidence_update_at      timestamptz,          -- last evidence_update fan-out (60 s throttle)
  ADD COLUMN wa_sos_location_at      timestamptz,          -- WhatsApp SOS (LOCATION header) sent
  ADD COLUMN wa_sos_text_at          timestamptz,          -- WhatsApp text-only fallback sent
  ADD COLUMN wa_safe_at              timestamptz;          -- WhatsApp "safe" sent

CREATE INDEX incidents_wa_pending_idx ON incidents (created_at)
  WHERE wa_sos_location_at IS NULL AND wa_sos_text_at IS NULL;

ALTER TABLE incident_helpers
  ADD COLUMN radius_km smallint NOT NULL DEFAULT 2 CHECK (radius_km IN (2, 5));

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
  'sos', 'duress', 'ended', 'helper_alert', 'responder', 'checkin_overdue', 'evidence_update'));

ALTER TABLE users ADD COLUMN whatsapp_enabled boolean NOT NULL DEFAULT false;

-- Her WhatsApp emergency contacts. Opt-in is per (user, number): a JOIN from a number opts it in
-- for every user who has listed it at that moment; STOP opts it out everywhere.
CREATE TABLE whatsapp_contacts (
  user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  number         text NOT NULL CHECK (number ~ '^\+[1-9][0-9]{7,14}$'),
  name           text NOT NULL DEFAULT '' CHECK (char_length(name) <= 60),
  position       smallint NOT NULL DEFAULT 0,            -- order as listed by the user
  opted_in      boolean NOT NULL DEFAULT false,
  opted_in_at    timestamptz,
  opted_out_at   timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, number)
);
CREATE INDEX whatsapp_contacts_number_idx ON whatsapp_contacts (number);

-- Every outbound WhatsApp message (also the throttle state for evidence messages).
CREATE TABLE whatsapp_messages (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              uuid REFERENCES users(id) ON DELETE CASCADE,
  incident_id          uuid REFERENCES incidents(id) ON DELETE CASCADE,
  evidence_id          uuid REFERENCES evidence(id) ON DELETE CASCADE,
  to_number            text NOT NULL,
  kind                 text NOT NULL CHECK (kind IN ('sos', 'sos_text', 'evidence', 'safe', 'reply')),
  template             text,
  status               text NOT NULL DEFAULT 'pending'
                         CHECK (status IN ('pending', 'sent', 'delivered', 'read', 'failed')),
  attempts             integer NOT NULL DEFAULT 0,
  provider_message_id  text,
  error                text,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX whatsapp_messages_incident_idx ON whatsapp_messages (incident_id, to_number, kind, created_at);
CREATE INDEX whatsapp_messages_provider_idx ON whatsapp_messages (provider_message_id);
