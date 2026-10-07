-- Her own mobile number, entered in the app's profile (E.164, like whatsapp_contacts.number).
-- Optional: users who never fill in the profile stay NULL.
ALTER TABLE users
  ADD COLUMN phone text CHECK (phone ~ '^\+[1-9][0-9]{7,14}$');
