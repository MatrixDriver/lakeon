-- PITR (Point-In-Time Restore) creates a new database row pointing at a branched
-- Neon timeline. This flag marks such rows so the UI / dbay-cli can render a "restored
-- from <timestamp>" badge and so operators can audit recovery activity.
ALTER TABLE database_instances
    ADD COLUMN recovered_from_pitr BOOLEAN NOT NULL DEFAULT FALSE;
