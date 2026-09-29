-- Helper fan-out repeats as her location changes (the first fix is often a stale cached
-- location, and she may be moving). Remember where the last search was centred.
ALTER TABLE incidents
  ADD COLUMN IF NOT EXISTS fanout_lat double precision,
  ADD COLUMN IF NOT EXISTS fanout_lng double precision;
