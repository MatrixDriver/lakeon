package main

import (
	"encoding/json"
	"testing"
	"time"
)

// TestParseLogEntry verifies that a valid JSON log line round-trips through LogEntry.
func TestParseLogEntry(t *testing.T) {
	raw := `{
		"ts": "2024-01-15T12:34:56Z",
		"level": "ERROR",
		"component": "lakeon-api",
		"requestId": "req-abc",
		"tenantId": "tenant-1",
		"dbId": "db-xyz",
		"logger": "com.lakeon.Service",
		"msg": "something failed",
		"durationMs": 42,
		"thread": "pool-1"
	}`

	var entry LogEntry
	if err := json.Unmarshal([]byte(raw), &entry); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}

	if entry.Level != "ERROR" {
		t.Errorf("Level: want ERROR, got %q", entry.Level)
	}
	if entry.Component != "lakeon-api" {
		t.Errorf("Component: want lakeon-api, got %q", entry.Component)
	}
	if entry.RequestID != "req-abc" {
		t.Errorf("RequestID: want req-abc, got %q", entry.RequestID)
	}
	if entry.TenantID != "tenant-1" {
		t.Errorf("TenantID: want tenant-1, got %q", entry.TenantID)
	}
	if entry.DbID != "db-xyz" {
		t.Errorf("DbID: want db-xyz, got %q", entry.DbID)
	}
	if entry.Logger != "com.lakeon.Service" {
		t.Errorf("Logger: want com.lakeon.Service, got %q", entry.Logger)
	}
	if entry.Msg != "something failed" {
		t.Errorf("Msg: want 'something failed', got %q", entry.Msg)
	}
	if entry.DurationMs == nil || *entry.DurationMs != 42 {
		t.Errorf("DurationMs: want 42, got %v", entry.DurationMs)
	}
	if entry.Thread != "pool-1" {
		t.Errorf("Thread: want pool-1, got %q", entry.Thread)
	}
	expectedTs := time.Date(2024, 1, 15, 12, 34, 56, 0, time.UTC)
	if !entry.Ts.Equal(expectedTs) {
		t.Errorf("Ts: want %v, got %v", expectedTs, entry.Ts)
	}
}

// TestParseNonJsonLog verifies that parseRawLog wraps plain text correctly.
func TestParseNonJsonLog(t *testing.T) {
	line := "some plain text log output"
	component := "fluent-bit"

	before := time.Now().UTC().Add(-time.Second)
	entry := parseRawLog(line, component)
	after := time.Now().UTC().Add(time.Second)

	if entry.Msg != line {
		t.Errorf("Msg: want %q, got %q", line, entry.Msg)
	}
	if entry.Component != component {
		t.Errorf("Component: want %q, got %q", component, entry.Component)
	}
	if entry.Level != "INFO" {
		t.Errorf("Level: want INFO, got %q", entry.Level)
	}
	if entry.Ts.Before(before) || entry.Ts.After(after) {
		t.Errorf("Ts %v not in expected range [%v, %v]", entry.Ts, before, after)
	}
}

// TestBatchFlush verifies batcher accumulates entries and flush returns + clears them.
func TestBatchFlush(t *testing.T) {
	b := newBatcher(100, 2*time.Second)

	e1 := LogEntry{Ts: time.Now(), Level: "INFO", Component: "test", Msg: "msg1"}
	e2 := LogEntry{Ts: time.Now(), Level: "WARN", Component: "test", Msg: "msg2"}
	e3 := LogEntry{Ts: time.Now(), Level: "ERROR", Component: "test", Msg: "msg3"}

	b.add(e1)
	b.add(e2)
	b.add(e3)

	// First flush should return all 3 entries.
	entries := b.flush()
	if len(entries) != 3 {
		t.Fatalf("flush: want 3 entries, got %d", len(entries))
	}
	if entries[0].Msg != "msg1" {
		t.Errorf("entry[0].Msg: want msg1, got %q", entries[0].Msg)
	}
	if entries[1].Msg != "msg2" {
		t.Errorf("entry[1].Msg: want msg2, got %q", entries[1].Msg)
	}
	if entries[2].Msg != "msg3" {
		t.Errorf("entry[2].Msg: want msg3, got %q", entries[2].Msg)
	}

	// Second flush on empty batcher should return nil.
	second := b.flush()
	if second != nil {
		t.Errorf("second flush: want nil, got %v", second)
	}
}

// TestBatchAutoFlush verifies add() returns true and drains the buffer when full.
func TestBatchAutoFlush(t *testing.T) {
	b := newBatcher(2, time.Minute)

	e1 := LogEntry{Msg: "a"}
	e2 := LogEntry{Msg: "b"}

	full1 := b.add(e1)
	if full1 {
		t.Error("add first entry of 2: should not be full yet")
	}
	full2 := b.add(e2)
	if !full2 {
		t.Error("add second entry of 2: should be full now")
	}

	// After auto-flush the internal buffer should be empty.
	remaining := b.flush()
	if remaining != nil {
		t.Errorf("after auto-flush, manual flush should return nil, got %v", remaining)
	}
}
