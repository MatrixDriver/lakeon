package commands

import (
	"strings"
	"testing"
)

func TestNewListTenantsCmd_RegistersCorrectly(t *testing.T) {
	cmd := NewListTenantsCmd(new(string))
	if cmd.Use != "list-tenants" {
		t.Errorf("Use: %s", cmd.Use)
	}
	if !strings.Contains(cmd.Short, "tenant") {
		t.Errorf("Short: %s", cmd.Short)
	}
	if cmd.RunE == nil {
		t.Error("RunE is nil")
	}
}
