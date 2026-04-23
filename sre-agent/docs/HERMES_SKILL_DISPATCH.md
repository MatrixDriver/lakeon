# Hermes Skill Dispatch Contract (investigation 2026-04-23)

## Finding

Hermes skills are **prompt-only markdown instructions** — the skill system loads
`SKILL.md` content as text, injects it into an LLM agent's system prompt, and
has the LLM reason about what to do.  There is no Python callable entrypoint
contract at all: `cron/scheduler.py:run_job()` (line 709) calls
`AIAgent.run_conversation(prompt)` directly; it never imports or invokes Python
code from skill directories.  The `SKILL.md` parser in
`tools/skills_hub.py:690` reads YAML frontmatter for metadata display only —
`triggers.cron` is not consumed by the scheduler.  Jobs are stored as JSON in
`~/.hermes/cron/jobs.json` via `cron/jobs.py:create_job()` and fired when
`cron/scheduler.py:get_due_jobs()` says they are due, which triggers an LLM
run, not a Python function.

**Evidence:**
- `hermes-agent/cron/scheduler.py:702-710` — `run_job()` instantiates `AIAgent`
  and calls `agent.run_conversation(prompt)`. No skill module is imported or
  called.
- `hermes-agent/cron/scheduler.py:664-699` — `_build_job_prompt()` injects
  skill content by calling `tools.skills_tool.skill_view(skill_name)`, which
  returns the raw `SKILL.md` markdown string.  This string becomes part of the
  LLM prompt — it is not executed as code.
- `hermes-agent/cron/jobs.py:374-473` — `create_job()` stores `prompt`,
  `skills` (name list), and `schedule` in JSON; there is no field for a Python
  callable.
- `hermes-agent/tools/skills_hub.py:690-695` — frontmatter parser reads `name`,
  `description`, `version`, `metadata` for display; `triggers.cron` is unknown
  to hermes and silently ignored.

## Implications for Our Phase 0a Skills

Our `Watcher.scan_once()`, `diagnose()`, and `OutcomeChecker.scan_once()`
classes are Python code that hermes will **never** invoke.  The `triggers.cron`
field in our `SKILL.md` files is decorative — the hermes scheduler reads it as
an unknown frontmatter field and ignores it.

If we rely on hermes's cron, we get an LLM agent prompt that reads the
`SKILL.md` markdown and tries to interpret it.  That LLM has no access to our
Python classes and cannot call dbay-sre-mcp tools at the frequency or
determinism we need.  Our structured watcher logic (regex matching, dedupe
window, session opening, branch evidence collection) would be lost — replaced
by an LLM approximation that cannot guarantee correctness.

## Chosen Approach: (b) Explicit croniter loop in `main.py`

We retain hermes solely for the feishu bidirectional gateway (user can DM the
bot) and run our own `croniter`-based dispatch loop in the main process.

### Architecture

```
main.py (main thread)
├── subprocess: hermes gateway start  (feishu DM bot)
├── subprocess: scripts/sync_loop.py  (OBS backup)
└── croniter loop (blocking, main thread)
    ├── */2 * * * * → Watcher.scan_once() → for each sid: diagnose()
    └── 0 9 * * *  → OutcomeChecker.scan_once()
```

### Design decisions

- **MCP client:** We import `dbay_sre_mcp` directly as a Python package (both
  are installed in the same Docker image via `uv pip install --system`).  This
  is simpler and faster than spawning a subprocess over stdio MCP protocol.
- **LLM client:** Thin `httpx` wrapper (`sre_agent/llm.py`) that calls
  Deepseek's OpenAI-compatible `/chat/completions` endpoint.  Keeps the client
  testable via dependency injection without pulling in the full hermes stack.
- **Feishu DM push:** We use the Feishu open-platform REST API directly
  (`https://open.feishu.cn/open-apis/im/v1/messages`) with the bot's
  `app_access_token`.  This avoids any IPC with the hermes subprocess and is
  simpler than trying to call hermes internals cross-process.
- **SKILL.md files** are kept as human-readable documentation of skill intent
  and tool requirements; they are not registered with hermes's cron.

### What `entrypoint.sh` changes

Old: `exec hermes gateway start --config ${HERMES_CONFIG}`
New: `exec python /app/main.py`

`main.py` starts hermes gateway as a background subprocess internally, so the
feishu bot keeps running alongside our cron loop.
