// Package rds provides a thin pgx wrapper used by `dbay-rescue rebuild-metadata`
// to reconstruct lakeon-api's metadata DB from OBS TenantManifest files.
//
// Schema source of truth: lakeon-api/src/main/resources/db/migration/V1__init_schema.sql
// plus later ALTERs (V3 quotas, V8 username/password, V34 email, V40 recovered_from_pitr).
//
// IMPORTANT - manifest <-> table mapping notes:
//   - `tenants.name` and `tenants.api_key` are NOT NULL UNIQUE in V1. Manifests do
//     not carry these (privacy + drift risk), so on rebuild we backfill name=tenant_id
//     and api_key='REBUILT-<tenant_id>' as placeholders. Operators must rotate the
//     api_key out-of-band after rebuild.
//   - `database_instances` has no `deleted_at` column; deletions are encoded as
//     status='DELETED'. The manifest's deleted_at is preserved by reflecting it
//     into status_message so the operator can see when it happened.
//   - `branches` has no `start_lsn` column; the timeline anchor is stored via
//     `neon_timeline_id` (branch's own timeline) and `parent_branch_id`. The
//     manifest's LSN is encoded into the synthetic branch `name` ("rebuilt@<lsn>")
//     when no original name is available - this is purely informational because
//     the original branch names are lost after RDS destruction.
package rds

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// Conn wraps a single pgx connection. Rebuilds run serially per-tenant so a
// pool is not needed.
type Conn struct {
	c *pgx.Conn
}

// Connect dials the target Postgres DSN.
func Connect(ctx context.Context, dsn string) (*Conn, error) {
	c, err := pgx.Connect(ctx, dsn)
	if err != nil {
		return nil, err
	}
	return &Conn{c: c}, nil
}

// Close releases the underlying connection.
func (c *Conn) Close(ctx context.Context) error {
	if c == nil || c.c == nil {
		return nil
	}
	return c.c.Close(ctx)
}

// Ping verifies the connection is live.
func (c *Conn) Ping(ctx context.Context) error {
	return c.c.Ping(ctx)
}

// Begin starts a transaction. Each tenant's rebuild runs in its own tx so a
// failure on one tenant cannot leave half-rebuilt state for that tenant, while
// other tenants continue.
func (c *Conn) Begin(ctx context.Context) (pgx.Tx, error) {
	return c.c.Begin(ctx)
}

// UpsertTenant inserts/updates a row in `tenants`. `name` and `api_key` are
// NOT NULL UNIQUE in the schema but missing from manifests; we synthesize
// placeholders that the operator must rotate out-of-band after rebuild.
func (c *Conn) UpsertTenant(ctx context.Context, tx pgx.Tx, id, email string, createdAt time.Time) error {
	if id == "" {
		return fmt.Errorf("tenant id is required")
	}
	name := id
	apiKey := "REBUILT-" + id
	_, err := tx.Exec(ctx, `
		INSERT INTO tenants (id, name, api_key, email, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $5)
		ON CONFLICT (id) DO UPDATE SET
			email      = COALESCE(EXCLUDED.email, tenants.email),
			updated_at = NOW()
	`, id, name, apiKey, nullIfEmpty(email), createdAt)
	return err
}

// UpsertDatabase upserts into `database_instances`. The schema lacks a
// `deleted_at` column; we encode soft-delete via `status='DELETED'` and copy
// the timestamp into `status_message` for audit.
func (c *Conn) UpsertDatabase(
	ctx context.Context,
	tx pgx.Tx,
	id, tenantID, name, neonTenantID, timelineID string,
	createdAt time.Time,
	deletedAt *time.Time,
) error {
	status := "ACTIVE"
	var statusMsg any = nil
	if deletedAt != nil {
		status = "DELETED"
		statusMsg = fmt.Sprintf("rebuilt: deleted_at=%s", deletedAt.UTC().Format(time.RFC3339))
	}
	_, err := tx.Exec(ctx, `
		INSERT INTO database_instances (
			id, name, tenant_id,
			neon_tenant_id, neon_timeline_id,
			status, status_message,
			created_at, updated_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8)
		ON CONFLICT (id) DO UPDATE SET
			name             = EXCLUDED.name,
			neon_tenant_id   = EXCLUDED.neon_tenant_id,
			neon_timeline_id = EXCLUDED.neon_timeline_id,
			status           = EXCLUDED.status,
			status_message   = EXCLUDED.status_message,
			updated_at       = NOW()
	`, id, name, tenantID, nullIfEmpty(neonTenantID), nullIfEmpty(timelineID), status, statusMsg, createdAt)
	return err
}

// UpsertBranch upserts into `branches`. The schema has no `start_lsn`; we
// stash the LSN into the branch `name` so the operator can see the anchor
// point of each rebuilt branch ("rebuilt@<lsn>" or just "rebuilt-<branch_id>").
func (c *Conn) UpsertBranch(ctx context.Context, tx pgx.Tx, id, dbID, parentID, lsn string) error {
	if id == "" {
		return fmt.Errorf("branch id is required")
	}
	name := "rebuilt-" + id
	if lsn != "" {
		name = "rebuilt@" + lsn
	}
	_, err := tx.Exec(ctx, `
		INSERT INTO branches (
			id, name, database_id,
			parent_branch_id, neon_timeline_id,
			status, created_at, updated_at
		) VALUES ($1, $2, $3, NULLIF($4, ''), NULLIF($5, ''), 'ACTIVE', NOW(), NOW())
		ON CONFLICT (id) DO UPDATE SET
			parent_branch_id = EXCLUDED.parent_branch_id,
			neon_timeline_id = EXCLUDED.neon_timeline_id,
			updated_at       = NOW()
	`, id, name, dbID, parentID, lsn)
	return err
}

func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}
