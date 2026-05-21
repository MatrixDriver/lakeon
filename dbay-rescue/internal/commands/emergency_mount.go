package commands

import (
	"fmt"

	"github.com/spf13/cobra"
)

func NewEmergencyMountCmd(credsPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "emergency-mount",
		Short: "Start temporary compute pod for a tenant when RDS is down",
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("not yet implemented (Task 27)")
		},
	}
}
