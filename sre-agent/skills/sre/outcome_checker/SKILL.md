---
name: outcome-checker
description: Re-check incident sessions 24h after close to verify suggested fixes worked.
version: v0.1
triggers:
  cron: "0 9 * * *"
tools:
  - dbay-sre-mcp.log_stats
  - dbay-sre-mcp.log_search
personality: sre
---

# outcome-checker

Runs every morning at 09:00.

For each closed incident session from the last 36 hours that doesn't yet have `outcome.md`:

1. Identify the affected (tenant, db) from the session trigger.
2. Query cold-start p95 for that pair over the last 24 hours via log_stats.
3. Compare against the triggering cold start time:
   - If new p95 < 5s AND significantly better than trigger: did_work=True.
   - If no improvement: did_work=False.
4. Write outcome.md on the session.
5. Update skill-ledger outcomes.
6. If did_work=False, DM Jacky on feishu: "建议未生效,请看 {session_id}".
