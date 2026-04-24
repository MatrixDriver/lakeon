---
name: pod_create_failure_watcher
description: Detect k8s pod creation failures (InvalidName, CrashLoopBackOff, ImagePullBackOff, etc.) every 2 minutes. Open incident per failure category.
version: v0.1
triggers:
  cron: "*/2 * * * *"
tools:
  - dbay-sre-mcp.pod_create_failures
personality: sre
---

# pod_create_failure_watcher

Every 2 minutes:
1. Call `pod_create_failures(since="5m")`.
2. For each category with count > 0 that hasn't been alerted in the dedupe window (10 min),
   open a `type=incident` session with tags `["component:k8s", "category:<cat>"]`.
3. Write conclusion: category + affected tenant_ids + first 3 error messages.
4. DM Jacky: `[SRE] {cat} pod create 失败 {count} 次 — 涉及 N 个 tenant`.

This watcher does NOT diagnose — categorisation is done by the tool. If user wants
root cause, they can ask `为什么 X tenant pod 起不来` and agent will call
database_status + log_search.
