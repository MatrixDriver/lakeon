package main

import (
	"encoding/json"
	"sync"
	"time"
)

// LogEntry represents a single structured log record.
type LogEntry struct {
	Ts         time.Time        `json:"ts"`
	Level      string           `json:"level"`
	Component  string           `json:"component"`
	RequestID  string           `json:"requestId,omitempty"`
	TenantID   string           `json:"tenantId,omitempty"`
	DbID       string           `json:"dbId,omitempty"`
	Logger     string           `json:"logger,omitempty"`
	Msg        string           `json:"msg"`
	DurationMs *int             `json:"durationMs,omitempty"`
	Extra      *json.RawMessage `json:"extra,omitempty"`
	Thread     string           `json:"thread,omitempty"`
}

// parseRawLog wraps a plain (non-JSON) text line into a LogEntry.
func parseRawLog(line, component string) LogEntry {
	return LogEntry{
		Ts:        time.Now().UTC(),
		Level:     "INFO",
		Component: component,
		Msg:       line,
	}
}

// batcher accumulates LogEntry values and flushes them in batches.
type batcher struct {
	mu       sync.Mutex
	entries  []LogEntry
	maxSize  int
	interval time.Duration
	ch       chan []LogEntry
}

// newBatcher creates a batcher that auto-flushes when full (maxSize) or on interval.
// Callers that want timer-driven flushing should call startTimer externally (main.go).
func newBatcher(maxSize int, interval time.Duration) *batcher {
	return &batcher{
		entries:  make([]LogEntry, 0, maxSize),
		maxSize:  maxSize,
		interval: interval,
		ch:       make(chan []LogEntry, 16),
	}
}

// add appends an entry. Returns true when the batch is full and was auto-flushed.
func (b *batcher) add(entry LogEntry) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.entries = append(b.entries, entry)
	if len(b.entries) >= b.maxSize {
		batch := b.entries
		b.entries = make([]LogEntry, 0, b.maxSize)
		select {
		case b.ch <- batch:
		default:
		}
		return true
	}
	return false
}

// flush drains the current buffer and returns its contents. Thread-safe.
func (b *batcher) flush() []LogEntry {
	b.mu.Lock()
	defer b.mu.Unlock()
	if len(b.entries) == 0 {
		return nil
	}
	batch := b.entries
	b.entries = make([]LogEntry, 0, b.maxSize)
	return batch
}
