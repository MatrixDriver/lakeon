-- Leader-election lease: each forwarder pod upserts with
-- ON CONFLICT WHERE locked_until < now() to claim a tenant's events
-- for one cycle. Other pods see no row affected and skip that tenant.

CREATE TABLE IF NOT EXISTS lbfs_forwarder_locks (
    tenant_id      VARCHAR(32) PRIMARY KEY,
    locked_by      VARCHAR(64) NOT NULL,           -- pod hostname
    locked_until   TIMESTAMPTZ NOT NULL,
    last_event_id  BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
