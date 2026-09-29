-- v1.2: the owner's emergency message, shown to nearby helpers together with her name.

ALTER TABLE incidents
  ADD COLUMN message text NOT NULL DEFAULT '' CHECK (char_length(message) <= 500);
