package commands

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/spf13/cobra"
)

func NewOwnerLookupCmd(credsPath *string) *cobra.Command {
	var email string
	cmd := &cobra.Command{
		Use:   "owner-lookup",
		Short: "Look up tenants owned by an email (queries OBS owners index)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if email == "" {
				return fmt.Errorf("--email is required")
			}
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			client, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer client.Close()

			h := sha256.Sum256([]byte(email))
			shard := hex.EncodeToString(h[:1])
			key := fmt.Sprintf("_global/owners/%s.idx", shard)

			var idx obs.OwnersIndex
			if err := client.GetJSON(key, &idx); err != nil {
				if obs.IsNotFound(err) {
					return fmt.Errorf("no owners index for shard %s (email %s has no tenants registered yet)", shard, email)
				}
				return fmt.Errorf("read owners index shard %s: %w", shard, err)
			}

			tenants, ok := idx.Owners[email]
			if !ok || len(tenants) == 0 {
				fmt.Printf("No tenants found for %s\n", email)
				return nil
			}
			fmt.Printf("Tenants owned by %s:\n", email)
			for _, tid := range tenants {
				var m obs.TenantManifest
				mkey := fmt.Sprintf("tenants/%s/_manifest.json", tid)
				if err := client.GetJSON(mkey, &m); err != nil {
					fmt.Printf("  %s  (manifest unavailable: %v)\n", tid, err)
					continue
				}
				fmt.Printf("  %s  created=%s  dbs=%d\n",
					tid, m.CreatedAt.Format("2006-01-02"), len(m.Databases))
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&email, "email", "e", "", "User email (required)")
	return cmd
}
