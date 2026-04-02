# Log Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a structured logging system that enables CC and SRE AI assistants to automatically diagnose production issues via requestId-traced logs stored in a dbay Serverless PG database.

**Architecture:** Components output JSON logs to stdout (with requestId/tenantId via MDC). Fluent Bit DaemonSet on CCE nodes tails container logs and forwards via HTTP to a Go log-collector service. CCI Job Pods push logs directly to log-collector via a shared Python module. log-collector batches INSERTs into a dedicated dbay-logs database. CC accesses logs through dbay-sre-mcp (4 SQL-based tools), SRE console provides 4 visual pages.

**Tech Stack:** Go (log-collector), Java/logback (structured logging), Python/FastMCP (MCP + log shipper), Fluent Bit (DaemonSet), Vue 3 (SRE pages), PostgreSQL (dbay log storage)

**Spec:** `docs/superpowers/specs/2026-04-01-log-observability-design.md`

---

## Phase 1: Log Infrastructure

### Task 1: Create dbay-logs database and schema

**Files:**
- Create: `deploy/cce/init-log-db.sh`

- [ ] **Step 1: Create the log database via dbay CLI**

```bash
# Create a dedicated database for logs (1cu, 30min suspend timeout)
dbay db create --name dbay-logs --compute-size 1cu --suspend-timeout 30m
```

Save the returned connection_uri and password.

- [ ] **Step 2: Create the logs table and indexes**

Connect to dbay-logs and run:

```sql
CREATE TABLE logs (
    id          BIGSERIAL PRIMARY KEY,
    ts          TIMESTAMPTZ NOT NULL,
    level       VARCHAR(8) NOT NULL,
    component   VARCHAR(32) NOT NULL,
    request_id  VARCHAR(32),
    tenant_id   VARCHAR(64),
    db_id       VARCHAR(64),
    logger      VARCHAR(128),
    msg         TEXT NOT NULL,
    duration_ms INTEGER,
    extra       JSONB,
    thread      VARCHAR(64)
);

CREATE INDEX idx_logs_ts ON logs (ts DESC);
CREATE INDEX idx_logs_request_id ON logs (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX idx_logs_tenant_ts ON logs (tenant_id, ts DESC) WHERE tenant_id IS NOT NULL;
CREATE INDEX idx_logs_level_ts ON logs (level, ts DESC);
CREATE INDEX idx_logs_component_ts ON logs (component, ts DESC);
CREATE INDEX idx_logs_msg_tsvector ON logs USING GIN (to_tsvector('simple', msg));
```

- [ ] **Step 3: Create the init script**

Write `deploy/cce/init-log-db.sh` that wraps the above SQL for repeatable setup:

```bash
#!/usr/bin/env bash
set -euo pipefail

LOG_DB_URI="${LOG_DB_URI:?Set LOG_DB_URI to dbay-logs connection string}"

psql "$LOG_DB_URI" <<'SQL'
CREATE TABLE IF NOT EXISTS logs (
    id          BIGSERIAL PRIMARY KEY,
    ts          TIMESTAMPTZ NOT NULL,
    level       VARCHAR(8) NOT NULL,
    component   VARCHAR(32) NOT NULL,
    request_id  VARCHAR(32),
    tenant_id   VARCHAR(64),
    db_id       VARCHAR(64),
    logger      VARCHAR(128),
    msg         TEXT NOT NULL,
    duration_ms INTEGER,
    extra       JSONB,
    thread      VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_logs_ts ON logs (ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_request_id ON logs (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_logs_tenant_ts ON logs (tenant_id, ts DESC) WHERE tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_logs_level_ts ON logs (level, ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_component_ts ON logs (component, ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_msg_tsvector ON logs USING GIN (to_tsvector('simple', msg));
SQL

echo "✓ dbay-logs schema initialized"
```

- [ ] **Step 4: Verify**

```bash
psql "$LOG_DB_URI" -c "\dt logs" -c "\di"
```

Expected: logs table + 6 indexes listed.

- [ ] **Step 5: Commit**

```bash
git add deploy/cce/init-log-db.sh
git commit -m "feat(log): add dbay-logs database init script"
```

---

### Task 2: Build log-collector Go service

**Files:**
- Create: `log-collector/go.mod`
- Create: `log-collector/main.go`
- Create: `log-collector/collector.go`
- Create: `log-collector/collector_test.go`
- Create: `log-collector/Dockerfile`

- [ ] **Step 1: Initialize Go module**

```bash
mkdir -p log-collector
cd log-collector
go mod init github.com/lakeon/log-collector
go get github.com/lib/pq
```

- [ ] **Step 2: Write collector_test.go**

```go
package main

import (
	"encoding/json"
	"testing"
	"time"
)

func TestParseLogEntry(t *testing.T) {
	raw := `{"ts":"2026-04-01T10:00:00.000Z","level":"INFO","component":"lakeon-api","requestId":"req_abc123","tenantId":"tn_xyz","msg":"test message","durationMs":42}`
	var entry LogEntry
	if err := json.Unmarshal([]byte(raw), &entry); err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if entry.RequestID != "req_abc123" {
		t.Errorf("requestId = %q, want req_abc123", entry.RequestID)
	}
	if entry.Level != "INFO" {
		t.Errorf("level = %q, want INFO", entry.Level)
	}
	if entry.DurationMs == nil || *entry.DurationMs != 42 {
		t.Errorf("durationMs = %v, want 42", entry.DurationMs)
	}
}

func TestParseNonJsonLog(t *testing.T) {
	raw := "2026-04-01 10:00:00.000 INFO pageserver raw text log line"
	entry := parseRawLog(raw, "pageserver")
	if entry.Component != "pageserver" {
		t.Errorf("component = %q, want pageserver", entry.Component)
	}
	if entry.Msg != raw {
		t.Errorf("msg not preserved")
	}
}

func TestBatchFlush(t *testing.T) {
	b := newBatcher(500, 2*time.Second)
	for i := 0; i < 10; i++ {
		b.add(LogEntry{Ts: time.Now(), Level: "INFO", Component: "test", Msg: "msg"})
	}
	batch := b.flush()
	if len(batch) != 10 {
		t.Errorf("batch len = %d, want 10", len(batch))
	}
	if len(b.flush()) != 0 {
		t.Error("expected empty after flush")
	}
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd log-collector && go test -v ./...
```

