//go:build e2e

package test

import (
	"context"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/jackc/pgx/v5"
)

func env(k string) string {
	return os.Getenv(k)
}

func TestE2E_ListTenants(t *testing.T) {
	if env("DBAY_RESCUE_TEST_CREDS") == "" {
		t.Skip("DBAY_RESCUE_TEST_CREDS not set")
	}
	cmd := exec.Command("../dbay-rescue", "list-tenants", "--creds", env("DBAY_RESCUE_TEST_CREDS"))
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("list-tenants: %v\n%s", err, out)
	}
	if !strings.Contains(string(out), "TENANT_ID") {
		t.Errorf("expected TENANT_ID header in output:\n%s", out)
	}
}

func TestE2E_RebuildMetadata_RoundTrip(t *testing.T) {
	creds := env("DBAY_RESCUE_TEST_CREDS")
	dsn := env("DBAY_RESCUE_TEST_RDS")
	endpoint := env("OBS_ENDPOINT")
	ak := env("OBS_AK")
	sk := env("OBS_SK")
	bucket := env("OBS_BUCKET")
	if creds == "" || dsn == "" || endpoint == "" || ak == "" || sk == "" || bucket == "" {
		t.Skip("E2E env vars missing")
	}

	c, err := obs.New(endpoint, ak, sk, bucket)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	tenantID := "e2etest_" + time.Now().UTC().Format("20060102150405")
	manifest := obs.TenantManifest{
		ManifestVersion: 1,
		TenantID:        tenantID,
		OwnerEmail:      "e2e@test.local",
		CreatedAt:       time.Now().UTC(),
		UpdatedAt:       time.Now().UTC(),
		Version:         1,
		Databases: []obs.DatabaseEntry{{
			DBID:         "db_e2e_" + tenantID,
			Name:         "edb",
			NeonTenantID: "nt_e2e",
			TimelineID:   "tl_e2e",
			CreatedAt:    time.Now().UTC(),
		}},
	}
	mkey := "tenants/" + tenantID + "/_manifest.json"
	if err := c.PutJSON(mkey, manifest); err != nil {
		t.Fatalf("put manifest: %v", err)
	}
	defer func() {
		_ = c.DeleteKey(mkey)
	}()

	cmd := exec.Command("../dbay-rescue", "rebuild-metadata", "--creds", creds, "--to", dsn)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("rebuild-metadata: %v\n%s", err, out)
	}

	ctx := context.Background()
	pg, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatalf("rds connect: %v", err)
	}
	defer pg.Close(ctx)
	var email string
	row := pg.QueryRow(ctx, "SELECT email FROM tenants WHERE id = $1", tenantID)
	if err := row.Scan(&email); err != nil {
		t.Fatalf("query tenant: %v", err)
	}
	if email != "e2e@test.local" {
		t.Errorf("expected e2e@test.local, got %s", email)
	}
	// cleanup
	_, _ = pg.Exec(ctx, "DELETE FROM database_instances WHERE id = $1", manifest.Databases[0].DBID)
	_, _ = pg.Exec(ctx, "DELETE FROM tenants WHERE id = $1", tenantID)
}
