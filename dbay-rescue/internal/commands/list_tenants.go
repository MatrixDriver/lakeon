package commands

import (
	"fmt"

	"github.com/spf13/cobra"
)

func NewListTenantsCmd(credsPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "list-tenants",
		Short: "List all tenants by scanning OBS manifests (does not require RDS)",
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("not yet implemented (Task 23)")
		},
	}
}
