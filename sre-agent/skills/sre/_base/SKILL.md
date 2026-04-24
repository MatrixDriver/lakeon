---
name: _base
description: Internal shared utilities for SRE watchers. Not a user-facing skill.
version: v0.1
personality: sre
---

# _base

Shared `WatcherBase` dataclass providing dedupe + session-open + ledger helpers
for all SRE watchers (cold_start_watcher, pod_create_failure_watcher,
fuse_queue_health_watcher, stuck_task_watcher, data_consistency_watcher,
multi_tenant_blast_radius_watcher).

Not callable as a skill by hermes — pure Python base for other watchers to inherit.