Expected: FAIL — types not defined.

- [ ] **Step 4: Write collector.go**

```go
package main

import (
	"encoding/json"
	"sync"
	"time"
)

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

func parseRawLog(line string, component string) LogEntry {
	return LogEntry{
		Ts:        time.Now(),
		Level:     "INFO",
		Component: component,
		Msg:       line,
	}
}

type batcher struct {
	mu       sync.Mutex
	entries  []LogEntry
	maxSize  int
	interval time.Duration
}

func newBatcher(maxSize int, interval time.Duration) *batcher {
	return &batcher{maxSize: maxSize, interval: interval}
}

func (b *batcher) add(e LogEntry) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.entries = append(b.entries, e)
	return len(b.entries) >= b.maxSize
}

func (b *batcher) flush() []LogEntry {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := b.entries
	b.entries = nil
	return out
}
```

- [ ] **Step 5: Run tests**

```bash
cd log-collector && go test -v ./...
```

Expected: PASS (3 tests).

- [ ] **Step 6: Write main.go**

```go
package main

import (
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

var (
	db      *sql.DB
	batch   *batcher
	dbDSN   string
	listen  string
)

func init() {
	dbDSN = os.Getenv("LOG_DB_DSN")
	if dbDSN == "" {
		log.Fatal("LOG_DB_DSN is required")
	}
	listen = os.Getenv("LOG_LISTEN")
	if listen == "" {
		listen = ":9880"
	}
}

func main() {
	var err error
	db, err = sql.Open("postgres", dbDSN)
	if err != nil {
		log.Fatalf("db open: %v", err)
	}
	db.SetMaxOpenConns(5)
	db.SetMaxIdleConns(2)
	db.SetConnMaxIdleTime(5 * time.Minute)

	batch = newBatcher(500, 2*time.Second)

	// Background flush loop
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go flushLoop(ctx)

	mux := http.NewServeMux()
	mux.HandleFunc("/logs", handleLogs)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	})

	srv := &http.Server{Addr: listen, Handler: mux}

	// Graceful shutdown
	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT)
		<-sigCh
		log.Println("shutting down...")
		cancel()
		flushToDB(batch.flush())
		srv.Shutdown(context.Background())
	}()

	log.Printf("log-collector listening on %s", listen)
	if err := srv.ListenAndServe(); err != http.ErrServerClosed {
		log.Fatalf("server: %v", err)
	}
}

func handleLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 10<<20)) // 10MB limit
	if err != nil {
		http.Error(w, "read error", http.StatusBadRequest)
		return
	}

	// Try array of entries first, then single entry
	var entries []LogEntry
	if err := json.Unmarshal(body, &entries); err != nil {
		var single LogEntry
		if err2 := json.Unmarshal(body, &single); err2 != nil {
			// Treat as raw text (Fluent Bit forward)
			component := r.URL.Query().Get("component")
			if component == "" {
				component = "unknown"
			}
			for _, line := range strings.Split(string(body), "\n") {
				line = strings.TrimSpace(line)
				if line == "" {
					continue
				}
				entries = append(entries, parseRawLog(line, component))
			}
		} else {
			entries = []LogEntry{single}
		}
	}

	for _, e := range entries {
		if full := batch.add(e); full {
			go flushToDB(batch.flush())
		}
	}
	w.WriteHeader(http.StatusAccepted)
}

func flushLoop(ctx context.Context) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if entries := batch.flush(); len(entries) > 0 {
				flushToDB(entries)
			}
		}
	}
}

func flushToDB(entries []LogEntry) {
	if len(entries) == 0 {
		return
	}
	// Build batch INSERT
	var b strings.Builder
	b.WriteString("INSERT INTO logs (ts, level, component, request_id, tenant_id, db_id, logger, msg, duration_ms, extra, thread) VALUES ")
	args := make([]interface{}, 0, len(entries)*11)
	for i, e := range entries {
		if i > 0 {
			b.WriteString(",")
		}
		offset := i * 11
		b.WriteString(fmt.Sprintf("($%d,$%d,$%d,$%d,$%d,$%d,$%d,$%d,$%d,$%d,$%d)",
			offset+1, offset+2, offset+3, offset+4, offset+5, offset+6,
			offset+7, offset+8, offset+9, offset+10, offset+11))
		var extraJSON *string
		if e.Extra != nil {
			s := string(*e.Extra)
			extraJSON = &s
		}
		args = append(args, e.Ts, e.Level, e.Component, nilIfEmpty(e.RequestID),
			nilIfEmpty(e.TenantID), nilIfEmpty(e.DbID), nilIfEmpty(e.Logger),
			e.Msg, e.DurationMs, extraJSON, nilIfEmpty(e.Thread))
	}

	for attempt := 0; attempt < 3; attempt++ {
		_, err := db.Exec(b.String(), args...)
		if err == nil {
			log.Printf("flushed %d log entries", len(entries))
			return
		}
		log.Printf("flush attempt %d failed: %v", attempt+1, err)
		time.Sleep(time.Duration(attempt+1) * time.Second) // 1s, 2s, 3s backoff
	}
	log.Printf("DROPPED %d log entries after 3 retries", len(entries))
}

func nilIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
```

