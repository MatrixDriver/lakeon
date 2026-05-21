package commands

import (
	"fmt"
	"os"
	"strings"
	"text/tabwriter"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/spf13/cobra"
)

func NewListTenantsCmd(credsPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "list-tenants",
		Short: "List all tenants by scanning OBS manifests (does not require RDS)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			client, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer client.Close()

			keys, err := client.ListKeys("tenants/")
			if err != nil {
				return fmt.Errorf("list OBS: %w", err)
			}

			tw := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
			fmt.Fprintln(tw, "TENANT_ID\tOWNER_EMAIL\tDBS\tCREATED_AT")

			count := 0
			skipped := 0
			for _, k := range keys {
				if !strings.HasSuffix(k, "/_manifest.json") {
					continue
				}
				var m obs.TenantManifest
				if err := client.GetJSON(k, &m); err != nil {
					fmt.Fprintf(os.Stderr, "  ! skip %s: %v\n", k, err)
					skipped++
					continue
				}
				fmt.Fprintf(tw, "%s\t%s\t%d\t%s\n",
					m.TenantID, m.OwnerEmail, len(m.Databases),
					m.CreatedAt.Format("2006-01-02"))
				count++
			}
			tw.Flush()
			fmt.Fprintf(os.Stderr, "\n%d tenants listed", count)
			if skipped > 0 {
				fmt.Fprintf(os.Stderr, ", %d skipped (see errors above)", skipped)
			}
			fmt.Fprintln(os.Stderr)
			return nil
		},
	}
}
