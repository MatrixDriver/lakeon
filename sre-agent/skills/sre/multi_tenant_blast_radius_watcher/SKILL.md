---
name: multi_tenant_blast_radius_watcher
description: Detect single error pattern affecting multiple tenants. Every 5 min. LLM hypothesizes common fault domain.
version: v0.1
triggers:
  cron: "*/5 * * * *"
tools:
  - dbay-sre-mcp.multi_tenant_blast_radius
personality: sre
---

# multi_tenant_blast_radius_watcher

Every 5 minutes:
1. Call `multi_tenant_blast_radius(window="15m", min_tenant_count=3)`.
2. For each cross-tenant incident (component + error_signature), open incident.
3. LLM reads the signature and hypothesizes fault domain (shared dep? config? DNS? upstream service?).

Catches bug 98a29218 family (single fault domain kills multiple tenants).
