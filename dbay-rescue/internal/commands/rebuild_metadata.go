package commands

import (
	"fmt"

	"github.com/spf13/cobra"
)

func NewRebuildMetadataCmd(credsPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "rebuild-metadata",
		Short: "Rebuild RDS metadata from OBS manifests",
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("not yet implemented (Task 26)")
		},
	}
}
