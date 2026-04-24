---
name: fuse_queue_health_watcher
description: Detect stuck dbay-fuse batches via repeated retry patterns in fuse logs. Every 5 min.
version: v0.1
triggers:
  cron: "*/5 * * * *"
tools:
  - dbay-sre-mcp.log_search
personality: sre
---

# fuse_queue_health_watcher

Every 5 minutes:
1. Call `log_search(component="dbay-fuse", keyword="retry", since="15m", limit=200)`.
2. Group by blob_id; if any blob_id has > N retries (default 5) across the window, flag as stuck batch.
3. Open incident with the stuck blob_ids + affected tenants.
4. DM: `[SRE] dbay-fuse 卡住 {n} 个 blob — 最老 {age}`.

This catches bug 1a5efca9 family (skip-and-ack on missing blob).
