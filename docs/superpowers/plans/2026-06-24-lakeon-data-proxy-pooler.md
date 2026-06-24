# Lakeon Data Proxy Pooler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Lakeon's upstream Neon proxy deployment with a Lakeon-owned data proxy path that can later provide built-in pooled Postgres endpoints.

**Architecture:** Start from Neon's `proxy` crate, but build a Lakeon-owned proxy image and deploy it through Lakeon's Helm chart. Milestone 1 preserves existing direct passthrough behavior; later milestones add endpoint policy, pooled mode, Admin management, and production rollout.

**Tech Stack:** Rust/Tokio Neon proxy code in `~/code/neon`, Spring Boot 3.3.5 Lakeon API, Vue 3 Admin, Helm/Kubernetes on CCE.

---

## File Structure

### Neon-Derived Proxy

- `~/code/neon/proxy/src/pglb/passthrough.rs`  
  Existing direct byte passthrough. Do not break this path.

- `~/code/neon/proxy/src/pglb/mod.rs`  
  Existing interactive TCP accept/handshake flow. Add mode dispatch here after Lakeon-specific endpoint mode is available.

- `~/code/neon/proxy/src/serverless/conn_pool_lib.rs`  
  Reference implementation for per-endpoint pool accounting, not a direct TCP transaction pool.

- `~/code/neon/proxy/src/lakeon_pooler/mod.rs`  
  New module for pooled TCP client state machine.

- `~/code/neon/proxy/src/lakeon_pooler/policy.rs`  
  New module for pool policy and mode.

- `~/code/neon/proxy/src/lakeon_pooler/pool.rs`  
  New module for per endpoint/user/database backend pools.

- `~/code/neon/proxy/src/lakeon_pooler/protocol.rs`  
  New module for PostgreSQL frontend/backend message classification and transaction-state detection.

### Lakeon API

- `lakeon-api/src/main/java/com/lakeon/controller/ProxyAdapterController.java`  
  Parse pooled endpoint suffixes and return pool policy fields.

- `lakeon-api/src/main/java/com/lakeon/model/entity/ConnectionPoolPolicyEntity.java`  
  New entity for endpoint pool policy.

- `lakeon-api/src/main/java/com/lakeon/repository/ConnectionPoolPolicyRepository.java`  
  New repository.

- `lakeon-api/src/main/java/com/lakeon/service/ConnectionPoolPolicyService.java`  
  New service for defaults, overrides, and admin actions.

- `lakeon-api/src/main/java/com/lakeon/controller/AdminConnectionPoolController.java`  
  New admin API.

### Admin

- `lakeon-admin/src/router/index.ts`  
  Add `/connection-pools`.

- `lakeon-admin/src/api/admin.ts`  
  Add connection pool endpoints.

- `lakeon-admin/src/views/connection-pools/ConnectionPoolList.vue`  
  New SRE list page.

- `lakeon-admin/src/views/databases/DatabaseDetail.vue`  
  Add pool panel.

### Deploy

- `deploy/helm/lakeon/values.yaml`  
  Add `proxy.image.repository`, `proxy.image.tag`, and pooler feature flags if not already present.

- `deploy/helm/lakeon/templates/deployment-proxy.yaml`  
  Use Lakeon proxy image and pass pooler flags.

---

## Milestone 1: Lakeon-Owned Proxy Image With Existing Behavior

### Task 1: Confirm the Current Proxy Path Is Pure Passthrough

**Files:**
- Read: `~/code/neon/proxy/src/pglb/mod.rs`
- Read: `~/code/neon/proxy/src/pglb/passthrough.rs`

- [ ] **Step 1: Record evidence**

Run:

```bash
sed -n '248,330p' ~/code/neon/proxy/src/pglb/mod.rs
sed -n '1,95p' ~/code/neon/proxy/src/pglb/passthrough.rs
```

