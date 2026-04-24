# Phase 1 Progress Notes

## dbay-sre-mcp 0.2.0 (DONE)

- 7 new tools: find_database / find_tenant / database_status / data_consistency_check / stuck_task_query / pod_create_failures / multi_tenant_blast_radius
- 100% backward compatible with 0.1.0 — no SRE agent runtime change beyond env vars + version bump
- New env vars wired: LAKEON_ADMIN_TOKEN, LAKEON_DB_DSN, LAKEON_API_BASE_URL
- 52 tests passing (5 admin_client + 20 log + 5 find_database + 3 find_tenant + 3 database_status + 5 data_consistency + 4 stuck_task + 4 pod_create_failures + 3 multi_tenant_blast_radius)
- Wheel built locally: `dist/dbay_sre_mcp-0.2.0-py3-none-any.whl` (19 660 bytes)
- Git tag: `dbay-sre-mcp-0.2.0`

## Pending operator action

1. PyPI publish: see `dbay-sre-mcp/PUBLISH-0.2.0.md`
2. After publish, push to trigger Railway rebuild of sre-agent
3. Verify in production: DM the SRE bot a question like "为什么 X 数据库唤醒失败" — agent should now use find_database + database_status, not bash on log_search

## Next: SRE agent watchers + 早晚报 (Plan B, separate plan)

Watchers planned (one per major bug family):
- fuse_queue_health_watcher (every 5m)
- pod_create_failure_watcher (every 2m)
- data_consistency_watcher (every 15m, runs all 4 invariant rules)
- stuck_task_watcher (every 5m)
- multi_tenant_blast_radius_watcher (every 5m)

Briefings planned:
- daily_morning_briefing (cron 0 1 UTC = 9:00 Asia/Shanghai)
- daily_evening_briefing (cron 0 14 UTC = 22:00 Asia/Shanghai — coexists with reading-companion's reflection)
- weekly_pattern_clustering (cron 0 1 mon UTC, weekly insight)

Domain glossary:
- skills/sre/domain_glossary/SKILL.md — dbay 对象模型 + 症状映射 + 标准 5 步诊断 (prompt-only)
