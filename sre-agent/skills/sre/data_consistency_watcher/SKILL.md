---
name: data_consistency_watcher
description: Run 4 invariant rules every 15 min; use LLM to suggest root cause when violations found.
version: v0.1
triggers:
  cron: "*/15 * * * *"
tools:
  - dbay-sre-mcp.data_consistency_check
personality: sre
---

# data_consistency_watcher

Every 15 minutes:
1. For each rule in [kb_implies_db_id, enqueued_implies_drained, db_ready_implies_pod_running, schema_seeded]:
   Call `data_consistency_check(rule=...)`.
2. If any rule has violations (ok=false), open incident.
3. LLM reads violations + `diagnose_prompt.md` → writes root-cause hypothesis.
4. DM: `[SRE] 数据一致性违规: {rule} × {count} — {top_guess}`.

Catches bugs family 54035cc9 / 4e42694d / b5c97605 (event timing / tx ordering).
