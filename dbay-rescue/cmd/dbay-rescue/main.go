package main

import (
	"fmt"
	"os"

	"github.com/dbay-cloud/dbay-rescue/internal/commands"
	"github.com/spf13/cobra"
)

var version = "dev"

func main() {
	var credsPath string

	root := &cobra.Command{
		Use:     "dbay-rescue",
		Short:   "dbay disaster recovery toolkit for SRE",
		Long:    "dbay-rescue is an air-gapped SRE tool. When lakeon-api or RDS is unavailable, it operates directly on OBS + Pageserver using offline-held SRE credentials.",
		Version: version,
	}
	root.PersistentFlags().StringVar(&credsPath, "creds", "",
		"Path to credentials file (default ~/.dbay/rescue-credentials.yaml)")

	root.AddCommand(
		commands.NewListTenantsCmd(&credsPath),
		commands.NewOwnerLookupCmd(&credsPath),
		commands.NewPitrCmd(&credsPath),
		commands.NewRebuildMetadataCmd(&credsPath),
		commands.NewEmergencyMountCmd(&credsPath),
	)

	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "Error:", err)
		os.Exit(1)
	}
}
