---
name: agentfs_forwarder_orphan_watcher
description: Detect AgentFS forwarder pushing events to deleted tenants ("forwarder: tenant tn_X not found" WARN spam). Every 15 min.
version: v0.1
triggers:
  cron: "*/15 * * * *"
tools:
  - dbay-sre-mcp.log_search
personality: sre
---

# agentfs_forwarder_orphan_watcher

Every 15 minutes:
1. Call `log_search(component="lakeon-api", keyword="forwarder", since="30m", limit=500)`.
2. Filter `level=WARN` rows whose `msg` matches `forwarder: tenant tn_<id> not found`.
3. Group by tenant id. If any tenant exceeds N WARN (default 5) in the window, open one incident per orphan tenant.
4. Dedupe per `agentfs_forwarder_orphan:<tenant_id>` within 6h — these are persistent, not flares; one signal per tenant per 6h is enough.

Catches the lakeon-api `c.l.agentfs.AgentFSEventForwarder` (scheduling-1 thread) leak: deleted tenants
keep their forwarder subscription, each scheduled push fails with "not found", emitting ~2 WARN/min/tenant.
At 12 orphan tenants this is ~34k WARN/day — pure log noise that masks real warnings.

**Fix lives in lakeon-api side**: clean up forwarder subscription on tenant delete, OR auto-unsubscribe
forwarder when it sees "not found". Watcher only reports — does not auto-remediate.