- [ ] **Step 7: Write Dockerfile**

```dockerfile
FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -o log-collector .

FROM scratch
COPY --from=builder /app/log-collector /log-collector
ENTRYPOINT ["/log-collector"]
```

- [ ] **Step 8: Build and verify**

```bash
cd log-collector
go build -o log-collector .
echo '{"ts":"2026-04-01T10:00:00Z","level":"INFO","component":"test","msg":"hello"}' | \
  curl -s -X POST -d @- http://localhost:9880/logs -w "%{http_code}"
```

Expected: `202` (Accepted). The flush to DB will fail without a real DB, but the HTTP endpoint works.

- [ ] **Step 9: Commit**

```bash
git add log-collector/
git commit -m "feat(log): add Go log-collector service (HTTP receive + PG batch write)"
```

---

### Task 3: Fluent Bit DaemonSet Helm chart

**Files:**
- Create: `deploy/helm/lakeon/templates/daemonset-fluentbit.yaml`
- Create: `deploy/helm/lakeon/templates/configmap-fluentbit.yaml`
- Modify: `deploy/helm/lakeon/values.yaml`

- [ ] **Step 1: Add Fluent Bit values**

Append to `deploy/helm/lakeon/values.yaml`:

```yaml
fluentbit:
  enabled: true
  image: "cr.fluentbit.io/fluent/fluent-bit:3.2"
  resources:
    requests:
      cpu: "50m"
      memory: "64Mi"
    limits:
      cpu: "200m"
      memory: "128Mi"
```

- [ ] **Step 2: Create Fluent Bit ConfigMap**

Write `deploy/helm/lakeon/templates/configmap-fluentbit.yaml`:

```yaml
{{- if .Values.fluentbit.enabled }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluentbit-config
  namespace: {{ .Values.global.namespace }}
  labels:
    {{- include "lakeon.labels" . | nindent 4 }}
data:
  fluent-bit.conf: |
    [SERVICE]
        Flush        2
        Log_Level    info
        Parsers_File parsers.conf

    [INPUT]
        Name              tail
        Path              /var/log/containers/*_{{ .Values.global.namespace }}_*.log
        Parser            docker
        Tag               kube.*
        Refresh_Interval  5
        Mem_Buf_Limit     5MB
        Skip_Long_Lines   On

    [INPUT]
        Name              tail
        Path              /var/log/containers/*_lakeon-compute_*.log
        Parser            docker
        Tag               kube.*
        Refresh_Interval  5
        Mem_Buf_Limit     5MB
        Skip_Long_Lines   On

    [OUTPUT]
        Name              http
        Match             *
        Host              log-collector.{{ .Values.global.namespace }}.svc.cluster.local
        Port              9880
        URI               /logs
        Format            json
        Json_date_key     false
        Retry_Limit       3

  parsers.conf: |
    [PARSER]
        Name        docker
        Format      json
        Time_Key    time
        Time_Format %Y-%m-%dT%H:%M:%S.%LZ
{{- end }}
```

- [ ] **Step 3: Create Fluent Bit DaemonSet**

Write `deploy/helm/lakeon/templates/daemonset-fluentbit.yaml`:

```yaml
{{- if .Values.fluentbit.enabled }}
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: lakeon-fluentbit
  namespace: {{ .Values.global.namespace }}
  labels:
    {{- include "lakeon.labels" . | nindent 4 }}
    app.kubernetes.io/component: fluentbit
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: lakeon-fluentbit
  template:
    metadata:
      labels:
        app.kubernetes.io/name: lakeon-fluentbit
    spec:
      {{- with .Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      tolerations:
        - key: "lakeon/role"
          operator: "Exists"
          effect: "NoSchedule"
      containers:
        - name: fluentbit
          image: {{ .Values.fluentbit.image }}
          volumeMounts:
            - name: varlog
              mountPath: /var/log
              readOnly: true
            - name: config
              mountPath: /fluent-bit/etc
          resources:
            {{- toYaml .Values.fluentbit.resources | nindent 12 }}
      volumes:
        - name: varlog
          hostPath:
            path: /var/log
        - name: config
          configMap:
            name: fluentbit-config
{{- end }}
```

- [ ] **Step 4: Create log-collector Deployment template**

Write `deploy/helm/lakeon/templates/deployment-log-collector.yaml`:

```yaml
{{- if .Values.fluentbit.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: log-collector
  namespace: {{ .Values.global.namespace }}
  labels:
    {{- include "lakeon.labels" . | nindent 4 }}
    app.kubernetes.io/component: log-collector
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: log-collector
  template:
    metadata:
      labels:
        app.kubernetes.io/name: log-collector
    spec:
      {{- with .Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: log-collector
          image: {{ .Values.logCollector.image.repository }}:{{ .Values.logCollector.image.tag }}
          ports:
            - containerPort: 9880
          env:
            - name: LOG_DB_DSN
              valueFrom:
                secretKeyRef:
                  name: log-collector-secret
                  key: dsn
          resources:
            requests:
              cpu: "50m"
              memory: "64Mi"
            limits:
              cpu: "500m"
              memory: "256Mi"
          livenessProbe:
            httpGet:
              path: /healthz
              port: 9880
            initialDelaySeconds: 5
            periodSeconds: 30
          readinessProbe:
            httpGet:
              path: /healthz
              port: 9880
            initialDelaySeconds: 3
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: log-collector
  namespace: {{ .Values.global.namespace }}
spec:
  selector:
    app.kubernetes.io/name: log-collector
  ports:
    - port: 9880
      targetPort: 9880
{{- end }}
```

