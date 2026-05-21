package commands

import (
	"fmt"

	"github.com/spf13/cobra"
)

func NewOwnerLookupCmd(credsPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "owner-lookup",
		Short: "Look up tenants owned by an email (queries OBS owners index)",
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("not yet implemented (Task 24)")
		},
	}
}
