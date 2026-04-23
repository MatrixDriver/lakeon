# Phase 0a Open Issues — BEFORE DEPLOY

These must be resolved (or scope-adjusted) before a meaningful Railway deploy.

## 1. Hermes skill contract is unresolved

**Status:** RESOLVED — 2026-04-23

**Finding:** Hermes skills are prompt-only. `SKILL.md` files are markdown
instructions injected into an LLM's context; the cron scheduler
(`cron/scheduler.py:run_job()`) calls `AIAgent.run_conversation(prompt)` and
never invokes Python code from skill directories. Our `triggers.cron` frontmatter
was silently ignored.

**Resolution:** Implemented approach (b) — explicit `croniter` loop in
`sre-agent/main.py`. The loop runs our Python watcher/checker classes directly on
schedule. Hermes is retained only for the feishu bidirectional gateway
(started as a subprocess). Feishu DM push uses the Feishu REST API directly.

**Files changed:**
- `sre-agent/main.py` — new: croniter loop, SREMCPAdapter, DeepseekLLMClient
- `sre-agent/entrypoint.sh` — updated: runs `main.py` instead of `hermes gateway start`
- `sre-agent/pyproject.toml` — added `croniter>=2.0` dependency
- `sre-agent/tests/test_main.py` — new: 4 dispatch-logic tests (all pass)
- `sre-agent/docs/HERMES_SKILL_DISPATCH.md` — investigation notes with file:line evidence

**See:** `sre-agent/docs/HERMES_SKILL_DISPATCH.md` for full investigation.

## 2. LLM retry is coarse

Current retry is a 2-try exponential backoff. For production this is crude. Phase 1 should add jittered retry, circuit breaker on persistent failures, and a per-session cost cap.

## 3. Session filesystem storage is single-writer / single-process

Documented in `FilesystemStore.__init__` but worth re-stating: if two hermes workers ever run in parallel (Railway horizontal scale), the commit log will race. Phase 0a assumes one replica. Enforce this in Railway deploy config.

## 4. Branch reload does not dedupe branch_open on concurrent resume

Edge case: if Session.load is called twice in parallel and both create a Branch object for an existing branch, second `branch_open` may still be written. Mitigated by single-process guarantee; Phase 1 should add a file lock.