- [ ] **Step 5: Add log-collector values**

Append to `deploy/helm/lakeon/values.yaml`:

```yaml
logCollector:
  image:
    repository: swr.cn-north-4.myhuaweicloud.com/flex/log-collector
    tag: "0.1.0"
```

- [ ] **Step 6: Commit**

```bash
git add deploy/helm/lakeon/templates/daemonset-fluentbit.yaml \
        deploy/helm/lakeon/templates/configmap-fluentbit.yaml \
        deploy/helm/lakeon/templates/deployment-log-collector.yaml \
        deploy/helm/lakeon/values.yaml
git commit -m "feat(log): add Fluent Bit DaemonSet + log-collector Deployment Helm templates"
```

---

## Phase 2: Log Production

### Task 4: lakeon-api structured JSON logging + RequestContextFilter

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/config/RequestContextFilter.java`
- Create: `lakeon-api/src/main/resources/logback-spring.xml`
- Modify: `lakeon-api/pom.xml`

- [ ] **Step 1: Add logstash-logback-encoder dependency**

Add to `lakeon-api/pom.xml` in `<dependencies>`:

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

- [ ] **Step 2: Create RequestContextFilter**

Write `lakeon-api/src/main/java/com/lakeon/config/RequestContextFilter.java`:

```java
package com.lakeon.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * Injects requestId and tenantId into SLF4J MDC for structured logging.
 * Runs after RateLimitFilter (Order 0) and before ApiKeyFilter (Order 1).
 */
@Component
@Order(-1)
public class RequestContextFilter implements Filter {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String requestId = "req_" + String.format("%08x", RANDOM.nextInt());
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(req, res);
        } finally {
            // Pick up tenantId set by ApiKeyFilter (if available)
            Object tenant = request.getAttribute("tenant");
            if (tenant != null) {
                // tenantId was set during request processing; already in MDC if ApiKeyFilter set it
            }
            MDC.clear();
        }
    }
}
```

- [ ] **Step 3: Add MDC tenantId in ApiKeyFilter**

In `lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java`, after `request.setAttribute("tenant", tenant)` (around line 170), add:

```java
org.slf4j.MDC.put("tenantId", tenant.getId());
```

- [ ] **Step 4: Create logback-spring.xml**

Write `lakeon-api/src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <fieldNames>
                <timestamp>ts</timestamp>
                <message>msg</message>
                <levelValue>[ignore]</levelValue>
                <logger>logger</logger>
                <thread>thread</thread>
                <version>[ignore]</version>
            </fieldNames>
            <customFields>{"component":"lakeon-api"}</customFields>
            <includeMdcKeyName>requestId</includeMdcKeyName>
            <includeMdcKeyName>tenantId</includeMdcKeyName>
            <includeMdcKeyName>dbId</includeMdcKeyName>
            <shortenedLoggerNameLength>36</shortenedLoggerNameLength>
            <timestampPattern>yyyy-MM-dd'T'HH:mm:ss.SSS'Z'</timestampPattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

    <!-- Reduce noisy loggers -->
    <logger name="org.apache.catalina" level="WARN"/>
    <logger name="org.springframework.web" level="WARN"/>
    <logger name="io.fabric8.kubernetes" level="WARN"/>
</configuration>
```

- [ ] **Step 5: Test locally**

```bash
cd lakeon-api && mvn spring-boot:run
```

Trigger any API call and check stdout — should see JSON output like:

```json
{"ts":"2026-04-01T10:00:00.000Z","level":"INFO","logger":"c.l.s.DatabaseService","msg":"...","component":"lakeon-api","requestId":"req_a1b2c3d4","thread":"http-nio-8090-exec-1"}
```

- [ ] **Step 6: Run existing tests**

```bash
cd lakeon-api && mvn test
```

Expected: All existing tests still pass (logback change doesn't affect test behavior).

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/RequestContextFilter.java \
        lakeon-api/src/main/resources/logback-spring.xml \
        lakeon-api/pom.xml \
        lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java
git commit -m "feat(log): structured JSON logging + RequestContextFilter with requestId/tenantId MDC"
```

---

### Task 5: lakeon-log Python module for CCI components

**Files:**
- Create: `log-collector/lakeon-log/pyproject.toml`
- Create: `log-collector/lakeon-log/lakeon_log/__init__.py`
- Create: `log-collector/lakeon-log/lakeon_log/handler.py`
- Create: `log-collector/lakeon-log/tests/test_handler.py`

- [ ] **Step 1: Write test**

Write `log-collector/lakeon-log/tests/test_handler.py`:

