package commands

import (
	"fmt"

	"github.com/spf13/cobra"
)

func NewPitrCmd(credsPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "pitr",
		Short: "Bypass lakeon-api and PITR directly via Pageserver",
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("not yet implemented (Task 25)")
		},
	}
}
