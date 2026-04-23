# Phase 0a Open Issues — BEFORE DEPLOY

These must be resolved (or scope-adjusted) before a meaningful Railway deploy.

## 1. Hermes skill contract is unresolved

**Status:** BLOCKER for live deploy.

Our `Watcher`, `diagnose()`, and `OutcomeChecker` classes are ready to execute but nothing currently invokes them from hermes's runtime. The SKILL.md manifests exist but we haven't confirmed how hermes actually dispatches skill code at the scheduled cron time.

**Research needed:**
- Read `/Users/jacky/code/hermes-agent/hermes/` skill loader source
- Find an example skill (e.g., one of the built-in ones in `hermes-agent/skills/` or `optional-skills/`) and confirm the Python callable contract hermes expects
- Options:
  - (a) Register our classes as Python skills via whatever decorator / import hook hermes uses
  - (b) Write a standalone `main.py` that uses hermes ONLY for feishu gateway and runs our own croniter loop
  - (c) Register our skills via hermes config file if that's supported

**Risk if ignored:** Railway deploy succeeds, hermes starts, feishu bot responds to DMs, but the cron never fires the watcher. First cold start happens silently. Nothing in the commit log. False success signal.

**Recommended action:** Jacky spends ~2 hours reading hermes source code with one of the approved CC subagents before Step 22.1. If that research is blocked, fall back to approach (b) — explicit croniter loop is the safest Phase 0a choice because it doesn't depend on hermes skill discovery semantics.

## 2. LLM retry is coarse

Current retry is a 2-try exponential backoff. For production this is crude. Phase 1 should add jittered retry, circuit breaker on persistent failures, and a per-session cost cap.

## 3. Session filesystem storage is single-writer / single-process

Documented in `FilesystemStore.__init__` but worth re-stating: if two hermes workers ever run in parallel (Railway horizontal scale), the commit log will race. Phase 0a assumes one replica. Enforce this in Railway deploy config.

## 4. Branch reload does not dedupe branch_open on concurrent resume

Edge case: if Session.load is called twice in parallel and both create a Branch object for an existing branch, second `branch_open` may still be written. Mitigated by single-process guarantee; Phase 1 should add a file lock.
