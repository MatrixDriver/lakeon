package main

import (
	"bufio"
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	_ "github.com/lib/pq"
)

const (
	flushInterval  = 2 * time.Second
	batchMaxSize   = 500
	maxBodyBytes   = 10 << 20 // 10 MB
	maxDBConns     = 5
	dbIdleTimeout  = 5 * time.Minute
	retryAttempts  = 3
)

var (
	db      *sql.DB
	bat     *batcher
	logger  = log.New(os.Stdout, "[log-collector] ", log.LstdFlags|log.LUTC)
)

// ---- HTTP handlers -------------------------------------------------------

func handleLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes))
	if err != nil {
		http.Error(w, "read error", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	body = bytes.TrimSpace(body)
	if len(body) == 0 {
		w.WriteHeader(http.StatusAccepted)
		return
	}

	entries := parseBody(body)
	for _, e := range entries {
		bat.add(e)
	}

	w.WriteHeader(http.StatusAccepted)
}

func handleHealthz(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

// parseBody accepts JSON array, single JSON object, or raw text lines.
func parseBody(body []byte) []LogEntry {
	var entries []LogEntry

	// Try JSON array first.
	if bytes.HasPrefix(body, []byte("[")) {
		var arr []json.RawMessage
		if err := json.Unmarshal(body, &arr); err == nil {
			for _, raw := range arr {
				var e LogEntry
				if err2 := json.Unmarshal(raw, &e); err2 == nil {
					entries = append(entries, e)
				} else {
					entries = append(entries, parseRawLog(string(raw), "unknown"))
				}
			}
			return entries
		}
	}

	// Try single JSON object.
	if bytes.HasPrefix(body, []byte("{")) {
		var e LogEntry
		if err := json.Unmarshal(body, &e); err == nil {
			return []LogEntry{e}
		}
	}

	// Fall back to line-by-line plain text.
	scanner := bufio.NewScanner(bytes.NewReader(body))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		// Each line might still be JSON.
		if strings.HasPrefix(line, "{") {
			var e LogEntry
			if err := json.Unmarshal([]byte(line), &e); err == nil {
				entries = append(entries, e)
				continue
			}
		}
		entries = append(entries, parseRawLog(line, "unknown"))
	}
	return entries
}

// ---- DB flush ------------------------------------------------------------

// flushToDB inserts a slice of entries into the logs table.
// It retries up to retryAttempts times with incremental backoff.
func flushToDB(entries []LogEntry) {
	if len(entries) == 0 {
		return
	}

	var lastErr error
	for attempt := 1; attempt <= retryAttempts; attempt++ {
		if err := insertBatch(entries); err != nil {
			lastErr = err
			logger.Printf("WARN flush attempt %d/%d failed: %v", attempt, retryAttempts, err)
			time.Sleep(time.Duration(attempt) * time.Second)
			continue
		}
		return // success
	}
	logger.Printf("ERROR dropping %d log entries after %d failures: %v", len(entries), retryAttempts, lastErr)
}

// insertBatch performs a single bulk INSERT for all entries.
func insertBatch(entries []LogEntry) error {
	if len(entries) == 0 {
		return nil
	}

	// Build: INSERT INTO logs (...) VALUES ($1,$2,...),($N+1,...) ...
	const cols = 11
	placeholders := make([]string, 0, len(entries))
	args := make([]interface{}, 0, len(entries)*cols)

	for i, e := range entries {
		base := i * cols
		placeholders = append(placeholders, fmt.Sprintf(
			"($%d,$%d,$%d,$%d,$%d,$%d,$%d,$%d,$%d,$%d,$%d)",
			base+1, base+2, base+3, base+4, base+5,
			base+6, base+7, base+8, base+9, base+10, base+11,
		))

		var extraBytes []byte
		if e.Extra != nil {
			extraBytes = []byte(*e.Extra)
		}

		args = append(args,
			e.Ts,
			e.Level,
			e.Component,
			nullString(e.RequestID),
			nullString(e.TenantID),
			nullString(e.DbID),
			nullString(e.Logger),
			e.Msg,
			e.DurationMs,
			extraBytes,
			nullString(e.Thread),
		)
	}

	query := `INSERT INTO logs (ts, level, component, request_id, tenant_id, db_id, logger, msg, duration_ms, extra, thread) VALUES ` +
		strings.Join(placeholders, ",")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	_, err := db.ExecContext(ctx, query, args...)
	return err
}

func nullString(s string) interface{} {
	if s == "" {
		return nil
	}
	return s
}

// ---- Main ----------------------------------------------------------------

func main() {
	dsn := os.Getenv("LOG_DB_DSN")
	if dsn == "" {
		logger.Fatal("LOG_DB_DSN env var is required")
	}
	listen := os.Getenv("LOG_LISTEN")
	if listen == "" {
		listen = ":9880"
	}

	// Open DB.
	var err error
	db, err = sql.Open("postgres", dsn)
	if err != nil {
		logger.Fatalf("failed to open DB: %v", err)
	}
	db.SetMaxOpenConns(maxDBConns)
	db.SetMaxIdleConns(maxDBConns)
	db.SetConnMaxIdleTime(dbIdleTimeout)

	// Ping to validate DSN early.
	pingCtx, pingCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer pingCancel()
	if err := db.PingContext(pingCtx); err != nil {
		logger.Fatalf("DB ping failed: %v", err)
	}
	logger.Printf("connected to DB")

	bat = newBatcher(batchMaxSize, flushInterval)

	// Background flush goroutine.
	stopFlush := make(chan struct{})
	go func() {
		ticker := time.NewTicker(flushInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				flushToDB(bat.flush())
			case batch := <-bat.ch:
				flushToDB(batch)
			case <-stopFlush:
				return
			}
		}
	}()

	// HTTP server.
	mux := http.NewServeMux()
	mux.HandleFunc("/logs", handleLogs)
	mux.HandleFunc("/healthz", handleHealthz)

	srv := &http.Server{
		Addr:         listen,
		Handler:      mux,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Graceful shutdown.
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGTERM, syscall.SIGINT)

	go func() {
		logger.Printf("listening on %s", listen)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatalf("server error: %v", err)
		}
	}()

	<-quit
	logger.Println("shutting down...")

	// Stop accepting new requests.
	shutCtx, shutCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutCancel()
	if err := srv.Shutdown(shutCtx); err != nil {
		logger.Printf("server shutdown error: %v", err)
	}

	// Stop flush goroutine and do final flush.
	close(stopFlush)
	flushToDB(bat.flush())

	_ = db.Close()
	logger.Println("shutdown complete")
}
