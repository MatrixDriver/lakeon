# Changelog

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
