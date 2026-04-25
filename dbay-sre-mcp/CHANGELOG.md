# Changelog

## 0.2.1 (2026-04-25) — hotfix + REST cleanup

### Fixed (CRITICAL)

- `LakeonAdminClient` was sending `Admin-Token: <token>` header but lakeon-api
  expects `Authorization: Bearer <token>` (per `ApiKeyFilter.java:111`). All
  7 admin-REST-based tools (`find_database`, `find_tenant`, `database_status`,
  `data_consistency_check`, `stuck_task_query`, `pod_create_failures`,
  `multi_tenant_blast_radius` indirectly) returned 403 in production until
  this fix. Header is now `Authorization: Bearer ...`.

### Changed

- `data_consistency_check` and `stuck_task_query` now go through lakeon-api
  admin REST endpoints (`/admin/data-consistency/{rule}` and `/admin/stuck-tasks`)
  instead of direct PG (`LAKEON_DB_DSN`). Reasons:
  - lakeon-api PG is on CCE internal network (192.168.x.x), unreachable from
    Railway-hosted sre-agent.
  - Aligns with the "all admin tools go via REST" principle established for
    the other 5 new tools.
  - Removes `LAKEON_DB_DSN` env requirement entirely.

### Removed

- `dbay_sre_mcp/lakeon_db.py` (no longer needed).
- `LAKEON_DB_DSN` env var support — `data_consistency_check` and
  `stuck_task_query` no longer connect to PG directly.

## 0.2.0 (2026-04-24)

### Added (7 new tools)

- `find_database(name=, db_id=)` — resolve human-readable DB name to internal id + tenant + status + compute_host
- `find_tenant(name=, tenant_id=)` — tenant metadata + held databases
- `database_status(name_or_id)` — comprehensive status snapshot + last 1h key events
- `data_consistency_check(rule)` — parameterized invariant checks (KB↔db_id orphans, enqueued↔drained, etc.)
- `stuck_task_query(type=, threshold_min=10)` — async tasks stuck in_progress beyond threshold
- `pod_create_failures(since=)` — k8s pod creation failures (InvalidName, CrashLoopBackOff)
- `multi_tenant_blast_radius(window=)` — detect single fault domain affecting multiple tenants

### Changed

- New env var `LAKEON_ADMIN_TOKEN` required for the 7 new tools (signs admin REST calls). Original 4 log_* tools unaffected.

### Compatibility

- 100% backward compatible: `log_search` / `log_trace` / `log_errors` / `log_stats` signatures and return JSON shapes unchanged.

## 0.1.0 (2026-04-22)

- Initial release. 4 log-only tools backed by Postgres dbay-logs.
