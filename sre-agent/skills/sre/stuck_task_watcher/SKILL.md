---
name: stuck_task_watcher
description: Detect async tasks stuck in_progress beyond threshold (wiki/agentfs/kb). Every 5 min.
version: v0.1
triggers:
  cron: "*/5 * * * *"
tools:
  - dbay-sre-mcp.stuck_task_query
personality: sre
---

# stuck_task_watcher

Every 5 minutes:
1. Call `stuck_task_query(threshold_minutes=10)`.
2. Open 1 incident grouping all stuck tasks (not 1 per task — too noisy).
3. DM: `[SRE] {count} 个 async 任务卡住 > 10min — {type_summary}`.

Catches bug b742634d family (WIKI_UPDATE 30-min recovery timeout) and 5f9e1fc9
(DeepSeek agent skips done() call).
