package commands

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/dbay-cloud/dbay-rescue/internal/rds"
	"github.com/spf13/cobra"
)

// NewRebuildMetadataCmd reconstructs the lakeon-api metadata DB from per-tenant
// TenantManifest objects in OBS. Used as a disaster-recovery procedure when RDS
// is destroyed but OBS is intact. The rebuild is idempotent (upserts per row)
// and per-tenant atomic (each tenant gets its own transaction).
func NewRebuildMetadataCmd(credsPath *string) *cobra.Command {
	var dsn string
	cmd := &cobra.Command{
		Use:   "rebuild-metadata",
		Short: "Rebuild RDS metadata from OBS manifests",
		RunE: func(cmd *cobra.Command, args []string) error {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
			defer cancel()

			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			if dsn == "" {
				dsn = c.RDS.DefaultDSN
			}
			if dsn == "" {
				return fmt.Errorf("--to DSN required (or set rds.default_dsn in creds)")
			}

			obsCli, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer obsCli.Close()

			conn, err := rds.Connect(ctx, dsn)
			if err != nil {
				return fmt.Errorf("connect rds: %w", err)
			}
			defer conn.Close(ctx)
			if err := conn.Ping(ctx); err != nil {
				return fmt.Errorf("ping rds: %w", err)
			}

			keys, err := obsCli.ListKeys("tenants/")
			if err != nil {
				return err
			}

			var ok, failed int
			for _, k := range keys {
				if !strings.HasSuffix(k, "/_manifest.json") {
					continue
				}
				var m obs.TenantManifest
				if err := obsCli.GetJSON(k, &m); err != nil {
					fmt.Printf("  ! parse %s: %v\n", k, err)
					failed++
					continue
				}
				if err := applyManifest(ctx, conn, m); err != nil {
					fmt.Printf("  ! apply tenant %s: %v\n", m.TenantID, err)
					failed++
					continue
				}
				ok++
				if ok%10 == 0 {
					fmt.Printf("  rebuilt %d tenants...\n", ok)
				}
			}
			fmt.Printf("\nDone. ok=%d failed=%d\n", ok, failed)
			if failed > 0 {
				return fmt.Errorf("%d tenants failed", failed)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&dsn, "to", "", "Target RDS DSN (or use rds.default_dsn in creds)")
	return cmd
}

// applyManifest rebuilds one tenant's rows (tenant + databases + branches) in
// a single transaction so a partial failure leaves the tenant's state
// unchanged.
func applyManifest(ctx context.Context, conn *rds.Conn, m obs.TenantManifest) error {
	tx, err := conn.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if err := conn.UpsertTenant(ctx, tx, m.TenantID, m.OwnerEmail, m.CreatedAt); err != nil {
		return fmt.Errorf("upsert tenant: %w", err)
	}
	for _, db := range m.Databases {
		if err := conn.UpsertDatabase(ctx, tx, db.DBID, m.TenantID, db.Name,
			db.NeonTenantID, db.TimelineID, db.CreatedAt, db.DeletedAt); err != nil {
			return fmt.Errorf("upsert db %s: %w", db.DBID, err)
		}
		for _, br := range db.Branches {
			if err := conn.UpsertBranch(ctx, tx, br.BranchID, db.DBID, br.Parent, br.LSN); err != nil {
				return fmt.Errorf("upsert branch %s: %w", br.BranchID, err)
			}
		}
	}
	return tx.Commit(ctx)
}
