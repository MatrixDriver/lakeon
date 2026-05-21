package commands

import (
	"strings"
	"testing"
	"time"
)

func TestNewPitrCmd_RegistersCorrectly(t *testing.T) {
	cmd := NewPitrCmd(new(string))
	if cmd.Use != "pitr" {
		t.Errorf("Use: %s", cmd.Use)
	}
	for _, flag := range []string{"db", "time", "tenant"} {
		if cmd.Flags().Lookup(flag) == nil {
			t.Errorf("--%s flag missing", flag)
		}
	}
}

func TestParseTime_ISO(t *testing.T) {
	tm, err := parseTime("2026-05-21T14:30:00Z")
	if err != nil {
		t.Fatal(err)
	}
	if tm.UTC().Format("2006-01-02 15:04:05") != "2026-05-21 14:30:00" {
		t.Errorf("got %v", tm)
	}
}

func TestParseTime_Relative(t *testing.T) {
	cases := []string{"5min ago", "1h ago", "2 days ago", "10sec ago"}
	for _, c := range cases {
		tm, err := parseTime(c)
		if err != nil {
			t.Errorf("%s: %v", c, err)
			continue
		}
		if !tm.Before(time.Now()) {
			t.Errorf("%s should be in past", c)
		}
	}
}

func TestParseTime_Invalid(t *testing.T) {
	if _, err := parseTime("not a time"); err == nil {
		t.Error("expected error")
	}
}

func TestRandHex_Length(t *testing.T) {
	s := randHex(16)
	if len(s) != 16 {
		t.Errorf("len: %d", len(s))
	}
	if !strings.ContainsAny(s, "0123456789abcdef") {
		t.Errorf("not hex: %s", s)
	}
}