```python
import json
import logging
import os
from unittest.mock import patch, MagicMock

import pytest

from lakeon_log import setup_logging, LakeonJsonFormatter, HttpBatchHandler


def test_json_formatter_output():
    fmt = LakeonJsonFormatter(component="test-component", request_id="req_abc", tenant_id="tn_xyz")
    record = logging.LogRecord(
        name="test", level=logging.INFO, pathname="", lineno=0,
        msg="hello world", args=(), exc_info=None,
    )
    line = fmt.format(record)
    data = json.loads(line)
    assert data["component"] == "test-component"
    assert data["requestId"] == "req_abc"
    assert data["tenantId"] == "tn_xyz"
    assert data["level"] == "INFO"
    assert data["msg"] == "hello world"
    assert "ts" in data


def test_json_formatter_with_duration():
    fmt = LakeonJsonFormatter(component="test")
    record = logging.LogRecord(
        name="test", level=logging.INFO, pathname="", lineno=0,
        msg="slow op", args=(), exc_info=None,
    )
    record.duration_ms = 142
    line = fmt.format(record)
    data = json.loads(line)
    assert data["durationMs"] == 142


def test_setup_logging_reads_env():
    with patch.dict(os.environ, {
        "LAKEON_REQUEST_ID": "req_envtest",
        "LAKEON_TENANT_ID": "tn_envtest",
        "LAKEON_LOG_ENDPOINT": "http://localhost:9880/logs",
    }):
        logger = setup_logging(component="knowledge-pipeline")
        assert logger.name == "knowledge-pipeline"
        # Check that JSON formatter is attached
        has_json = any(
            isinstance(getattr(h, 'formatter', None), LakeonJsonFormatter)
            for h in logger.handlers
        )
        assert has_json


def test_http_handler_batches(monkeypatch):
    handler = HttpBatchHandler(endpoint="http://fake:9880/logs", batch_size=3, flush_interval=60)
    sent = []
    monkeypatch.setattr(handler, "_send", lambda entries: sent.append(entries))

    for i in range(3):
        record = logging.LogRecord(
            name="test", level=logging.INFO, pathname="", lineno=0,
            msg=f"msg-{i}", args=(), exc_info=None,
        )
        handler.emit(record)

    assert len(sent) == 1
    assert len(sent[0]) == 3
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd log-collector/lakeon-log && pip install -e . && pytest tests/ -v
```

Expected: FAIL — module not found.

- [ ] **Step 3: Write pyproject.toml**

```toml
[project]
name = "lakeon-log"
version = "0.1.0"
description = "Structured logging for Lakeon CCI components"
requires-python = ">=3.10"
dependencies = ["requests>=2.28"]

[project.optional-dependencies]
test = ["pytest>=7.0"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"
```

- [ ] **Step 4: Write handler.py**

Write `log-collector/lakeon-log/lakeon_log/handler.py`:

```python
"""Lakeon structured logging: JSON formatter + HTTP batch handler."""
import json
import logging
import os
import threading
import time
from datetime import datetime, timezone

import requests


class LakeonJsonFormatter(logging.Formatter):
    """Format log records as JSON matching the dbay-logs schema."""

    def __init__(self, component: str, request_id: str = "", tenant_id: str = ""):
        super().__init__()
        self.component = component
        self.request_id = request_id
        self.tenant_id = tenant_id

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "ts": datetime.fromtimestamp(record.created, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
            "level": record.levelname,
            "component": self.component,
            "logger": record.name,
            "msg": record.getMessage(),
            "thread": record.threadName,
        }
        if self.request_id:
            entry["requestId"] = self.request_id
        if self.tenant_id:
            entry["tenantId"] = self.tenant_id
        if hasattr(record, "duration_ms"):
            entry["durationMs"] = record.duration_ms
        if hasattr(record, "extra_data"):
            entry["extra"] = record.extra_data
        if record.exc_info and record.exc_info[1]:
            entry["msg"] += "\n" + self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False)


class HttpBatchHandler(logging.Handler):
    """Async batch HTTP POST to log-collector."""

    def __init__(self, endpoint: str, batch_size: int = 100, flush_interval: float = 2.0):
        super().__init__()
        self.endpoint = endpoint
        self.batch_size = batch_size
        self.flush_interval = flush_interval
        self._buffer: list[str] = []
        self._lock = threading.Lock()
        self._timer: threading.Timer | None = None
        self._start_timer()

    def _start_timer(self):
        self._timer = threading.Timer(self.flush_interval, self._timed_flush)
        self._timer.daemon = True
        self._timer.start()

    def _timed_flush(self):
        self._do_flush()
        self._start_timer()

    def emit(self, record: logging.LogRecord):
        try:
            line = self.format(record)
            flush_now = False
            with self._lock:
                self._buffer.append(line)
                if len(self._buffer) >= self.batch_size:
                    flush_now = True
            if flush_now:
                self._do_flush()
        except Exception:
            self.handleError(record)

    def _do_flush(self):
        with self._lock:
            if not self._buffer:
                return
            entries = self._buffer[:]
            self._buffer.clear()
        self._send(entries)

    def _send(self, entries: list[str]):
        try:
            payload = "[" + ",".join(entries) + "]"
            requests.post(self.endpoint, data=payload,
                          headers={"Content-Type": "application/json"}, timeout=5)
        except Exception:
            pass  # Log collection failure must not crash the job

    def close(self):
        if self._timer:
            self._timer.cancel()
        self._do_flush()
        super().close()
```

- [ ] **Step 5: Write __init__.py**

Write `log-collector/lakeon-log/lakeon_log/__init__.py`:

```python
"""Lakeon structured logging for CCI components."""
import logging
import os

from .handler import LakeonJsonFormatter, HttpBatchHandler


def setup_logging(component: str, level: int = logging.INFO) -> logging.Logger:
    """Initialize structured JSON logging with optional HTTP shipping.

    Reads from environment:
      LAKEON_REQUEST_ID  — request correlation ID
      LAKEON_TENANT_ID   — tenant ID
      LAKEON_LOG_ENDPOINT — log-collector HTTP endpoint (optional)
    """
    request_id = os.environ.get("LAKEON_REQUEST_ID", "")
    tenant_id = os.environ.get("LAKEON_TENANT_ID", "")
    endpoint = os.environ.get("LAKEON_LOG_ENDPOINT", "")

    logger = logging.getLogger(component)
    logger.setLevel(level)
    logger.handlers.clear()

    formatter = LakeonJsonFormatter(component=component, request_id=request_id, tenant_id=tenant_id)

    # Always output to stdout (for kubectl logs / Fluent Bit)
    stdout_handler = logging.StreamHandler()
    stdout_handler.setFormatter(formatter)
    logger.addHandler(stdout_handler)

    # HTTP handler if endpoint configured
    if endpoint:
        http_handler = HttpBatchHandler(endpoint=endpoint)
        http_handler.setFormatter(formatter)
        logger.addHandler(http_handler)

    return logger
```

