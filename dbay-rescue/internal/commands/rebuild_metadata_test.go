package commands

import "testing"

// DB-integration tests for rebuild-metadata live in Task 28 (smoke). This
// test only verifies the cobra command wiring so a typo in flag definitions
// fails the unit suite.
func TestNewRebuildMetadataCmd_RegistersCorrectly(t *testing.T) {
	cmd := NewRebuildMetadataCmd(new(string))
	if cmd.Use != "rebuild-metadata" {
		t.Errorf("Use: %s", cmd.Use)
	}
	if cmd.Flags().Lookup("to") == nil {
		t.Error("--to flag missing")
	}
}
