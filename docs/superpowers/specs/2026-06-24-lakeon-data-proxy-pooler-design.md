# Lakeon Data Proxy Pooler Design

## Decision

Lakeon will use option C as the target architecture: the data-plane proxy owns both direct Postgres routing and pooled transaction routing. The pooler is a built-in endpoint capability, not a user-created PgBouncer resource.

We should not edit the dirty `~/code/neon` working tree in place. That tree already has local compute changes. Instead, Lakeon should maintain a dedicated Lakeon data-proxy fork or branch derived from Neon's `proxy` crate, publish a Lakeon proxy image, and deploy that image from `deploy/helm/lakeon/templates/deployment-proxy.yaml`.

## Why Modify Neon-Derived Code

The current Lakeon data-plane proxy uses the upstream Neon `proxy` binary. Its TCP path authenticates the client, connects to compute, and then enters byte passthrough. That path is correct for direct connections but cannot multiplex many client sessions onto fewer compute backend sessions.

Transaction pooling requires the proxy to understand PostgreSQL protocol boundaries after authentication. It must keep client sessions open, borrow a backend connection for a transaction, return that backend after transaction completion, and reject or pin workloads that need persistent session state.

Neon's repo is still the right code base because it already contains:

- Postgres startup, TLS, SCRAM, cancellation, and control-plane auth integration.
- `wake_compute` and `connect_compute` cache/lock/retry machinery.
- Existing pglb passthrough code for direct routing.
- Serverless SQL-over-HTTP connection pool code that can guide pool data structures, metrics, idle cleanup, and per-endpoint accounting.

The missing piece is transaction pooling for ordinary Postgres TCP clients.

## Product Model

Every Lakeon compute endpoint exposes both connection modes:

```text
Direct:
  postgresql://user:pass@<endpoint>.pg.dbay.cloud:4432/db

Pooled:
  postgresql://user:pass@<endpoint>-pooler.pg.dbay.cloud:4432/db
```

Existing `dbName` and `dbName--branchName` endpointish values remain supported. The `-pooler` suffix selects pooled mode and is stripped before database/branch resolution.

The pooled endpoint is available only for native Postgres password roles. OAuth-style short-lived credentials, if added later, use direct mode or client-side connection pools until the proxy can safely rotate credentials at backend checkout time.

## Architecture

```text
client
  -> Lakeon Data Proxy
       -> startup/TLS/SCRAM auth
       -> endpoint resolve and compute wake
       -> direct mode: 1 client session -> 1 compute backend session
       -> pooled mode: N client sessions -> M compute backend sessions
  -> compute pod Postgres
```

The proxy owns these state machines in one process:

- Endpoint parsing and mode selection.
- Access control and role secret lookup through `lakeon-api /proxy`.
- Compute wake, cache, and stale-address recovery.
- Direct connection passthrough.
- Pooled connection wait queues and backend pools.
- Per endpoint/user/database connection budgets.
- Admin metrics and drain controls.

## Pool Key And Budget

Pool key:

```text
endpoint_id + branch_id + database_name + role_name
```

Pool budget is derived from policy returned by Lakeon API:

```text
pool_mode = transaction
max_client_conn = 10000
default_pool_size = floor(0.75 * compute_max_connections)
reserve_pool_size = floor(0.10 * compute_max_connections)
query_wait_timeout = 120s
max_prepared_statements = 1000
```

The initial factor is intentionally lower than Databricks' published `0.9 * max_connections` because Lakeon compute pods also carry Kubernetes/container overhead and may run smaller resource profiles.

## Pooler Modes

Lakeon stores a policy mode per endpoint:

```text
DISABLED       no pooled endpoint is advertised
DIRECT_ONLY    pooled endpoint returns a clear direct-only error
PROXY_POOLED   Lakeon Data Proxy handles pooled endpoint
```

`SIDECAR` and `DEDICATED` are intentionally not first-class modes in the option C target. They can remain emergency fallback implementation details, but the product model should keep users and SREs focused on endpoint connection mode, not PgBouncer resources.

## Protocol Semantics

Direct mode preserves existing behavior.

Pooled mode initially supports safe transaction workloads:

- Simple autocommit query.
- Explicit `BEGIN` / `COMMIT` / `ROLLBACK`.
- Extended query protocol for ordinary prepared/execute flow.
- Protocol-level prepared statement tracking after the first pooled MVP.

Pooled mode rejects or requires direct connection for:

- SQL-level `PREPARE`, `EXECUTE`, `DEALLOCATE` until explicitly supported.
- `LISTEN` / `NOTIFY`.
- Session-level advisory locks.
- `WITH HOLD` cursors.
- Session-held temporary tables.
- `COPY` in the first MVP unless the session is pinned to one backend for the COPY lifecycle.
- `SET` that must persist outside the current transaction.
- `pg_dump`, schema migrations, and logical replication.

The proxy must never silently reuse a backend connection if client-visible session state may leak.

## Admin/SRE Surface

Admin adds a `Connection Pools` page and a pool section in `DatabaseDetail`.

Global list columns:

- Endpoint, tenant, database, branch, compute pod.
- Pooler mode, compute status, pool status.
- Client active, idle, waiting.
- Server active, idle, total.
- Pool utilization, wait p95, timeout count.
- Pool size, max client connections, last backend reconnect.

Actions:

- Enable pooled endpoint.
- Disable pooled endpoint.
- Force direct-only.
- Drain endpoint pool.
- Kill idle backend connections.
- Clear stale endpoint cache.
- Restart data proxy deployment.

All actions write operation logs.

## Rollout Strategy

1. Build and deploy a Lakeon data-proxy binary that is behavior-compatible with the current Neon proxy passthrough.
2. Add API support for parsing `-pooler` endpointish values and returning endpoint policy.
3. Add pooled mode to Lakeon data proxy behind a disabled-by-default feature flag.
4. Enable pooled mode for one test tenant and run API/browser/SRE E2E.
5. Enable pooled endpoint advertisement in Console and Admin.
6. Gradually migrate production traffic from upstream Neon proxy image to Lakeon data proxy image.

## Non-Goals For The First Implementation

- Cross-proxy shared backend pools.
- OAuth pooled connections.
- Read-only pooled endpoint and read replica routing.
- Session pinning for every PostgreSQL feature.
- User-customizable PgBouncer-style config.

## Open Risks

- Transaction pooling correctness is stricter than byte passthrough. The proxy must fail closed on unsupported session features.
- Multiple proxy replicas can multiply backend pool budgets unless endpoint ownership is explicit. The first pooled rollout should route each endpoint to one owner shard or keep pooled max sizes conservative.
- Prepared statement support can be expensive in memory and CPU. It should ship after the safe autocommit/transaction MVP.