- [ ] **Step 6: Run tests**

```bash
cd log-collector/lakeon-log && pip install -e ".[test]" && pytest tests/ -v
```

Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add log-collector/lakeon-log/
git commit -m "feat(log): add lakeon-log Python module (JSON formatter + HTTP batch handler)"
```

---

### Task 6: Knowledge Pipeline integration + requestId injection

**Files:**
- Modify: `knowledge/job/main.py:17-18`
- Modify: `lakeon-api/src/main/java/com/lakeon/job/JobPodManager.java:176`

- [ ] **Step 1: Modify Knowledge Pipeline logging setup**

In `knowledge/job/main.py`, replace lines 17-18:

```python
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
logger = logging.getLogger("knowledge-job")
```

With:

```python
try:
    from lakeon_log import setup_logging
    logger = setup_logging(component="knowledge-pipeline")
except ImportError:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    logger = logging.getLogger("knowledge-pipeline")
```

- [ ] **Step 2: Inject requestId env vars in JobPodManager**

In `lakeon-api/src/main/java/com/lakeon/job/JobPodManager.java`, after line 176 (the last `.endEnv()` before `.addNewVolumeMount()`), add:

```java
                    .addNewEnv()
                        .withName("LAKEON_REQUEST_ID")
                        .withValue(job.getRequestId() != null ? job.getRequestId() : "")
                    .endEnv()
                    .addNewEnv()
                        .withName("LAKEON_TENANT_ID")
                        .withValue(job.getTenantId() != null ? job.getTenantId() : "")
                    .endEnv()
                    .addNewEnv()
                        .withName("LAKEON_LOG_ENDPOINT")
                        .withValue("http://log-collector." + props.getK8s().getNamespace() + ".svc.cluster.local:9880/logs")
                    .endEnv()
```

- [ ] **Step 3: Add requestId field to JobEntity**

Check if `JobEntity` already has a `requestId` field. If not, add it and pass it through from the API layer where MDC has the requestId.

- [ ] **Step 4: Install lakeon-log in Knowledge Pipeline Docker image**

In `knowledge/job/requirements.txt` (or Dockerfile pip install), add:

```
lakeon-log @ file:///app/lakeon-log
```

Or in the Dockerfile, add:

```dockerfile
COPY log-collector/lakeon-log /app/lakeon-log
RUN pip install /app/lakeon-log
```

- [ ] **Step 5: End-to-end verification**

1. Deploy updated lakeon-api and knowledge-pipeline
2. Upload a document to a knowledge base
3. Query dbay-logs:

```sql
SELECT ts, component, request_id, msg
FROM logs
WHERE request_id IS NOT NULL
ORDER BY ts DESC
LIMIT 20;
```

Expected: See entries from both `lakeon-api` and `knowledge-pipeline` with the same `request_id`.

- [ ] **Step 6: Commit**

```bash
git add knowledge/job/main.py \
        lakeon-api/src/main/java/com/lakeon/job/JobPodManager.java
git commit -m "feat(log): integrate Knowledge Pipeline with structured logging + requestId propagation"
```

---

## Phase 3: Log Consumption

### Task 7: dbay-sre-mcp — 4 log diagnostic tools

**Files:**
- Create: `dbay-sre-mcp/pyproject.toml`
- Create: `dbay-sre-mcp/src/dbay_sre_mcp/__init__.py`
- Create: `dbay-sre-mcp/src/dbay_sre_mcp/__main__.py`
- Create: `dbay-sre-mcp/src/dbay_sre_mcp/server.py`
- Create: `dbay-sre-mcp/tests/test_tools.py`

- [ ] **Step 1: Write tests**

Write `dbay-sre-mcp/tests/test_tools.py`:

```python
"""Test MCP tool SQL generation (no real DB needed)."""
import pytest
from dbay_sre_mcp.server import _build_search_query, _build_trace_query, _build_errors_query, _build_stats_query


def test_search_query_basic():
    sql, params = _build_search_query(component="lakeon-api", level="ERROR", since="1h", limit=50)
    assert "component = " in sql
    assert "level = " in sql
    assert "LIMIT" in sql
    assert len(params) >= 2


def test_search_query_keyword():
    sql, params = _build_search_query(keyword="timeout")
    assert "to_tsvector" in sql


def test_trace_query():
    sql, params = _build_trace_query(request_id="req_abc123")
    assert "request_id = " in sql
    assert "ORDER BY ts" in sql
    assert params[0] == "req_abc123"


def test_errors_query():
    sql, params = _build_errors_query(since="2h", component="knowledge-pipeline")
    assert "level IN" in sql
    assert "component = " in sql


def test_stats_query():
    sql, params = _build_stats_query(since="24h")
    assert "GROUP BY" in sql
    assert "component" in sql
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd dbay-sre-mcp && pip install -e ".[test]" && pytest tests/ -v
```

Expected: FAIL — module not found.

- [ ] **Step 3: Write pyproject.toml**

```toml
[project]
name = "dbay-sre-mcp"
version = "0.1.0"
description = "DBay SRE diagnostic MCP server — log search, trace, errors, stats"
requires-python = ">=3.10"
dependencies = ["fastmcp>=2.0", "psycopg2-binary>=2.9"]

[project.scripts]
dbay-sre-mcp = "dbay_sre_mcp.__main__:main"