Expected: `handle_connection` returns `ProxyPassthrough`, and `ProxyPassthrough::proxy_pass` calls `copy_bidirectional_client_compute`.

- [ ] **Step 2: Add a plan note**

Append to this plan's execution notes:

```markdown
Execution note: current TCP path authenticates, connects to compute, then byte-copies client <-> compute. It is not a transaction pooler.
```

### Task 2: Create a Lakeon Proxy Build Target

**Files:**
- Modify: `~/code/neon/proxy/src/binary/proxy.rs`
- Modify: `~/code/neon/proxy/Cargo.toml`

- [ ] **Step 1: Add a Lakeon build marker flag**

Add a CLI flag to `ProxyCliArgs`:

```rust
/// Enable Lakeon-specific data proxy behavior. Milestone 1 keeps behavior compatible.
#[clap(long, default_value_t = false, value_parser = clap::builder::BoolishValueParser::new(), action = clap::ArgAction::Set)]
lakeon_data_proxy: bool,
```

- [ ] **Step 2: Log the marker on startup**

In the startup configuration block after args parsing, add:

```rust
if args.lakeon_data_proxy {
    info!("Lakeon data proxy mode enabled");
}
```

- [ ] **Step 3: Build the proxy**

Run:

```bash
cd ~/code/neon
cargo build -p proxy --bin proxy
```

Expected: build succeeds.

### Task 3: Publish A Lakeon Proxy Image

**Files:**
- Create: `deploy/docker/Dockerfile.lakeon-proxy`
- Modify: `deploy/cce/build-and-push-proxy.sh`

- [ ] **Step 1: Create Dockerfile**

Create `deploy/docker/Dockerfile.lakeon-proxy`:

```dockerfile
FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libssl3 \
    && rm -rf /var/lib/apt/lists/*
COPY proxy /usr/local/bin/proxy
ENTRYPOINT ["/usr/local/bin/proxy"]
```

- [ ] **Step 2: Create build script**

Create or update `deploy/cce/build-and-push-proxy.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

SITE="${SITE:-hwstaff}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NEON_ROOT="${NEON_ROOT:-$HOME/code/neon}"
IMAGE_TAG="${IMAGE_TAG:-$(git -C "$ROOT" rev-parse --short HEAD)}"

source "$ROOT/deploy/cce/sites/$SITE/.env"

cargo build --manifest-path "$NEON_ROOT/Cargo.toml" -p proxy --bin proxy --release
cp "$NEON_ROOT/target/release/proxy" "$ROOT/deploy/docker/proxy"

docker build -f "$ROOT/deploy/docker/Dockerfile.lakeon-proxy" \
  -t "$SWR_REGISTRY/$SWR_NAMESPACE/lakeon-proxy:$IMAGE_TAG" \
  "$ROOT/deploy/docker"

docker push "$SWR_REGISTRY/$SWR_NAMESPACE/lakeon-proxy:$IMAGE_TAG"
rm -f "$ROOT/deploy/docker/proxy"

echo "$SWR_REGISTRY/$SWR_NAMESPACE/lakeon-proxy:$IMAGE_TAG"
```

- [ ] **Step 3: Make the script executable**

Run:

```bash
chmod +x deploy/cce/build-and-push-proxy.sh
```

### Task 4: Deploy Lakeon Proxy Image Without Behavior Change

**Files:**
- Modify: `deploy/helm/lakeon/values.yaml`
- Modify: `deploy/helm/lakeon/templates/deployment-proxy.yaml`

- [ ] **Step 1: Add proxy image values**

In `deploy/helm/lakeon/values.yaml`, add under `proxy:`:

```yaml
  image:
    repository: ""
    tag: ""
    pullPolicy: IfNotPresent
  lakeonDataProxy:
    enabled: false
```

- [ ] **Step 2: Use the proxy image override**

Change the proxy image expression in `deployment-proxy.yaml` to:

