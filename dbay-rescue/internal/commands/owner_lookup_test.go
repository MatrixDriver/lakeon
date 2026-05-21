package commands

import (
	"testing"
)

func TestNewOwnerLookupCmd_RegistersCorrectly(t *testing.T) {
	cmd := NewOwnerLookupCmd(new(string))
	if cmd.Use != "owner-lookup" {
		t.Errorf("Use: %s", cmd.Use)
	}
	if cmd.Flags().Lookup("email") == nil {
		t.Error("--email flag missing")
	}
	if cmd.RunE == nil {
		t.Error("RunE nil")
	}
}
