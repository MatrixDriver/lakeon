package commands

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/neon"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/spf13/cobra"
)

// NewPitrCmd builds the `dbay-rescue pitr` subcommand which performs a
// point-in-time recovery directly against the Pageserver mgmt API, bypassing
// lakeon-api. It reads the per-tenant manifest from OBS to recover the
// (neon_tenant_id, ancestor_timeline_id) pair for the target database, calls
// get_lsn_by_timestamp to map a wall-clock time to an LSN, then creates a new
// branch off of that LSN. The user then runs `emergency-mount` to obtain a
// connection string against the new timeline.
func NewPitrCmd(credsPath *string) *cobra.Command {
	var dbID, timeArg, tenantHint string
	cmd := &cobra.Command{
		Use:   "pitr",
		Short: "Bypass lakeon-api and PITR directly via Pageserver",
		RunE: func(cmd *cobra.Command, args []string) error {
			if dbID == "" || timeArg == "" {
				return fmt.Errorf("--db and --time required")
			}
			ts, err := parseTime(timeArg)
			if err != nil {
				return err
			}
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			obsCli, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer obsCli.Close()

			neonTenantID, timelineID, err := locateTimeline(obsCli, dbID, tenantHint)
			if err != nil {
				return err
			}
			fmt.Printf("Located: neon_tenant=%s timeline=%s\n", neonTenantID, timelineID)

			ps := neon.New(c.Pageserver.MgmtEndpoint, c.Pageserver.Token)
			lsn, err := ps.GetLsnByTimestamp(neonTenantID, timelineID, ts)
			if err != nil {
				return fmt.Errorf("get_lsn_by_timestamp: %w", err)
			}
			fmt.Printf("LSN at %s: %s\n", ts.Format(time.RFC3339), lsn)

			newTl := "tl_" + randHex(16)
			resp, err := ps.CreateBranch(neonTenantID, neon.CreateBranchReq{
				AncestorTimelineID: timelineID,
				AncestorStartLSN:   lsn,
				NewTimelineID:      newTl,
			})
			if err != nil {
				return fmt.Errorf("create_branch: %w", err)
			}
			fmt.Printf("\nNew timeline created: %s\n", resp.TimelineID)
			fmt.Printf("  Ancestor: %s @ %s\n", timelineID, lsn)
			fmt.Printf("\nNext step: run emergency-mount on this tenant to get a connection string.\n")
			return nil
		},
	}
	cmd.Flags().StringVarP(&dbID, "db", "d", "", "Database ID (required)")
	cmd.Flags().StringVarP(&timeArg, "time", "t", "", "Target time (ISO 8601 or '5min ago')")
	cmd.Flags().StringVar(&tenantHint, "tenant", "", "Lakeon tenant ID (optional, speeds up lookup)")
	return cmd
}

// parseTime accepts either an RFC3339 ISO 8601 timestamp or a relative
// expression of the form "Ns/Nmin/Nh/Nd ago" (whitespace tolerant).
func parseTime(s string) (time.Time, error) {
	s = strings.TrimSpace(s)
	re := regexp.MustCompile(`^(\d+)\s*(s|sec|seconds?|min|minutes?|h|hours?|d|days?)\s*ago$`)
	if m := re.FindStringSubmatch(strings.ToLower(s)); m != nil {
		n, _ := strconv.Atoi(m[1])
		var d time.Duration
		switch {
		case strings.HasPrefix(m[2], "min"):
			d = time.Duration(n) * time.Minute
		case strings.HasPrefix(m[2], "s"):
			d = time.Duration(n) * time.Second
		case strings.HasPrefix(m[2], "h"):
			d = time.Duration(n) * time.Hour
		case strings.HasPrefix(m[2], "d"):
			d = time.Duration(n) * 24 * time.Hour
		}
		return time.Now().UTC().Add(-d), nil
	}
	return time.Parse(time.RFC3339, s)
}

// locateTimeline finds the (neon_tenant_id, timeline_id) for `dbID` by
// reading the per-tenant manifest. If `lakeonTenantHint` is non-empty we
// read just that manifest; otherwise we scan tenants/*/_manifest.json.
func locateTimeline(c *obs.Client, dbID, lakeonTenantHint string) (string, string, error) {
	if lakeonTenantHint != "" {
		var m obs.TenantManifest
		key := fmt.Sprintf("tenants/%s/_manifest.json", lakeonTenantHint)
		if err := c.GetJSON(key, &m); err != nil {
			return "", "", fmt.Errorf("read manifest for %s: %w", lakeonTenantHint, err)
		}
		for _, db := range m.Databases {
			if db.DBID == dbID {
				if db.NeonTenantID == "" {
					return "", "", fmt.Errorf("database %s in tenant %s has no neon_tenant_id in manifest (regenerate manifest first)", dbID, lakeonTenantHint)
				}
				return db.NeonTenantID, db.TimelineID, nil
			}
		}
		return "", "", fmt.Errorf("db %s not found in tenant %s", dbID, lakeonTenantHint)
	}
	// Fallback: scan all manifests.
	keys, err := c.ListKeys("tenants/")
	if err != nil {
		return "", "", err
	}
	for _, k := range keys {
		if !strings.HasSuffix(k, "/_manifest.json") {
			continue
		}
		var m obs.TenantManifest
		if err := c.GetJSON(k, &m); err != nil {
			continue
		}
		for _, db := range m.Databases {
			if db.DBID == dbID {
				if db.NeonTenantID == "" {
					return "", "", fmt.Errorf("database %s has no neon_tenant_id (regenerate manifest)", dbID)
				}
				return db.NeonTenantID, db.TimelineID, nil
			}
		}
	}
	return "", "", fmt.Errorf("db %s not found in any tenant manifest", dbID)
}

// randHex returns a lowercase hex string of length n (must be even).
func randHex(n int) string {
	b := make([]byte, n/2)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
