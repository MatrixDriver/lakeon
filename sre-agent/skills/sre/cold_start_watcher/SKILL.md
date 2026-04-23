---
name: cold-start-watcher
description: Detect dbay.cloud compute cold starts exceeding 5 seconds and open a diagnostic session.
version: v0.1
triggers:
  cron: "*/2 * * * *"
tools:
  - dbay-sre-mcp.log_search
  - dbay-sre-mcp.log_stats
personality: sre
---

# cold-start-watcher

Every 2 minutes, scan recent lakeon-api logs for lines matching
`compute started in {ms}ms for tenant={t} db={d}`.

For each match where `ms > 5000`:

1. Dedupe: skip if an incident session for this (tenant, db) was opened within the last 10 minutes.
2. Open a new session of type=incident with tags including `severity:medium`,
   `component:compute`, `skill:cold-start-watcher`.
3. Record the watcher invocation in the skill ledger.
4. Hand off to the diagnose prompt for LLM-driven investigation.
5. On conclusion, post an interactive feishu card to the allowed user.

This skill does NOT execute remediations. It reports only.