```yaml
          image: "{{ .Values.proxy.image.repository | default .Values.neon.image.repository }}:{{ .Values.proxy.image.tag | default .Values.neon.image.tag }}"
          imagePullPolicy: {{ .Values.proxy.image.pullPolicy | default .Values.neon.image.pullPolicy }}
```

- [ ] **Step 3: Pass the Lakeon marker flag**

Add under proxy args:

```yaml
            {{- if .Values.proxy.lakeonDataProxy.enabled }}
            - "--lakeon-data-proxy=true"
            {{- end }}
```

- [ ] **Step 4: Render Helm**

Run:

```bash
helm template lakeon deploy/helm/lakeon --set proxy.lakeonDataProxy.enabled=true >/tmp/lakeon-proxy-render.yaml
rg -- '--lakeon-data-proxy=true|image:' /tmp/lakeon-proxy-render.yaml
```

Expected: rendered deployment contains `--lakeon-data-proxy=true` and a valid image.

---

## Milestone 2: Endpoint Policy In Lakeon API

### Task 5: Add Pool Policy Entity

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/entity/ConnectionPoolPolicyEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/repository/ConnectionPoolPolicyRepository.java`
- Test: `lakeon-api/src/test/java/com/lakeon/service/ConnectionPoolPolicyServiceTest.java`

- [ ] **Step 1: Add entity**

Create `ConnectionPoolPolicyEntity.java`:

```java
package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "connection_pool_policies",
       uniqueConstraints = @UniqueConstraint(columnNames = {"endpoint_id"}))
public class ConnectionPoolPolicyEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "endpoint_id", nullable = false, length = 128)
    private String endpointId;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "branch_id", length = 64)
    private String branchId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 32)
    private String mode = "DISABLED";

    @Column(name = "max_client_conn", nullable = false)
    private int maxClientConn = 10000;

    @Column(name = "pool_size_factor", nullable = false)
    private double poolSizeFactor = 0.75;

    @Column(name = "query_wait_timeout_seconds", nullable = false)
    private int queryWaitTimeoutSeconds = 120;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getMaxClientConn() { return maxClientConn; }
    public void setMaxClientConn(int maxClientConn) { this.maxClientConn = maxClientConn; }
    public double getPoolSizeFactor() { return poolSizeFactor; }
    public void setPoolSizeFactor(double poolSizeFactor) { this.poolSizeFactor = poolSizeFactor; }
    public int getQueryWaitTimeoutSeconds() { return queryWaitTimeoutSeconds; }
    public void setQueryWaitTimeoutSeconds(int queryWaitTimeoutSeconds) { this.queryWaitTimeoutSeconds = queryWaitTimeoutSeconds; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 2: Add repository**

Create `ConnectionPoolPolicyRepository.java`:

```java
package com.lakeon.repository;

import com.lakeon.model.entity.ConnectionPoolPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConnectionPoolPolicyRepository extends JpaRepository<ConnectionPoolPolicyEntity, String> {
    Optional<ConnectionPoolPolicyEntity> findByEndpointId(String endpointId);
}
```

### Task 6: Parse Pooled Endpoint Suffix In Proxy Adapter

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/ProxyAdapterController.java`
- Test: `lakeon-api/src/test/java/com/lakeon/controller/ProxyAdapterControllerTest.java`

- [ ] **Step 1: Add endpoint parser**

Add a private record and parser inside `ProxyAdapterController`:

```java
private record EndpointRoute(String raw, String normalized, boolean pooled) {}

private EndpointRoute parseEndpointRoute(String endpointish) {
    if (endpointish.endsWith("-pooler")) {
        return new EndpointRoute(endpointish, endpointish.substring(0, endpointish.length() - "-pooler".length()), true);
    }
    return new EndpointRoute(endpointish, endpointish, false);
}
```

- [ ] **Step 2: Use normalized endpoint for database and branch lookup**

At the top of `wakeCompute`, replace direct `endpointish` parsing with:

```java
EndpointRoute route = parseEndpointRoute(endpointish);
String dbName = route.normalized().contains("--") ? route.normalized().split("--", 2)[0] : route.normalized();
```

Call `resolveBranch(db, route.normalized())`.

- [ ] **Step 3: Include pool mode in aux**

Add:

```java
aux.put("pooler_mode", route.pooled() ? "PROXY_POOLED" : "DIRECT");
```

- [ ] **Step 4: Mirror parsing in access control**

At the top of `getEndpointAccessControl`, use:

```java
EndpointRoute route = parseEndpointRoute(endpointish);
String dbName = route.normalized().contains("--") ? route.normalized().split("--", 2)[0] : route.normalized();
```

Call `resolveBranch(db, route.normalized())`.

---

## Milestone 3: Pooled TCP MVP In Lakeon Data Proxy

### Task 7: Add Pooled Mode Skeleton

**Files:**
- Create: `~/code/neon/proxy/src/lakeon_pooler/mod.rs`
- Create: `~/code/neon/proxy/src/lakeon_pooler/policy.rs`
- Modify: `~/code/neon/proxy/src/lib.rs`

- [ ] **Step 1: Expose module**

Add to `~/code/neon/proxy/src/lib.rs`:

```rust
mod lakeon_pooler;
```

- [ ] **Step 2: Add policy types**

Create `policy.rs`:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum LakeonPoolerMode {
    Direct,
    ProxyPooled,
}

#[derive(Debug, Clone)]
pub(crate) struct LakeonPoolPolicy {
    pub(crate) mode: LakeonPoolerMode,
    pub(crate) max_client_conn: usize,
    pub(crate) default_pool_size: usize,
    pub(crate) query_wait_timeout_secs: u64,
}

impl LakeonPoolPolicy {
    pub(crate) fn direct() -> Self {
        Self {
            mode: LakeonPoolerMode::Direct,
            max_client_conn: 0,
            default_pool_size: 0,
            query_wait_timeout_secs: 0,
        }
    }
}
```

- [ ] **Step 3: Add module root**

Create `mod.rs`:

```rust
pub(crate) mod policy;
```

### Task 8: Implement Safe MVP Behavior

**Files:**
- Create: `~/code/neon/proxy/src/lakeon_pooler/protocol.rs`
- Create: `~/code/neon/proxy/src/lakeon_pooler/pool.rs`

- [ ] **Step 1: Implement protocol classifier**

Create `protocol.rs` with a conservative classifier:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum FrontendEvent {
    Query,
    Begin,
    Commit,
    Rollback,
    UnsupportedSessionFeature,
    Other,
}

pub(crate) fn classify_simple_query(sql: &str) -> FrontendEvent {
    let normalized = sql.trim_start().to_ascii_lowercase();
    if normalized.starts_with("begin") || normalized.starts_with("start transaction") {
        FrontendEvent::Begin
    } else if normalized.starts_with("commit") {
        FrontendEvent::Commit
    } else if normalized.starts_with("rollback") {
        FrontendEvent::Rollback
    } else if normalized.starts_with("listen")
        || normalized.starts_with("notify")
        || normalized.starts_with("prepare ")
        || normalized.starts_with("execute ")
        || normalized.starts_with("deallocate")
        || normalized.starts_with("copy ")
        || normalized.starts_with("declare ")
        || normalized.starts_with("set ")
    {
        FrontendEvent::UnsupportedSessionFeature
    } else {
        FrontendEvent::Query
    }
}
```

- [ ] **Step 2: Add unit tests**

Add tests in the same file:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_transaction_boundaries() {
        assert_eq!(classify_simple_query("BEGIN"), FrontendEvent::Begin);
        assert_eq!(classify_simple_query(" commit"), FrontendEvent::Commit);
        assert_eq!(classify_simple_query("ROLLBACK"), FrontendEvent::Rollback);
    }

    #[test]
    fn rejects_session_features() {
        assert_eq!(classify_simple_query("SET search_path TO app"), FrontendEvent::UnsupportedSessionFeature);
        assert_eq!(classify_simple_query("LISTEN changes"), FrontendEvent::UnsupportedSessionFeature);
        assert_eq!(classify_simple_query("COPY table TO STDOUT"), FrontendEvent::UnsupportedSessionFeature);
    }
}
```

- [ ] **Step 3: Run tests**

Run:

```bash
cd ~/code/neon
cargo test -p proxy lakeon_pooler::protocol
```

Expected: tests pass.

---

## Milestone 4: Admin Management

### Task 9: Add Admin API

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/controller/AdminConnectionPoolController.java`
- Create: `lakeon-api/src/main/java/com/lakeon/service/ConnectionPoolPolicyService.java`

- [ ] **Step 1: Create service**

Create service methods:

```java
public ConnectionPoolPolicyEntity enable(String endpointId, String actor) { ... }
public ConnectionPoolPolicyEntity disable(String endpointId, String actor) { ... }
public ConnectionPoolPolicyEntity directOnly(String endpointId, String actor) { ... }
public Map<String, Object> list() { ... }
```

The service must set `updatedAt=Instant.now()` and write an operation log for mode changes.

- [ ] **Step 2: Create controller routes**

Add routes:

```java
@GetMapping("/api/v1/admin/connection-pools")
@PostMapping("/api/v1/admin/connection-pools/{endpointId}/enable")
@PostMapping("/api/v1/admin/connection-pools/{endpointId}/disable")
@PostMapping("/api/v1/admin/connection-pools/{endpointId}/direct-only")
```

### Task 10: Add Admin UI

**Files:**
- Modify: `lakeon-admin/src/router/index.ts`
- Modify: `lakeon-admin/src/api/admin.ts`
- Create: `lakeon-admin/src/views/connection-pools/ConnectionPoolList.vue`

- [ ] **Step 1: Add route**

Add:

```ts
{ path: 'connection-pools', name: 'ConnectionPools', component: () => import('../views/connection-pools/ConnectionPoolList.vue') },
```

- [ ] **Step 2: Add API functions**

Add:

```ts
export function listConnectionPools() {
  return adminClient.get('/admin/connection-pools')
}

export function enableConnectionPool(endpointId: string) {
  return adminClient.post(`/admin/connection-pools/${endpointId}/enable`)
}

export function disableConnectionPool(endpointId: string) {
  return adminClient.post(`/admin/connection-pools/${endpointId}/disable`)
}
```

- [ ] **Step 3: Add dense SRE table**

Create a table with columns:

```text
Endpoint | Tenant | Database | Branch | Mode | Compute | Clients | Servers | Waiting | Timeouts | Updated
```

Use existing Admin table styling and no nested cards.

---

## Verification

Run these before claiming completion:

```bash
cd ~/code/neon && cargo test -p proxy lakeon_pooler
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw test
cd /Users/jacky/code/lakeon/lakeon-admin && npx vue-tsc -b --noEmit && npm run build
helm template lakeon /Users/jacky/code/lakeon/deploy/helm/lakeon >/tmp/lakeon-render.yaml
```

Production smoke test after deploy:

```bash
DBAY_ENDPOINT="https://api.dbay.cloud:8443" DBAY_ADMIN_TOKEN="$DBAY_ADMIN_TOKEN" python3 -m pytest tests/e2e/test_connection.py -v
```

## Self-Review

- Spec coverage: The plan covers Lakeon-owned proxy image, endpoint policy, pooled suffix parsing, pooled proxy skeleton, and Admin SRE surface.
- Deliberate deferral: Full PostgreSQL transaction pooling is split after the safe protocol classifier because implementing it correctly requires a larger Rust state machine and integration tests against real Postgres.
- No current Neon dirty files should be reverted. Any Neon edits must be made on a dedicated branch/worktree before execution.
