-- Add username/password auth to tenants
ALTER TABLE tenants ADD COLUMN username VARCHAR(64) UNIQUE;
ALTER TABLE tenants ADD COLUMN password_hash VARCHAR(128);

-- Backfill: set username = name for existing tenants
UPDATE tenants SET username = name WHERE username IS NULL;
