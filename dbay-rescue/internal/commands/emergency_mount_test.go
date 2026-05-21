package commands

import (
	"testing"
)

func TestNewEmergencyMountCmd_RegistersCorrectly(t *testing.T) {
	cmd := NewEmergencyMountCmd(new(string))
	if cmd.Use != "emergency-mount" {
		t.Errorf("Use: %s", cmd.Use)
	}
	for _, f := range []string{"tenant", "owner", "namespace", "writable", "hours"} {
		if cmd.Flags().Lookup(f) == nil {
			t.Errorf("--%s missing", f)
		}
	}
}

func TestRandAlnum_Length(t *testing.T) {
	for _, n := range []int{8, 16, 32} {
		if got := randAlnum(n); len(got) != n {
			t.Errorf("randAlnum(%d) = %d", n, len(got))
		}
	}
}

func TestTruncate(t *testing.T) {
	if got := truncate("abcdef", 3); got != "abc" {
		t.Errorf("truncate long: %s", got)
	}
	if got := truncate("ab", 5); got != "ab" {
		t.Errorf("truncate short: %s", got)
	}
}