[project.optional-dependencies]
test = ["pytest>=7.0"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"
```

- [ ] **Step 4: Write server.py**

Write `dbay-sre-mcp/src/dbay_sre_mcp/server.py`:

```python
"""DBay SRE MCP server — log diagnostic tools for CC and SRE AI assistants."""
import json
import os
import re
from pathlib import Path

import psycopg2
import psycopg2.extras
from fastmcp import FastMCP

CONFIG_FILE = Path.home() / ".dbay" / "sre-config.json"

mcp = FastMCP("dbay-sre", instructions="DBay SRE diagnostic tools for searching logs, tracing requests, finding errors, and viewing stats.")


def _get_dsn() -> str:
    dsn = os.environ.get("LOG_DB_DSN")
    if dsn:
        return dsn
    if CONFIG_FILE.exists():
        cfg = json.loads(CONFIG_FILE.read_text())
        return cfg.get("log_db_dsn", "")
    raise ValueError("LOG_DB_DSN env var or ~/.dbay/sre-config.json required")


def _query(sql: str, params: list) -> list[dict]:
    with psycopg2.connect(_get_dsn()) as conn:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
            return [dict(r) for r in rows]


def _parse_interval(since: str) -> str:
    """Convert '1h', '30m', '2d' to PostgreSQL interval string."""
    m = re.match(r"^(\d+)([mhd])$", since)
    if not m:
        return "1 hour"
    val, unit = m.group(1), m.group(2)
    units = {"m": "minutes", "h": "hours", "d": "days"}
    return f"{val} {units[unit]}"


def _build_search_query(component: str = "", level: str = "", keyword: str = "",
                         tenant_id: str = "", db_id: str = "", since: str = "1h",
                         limit: int = 100) -> tuple[str, list]:
    conditions = ["ts > now() - %s::interval"]
    params: list = [_parse_interval(since)]

    if component:
        conditions.append("component = %s")
        params.append(component)
    if level:
        conditions.append("level = %s")
        params.append(level)
    if tenant_id:
        conditions.append("tenant_id = %s")
        params.append(tenant_id)
    if db_id:
        conditions.append("db_id = %s")
        params.append(db_id)
    if keyword:
        conditions.append("to_tsvector('simple', msg) @@ plainto_tsquery('simple', %s)")
        params.append(keyword)

    where = " AND ".join(conditions)
    sql = f"SELECT ts, level, component, request_id, tenant_id, logger, msg, duration_ms FROM logs WHERE {where} ORDER BY ts DESC LIMIT %s"
    params.append(limit)
    return sql, params


def _build_trace_query(request_id: str) -> tuple[str, list]:
    sql = "SELECT ts, level, component, logger, msg, duration_ms, extra FROM logs WHERE request_id = %s ORDER BY ts"
    return sql, [request_id]


def _build_errors_query(since: str = "1h", component: str = "") -> tuple[str, list]:
    conditions = ["level IN ('ERROR', 'WARN')", "ts > now() - %s::interval"]
    params: list = [_parse_interval(since)]
    if component:
        conditions.append("component = %s")
        params.append(component)
    where = " AND ".join(conditions)
    sql = f"SELECT ts, level, component, request_id, tenant_id, msg FROM logs WHERE {where} ORDER BY ts DESC LIMIT 200"
    return sql, params


def _build_stats_query(since: str = "24h") -> tuple[str, list]:
    interval = _parse_interval(since)
    sql = """
        SELECT component, level, count(*) as count
        FROM logs
        WHERE ts > now() - %s::interval
        GROUP BY component, level
        ORDER BY component, count DESC
    """
    return sql, [interval]


@mcp.tool(description="Search logs by component, level, keyword, tenant, time range. Returns matching log entries.")
def log_search(component: str = "", level: str = "", keyword: str = "",
               tenant_id: str = "", db_id: str = "", since: str = "1h",
               limit: int = 100) -> str:
    sql, params = _build_search_query(component, level, keyword, tenant_id, db_id, since, limit)
    rows = _query(sql, params)
    return json.dumps(rows, default=str, ensure_ascii=False)


@mcp.tool(description="Trace a request across components by requestId. Returns the full call chain in chronological order.")
def log_trace(request_id: str) -> str:
    sql, params = _build_trace_query(request_id)
    rows = _query(sql, params)
    return json.dumps(rows, default=str, ensure_ascii=False)


@mcp.tool(description="List recent errors and warnings. Useful for quick health check.")
def log_errors(since: str = "1h", component: str = "") -> str:
    sql, params = _build_errors_query(since, component)
    rows = _query(sql, params)
    return json.dumps(rows, default=str, ensure_ascii=False)


@mcp.tool(description="Log volume and error statistics by component. Shows counts grouped by component and level.")
def log_stats(since: str = "24h") -> str:
    sql, params = _build_stats_query(since)
    rows = _query(sql, params)
    # Also get slow operations
    slow_sql = "SELECT ts, component, request_id, msg, duration_ms FROM logs WHERE duration_ms IS NOT NULL AND ts > now() - %s::interval ORDER BY duration_ms DESC LIMIT 10"
    slow_rows = _query(slow_sql, [_parse_interval(since)])
    return json.dumps({"stats": rows, "slow_top10": slow_rows}, default=str, ensure_ascii=False)
```

- [ ] **Step 5: Write __main__.py and __init__.py**

Write `dbay-sre-mcp/src/dbay_sre_mcp/__main__.py`:

```python
from .server import mcp

def main():
    mcp.run(transport="stdio")

if __name__ == "__main__":
    main()
```

Write `dbay-sre-mcp/src/dbay_sre_mcp/__init__.py`:

```python
"""DBay SRE diagnostic MCP server."""
```

- [ ] **Step 6: Run tests**

```bash
cd dbay-sre-mcp && pip install -e ".[test]" && pytest tests/ -v
```

Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add dbay-sre-mcp/
git commit -m "feat(log): add dbay-sre-mcp with log_search/log_trace/log_errors/log_stats tools"
```

---

### Task 8: SRE console — 4 log diagnostic pages

**Files:**
- Modify: `lakeon-admin/src/router/index.ts:22`
- Modify: `lakeon-admin/src/api/admin.ts`
- Create: `lakeon-admin/src/views/logs/LogSearch.vue`
- Create: `lakeon-admin/src/views/logs/LogTrace.vue`
- Create: `lakeon-admin/src/views/logs/LogErrors.vue`
- Create: `lakeon-admin/src/views/logs/LogStats.vue`
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`

This is a large task. The implementation should follow the existing SRE console patterns (harbor warm tone, admin API client, Vue 3 composition API).

- [ ] **Step 1: Add Admin API log endpoints**

In `AdminController.java`, add 4 new endpoints under `/api/v1/admin/structured-logs/`:

- `GET /structured-logs/search` — params: component, level, keyword, tenant_id, since, limit
- `GET /structured-logs/trace/{requestId}` — returns call chain
- `GET /structured-logs/errors` — params: since, component
- `GET /structured-logs/stats` — params: since

These endpoints connect to the dbay-logs database (a separate DataSource or direct JDBC connection, not the metadata RDS).

- [ ] **Step 2: Add API client methods**

In `lakeon-admin/src/api/admin.ts`, add:

```typescript
  // Structured Logs (dbay-logs database)
  logSearch: (params: { component?: string; level?: string; keyword?: string; tenant_id?: string; since?: string; limit?: number }) =>
    client.get('/structured-logs/search', { params }),
  logTrace: (requestId: string) =>
    client.get(`/structured-logs/trace/${requestId}`),
  logErrors: (params?: { since?: string; component?: string }) =>
    client.get('/structured-logs/errors', { params }),
  logStats: (params?: { since?: string }) =>
    client.get('/structured-logs/stats', { params }),
```

- [ ] **Step 3: Add routes**

In `lakeon-admin/src/router/index.ts`, replace the existing `logs` route (line 22) and add:

```typescript
      { path: 'logs', name: 'LogViewer', component: () => import('../views/system/LogViewer.vue') },
      { path: 'logs/search', name: 'LogSearch', component: () => import('../views/logs/LogSearch.vue') },
      { path: 'logs/trace', name: 'LogTrace', component: () => import('../views/logs/LogTrace.vue') },
      { path: 'logs/trace/:requestId', name: 'LogTraceDetail', component: () => import('../views/logs/LogTrace.vue') },
      { path: 'logs/errors', name: 'LogErrors', component: () => import('../views/logs/LogErrors.vue') },
      { path: 'logs/stats', name: 'LogStats', component: () => import('../views/logs/LogStats.vue') },
```

- [ ] **Step 4: Create LogSearch.vue**

Page with filter bar (component dropdown, level dropdown, tenant input, time range, keyword), results table with expandable rows. requestId column is clickable → navigates to LogTrace.

Follow existing harbor warm tone style from other views (e.g., `DatabaseList.vue`).

- [ ] **Step 5: Create LogTrace.vue**

Input: requestId (from route param or text input). Displays timeline of log entries from the same requestId, color-coded by component. Shows time delta between consecutive entries.

- [ ] **Step 6: Create LogErrors.vue**

Displays recent ERROR/WARN logs grouped by error message (count + last occurrence). Expandable to see individual entries. Filter by component and time range.

- [ ] **Step 7: Create LogStats.vue**

Dashboard with: component × level count table, log volume trend chart (per hour), slow operations Top 10 table.

- [ ] **Step 8: Verify**

Open SRE console → navigate to each of the 4 log pages → verify data loads from dbay-logs database.

- [ ] **Step 9: Commit**

```bash
git add lakeon-admin/src/views/logs/ \
        lakeon-admin/src/router/index.ts \
        lakeon-admin/src/api/admin.ts \
        lakeon-api/src/main/java/com/lakeon/controller/AdminController.java
git commit -m "feat(log): SRE console log diagnostic pages (search, trace, errors, stats)"
```

---

## Phase 4: SRE AI Assistant Integration

### Task 9: SRE AI assistant log query integration

This task depends on the SRE AI assistant architecture (to be designed separately). The integration point is:

- AI assistant backend (CCE) connects to dbay-logs database with the same SQL queries as dbay-sre-mcp
- Reuse the same query-building logic from the Admin API endpoints added in Task 8
- The AI assistant calls the Admin API `/structured-logs/*` endpoints internally

- [ ] **Step 1: Verify AI assistant can query logs**

Once the AI assistant is implemented, test:

```
User: "最近知识库有没有报错？"
AI: → calls GET /structured-logs/errors?component=knowledge-pipeline&since=1h
   → returns formatted diagnosis
```

- [ ] **Step 2: Commit**

Integration commit with AI assistant feature.

---

## Verification Checklist

After all phases:

- [ ] Upload a document to knowledge base → check dbay-logs has entries from both `lakeon-api` and `knowledge-pipeline` with same `request_id`
- [ ] CC runs `log_errors()` via dbay-sre-mcp → gets recent errors
- [ ] CC runs `log_trace(request_id)` → gets full call chain across components
- [ ] SRE console LogSearch page shows filtered results
- [ ] SRE console LogTrace page shows timeline view
- [ ] Fluent Bit DaemonSet running on all CCE nodes (fixed + elastic)
- [ ] log-collector Deployment healthy, writing to dbay-logs
