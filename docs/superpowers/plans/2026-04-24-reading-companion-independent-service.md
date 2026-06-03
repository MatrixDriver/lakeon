# Reading Companion Independent Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 reading companion 从 `sre-agent/` 内的 skill 子目录提升为独立的 Railway service `reading-companion/`，背后用 uv workspace 把共享的 `agent_session_log` 数据层和 `hermes_agent_utils` (LLM/feishu/cron/OBS helpers) 抽成内部 packages，让 SRE 和 reading 两个 service 各跑各的，共享同一份 schema 与同一份 OBS bucket（不同 prefix），独立 cron / 独立 Volume / 独立 feishu bot / 独立部署生命周期。**这是 Phase 2 (`agent_session_log` → 独立 PyPI package) 的提前演练**——抽包后只剩"建独立 repo + CI"一步。

**Architecture:**
- **Workspace 化**：`lakeon/` 根加 `pyproject.toml` 用 uv workspace，两个内部 package 在 `lakeon/packages/agent-session-log/` 和 `lakeon/packages/hermes-agent-utils/`，sre-agent 和 reading-companion 都通过 path source 装。
- **职责切线**：`agent-session-log` = 业务数据层（zero hermes/dbay 依赖，Phase 2 抽包目标）；`hermes-agent-utils` = LLM/feishu/cron/subprocess 管理 helper（hermes runtime adjacent）；service-specific 代码（main.py、skills、Dockerfile、railway.toml）留在各 service 目录。
- **Reading-companion 独立**：自己的 Dockerfile（不装 hermes-agent / 不装 dbay-sre-mcp，trafilatura 装上）、自己的 railway.toml、自己的 main.py（只挂 `0 14 * * *` cron + `python -m skills.reading.<x>.cli` 入口）、自己的 feishu bot env vars、自己的 Railway Volume；OBS 共享 bucket，prefix 区分。

**Tech Stack:** uv workspace (uv >= 0.4)、hatchling、Python 3.11、httpx、croniter、PyYAML、esdk-obs-python、trafilatura（仅 reading）、hermes-agent + dbay-sre-mcp（仅 sre）、pytest。

**Related plans/specs:**
- [`docs/superpowers/plans/2026-04-23-sre-agent-phase-0a-plan.md`](./2026-04-23-sre-agent-phase-0a-plan.md)
- [`docs/superpowers/plans/2026-04-23-sre-agent-phase-0b-plan.md`](./2026-04-23-sre-agent-phase-0b-plan.md)
- [`docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md`](../specs/2026-04-23-agent-commit-log-phase0-design.md) — Phase 2 抽包路线

---

## Hard Constraints

1. **`agent-session-log` 公共 API 不动**——`LogStore` / `Session` / `SkillLedger` / `Blob` / `FilesystemStore` / `BlobRef` / `SessionManifest` / `SessionType` / `SessionStatus` / `TurnType` / `new_session_id` / `utc_now_iso` 的签名与行为零变。所有现有 import path `from agent_session_log import …` 继续可用。
2. **Phase 0a SRE service 在 Railway 上的运行不能中断**——sre-agent 改完后必须能 `docker build` 通过；改造期间不强求重新部署，但 Dockerfile 必须能跑出新镜像。
3. **Phase 0b 所有 39 个 reading 相关测试 + Phase 0a 60+ SRE 测试都必须保持绿**（pre-existing `test_sre_mcp_adapter_decodes_log_search` 失败因为本地 venv 没装 `dbay_sre_mcp` 是已知不相关）。
4. **uv workspace path-installed packages**，不发 PyPI（Phase 2 才发）。`lakeon/pyproject.toml` 是 workspace root，不是 distributable project。
5. **`reading-companion/skills/reading/` 不能 import `sre-agent/skills/sre/`**（实际上两个 service 编译时都看不到对方），import discipline 测试在 reading-companion/tests/ 里再写一份。
6. **OBS 共享 bucket，prefix 隔离**：`OBS_PREFIX=agent-log/sre/` vs `OBS_PREFIX=agent-log/reading/`。已经支持（`ObsSync.__init__(prefix=…)` + `sync_loop.py` 读 `OBS_PREFIX` env）。
7. **Reading-companion 不启 hermes gateway 子进程**——它不需要接收 inbound feishu，只 push DM。daily reflection cron 触发后调用 `feishu_send_dm` 直接 REST API push。Hermes runtime 不在 reading 镜像里。

---

## File Structure (target end-state)

```
lakeon/
├── pyproject.toml                           # NEW — uv workspace root (no project, just members)
├── packages/                                # NEW
│   ├── agent-session-log/
│   │   ├── pyproject.toml                   # NEW — name="agent-session-log"
│   │   ├── README.md                        # NEW
│   │   ├── agent_session_log/               # MOVED from sre-agent/agent_session_log/
│   │   │   ├── __init__.py
│   │   │   ├── types.py
│   │   │   ├── ids.py
│   │   │   ├── evidence.py
│   │   │   ├── store.py
│   │   │   ├── session.py
│   │   │   ├── log.py
│   │   │   ├── skill_ledger.py
│   │   │   ├── obs_sync.py
│   │   │   └── py.typed
│   │   └── tests/                           # MOVED — pure-library tests
│   │       ├── conftest.py
│   │       ├── test_types.py
│   │       ├── test_evidence.py
│   │       ├── test_store.py
│   │       ├── test_session.py
│   │       ├── test_log_query.py
│   │       ├── test_skill_ledger.py
│   │       ├── test_obs_sync.py
│   │       └── test_end_to_end.py
│   └── hermes-agent-utils/
│       ├── pyproject.toml                   # NEW — name="hermes-agent-utils"
│       ├── README.md                        # NEW
│       ├── hermes_agent_utils/              # NEW package
│       │   ├── __init__.py                  # public exports
│       │   ├── llm.py                       # DeepseekLLMClient
│       │   ├── feishu.py                    # feishu_send_dm, jacky_open_id
│       │   ├── factory.py                   # make_log_store, make_skill_ledger, hermes_home, hermes_config
│       │   ├── runner.py                    # cron_loop, start_subprocess, shutdown_children, env bridges
│       │   ├── cli.py                       # python -m hermes_agent_utils.cli sync (OBS sync loop)
│       │   └── py.typed
│       └── tests/
│           ├── conftest.py
│           ├── test_llm.py
│           ├── test_feishu.py
│           ├── test_factory.py
│           ├── test_runner.py
│           └── test_sync_cli.py
│
├── sre-agent/                               # MODIFIED — slim down
│   ├── pyproject.toml                       # MODIFY: deps add agent-session-log + hermes-agent-utils
│   ├── Dockerfile                           # MODIFY: build context still sre-agent/ but COPY workspace packages
│   ├── railway.toml                         # UNCHANGED
│   ├── entrypoint.sh                        # UNCHANGED
│   ├── main.py                              # MODIFY: ~400 → ~150 lines; only SRE-specific code
│   ├── agent_session_log/                   # DELETE
│   ├── hermes_config/                       # UNCHANGED
│   ├── scripts/
│   │   ├── verify_env.py                    # UNCHANGED (validate sre-specific env)
│   │   ├── probe_dbay_logs.py               # UNCHANGED
│   │   ├── simulate_cold_start.py           # UNCHANGED
│   │   ├── onboard_feishu.py                # UNCHANGED
│   │   └── sync_loop.py                     # DELETE — replaced by `python -m hermes_agent_utils.cli sync`
│   ├── skills/
│   │   ├── sre/                             # UNCHANGED
│   │   └── reading/                         # DELETE (moved to reading-companion/)
│   └── tests/
│       ├── conftest.py                      # UNCHANGED
│       ├── test_main.py                     # MODIFY: assert SRE-only crons (drop daily_reflection)
│       ├── test_import_discipline.py        # MODIFY: drop reading/ checks (no longer in sre-agent)
│       ├── test_onboard_feishu.py           # UNCHANGED
│       └── integration/
│           ├── test_cold_start_watcher.py   # UNCHANGED
│           ├── test_outcome_checker.py      # UNCHANGED
│           └── test_report.py               # UNCHANGED
│       (DELETED: test_url_handler.py, test_query_handler.py, test_daily_reflection.py,
│                test_phase_0b_cross_consumer.py — moved to reading-companion or deleted)
│       (DELETED: test_types.py, test_evidence.py, test_store.py, test_session.py,
│                test_log_query.py, test_skill_ledger.py, test_obs_sync.py, test_end_to_end.py
│                — moved to packages/agent-session-log/tests/)
│
├── reading-companion/                       # NEW service
│   ├── pyproject.toml                       # NEW
│   ├── Dockerfile                           # NEW
│   ├── railway.toml                         # NEW
│   ├── README.md                            # NEW
│   ├── .env.example                         # NEW
│   ├── .python-version                      # NEW (3.11)
│   ├── entrypoint.sh                        # NEW
│   ├── main.py                              # NEW (~80 lines, reading-only)
│   ├── hermes_config/                       # NEW (placeholder, mostly env-driven)
│   │   └── README.md                        # explains feishu bot env vars
│   ├── scripts/
│   │   └── verify_env.py                    # NEW (validate reading-specific env)
│   ├── skills/
│   │   ├── __init__.py                      # NEW
│   │   └── reading/                         # MOVED from sre-agent/skills/reading/
│   └── tests/
│       ├── conftest.py                      # NEW
│       ├── test_main.py                     # NEW (assert reading-only cron)
│       ├── test_import_discipline.py        # NEW (no skills.sre imports)
│       └── integration/
│           ├── __init__.py
│           ├── test_url_handler.py          # MOVED
│           ├── test_query_handler.py        # MOVED
│           └── test_daily_reflection.py     # MOVED
│
└── docs/superpowers/plans/2026-04-24-reading-companion-independent-service.md  # this file
```

### Module responsibilities

| Module | Responsibility | Dependencies |
|---|---|---|
| `agent-session-log` | Session/Turn/Branch/Evidence/SkillLedger data layer + OBS sync. Zero knowledge of LLM, hermes, dbay. | stdlib + pyyaml + esdk-obs-python (for HuaweiObsAdapter) |
| `hermes-agent-utils.llm` | `DeepseekLLMClient` — OpenAI-compat LLM client used by both agents. | httpx |
| `hermes-agent-utils.feishu` | `feishu_send_dm`, `_feishu_app_access_token`, `jacky_open_id` (read FEISHU_ALLOWED_USERS). | httpx |
| `hermes-agent-utils.factory` | `hermes_home()`, `hermes_config_path()`, `make_log_store()`, `make_skill_ledger()`. | agent-session-log |
| `hermes-agent-utils.runner` | `cron_loop(tasks: list[(expr, callable)])`, `start_subprocess`, `shutdown_children`, signal handlers, env-var bridging (`DBAY_LOGS_DSN → LOG_DB_DSN`). | croniter |
| `hermes-agent-utils.cli` | `python -m hermes_agent_utils.cli sync` — long-running OBS sync loop (replaces `scripts/sync_loop.py`). | agent-session-log |
| `sre-agent/main.py` | SRE-specific: `SREMCPAdapter`, `run_cold_start_watcher`, `run_outcome_checker`, `_CRON_TASKS`, `main()`. | hermes-agent-utils, agent-session-log, dbay-sre-mcp, hermes-agent (gateway) |
| `reading-companion/main.py` | Reading-specific: `run_daily_reflection`, `_CRON_TASKS`, `main()` (no hermes gateway, no MCP adapter, no SRE imports). | hermes-agent-utils, agent-session-log |

---

## Work Breakdown — 10 Tasks

| Group | Tasks | What it produces |
|---|---|---|
| A. Workspace foundation | 1 | uv workspace root recognized; `uv sync` from `lakeon/` works |
| B. Extract agent-session-log | 2-3 | Independent package; sre-agent uses it via path source; tests still green |
| C. Extract hermes-agent-utils | 4-5 | Helpers consolidated; sre-agent uses them; main.py slimmed |
| D. Build reading-companion service | 6-8 | Reading skills moved out; reading service has its own main.py + Dockerfile + tests; Phase 0b coverage preserved |
| E. Operational | 9-10 | Independent Dockerfile/railway.toml; OBS prefix isolation; runbook + report |

---

## Group A: Workspace Foundation

### Task 1: Create uv workspace root

**Why:** uv workspace lets multiple packages in the same repo share a single venv and reference each other by path. This is the foundation for Tasks 2-5.

**Files:**
- Create: `lakeon/pyproject.toml`
- Create: `lakeon/.python-version` (if absent)

- [ ] **Step 1.1: Create `lakeon/pyproject.toml`**

```toml
# lakeon/pyproject.toml
# uv workspace root — declares member packages and shared tool config.
# This file is NOT a distributable project; it has no [project] table.
# See https://docs.astral.sh/uv/concepts/workspaces/

[tool.uv.workspace]
members = [
  "packages/agent-session-log",
  "packages/hermes-agent-utils",
  "sre-agent",
  "reading-companion",
]

[tool.ruff]
line-length = 100
target-version = "py311"

[tool.pytest.ini_options]
# Default test discovery — each package overrides via its own pyproject.toml.
asyncio_mode = "auto"
```

- [ ] **Step 1.2: Create `lakeon/.python-version` if absent**

```bash
cd /Users/jacky/code/lakeon
[ -f .python-version ] || echo "3.11" > .python-version
```

- [ ] **Step 1.3: Verify `uv sync` from workspace root**

```bash
cd /Users/jacky/code/lakeon
uv --version  # must be >= 0.4.0; bump if older
uv sync
```

Expected: succeeds with no errors. Members `packages/agent-session-log`, `packages/hermes-agent-utils`, `reading-companion` are listed but don't exist yet — uv will warn or skip; that's fine for now. The existing `sre-agent` member should be discovered.

If uv complains about missing members: temporarily comment out the not-yet-existing members from `members = [...]`; we'll re-enable each in its task.

- [ ] **Step 1.4: Commit**

```bash
cd /Users/jacky/code/lakeon
git add pyproject.toml .python-version
git commit -m "feat(workspace): introduce uv workspace root with member declarations"
```

---

## Group B: Extract `agent-session-log`

### Task 2: Create `packages/agent-session-log/` and move source + library tests

**Why:** This is the central abstraction Phase 2 wants to ship as a standalone package. Moving it now (with no API change) is half of Phase 2.

**Files:**
- Create: `lakeon/packages/agent-session-log/pyproject.toml`
- Create: `lakeon/packages/agent-session-log/README.md`
- Move: `lakeon/sre-agent/agent_session_log/` → `lakeon/packages/agent-session-log/agent_session_log/`
- Move (8 test files): `lakeon/sre-agent/tests/test_{types,evidence,store,session,log_query,skill_ledger,obs_sync,end_to_end}.py` → `lakeon/packages/agent-session-log/tests/`
- Move: `lakeon/sre-agent/tests/conftest.py` (the `tmp_log_root` fixture) → also lives in `lakeon/packages/agent-session-log/tests/conftest.py` (copy, not move — sre-agent tests still need it)

- [ ] **Step 2.1: Create the package directory + pyproject.toml**

```bash
mkdir -p /Users/jacky/code/lakeon/packages/agent-session-log/tests
```

Create `lakeon/packages/agent-session-log/pyproject.toml`:

```toml
[project]
name = "agent-session-log"
version = "0.1.0"
description = "LLM-native session/turn/branch/evidence commit log — runtime-agnostic data layer for agent dogfooding."
requires-python = ">=3.11"
dependencies = [
  "pyyaml>=6.0",
  "esdk-obs-python>=3.24",
]

[project.optional-dependencies]
dev = [
  "pytest>=8.0",
  "pytest-cov>=5.0",
]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["agent_session_log"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

- [ ] **Step 2.2: Create `README.md`**

```markdown
# agent-session-log

LLM-native session/turn/branch/evidence commit log. Runtime-agnostic Python library
that lets any agent record its work in a structured, content-addressable, query-able
form on disk (and optionally sync archives to OBS).

Concepts: Session, Turn, Branch, Evidence, SkillLedger.

Public API:
- `LogStore(root)` — top-level store
- `Session` — write API (append_turn, branch, attach_evidence, conclude, close, record_outcome)
- `SkillLedger` — per-skill invocation/outcome stats
- `FilesystemStore` — low-level file backend
- `Blob`, `BlobRef`, `SessionManifest`, `SessionType`, `SessionStatus`, `TurnType`
- `new_session_id()`, `utc_now_iso()`

This package is the Phase 2 target: extract it from `lakeon/` into its own repo
and publish to PyPI when the abstraction is verified by 2+ consumers (Phase 0b done).

See also: `docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md`.
```

- [ ] **Step 2.3: Move source code**

```bash
cd /Users/jacky/code/lakeon
git mv sre-agent/agent_session_log packages/agent-session-log/agent_session_log
```

- [ ] **Step 2.4: Move pure-library test files**

```bash
cd /Users/jacky/code/lakeon
for f in test_types.py test_evidence.py test_store.py test_session.py \
         test_log_query.py test_skill_ledger.py test_obs_sync.py test_end_to_end.py; do
  git mv sre-agent/tests/$f packages/agent-session-log/tests/$f
done
cp sre-agent/tests/conftest.py packages/agent-session-log/tests/conftest.py
```

(The `cp` is intentional — sre-agent's remaining integration tests in `tests/integration/` still use `tmp_log_root` from sre-agent/tests/conftest.py.)

Also create `packages/agent-session-log/tests/__init__.py` (empty file):

```bash
touch /Users/jacky/code/lakeon/packages/agent-session-log/tests/__init__.py
```

- [ ] **Step 2.5: Re-enable workspace member**

If you commented out members in Task 1.3, re-enable now:

```bash
cd /Users/jacky/code/lakeon
# verify lakeon/pyproject.toml lists "packages/agent-session-log" in tool.uv.workspace.members
uv sync
```

Expected: `packages/agent-session-log` is installed into the venv.

- [ ] **Step 2.6: Run agent-session-log tests in isolation**

```bash
cd /Users/jacky/code/lakeon/packages/agent-session-log
.venv/bin/pytest tests/ -v
# OR if venv is at lakeon root:
/Users/jacky/code/lakeon/.venv/bin/pytest tests/ -v
```

Expected: all moved tests pass (8 test files, ~30+ tests). No `import sre_agent` or `from sre_agent` allowed — this is the test that the package is self-contained.

If tests fail because of missing `tests/__init__.py`, add it. If fails because conftest.py has imports that don't exist, fix those imports (likely `from agent_session_log import …` continues to work).

- [ ] **Step 2.7: Commit**

```bash
cd /Users/jacky/code/lakeon
git add packages/agent-session-log/
git rm -r --cached sre-agent/agent_session_log 2>/dev/null || true   # already moved
git commit -m "feat(packages): extract agent-session-log as workspace member"
```

(`git mv` already staged the moves; this commit captures the new pyproject + README + the conftest copy.)

---

### Task 3: Switch sre-agent to consume agent-session-log via path source

**Why:** sre-agent must continue to work — same `from agent_session_log import …` imports, but now the package is installed via uv workspace path source rather than living inside sre-agent.

**Files:**
- Modify: `lakeon/sre-agent/pyproject.toml` (add path-source dependency, drop agent_session_log from `[tool.hatch.build.targets.wheel]`)
- Modify: `lakeon/sre-agent/Dockerfile` (COPY workspace packages, install them before pip install /app)
- Verify: All `from agent_session_log import …` imports across `sre-agent/main.py`, `sre-agent/scripts/sync_loop.py`, `sre-agent/skills/sre/**/*.py`, `sre-agent/skills/reading/**/*.py`, `sre-agent/tests/**/*.py` — no code changes needed because import path is identical.

- [ ] **Step 3.1: Modify `sre-agent/pyproject.toml`**

Replace contents:

```toml
[project]
name = "sre-agent"
version = "0.0.1"
description = "dbay.cloud SRE agent — incident detection + diagnosis + outcome tracking"
requires-python = ">=3.11"
dependencies = [
  "pyyaml>=6.0",
  "httpx>=0.27",
  "croniter>=2.0",
  "esdk-obs-python>=3.24",
  "agent-session-log",
  "hermes-agent-utils",
]

[project.optional-dependencies]
dev = [
  "pytest>=8.0",
  "pytest-asyncio>=0.23",
  "pytest-cov>=5.0",
  "mypy>=1.10",
  "ruff>=0.5",
  "psycopg2-binary>=2.9",
]

[tool.uv.sources]
agent-session-log = { workspace = true }
hermes-agent-utils = { workspace = true }

[tool.pytest.ini_options]
testpaths = ["tests"]
asyncio_mode = "auto"

[tool.ruff]
line-length = 100
target-version = "py311"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["skills"]
```

Note removed `trafilatura` (will live in reading-companion only; SRE doesn't need it). Also removed `agent_session_log` from the `packages = [...]` list.

- [ ] **Step 3.2: Sync workspace and run sre-agent tests**

```bash
cd /Users/jacky/code/lakeon
uv sync
cd sre-agent
.venv/bin/pytest tests/ -v
# OR
/Users/jacky/code/lakeon/.venv/bin/pytest tests/ -v
```

Expected: all sre-agent tests still pass (the integration tests for SRE skills + reading skills + cross-consumer + import discipline). The 1 pre-existing `test_sre_mcp_adapter_decodes_log_search` failure (`ModuleNotFoundError: No module named 'dbay_sre_mcp'`) is unrelated.

(Task 4-5 will deal with `hermes-agent-utils` not existing yet. For Task 3, temporarily comment out `"hermes-agent-utils"` from sre-agent/pyproject.toml dependencies and `[tool.uv.sources]` so this step succeeds. Re-add in Task 5.)

- [ ] **Step 3.3: Modify Dockerfile to copy workspace packages**

Replace `lakeon/sre-agent/Dockerfile` body. Note: Railway's build context for sre-agent service is `lakeon/sre-agent/` (rootDirectory=/sre-agent). To get workspace packages into the image, **change Railway's rootDirectory to `/`** in dashboard; then this Dockerfile reads from the workspace root.

```dockerfile
# sre-agent/Dockerfile
# Build context: lakeon/ repo root (Railway rootDirectory=/).
# Reading the workspace structure to install path-source packages.
FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir uv==0.4.18

# Install hermes-agent from public GitHub.
ARG HERMES_REF=main
ENV HERMES_REF=${HERMES_REF}
RUN uv pip install --system "hermes-agent[feishu,mcp] @ git+https://github.com/NousResearch/hermes-agent@${HERMES_REF}"
RUN uv pip install --system "mcp>=1.2.0,<2" "lark-oapi>=1.5.3,<2" "qrcode>=7.0,<8"

# Install dbay-sre-mcp from PyPI.
ARG DBAY_SRE_MCP_VERSION=0.1.0
RUN uv pip install --system "dbay-sre-mcp==${DBAY_SRE_MCP_VERSION}"

# Install workspace packages (agent-session-log + hermes-agent-utils).
COPY packages /app/packages
RUN uv pip install --system /app/packages/agent-session-log
RUN uv pip install --system /app/packages/hermes-agent-utils

# Install sre-agent itself (skills package).
COPY sre-agent/pyproject.toml /app/sre-agent/pyproject.toml
COPY sre-agent/skills /app/sre-agent/skills
RUN uv pip install --system /app/sre-agent

# Copy runtime assets.
COPY sre-agent/hermes_config /app/hermes_config
COPY sre-agent/scripts /app/scripts
COPY sre-agent/main.py /app/main.py
COPY sre-agent/entrypoint.sh /app/entrypoint.sh

ENV HERMES_HOME=/data/hermes
ENV HERMES_CONFIG=/app/hermes_config/config.yaml
ENV PYTHONPATH=/app

RUN chmod +x /app/entrypoint.sh

CMD ["/app/entrypoint.sh"]
```

- [ ] **Step 3.4: Update sre-agent/railway.toml for new build context**

```toml
# sre-agent/railway.toml
[build]
builder = "DOCKERFILE"
# Build context is repo root (rootDirectory=/) so workspace packages are visible.
dockerfilePath = "sre-agent/Dockerfile"
watchPatterns = [
  "sre-agent/**",
  "packages/**",
]

[deploy]
startCommand = "/app/entrypoint.sh"
restartPolicyType = "on_failure"
restartPolicyMaxRetries = 5
```

**Operator note** (add as comment in railway.toml): "Set Railway dashboard `Service → Settings → Source → Root Directory` to `/` (not `/sre-agent`) so the build context includes `packages/`."

- [ ] **Step 3.5: Local Docker build smoke**

```bash
cd /Users/jacky/code/lakeon
docker build -f sre-agent/Dockerfile -t sre-agent:b2 .
```

Expected: succeeds. If hermes-agent-utils install fails (it doesn't exist yet), temporarily comment out the line in Dockerfile until Task 5; commit a TODO and continue.

- [ ] **Step 3.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/pyproject.toml sre-agent/Dockerfile sre-agent/railway.toml
git commit -m "refactor(sre-agent): consume agent-session-log via uv workspace path source"
```

---

## Group C: Extract `hermes-agent-utils`

### Task 4: Create `packages/hermes-agent-utils/` with extracted helpers

**Why:** `DeepseekLLMClient`, `feishu_send_dm`, `_make_log_store`, `cron_loop`, `_start_subprocess`, `HuaweiObsAdapter` setup, env-var bridging — all of these are reused identically by sre-agent and reading-companion. Extract once.

**Files:**
- Create: `lakeon/packages/hermes-agent-utils/pyproject.toml`
- Create: `lakeon/packages/hermes-agent-utils/README.md`
- Create: `lakeon/packages/hermes-agent-utils/hermes_agent_utils/__init__.py`
- Create: `lakeon/packages/hermes-agent-utils/hermes_agent_utils/llm.py`
- Create: `lakeon/packages/hermes-agent-utils/hermes_agent_utils/feishu.py`
- Create: `lakeon/packages/hermes-agent-utils/hermes_agent_utils/factory.py`
- Create: `lakeon/packages/hermes-agent-utils/hermes_agent_utils/runner.py`
- Create: `lakeon/packages/hermes-agent-utils/hermes_agent_utils/cli.py`
- Create: `lakeon/packages/hermes-agent-utils/hermes_agent_utils/py.typed`
- Create: `lakeon/packages/hermes-agent-utils/tests/conftest.py`
- Create: `lakeon/packages/hermes-agent-utils/tests/test_llm.py`
- Create: `lakeon/packages/hermes-agent-utils/tests/test_feishu.py`
- Create: `lakeon/packages/hermes-agent-utils/tests/test_factory.py`
- Create: `lakeon/packages/hermes-agent-utils/tests/test_runner.py`

- [ ] **Step 4.1: Create pyproject + README**

```bash
mkdir -p /Users/jacky/code/lakeon/packages/hermes-agent-utils/{hermes_agent_utils,tests}
```

`packages/hermes-agent-utils/pyproject.toml`:
```toml
[project]
name = "hermes-agent-utils"
version = "0.1.0"
description = "Shared runtime helpers for dbay.cloud agents — LLM client, feishu DM, cron loop, OBS sync runner."
requires-python = ">=3.11"
dependencies = [
  "httpx>=0.27",
  "croniter>=2.0",
  "agent-session-log",
]

[project.optional-dependencies]
dev = [
  "pytest>=8.0",
  "pytest-cov>=5.0",
]

[project.scripts]
hermes-agent-utils = "hermes_agent_utils.cli:main"

[tool.uv.sources]
agent-session-log = { workspace = true }

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["hermes_agent_utils"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

`packages/hermes-agent-utils/README.md`:
```markdown
# hermes-agent-utils

Shared runtime helpers used by dbay.cloud agents:

- `llm.DeepseekLLMClient` — OpenAI-compat LLM client (also works for HWC MaaS).
- `feishu.feishu_send_dm` / `feishu.jacky_open_id` — outbound Feishu DM via REST.
- `factory.make_log_store` / `make_skill_ledger` — wire `agent_session_log` from env.
- `runner.cron_loop(tasks)` / `start_subprocess` / `shutdown_children` — main-loop primitives.
- `cli` — `python -m hermes_agent_utils.cli sync` runs the OBS sync loop.

Not intended for distribution outside this workspace; lives here so SRE and
reading-companion services can share without duplication.
```

`packages/hermes-agent-utils/hermes_agent_utils/py.typed`: empty file.

- [ ] **Step 4.2: Move env-bridging + factory helpers from sre-agent/main.py**

`packages/hermes-agent-utils/hermes_agent_utils/factory.py`:
```python
"""Factory helpers for wiring agent_session_log from env vars."""
from __future__ import annotations

import os
from pathlib import Path

from agent_session_log import LogStore, SkillLedger


def hermes_home() -> Path:
    """HERMES_HOME or ~/.hermes."""
    return Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))


def hermes_config_path() -> str:
    """HERMES_CONFIG or default ./hermes_config/config.yaml relative to caller's dir."""
    return os.environ.get(
        "HERMES_CONFIG",
        str(Path.cwd() / "hermes_config" / "config.yaml"),
    )


def make_log_store() -> LogStore:
    return LogStore(hermes_home() / "data")


def make_skill_ledger(log_store: LogStore | None = None) -> SkillLedger:
    if log_store is None:
        log_store = make_log_store()
    return SkillLedger(log_store.store.root)


def bridge_env_vars() -> None:
    """Set LOG_DB_DSN from DBAY_LOGS_DSN if missing (for dbay-sre-mcp).

    Idempotent — safe to call multiple times.
    """
    if not os.environ.get("LOG_DB_DSN") and os.environ.get("DBAY_LOGS_DSN"):
        os.environ["LOG_DB_DSN"] = os.environ["DBAY_LOGS_DSN"]
```

- [ ] **Step 4.3: Move LLM client**

`packages/hermes-agent-utils/hermes_agent_utils/llm.py`:
```python
"""DeepseekLLMClient — OpenAI-compat HTTP client.

Works against api.deepseek.com or HWC MaaS (set DEEPSEEK_BASE_URL accordingly).
"""
from __future__ import annotations

import os
from typing import Any

import httpx


class DeepseekLLMClient:
    """Thin OpenAI-compatible client for Deepseek / HWC MaaS."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str = "deepseek-chat",
        timeout: float = 120.0,
    ) -> None:
        self._api_key = api_key or os.environ["DEEPSEEK_API_KEY"]
        self._base_url = (
            base_url or os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
        ).rstrip("/")
        self._model = model
        self._timeout = timeout

    def complete(
        self, *, system: str, user: str, tools: list[dict] | None = None
    ) -> dict:
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        if tools:
            payload["tools"] = tools

        with httpx.Client(timeout=self._timeout) as client:
            resp = client.post(
                f"{self._base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self._api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
        resp.raise_for_status()
        data = resp.json()
        choice = data["choices"][0]
        text = choice.get("message", {}).get("content") or ""
        usage = data.get("usage", {})
        return {
            "text": text,
            "model": data.get("model", self._model),
            "tokens_in": usage.get("prompt_tokens"),
            "tokens_out": usage.get("completion_tokens"),
            "cost_usd": None,
        }
```

- [ ] **Step 4.4: Move Feishu DM helper**

`packages/hermes-agent-utils/hermes_agent_utils/feishu.py`:
```python
"""Feishu outbound DM via REST API.

Both agents push messages to Jacky from cron tasks; nobody listens to inbound.
"""
from __future__ import annotations

import json
import os

import httpx


def _app_access_token() -> str:
    app_id = os.environ["FEISHU_APP_ID"]
    app_secret = os.environ["FEISHU_APP_SECRET"]
    resp = httpx.post(
        "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal",
        json={"app_id": app_id, "app_secret": app_secret},
        timeout=10.0,
    )
    resp.raise_for_status()
    return resp.json()["app_access_token"]


def feishu_send_dm(open_id: str, text: str) -> None:
    """Send a plain-text DM to a feishu user by open_id."""
    token = _app_access_token()
    resp = httpx.post(
        "https://open.feishu.cn/open-apis/im/v1/messages",
        params={"receive_id_type": "open_id"},
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        json={
            "receive_id": open_id,
            "msg_type": "text",
            "content": json.dumps({"text": text}),
        },
        timeout=15.0,
    )
    resp.raise_for_status()


def jacky_open_id() -> str | None:
    """First open_id from FEISHU_ALLOWED_USERS env (comma-separated)."""
    users = os.environ.get("FEISHU_ALLOWED_USERS", "")
    parts = [u.strip() for u in users.split(",") if u.strip()]
    return parts[0] if parts else None
```

- [ ] **Step 4.5: Move runner / cron / subprocess helpers**

`packages/hermes-agent-utils/hermes_agent_utils/runner.py`:
```python
"""Main-loop primitives shared by SRE and reading services."""
from __future__ import annotations

import logging
import os
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from typing import Callable

from croniter import croniter


_log = logging.getLogger("hermes_agent_utils.runner")


_CHILD_PROCS: list[subprocess.Popen] = []


def start_subprocess(cmd: list[str], label: str) -> subprocess.Popen:
    """Launch a child subprocess in the background, tracked for cleanup on shutdown."""
    _log.info("[runner] starting %s: %s", label, " ".join(cmd))
    env = {**os.environ, "PYTHONUNBUFFERED": "1"}
    proc = subprocess.Popen(cmd, env=env)
    _CHILD_PROCS.append(proc)
    return proc


def shutdown_children(signum: int, frame: object) -> None:
    """SIGTERM/SIGINT handler — terminate tracked subprocesses then exit."""
    _log.info("[runner] signal %s received — shutting down children", signum)
    for proc in _CHILD_PROCS:
        try:
            proc.terminate()
        except Exception:
            pass
    sys.exit(0)


def install_signal_handlers() -> None:
    signal.signal(signal.SIGTERM, shutdown_children)
    signal.signal(signal.SIGINT, shutdown_children)


def cron_loop(tasks: list[tuple[str, Callable[[], None]]]) -> None:
    """Block forever, running tasks on schedule.

    tasks: list of (cron_expr_in_UTC, callable). Cron expressions are evaluated
    against UTC time; convert wall-clock requirements (e.g. 22:00 Asia/Shanghai
    → 14:00 UTC) at the call site.
    """
    iters = {expr: croniter(expr, datetime.now(timezone.utc)) for expr, _ in tasks}
    next_runs = {expr: iters[expr].get_next(datetime) for expr, _ in tasks}

    _log.info("[cron] loop started with %d task(s)", len(tasks))

    while True:
        now = datetime.now(timezone.utc)
        for expr, task in tasks:
            if now >= next_runs[expr]:
                _log.info("[cron] firing %s → %s", expr, task.__name__)
                try:
                    task()
                except Exception as exc:
                    _log.exception("[cron] task %s raised: %s", task.__name__, exc)
                next_runs[expr] = iters[expr].get_next(datetime)

        soonest = min(next_runs.values())
        sleep_secs = max(
            0.0,
            min(60.0, (soonest - datetime.now(timezone.utc)).total_seconds()),
        )
        time.sleep(sleep_secs)
```

- [ ] **Step 4.6: Create CLI entry point (replaces sre-agent/scripts/sync_loop.py)**

`packages/hermes-agent-utils/hermes_agent_utils/cli.py`:
```python
"""hermes-agent-utils CLI: `python -m hermes_agent_utils.cli sync`

Subcommands:
  sync — long-running OBS sync loop.

Reads HERMES_HOME, OBS_ACCESS_KEY, OBS_SECRET_KEY, OBS_ENDPOINT, OBS_BUCKET,
optional OBS_PREFIX (default "agent-log/") and OBS_SYNC_INTERVAL_SEC (default 60).
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
import time

from agent_session_log import LogStore
from agent_session_log.obs_sync import HuaweiObsAdapter, ObsSync

from hermes_agent_utils.factory import hermes_home


_log = logging.getLogger("hermes_agent_utils.cli")


def cmd_sync(_args: argparse.Namespace) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    root = hermes_home() / "data"
    log_store = LogStore(root)
    adapter = HuaweiObsAdapter(
        access_key=os.environ["OBS_ACCESS_KEY"],
        secret_key=os.environ["OBS_SECRET_KEY"],
        endpoint=os.environ["OBS_ENDPOINT"],
    )
    sync = ObsSync(
        log_store.store,
        client=adapter,
        bucket=os.environ["OBS_BUCKET"],
        prefix=os.environ.get("OBS_PREFIX", "agent-log/"),
    )
    interval = int(os.environ.get("OBS_SYNC_INTERVAL_SEC", "60"))
    _log.info(
        "obs_sync: starting, root=%s, bucket=%s, prefix=%s, interval=%ds",
        root, sync._bucket, sync._prefix, interval,
    )
    while True:
        try:
            uploaded = sync.upload_pending(limit=20)
            if uploaded:
                _log.info("obs_sync: uploaded %d sessions", len(uploaded))
        except Exception as exc:
            _log.error("obs_sync: loop error: %s", exc)
        time.sleep(interval)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="hermes-agent-utils")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_sync = sub.add_parser("sync", help="Run OBS sync loop (long-running).")
    p_sync.set_defaults(func=cmd_sync)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4.7: Public exports in `__init__.py`**

`packages/hermes-agent-utils/hermes_agent_utils/__init__.py`:
```python
"""Public API for hermes-agent-utils."""
from hermes_agent_utils.feishu import feishu_send_dm, jacky_open_id
from hermes_agent_utils.factory import (
    bridge_env_vars,
    hermes_config_path,
    hermes_home,
    make_log_store,
    make_skill_ledger,
)
from hermes_agent_utils.llm import DeepseekLLMClient
from hermes_agent_utils.runner import (
    cron_loop,
    install_signal_handlers,
    shutdown_children,
    start_subprocess,
)

__all__ = [
    "DeepseekLLMClient",
    "bridge_env_vars",
    "cron_loop",
    "feishu_send_dm",
    "hermes_config_path",
    "hermes_home",
    "install_signal_handlers",
    "jacky_open_id",
    "make_log_store",
    "make_skill_ledger",
    "shutdown_children",
    "start_subprocess",
]
__version__ = "0.1.0"
```

- [ ] **Step 4.8: Tests for hermes-agent-utils**

`packages/hermes-agent-utils/tests/conftest.py`:
```python
import pytest


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    """Prevent tests from accidentally hitting real services."""
    for key in ("DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL",
                "FEISHU_APP_ID", "FEISHU_APP_SECRET", "FEISHU_ALLOWED_USERS",
                "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET", "OBS_ENDPOINT",
                "DBAY_LOGS_DSN", "LOG_DB_DSN", "HERMES_HOME"):
        monkeypatch.delenv(key, raising=False)
```

`packages/hermes-agent-utils/tests/test_factory.py`:
```python
from pathlib import Path

from hermes_agent_utils.factory import (
    bridge_env_vars,
    hermes_home,
    make_log_store,
    make_skill_ledger,
)


def test_hermes_home_default(monkeypatch):
    monkeypatch.delenv("HERMES_HOME", raising=False)
    assert hermes_home() == Path.home() / ".hermes"


def test_hermes_home_env(monkeypatch, tmp_path):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    assert hermes_home() == tmp_path


def test_make_log_store(monkeypatch, tmp_path):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    log = make_log_store()
    s = log.new_session(type="incident", trigger={}, tags=[])
    assert s.id


def test_make_skill_ledger(monkeypatch, tmp_path):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    ledger = make_skill_ledger()
    ledger.record_invocation("x", version="v1", session_id="s1",
                             triggered_at="2026-04-24T00:00:00Z")
    stats = ledger.stats("x")
    assert stats["total_invocations"] == 1


def test_bridge_env_vars_sets_log_db_dsn(monkeypatch):
    monkeypatch.setenv("DBAY_LOGS_DSN", "postgresql://x")
    monkeypatch.delenv("LOG_DB_DSN", raising=False)
    bridge_env_vars()
    import os
    assert os.environ["LOG_DB_DSN"] == "postgresql://x"


def test_bridge_env_vars_no_overwrite(monkeypatch):
    monkeypatch.setenv("LOG_DB_DSN", "existing")
    monkeypatch.setenv("DBAY_LOGS_DSN", "other")
    bridge_env_vars()
    import os
    assert os.environ["LOG_DB_DSN"] == "existing"
```

`packages/hermes-agent-utils/tests/test_llm.py`:
```python
import pytest

from hermes_agent_utils.llm import DeepseekLLMClient


def test_init_requires_api_key(monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    with pytest.raises(KeyError):
        DeepseekLLMClient()


def test_init_with_explicit_api_key():
    client = DeepseekLLMClient(api_key="test-key")
    assert client._api_key == "test-key"
    assert client._model == "deepseek-chat"
    assert client._base_url == "https://api.deepseek.com"


def test_init_strips_trailing_slash():
    client = DeepseekLLMClient(api_key="k", base_url="https://maas.example/v1/")
    assert client._base_url == "https://maas.example/v1"


def test_complete_returns_shape(monkeypatch):
    """Mock httpx and verify the dict shape we return."""
    import httpx

    class FakeClient:
        def __init__(self, *a, **kw): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def post(self, url, headers=None, json=None):
            class R:
                def raise_for_status(self): pass
                def json(self):
                    return {
                        "choices": [{"message": {"content": "hello"}}],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 5},
                        "model": "deepseek-chat",
                    }
            return R()

    monkeypatch.setattr(httpx, "Client", FakeClient)
    client = DeepseekLLMClient(api_key="k")
    out = client.complete(system="s", user="u")
    assert out["text"] == "hello"
    assert out["tokens_in"] == 10
    assert out["tokens_out"] == 5
    assert out["model"] == "deepseek-chat"
    assert out["cost_usd"] is None
```

`packages/hermes-agent-utils/tests/test_feishu.py`:
```python
from hermes_agent_utils.feishu import jacky_open_id


def test_jacky_open_id_unset(monkeypatch):
    monkeypatch.delenv("FEISHU_ALLOWED_USERS", raising=False)
    assert jacky_open_id() is None


def test_jacky_open_id_first(monkeypatch):
    monkeypatch.setenv("FEISHU_ALLOWED_USERS", "ou_alice,ou_bob")
    assert jacky_open_id() == "ou_alice"


def test_jacky_open_id_strips_whitespace(monkeypatch):
    monkeypatch.setenv("FEISHU_ALLOWED_USERS", "  ou_jacky  ,  ou_other ")
    assert jacky_open_id() == "ou_jacky"


def test_jacky_open_id_only_whitespace(monkeypatch):
    monkeypatch.setenv("FEISHU_ALLOWED_USERS", "  ,  ")
    assert jacky_open_id() is None
```

`packages/hermes-agent-utils/tests/test_runner.py`:
```python
from hermes_agent_utils.runner import _CHILD_PROCS, cron_loop, start_subprocess


def test_start_subprocess_tracks_for_cleanup(monkeypatch):
    """start_subprocess appends to _CHILD_PROCS so shutdown_children can terminate it."""
    import subprocess

    class FakePopen:
        def __init__(self, cmd, env=None): self.cmd = cmd
        def terminate(self): pass

    monkeypatch.setattr(subprocess, "Popen", FakePopen)
    _CHILD_PROCS.clear()
    proc = start_subprocess(["echo", "hi"], "test")
    assert proc in _CHILD_PROCS


def test_cron_loop_signature():
    """Smoke: cron_loop accepts list of (str, callable) tuples."""
    import inspect
    sig = inspect.signature(cron_loop)
    assert list(sig.parameters) == ["tasks"]
```

- [ ] **Step 4.9: Sync workspace and run hermes-agent-utils tests**

```bash
cd /Users/jacky/code/lakeon
uv sync
.venv/bin/pytest packages/hermes-agent-utils/tests/ -v
```

Expected: ≥ 15 tests pass.

- [ ] **Step 4.10: Commit**

```bash
cd /Users/jacky/code/lakeon
git add packages/hermes-agent-utils/
git commit -m "feat(packages): extract hermes-agent-utils as workspace member"
```

---

### Task 5: Slim down sre-agent/main.py to use hermes-agent-utils

**Why:** sre-agent/main.py currently ~400 lines includes `DeepseekLLMClient`, `feishu_send_dm`, `_make_log_store`, `_start_subprocess`, `cron_loop`, env bridging. After Task 4 those live in hermes-agent-utils. This task replaces the in-file copies with imports and deletes the now-dead code.

**Files:**
- Modify: `lakeon/sre-agent/main.py` (delete code that moved; import from hermes_agent_utils)
- Delete: `lakeon/sre-agent/scripts/sync_loop.py`
- Delete: `lakeon/sre-agent/main.py`'s `run_daily_reflection` function and the `("0 14 * * *", run_daily_reflection)` cron entry — that responsibility moves to reading-companion in Task 7. **Delete it now** so SRE doesn't double-fire it after reading-companion deploys.
- Modify: `lakeon/sre-agent/tests/test_main.py` — assertions about `_CRON_TASKS` length should now expect 2 entries (cold-start, outcome-checker), not 3.

- [ ] **Step 5.1: Re-enable hermes-agent-utils dep in sre-agent/pyproject.toml**

If you commented it out in Task 3, re-enable. The full `dependencies` block:

```toml
dependencies = [
  "pyyaml>=6.0",
  "httpx>=0.27",
  "croniter>=2.0",
  "esdk-obs-python>=3.24",
  "agent-session-log",
  "hermes-agent-utils",
]
```

And keep `[tool.uv.sources]` with both workspace entries.

- [ ] **Step 5.2: Replace `sre-agent/main.py`**

Full new content (~150 lines):

```python
"""sre-agent main.py — SRE agent runtime entry point.

Cron tasks:
  - */2 * * * *  → cold_start_watcher
  - 0 9 * * *    → outcome_checker

(daily_reflection moved to reading-companion service in B2 refactor.)

Subprocesses managed:
  - obs sync loop (`python -m hermes_agent_utils.cli sync`)
  - hermes gateway (`hermes gateway run`) — for inbound feishu messages

Shared helpers (LLM, feishu DM, factory, cron loop) come from `hermes-agent-utils`.
"""
from __future__ import annotations

import json
import logging
import os
import shutil
import sys
from pathlib import Path
from typing import Any

from hermes_agent_utils import (
    DeepseekLLMClient,
    bridge_env_vars,
    cron_loop,
    feishu_send_dm,
    hermes_config_path,
    hermes_home,
    install_signal_handlers,
    jacky_open_id,
    make_log_store,
    make_skill_ledger,
    start_subprocess,
)


_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))

bridge_env_vars()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-7s  %(name)s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("sre-agent")


# ─── MCP adapter (SRE-specific) ───────────────────────────────────────────────

class SREMCPAdapter:
    """Thin adapter over dbay_sre_mcp server functions."""

    def log_search(
        self,
        *,
        component: str = "",
        keyword: str = "",
        since: str = "1h",
        limit: int = 100,
        tenant_id: str = "",
        db_id: str = "",
        **_kwargs: Any,
    ) -> list[dict]:
        from dbay_sre_mcp.server import log_search as _log_search
        raw = _log_search(
            component=component, keyword=keyword, since=since, limit=limit,
            tenant_id=tenant_id, db_id=db_id,
        )
        return json.loads(raw)

    def log_trace(self, request_id: str) -> list[dict]:
        from dbay_sre_mcp.server import log_trace as _log_trace
        return json.loads(_log_trace(request_id))

    def log_stats(self, *, since: str = "24h") -> dict:
        from dbay_sre_mcp.server import log_stats as _log_stats
        return json.loads(_log_stats(since))


# ─── cron tasks ───────────────────────────────────────────────────────────────

def run_cold_start_watcher() -> None:
    """*/2 * * * * cron task."""
    from skills.sre.cold_start_watcher.watcher import Watcher
    from skills.sre.cold_start_watcher.diagnose import diagnose

    log.info("[watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    watcher = Watcher(log=log_store, mcp=mcp)
    try:
        session_ids = watcher.scan_once()
    except Exception as exc:
        log.error("[watcher] scan_once failed: %s", exc)
        return

    if not session_ids:
        log.info("[watcher] no new cold-start incidents")
        return

    log.info("[watcher] opened %d incident session(s): %s",
             len(session_ids), session_ids)
    llm = DeepseekLLMClient()
    for sid in session_ids:
        log.info("[watcher] diagnosing session %s", sid)
        try:
            session = log_store.get_session(sid)
            diagnose(session, llm=llm, mcp=mcp)
            log.info("[watcher] diagnosis complete for %s", sid)
            open_id = jacky_open_id()
            if open_id:
                try:
                    feishu_send_dm(
                        open_id,
                        f"[SRE] 冷启动告警已诊断, session={sid}\n"
                        f"请查看 {hermes_home()}/data/{sid}/conclusion.md",
                    )
                except Exception as dm_exc:
                    log.warning("[watcher] feishu DM failed for %s: %s", sid, dm_exc)
        except Exception as exc:
            log.error("[watcher] diagnosis failed for session %s: %s", sid, exc)


def run_outcome_checker() -> None:
    """0 9 * * * cron task."""
    from skills.sre.outcome_checker.checker import OutcomeChecker

    log.info("[outcome_checker] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    ledger = make_skill_ledger(log_store)
    checker = OutcomeChecker(log=log_store, mcp=mcp, ledger=ledger)
    try:
        updated = checker.scan_once()
    except Exception as exc:
        log.error("[outcome_checker] scan_once failed: %s", exc)
        return

    log.info("[outcome_checker] updated %d session(s)", len(updated))

    open_id = jacky_open_id()
    if not open_id:
        return
    for sid in updated:
        try:
            outcome_path = log_store.store.root / sid / "outcome.md"
            if outcome_path.exists():
                text = outcome_path.read_text()
                if "did_work: false" in text.lower() or "did_work: no" in text.lower():
                    feishu_send_dm(open_id, f"[SRE] 建议未生效, 请看 {sid}")
        except Exception as exc:
            log.warning("[outcome_checker] feishu DM failed for %s: %s", sid, exc)


_CRON_TASKS = [
    ("*/2 * * * *", run_cold_start_watcher),
    ("0 9 * * *",   run_outcome_checker),
]


# ─── entrypoint ───────────────────────────────────────────────────────────────

def main() -> None:
    install_signal_handlers()

    # OBS sync loop — replaces the old scripts/sync_loop.py
    start_subprocess(
        [sys.executable, "-m", "hermes_agent_utils.cli", "sync"],
        "obs_sync_loop",
    )

    # Hermes gateway (feishu bidi). Seed config + skills into HERMES_HOME.
    hermes_config_src = hermes_config_path()
    home = hermes_home()
    home.mkdir(parents=True, exist_ok=True)
    hermes_config_dst = home / "config.yaml"
    if Path(hermes_config_src).exists():
        shutil.copy2(hermes_config_src, hermes_config_dst)
        log.info("[main] seeded hermes config → %s", hermes_config_dst)

    skills_src = _HERE / "skills"
    skills_dst = home / "skills"
    if skills_src.exists():
        if skills_dst.exists():
            shutil.rmtree(skills_dst)
        shutil.copytree(skills_src, skills_dst)
        log.info("[main] seeded hermes skills → %s", skills_dst)

    start_subprocess(["hermes", "gateway", "run"], "hermes_gateway")

    # Block forever in cron loop.
    cron_loop(_CRON_TASKS)


if __name__ == "__main__":
    main()
```

- [ ] **Step 5.3: Delete `sre-agent/scripts/sync_loop.py`**

```bash
cd /Users/jacky/code/lakeon
git rm sre-agent/scripts/sync_loop.py
```

- [ ] **Step 5.4: Update `sre-agent/tests/test_main.py`**

The existing test asserts `_CRON_TASKS` has 3 entries. Update to 2:

Read current file:
```bash
cat /Users/jacky/code/lakeon/sre-agent/tests/test_main.py
```

Find the assertion `assert len(main._CRON_TASKS) == 3` (or similar) and change to `== 2`. Also check for assertions about `run_daily_reflection` being in `_CRON_TASKS` — remove those.

If `test_sre_mcp_adapter_decodes_log_search` is in the file and fails because `dbay_sre_mcp` isn't installed locally, leave that pre-existing failure as-is.

- [ ] **Step 5.5: Run sre-agent test suite**

```bash
cd /Users/jacky/code/lakeon
.venv/bin/pytest sre-agent/tests/ -v --ignore=sre-agent/tests/integration/test_url_handler.py \
                                      --ignore=sre-agent/tests/integration/test_query_handler.py \
                                      --ignore=sre-agent/tests/integration/test_daily_reflection.py \
                                      --ignore=sre-agent/tests/integration/test_phase_0b_cross_consumer.py
```

Note: the reading-related integration tests will move out in Task 7. For now we skip them so the test run is clean. Alternative: leave them in place; they should still pass because `from skills.reading.url_handler.handler import handle_url` still works (skills/reading/ still exists in sre-agent until Task 7 deletes it).

Expected: SRE tests + cross-consumer test pass; 1 pre-existing dbay-sre-mcp failure unrelated.

- [ ] **Step 5.6: Local Docker build smoke (sre-agent)**

```bash
cd /Users/jacky/code/lakeon
docker build -f sre-agent/Dockerfile -t sre-agent:b2-task5 .
```

Expected: build succeeds.

- [ ] **Step 5.7: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/main.py sre-agent/tests/test_main.py
git rm sre-agent/scripts/sync_loop.py
git commit -m "refactor(sre-agent): consume hermes-agent-utils; slim main.py to SRE-specific code"
```

---

## Group D: Build `reading-companion` Service

### Task 6: Create `reading-companion/` skeleton

**Files:**
- Create: `lakeon/reading-companion/pyproject.toml`
- Create: `lakeon/reading-companion/README.md`
- Create: `lakeon/reading-companion/.python-version` (`3.11`)
- Create: `lakeon/reading-companion/.env.example`
- Create: `lakeon/reading-companion/scripts/verify_env.py`
- Create: `lakeon/reading-companion/skills/__init__.py` (empty)
- Create: `lakeon/reading-companion/tests/__init__.py` (empty)
- Create: `lakeon/reading-companion/tests/conftest.py`

- [ ] **Step 6.1: Create directory structure**

```bash
mkdir -p /Users/jacky/code/lakeon/reading-companion/{scripts,skills,tests/integration,hermes_config}
touch /Users/jacky/code/lakeon/reading-companion/skills/__init__.py
touch /Users/jacky/code/lakeon/reading-companion/tests/__init__.py
touch /Users/jacky/code/lakeon/reading-companion/tests/integration/__init__.py
echo "3.11" > /Users/jacky/code/lakeon/reading-companion/.python-version
```

- [ ] **Step 6.2: Create `pyproject.toml`**

```toml
[project]
name = "reading-companion"
version = "0.0.1"
description = "dbay reading companion — fetch URLs, distill via LLM, link to past readings, daily reflect."
requires-python = ">=3.11"
dependencies = [
  "httpx>=0.27",
  "trafilatura>=1.6",
  "agent-session-log",
  "hermes-agent-utils",
]

[project.optional-dependencies]
dev = [
  "pytest>=8.0",
  "pytest-cov>=5.0",
  "ruff>=0.5",
]

[tool.uv.sources]
agent-session-log = { workspace = true }
hermes-agent-utils = { workspace = true }

[tool.pytest.ini_options]
testpaths = ["tests"]

[tool.ruff]
line-length = 100
target-version = "py311"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["skills"]
```

- [ ] **Step 6.3: Create `README.md`**

```markdown
# reading-companion

Independent Railway service for the dbay reading-companion agent. Cron-triggered
daily reflection (22:00 Asia/Shanghai = 14:00 UTC). URL ingestion + recall query
via CLI: `python -m skills.reading.url_handler.cli --url <url>` and
`python -m skills.reading.query_handler.cli "<question>"`.

Uses `agent-session-log` (workspace package) as the data layer and
`hermes-agent-utils` (workspace package) for LLM/feishu/cron primitives.

Does NOT run a hermes gateway — push-only feishu, no inbound listening.

Env vars (see .env.example): DEEPSEEK_API_KEY, DEEPSEEK_BASE_URL,
FEISHU_APP_ID, FEISHU_APP_SECRET, FEISHU_ALLOWED_USERS,
OBS_ACCESS_KEY, OBS_SECRET_KEY, OBS_BUCKET, OBS_ENDPOINT, OBS_PREFIX,
HERMES_HOME.
```

- [ ] **Step 6.4: Create `.env.example`**

```
# LLM
DEEPSEEK_API_KEY=sk-xxxxx
DEEPSEEK_BASE_URL=https://api.deepseek.com

# Feishu (separate bot from sre-agent — register a new self-built app)
FEISHU_APP_ID=cli_xxxxx
FEISHU_APP_SECRET=xxxxx
FEISHU_ALLOWED_USERS=ou_jackys_open_id

# OBS — shared bucket with sre-agent, distinct prefix
OBS_ACCESS_KEY=xxxxx
OBS_SECRET_KEY=xxxxx
OBS_BUCKET=dbay-agent-log
OBS_ENDPOINT=obs.cn-north-4.myhuaweicloud.com
OBS_PREFIX=agent-log/reading/

# Data dir (Railway Volume mount target)
HERMES_HOME=/data/hermes
```

- [ ] **Step 6.5: Create `scripts/verify_env.py`**

```python
"""Pre-flight env check for reading-companion."""
import os
import sys


REQUIRED = [
    "DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL",
    "FEISHU_APP_ID", "FEISHU_APP_SECRET", "FEISHU_ALLOWED_USERS",
    "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET", "OBS_ENDPOINT",
]
OPTIONAL = ["OBS_PREFIX", "HERMES_HOME"]


def main() -> int:
    missing = [k for k in REQUIRED if not os.environ.get(k)]
    if missing:
        print("MISSING required env vars:")
        for k in missing:
            print(f"  - {k}")
        return 1
    print(f"OK — all {len(REQUIRED)} required env vars set")
    for k in OPTIONAL:
        v = os.environ.get(k)
        print(f"  optional {k} = {v if v else '(unset, using default)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 6.6: Create `tests/conftest.py`**

```python
from pathlib import Path

import pytest


@pytest.fixture
def tmp_log_root(tmp_path: Path) -> Path:
    """Isolated agent_session_log root for a single test."""
    root = tmp_path / "hermes_data"
    root.mkdir()
    return root


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    """Prevent tests from accidentally hitting real services via env."""
    for key in ("DEEPSEEK_API_KEY", "FEISHU_APP_ID", "FEISHU_APP_SECRET",
                "FEISHU_ALLOWED_USERS", "OBS_ACCESS_KEY", "OBS_SECRET_KEY",
                "OBS_BUCKET", "OBS_ENDPOINT"):
        monkeypatch.delenv(key, raising=False)
```

- [ ] **Step 6.7: Sync workspace and verify reading-companion is recognized**

```bash
cd /Users/jacky/code/lakeon
uv sync
.venv/bin/python -c "import skills" 2>&1 || echo "skills not yet on path — expected, Task 7 fixes"
```

Expected: `uv sync` succeeds; the `import skills` test errors because `skills/` is empty until Task 7 — that's expected.

- [ ] **Step 6.8: Commit**

```bash
cd /Users/jacky/code/lakeon
git add reading-companion/
git commit -m "feat(reading-companion): scaffold service skeleton"
```

---

### Task 7: Move reading skills + tests from sre-agent to reading-companion

**Files:**
- Move (5 dirs): `lakeon/sre-agent/skills/reading/{__init__.py,SKILL.md,url_handler/,query_handler/,daily_reflection/}` → `lakeon/reading-companion/skills/reading/`
- Move (3 test files): `lakeon/sre-agent/tests/integration/test_{url_handler,query_handler,daily_reflection}.py` → `lakeon/reading-companion/tests/integration/`
- Delete: `lakeon/sre-agent/tests/integration/test_phase_0b_cross_consumer.py` (no longer applicable — two services don't share an in-process LogStore; isolation now enforced at the file system level via OBS prefix and separate Volume)
- Modify: `lakeon/sre-agent/tests/test_import_discipline.py` — remove the three `reading_skills_*` tests added in Phase 0b Task 9 (no reading code in sre-agent anymore).

- [ ] **Step 7.1: Move reading skill code**

```bash
cd /Users/jacky/code/lakeon
git mv sre-agent/skills/reading/__init__.py reading-companion/skills/reading/__init__.py 2>/dev/null || (mkdir -p reading-companion/skills/reading && git mv sre-agent/skills/reading/__init__.py reading-companion/skills/reading/__init__.py)
git mv sre-agent/skills/reading/SKILL.md reading-companion/skills/reading/SKILL.md
git mv sre-agent/skills/reading/url_handler reading-companion/skills/reading/url_handler
git mv sre-agent/skills/reading/query_handler reading-companion/skills/reading/query_handler
git mv sre-agent/skills/reading/daily_reflection reading-companion/skills/reading/daily_reflection
```

(If `sre-agent/skills/reading/` is now empty, `rmdir` it implicitly via git.)

- [ ] **Step 7.2: Move reading integration tests**

```bash
cd /Users/jacky/code/lakeon
git mv sre-agent/tests/integration/test_url_handler.py reading-companion/tests/integration/test_url_handler.py
git mv sre-agent/tests/integration/test_query_handler.py reading-companion/tests/integration/test_query_handler.py
git mv sre-agent/tests/integration/test_daily_reflection.py reading-companion/tests/integration/test_daily_reflection.py
git rm sre-agent/tests/integration/test_phase_0b_cross_consumer.py
```

- [ ] **Step 7.3: Update `_HERE.parents[N]` paths in CLI files**

The `cli.py` files in `url_handler/` and `query_handler/` did `_HERE.parents[2]` to reach `sre-agent/`. After move:
- `reading-companion/skills/reading/url_handler/cli.py` — its `_HERE` is `…/url_handler/`, `parents[0] = reading/`, `parents[1] = skills/`, `parents[2] = reading-companion/`. Same depth — no change needed.

But the lazy import `from main import DeepseekLLMClient` and `from main import feishu_send_dm` in both CLI files will fail because reading-companion's `main.py` doesn't expose those names directly anymore (they live in `hermes_agent_utils`).

Edit `reading-companion/skills/reading/url_handler/cli.py`:
- Change `from main import DeepseekLLMClient` → `from hermes_agent_utils import DeepseekLLMClient`
- Change `from main import feishu_send_dm` → `from hermes_agent_utils import feishu_send_dm`

Same change in `reading-companion/skills/reading/query_handler/cli.py`.

The `sys.path.insert(0, str(_HERE.parents[2]))` line can stay — it adds `reading-companion/` to the path so `import skills.reading…` resolves; harmless that it's also where `main.py` lives.

- [ ] **Step 7.4: Update `sre-agent/tests/test_import_discipline.py`**

Open the file. Find the three tests added in Phase 0b Task 9:
- `test_reading_skills_do_not_import_sre_skills`
- `test_sre_skills_do_not_import_reading_skills`
- `test_reading_skills_use_only_public_agent_session_log_api`

Delete them — there's no `skills/reading/` in sre-agent anymore. The remaining test (the original Phase 0a `test_no_forbidden_imports`) stays; but note it now scans the empty space where `agent_session_log/` used to be. The plan moved `agent_session_log/` out in Task 2, so this Phase 0a test should now look for the package via Python import, not file path. Update it to:

```python
def test_no_forbidden_imports():
    """sre-agent code (skills/sre/, main.py) must not import lakeon internals or hermes runtime privates."""
    import ast
    from pathlib import Path

    roots = [
        Path(__file__).resolve().parents[1] / "skills" / "sre",
    ]
    main_py = Path(__file__).resolve().parents[1] / "main.py"

    forbidden = ("lakeon", "hermes")  # we use hermes runtime via subprocess, not import
    violations: list[str] = []

    def _scan(py: Path):
        text = py.read_text(encoding="utf-8")
        tree = ast.parse(text)
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    if any(alias.name == p or alias.name.startswith(p + ".") for p in forbidden):
                        violations.append(f"{py}: import {alias.name}")
            elif isinstance(node, ast.ImportFrom) and node.module:
                if any(node.module == p or node.module.startswith(p + ".") for p in forbidden):
                    violations.append(f"{py}: from {node.module}")

    for root in roots:
        for py in root.rglob("*.py"):
            _scan(py)
    if main_py.exists():
        _scan(main_py)

    assert not violations, "\n".join(violations)
```

- [ ] **Step 7.5: Create `reading-companion/tests/test_import_discipline.py`**

```python
"""Reading-companion code must not import sre-agent code (no shared physical files)
and must use only public agent_session_log API.
"""
import ast
from pathlib import Path


def test_reading_skills_do_not_import_sre_skills():
    reading_root = Path(__file__).resolve().parents[1] / "skills" / "reading"
    violations = []
    for py in reading_root.rglob("*.py"):
        text = py.read_text(encoding="utf-8")
        for needle in ("skills.sre", "from skills.sre", "sre.cold_start", "sre.outcome"):
            if needle in text:
                violations.append(f"{py}: {needle}")
    assert not violations, "\n".join(violations)


def test_reading_skills_use_only_public_agent_session_log_api():
    reading_root = Path(__file__).resolve().parents[1] / "skills" / "reading"
    violations = []
    for py in reading_root.rglob("*.py"):
        tree = ast.parse(py.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module:
                if node.module.startswith("agent_session_log."):
                    violations.append(f"{py}: from {node.module} (private submodule)")
    assert not violations, "\n".join(violations)
```

- [ ] **Step 7.6: Run reading-companion tests**

```bash
cd /Users/jacky/code/lakeon
uv sync
.venv/bin/pytest reading-companion/tests/ -v
```

Expected: 39+ tests pass (the 3 reading integration test files + the new import discipline tests). Some tests may need fixture path adjustment — investigate any failures and fix in-place.

- [ ] **Step 7.7: Run sre-agent tests (regression)**

```bash
cd /Users/jacky/code/lakeon
.venv/bin/pytest sre-agent/tests/ -v
```

Expected: SRE-only tests pass. (1 pre-existing dbay_sre_mcp failure OK.)

- [ ] **Step 7.8: Commit**

```bash
cd /Users/jacky/code/lakeon
git add reading-companion/skills reading-companion/tests sre-agent/tests/test_import_discipline.py
git commit -m "refactor: move reading skills + tests from sre-agent to reading-companion service"
```

---

### Task 8: Implement reading-companion `main.py` + entrypoint

**Files:**
- Create: `lakeon/reading-companion/main.py`
- Create: `lakeon/reading-companion/entrypoint.sh`
- Create: `lakeon/reading-companion/tests/test_main.py`

- [ ] **Step 8.1: Write failing test for main**

`reading-companion/tests/test_main.py`:
```python
"""Smoke test for reading-companion main module."""


def test_cron_tasks_only_daily_reflection():
    import main
    exprs = [expr for expr, _ in main._CRON_TASKS]
    assert exprs == ["0 14 * * *"], f"expected only daily_reflection cron, got {exprs}"


def test_run_daily_reflection_callable():
    import main
    assert callable(main.run_daily_reflection)


def test_main_module_has_no_sre_imports():
    """reading-companion/main.py must not import any skills.sre.*"""
    import inspect
    import main
    src = inspect.getsource(main)
    assert "skills.sre" not in src
    assert "from skills.sre" not in src
    assert "dbay_sre_mcp" not in src
    assert "SREMCPAdapter" not in src
```

Run: `cd /Users/jacky/code/lakeon && .venv/bin/pytest reading-companion/tests/test_main.py -v`
Expected: FAIL — `main` doesn't exist.

- [ ] **Step 8.2: Implement `reading-companion/main.py`**

```python
"""reading-companion main.py — Reading agent runtime entry point.

Cron tasks:
  - 0 14 * * *  → daily_reflection (= 22:00 Asia/Shanghai)

Subprocesses managed:
  - obs sync loop (`python -m hermes_agent_utils.cli sync`)

NOT started here (vs. sre-agent):
  - hermes gateway — reading does not need inbound feishu listening; it is
    push-only (DM via REST) plus CLI-triggered URL ingestion + query.

CLI entry points (invoked by user, not by main):
  - python -m skills.reading.url_handler.cli --url <url> [--no-push]
  - python -m skills.reading.query_handler.cli "<question>" [--no-push]

Shared helpers come from `hermes-agent-utils`.
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

from hermes_agent_utils import (
    DeepseekLLMClient,
    cron_loop,
    feishu_send_dm,
    install_signal_handlers,
    jacky_open_id,
    make_log_store,
    start_subprocess,
)


_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-7s  %(name)s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("reading-companion")


# ─── cron tasks ───────────────────────────────────────────────────────────────

def run_daily_reflection() -> None:
    """0 14 * * * UTC = 22:00 Asia/Shanghai cron task."""
    from skills.reading.daily_reflection.reflect import reflect_today

    log.info("[daily_reflection] reflect_today starting")
    log_store = make_log_store()
    llm = DeepseekLLMClient()
    try:
        result = reflect_today(log=log_store, llm=llm)
    except Exception as exc:
        log.error("[daily_reflection] reflect_today failed: %s", exc)
        return

    if result.skipped_reason:
        log.info("[daily_reflection] skipped: %s", result.skipped_reason)
        return

    log.info("[daily_reflection] wrote session %s", result.session_id)

    open_id = jacky_open_id()
    if open_id and result.reflection_text:
        try:
            feishu_send_dm(open_id, f"📖 今日反思\n\n{result.reflection_text}")
            log.info("[daily_reflection] feishu DM sent to %s", open_id)
        except Exception as exc:
            log.warning("[daily_reflection] feishu DM failed: %s", exc)


_CRON_TASKS = [
    ("0 14 * * *", run_daily_reflection),  # 14:00 UTC = 22:00 Asia/Shanghai
]


# ─── entrypoint ───────────────────────────────────────────────────────────────

def main() -> None:
    install_signal_handlers()

    start_subprocess(
        [sys.executable, "-m", "hermes_agent_utils.cli", "sync"],
        "obs_sync_loop",
    )

    cron_loop(_CRON_TASKS)


if __name__ == "__main__":
    main()
```

- [ ] **Step 8.3: Re-run main tests — pass**

```bash
cd /Users/jacky/code/lakeon
.venv/bin/pytest reading-companion/tests/test_main.py -v
```
Expected: 3 passed.

- [ ] **Step 8.4: Create `entrypoint.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "[entrypoint] verifying env..."
python /app/scripts/verify_env.py

mkdir -p "${HERMES_HOME:-/data/hermes}/data"

echo "[entrypoint] launching reading-companion main.py..."
exec python /app/main.py
```

```bash
chmod +x /Users/jacky/code/lakeon/reading-companion/entrypoint.sh
```

- [ ] **Step 8.5: Run full reading-companion suite**

```bash
cd /Users/jacky/code/lakeon
.venv/bin/pytest reading-companion/tests/ -v
```
Expected: all green (≈ 40+ tests).

- [ ] **Step 8.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add reading-companion/main.py reading-companion/entrypoint.sh reading-companion/tests/test_main.py
git commit -m "feat(reading-companion): main.py with daily_reflection cron + entrypoint"
```

---

## Group E: Operational

### Task 9: Reading-companion Dockerfile + railway.toml

**Files:**
- Create: `lakeon/reading-companion/Dockerfile`
- Create: `lakeon/reading-companion/railway.toml`

- [ ] **Step 9.1: Create Dockerfile**

```dockerfile
# reading-companion/Dockerfile
# Build context: lakeon/ repo root (Railway rootDirectory=/).
# Workspace packages (agent-session-log, hermes-agent-utils) installed via path.
#
# Notable absences vs. sre-agent/Dockerfile:
#   - NO hermes-agent install (no inbound feishu / no LLM gateway needed)
#   - NO dbay-sre-mcp install (reading doesn't query dbay logs)
#   - Adds trafilatura for HTML body extraction
FROM python:3.11-slim

# trafilatura needs libxml2 + libxslt for the lxml C extension.
RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl ca-certificates libxml2 libxslt1.1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir uv==0.4.18

# Install workspace packages.
COPY packages /app/packages
RUN uv pip install --system /app/packages/agent-session-log
RUN uv pip install --system /app/packages/hermes-agent-utils

# Install reading-companion itself.
COPY reading-companion/pyproject.toml /app/reading-companion/pyproject.toml
COPY reading-companion/skills /app/reading-companion/skills
RUN uv pip install --system /app/reading-companion

# Runtime assets.
COPY reading-companion/scripts /app/scripts
COPY reading-companion/main.py /app/main.py
COPY reading-companion/entrypoint.sh /app/entrypoint.sh

ENV HERMES_HOME=/data/hermes
ENV PYTHONPATH=/app

RUN chmod +x /app/entrypoint.sh

CMD ["/app/entrypoint.sh"]
```

- [ ] **Step 9.2: Create railway.toml**

```toml
# reading-companion/railway.toml
[build]
builder = "DOCKERFILE"
# Build context is repo root (Railway dashboard → Settings → Source → Root Directory = /).
dockerfilePath = "reading-companion/Dockerfile"
watchPatterns = [
  "reading-companion/**",
  "packages/**",
]

[deploy]
startCommand = "/app/entrypoint.sh"
restartPolicyType = "on_failure"
restartPolicyMaxRetries = 5

# Volume must be provisioned in Railway dashboard (Service → Settings → Volumes,
# mount path /data/hermes). Do NOT share Volume with sre-agent service.
```

- [ ] **Step 9.3: Local Docker build smoke**

```bash
cd /Users/jacky/code/lakeon
docker build -f reading-companion/Dockerfile -t reading-companion:b2 .
```

Expected: succeeds. Image size ≈ 250-350 MB (no hermes-agent, no dbay-sre-mcp).

- [ ] **Step 9.4: Local container smoke (dry-run)**

```bash
docker run --rm -e DEEPSEEK_API_KEY=test -e DEEPSEEK_BASE_URL=https://api.deepseek.com \
                -e FEISHU_APP_ID=test -e FEISHU_APP_SECRET=test \
                -e FEISHU_ALLOWED_USERS=ou_test \
                -e OBS_ACCESS_KEY=test -e OBS_SECRET_KEY=test \
                -e OBS_BUCKET=dbay-agent-log -e OBS_ENDPOINT=obs.cn-north-4.myhuaweicloud.com \
                -e OBS_PREFIX=agent-log/reading/ \
                reading-companion:b2 \
                python /app/scripts/verify_env.py
```

Expected: `OK — all 9 required env vars set`.

- [ ] **Step 9.5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add reading-companion/Dockerfile reading-companion/railway.toml
git commit -m "feat(reading-companion): Dockerfile + railway.toml for independent service"
```

---

### Task 10: Deployment runbook + B2 completion report

**Files:**
- Create: `lakeon/reading-companion/docs/DEPLOY_RUNBOOK.md`
- Create: `lakeon/sre-agent/reports/b2-refactor-report.md`

- [ ] **Step 10.1: Create deployment runbook**

```bash
mkdir -p /Users/jacky/code/lakeon/reading-companion/docs
```

`reading-companion/docs/DEPLOY_RUNBOOK.md`:

```markdown
# reading-companion Deployment Runbook

## Prerequisites

- Railway account with access to dbay project
- Feishu open platform account
- Existing OBS bucket `dbay-agent-log` (shared with sre-agent)

## Step 1 — Register a NEW feishu self-built app

1. Go to https://open.feishu.cn/app → 创建自建应用 → name: "dbay reading companion"
2. After creation, get: app_id, app_secret
3. Application capability: enable 机器人 → fill in bot avatar/name (e.g. "📖 读了什么")
4. Permissions:
   - `im:message:send_as_bot` (send DM as bot)
5. Version & release → 创建版本 → submit → 等管理员审核
6. Add Jacky as a tester so the bot can DM him before public release
7. Note Jacky's open_id under THIS app (different open_id than the SRE bot — open_ids are per-app):
   - On the open platform admin → Application → 通讯录 → search Jacky → copy open_id

## Step 2 — Create Railway service

1. Railway dashboard → dbay project → New Service → Deploy from GitHub repo `lakeon`
2. Settings → Source:
   - Branch: main
   - **Root Directory: `/`** (NOT `/reading-companion` — the workspace needs visibility into `packages/`)
3. Settings → Volumes:
   - Mount path: `/data/hermes`
   - Size: 5GB (reading data is small; ~10 MB / month)
4. Settings → Networking: not needed (no inbound HTTP)
5. Variables:
   ```
   DEEPSEEK_API_KEY=<copy from sre-agent service>
   DEEPSEEK_BASE_URL=<copy from sre-agent service>
   FEISHU_APP_ID=cli_xxx (from Step 1)
   FEISHU_APP_SECRET=xxx (from Step 1)
   FEISHU_ALLOWED_USERS=ou_jackys_open_id_under_reading_bot (from Step 1)
   OBS_ACCESS_KEY=<copy from sre-agent>
   OBS_SECRET_KEY=<copy from sre-agent>
   OBS_BUCKET=dbay-agent-log
   OBS_ENDPOINT=obs.cn-north-4.myhuaweicloud.com
   OBS_PREFIX=agent-log/reading/
   HERMES_HOME=/data/hermes
   ```

## Step 3 — Trigger first deploy

1. Push any commit on main → Railway auto-deploys
2. Watch build logs:
   - `apt-get install` succeeds (libxml2/libxslt1.1)
   - `uv pip install /app/packages/agent-session-log` succeeds
   - `uv pip install /app/packages/hermes-agent-utils` succeeds
   - `uv pip install /app/reading-companion` succeeds
3. Watch deploy logs:
   - `[entrypoint] verifying env...` → `OK — all 9 required env vars set`
   - `[entrypoint] launching reading-companion main.py...`
   - `[runner] starting obs_sync_loop: ...`
   - `[cron] loop started with 1 task(s)`

## Step 4 — Smoke test from local

```bash
# From your laptop
cd /Users/jacky/code/lakeon
.venv/bin/python -m skills.reading.url_handler.cli \
  --url "https://en.wikipedia.org/wiki/Stub_(distributed_computing)" \
  --no-push
```

Set `HERMES_HOME=/tmp/reading_smoke` to avoid polluting production data.

Expected: session created locally; `--no-push` skips feishu DM.

## Step 5 — Production smoke test from Railway shell

```bash
railway run --service reading-companion -- \
  python -m skills.reading.url_handler.cli \
  --url "https://en.wikipedia.org/wiki/Stub_(distributed_computing)"
```

(no `--no-push` this time → DM should arrive on the new bot)

Expected: feishu DM with 📖 card appears within 30s.

## Step 6 — Wait for 22:00 reflection

At 22:00 Asia/Shanghai (= 14:00 UTC), Railway logs should show:
```
[cron] firing 0 14 * * * → run_daily_reflection
[daily_reflection] reflect_today starting
[daily_reflection] wrote session sess_...
[daily_reflection] feishu DM sent to ou_...
```

If you've fed at least one URL today, the DM arrives. Otherwise:
```
[daily_reflection] skipped: no reading sessions in last 24h
```

## Step 7 — Verify OBS isolation

```bash
# OBS bucket should now have two prefixes
obsutil ls obs://dbay-agent-log/agent-log/sre/    # sre-agent's sessions
obsutil ls obs://dbay-agent-log/agent-log/reading/ # reading-companion's sessions
```

Each side should list its own session tar.gz files only.

## Rollback

If reading-companion misbehaves:
1. Railway dashboard → reading-companion service → Pause Deployments
2. Existing data on Volume + OBS is preserved
3. SRE service is unaffected (separate process, separate Volume, only shares OBS bucket)
```

- [ ] **Step 10.2: Create B2 refactor report**

```bash
mkdir -p /Users/jacky/code/lakeon/sre-agent/reports
```

`sre-agent/reports/b2-refactor-report.md`:

```markdown
# B2 Refactor Report — Reading Companion as Independent Service

## Outcome (fill in after deploy + 1 week)

- [ ] sre-agent on Railway redeployed cleanly with new workspace-aware Dockerfile
- [ ] reading-companion service stood up on Railway
- [ ] OBS bucket `dbay-agent-log` shows both `agent-log/sre/` and `agent-log/reading/` prefixes
- [ ] Daily reflection at 22:00 Asia/Shanghai working
- [ ] CLI `python -m skills.reading.url_handler.cli` working in production
- [ ] No data lost in migration (existing SRE sessions on Volume still readable)

## Phase 2 progress (the big point)

- [ ] `agent_session_log` lives at `lakeon/packages/agent-session-log/` with its own pyproject.toml
- [ ] Both consumers (sre-agent + reading-companion) install it via uv workspace path source
- [ ] Zero `agent_session_log.*` API changes during refactor
- [ ] Phase 2 next steps: extract `lakeon/packages/agent-session-log/` to its own git repo,
      add CI, publish to PyPI as `agent-session-log==0.1.0`. Code is already shaped for it.

## Architecture facts post-B2

| | sre-agent | reading-companion |
|---|---|---|
| Railway service | `sre-agent` (existing) | `reading-companion` (new) |
| Volume mount | `/data/hermes` (own) | `/data/hermes` (own) |
| OBS bucket | `dbay-agent-log` (shared) | `dbay-agent-log` (shared) |
| OBS prefix | `agent-log/sre/` | `agent-log/reading/` |
| Feishu bot | original SRE bot | NEW reading bot |
| Image | hermes-agent + dbay-sre-mcp + agent-session-log + hermes-agent-utils | trafilatura + agent-session-log + hermes-agent-utils |
| Crons | `*/2 *` (cold-start), `0 9` (outcome) | `0 14` (daily reflection) |
| Inbound feishu | YES (hermes gateway) | NO (push-only) |

## Code shape

```
lakeon/
├── pyproject.toml                # workspace root
├── packages/
│   ├── agent-session-log/        # the data layer (Phase 2 candidate)
│   └── hermes-agent-utils/       # shared helpers
├── sre-agent/                    # SRE service
└── reading-companion/            # reading service
```

## Surprises / friction during refactor

- ...

## Phase 2 decision point

After 1 week of reading-companion in production, if no API friction surfaced
(check `agent-session-log` git log for hot-fixes), proceed to Phase 2:
1. Initialize `~/code/agent-session-log/` git repo
2. Copy contents of `lakeon/packages/agent-session-log/` over
3. Add GitHub Actions: pytest + ruff + build wheel
4. Publish to PyPI as `agent-session-log==0.1.0`
5. Update `lakeon/packages/agent-session-log/` to be a re-export stub OR delete it,
   change sre-agent + reading-companion deps from `workspace = true` to `>=0.1.0`
```

- [ ] **Step 10.3: Run final full-workspace test sweep**

```bash
cd /Users/jacky/code/lakeon
uv sync
.venv/bin/pytest packages/ sre-agent/ reading-companion/ -v 2>&1 | tail -30
```

Expected: all green except the 1 pre-existing dbay-sre-mcp local-env failure.

- [ ] **Step 10.4: Commit**

```bash
cd /Users/jacky/code/lakeon
git add reading-companion/docs/DEPLOY_RUNBOOK.md sre-agent/reports/b2-refactor-report.md
git commit -m "docs(b2): deployment runbook + refactor report skeleton"
```

---

## Self-Review Checklist

- [x] **Spec coverage**: every B2 component (workspace root, agent-session-log extraction, hermes-agent-utils extraction, sre-agent slimming, reading-companion service, Dockerfile/railway.toml, OBS prefix isolation, runbook, report) maps to a task.
- [x] **No placeholders**: every Step has executable commands or complete code blocks.
- [x] **Type/identifier consistency**: `make_log_store` (not `_make_log_store`), `feishu_send_dm`, `DeepseekLLMClient`, `cron_loop`, `start_subprocess`, `_CRON_TASKS`, `run_daily_reflection`, `run_cold_start_watcher`, `run_outcome_checker`, `SREMCPAdapter` consistent across Tasks 4-8.
- [x] **Hard constraint coverage**: Constraint 1 (API stability) — Tasks 2/3/4/5 explicitly preserve `from agent_session_log import …` paths. Constraint 3 (test continuity) — Tasks 3/5/7 run the suite. Constraint 7 (no hermes gateway in reading) — Task 8 explicitly omits it.
- [x] **Phase 0a SRE not broken**: Tasks 3/5 keep sre-agent runnable; Task 5 explicitly slims main.py without changing behavior; Dockerfile updates preserve the same runtime topology (obs sync subprocess + hermes gateway + cron loop).
- [x] **Reading skill code paths preserved**: Task 7 moves intact; Task 7.3 fixes the only meaningful import change (`from main import …` → `from hermes_agent_utils import …`).
- [x] **OBS prefix isolation actually works**: confirmed `ObsSync.__init__(prefix=…)` and `cli.py` sync subcommand reads `OBS_PREFIX` env (Task 4.6).
- [x] **Railway rootDirectory change documented**: Tasks 3.4, 9.2, 10.1 all flag the dashboard-side change required.

## Open Risks During Execution

1. **Railway rootDirectory change for sre-agent**: Going from `/sre-agent` to `/` is a Railway dashboard action. If skipped, the sre-agent build will fail because `packages/` won't be in the build context. Either change rootDirectory before the next sre-agent deploy, or revert the Dockerfile to bundle workspace packages differently.
2. **Existing sre-agent Volume**: Phase 0a's `/data/hermes` Volume contains real session data. The B2 refactor preserves this — sre-agent's main.py is unchanged in behavior, only refactored. Verify Step 3.6 builds, then watch the next Railway deploy log.
3. **uv version**: workspaces require uv >= 0.4. `uv --version` check in Task 1.3 catches this. The Dockerfile pins `uv==0.4.18` so Railway is safe.
4. **Test paths after move (Task 2.4 / 7)**: pytest may fail to find `tests/integration/__init__.py` after moves — Task 6.1 explicitly creates it. If existing sre-agent integration tests have an `__init__.py` we relied on, verify it survives the moves.
5. **`from main import …` lazy imports in CLI files**: Task 7.3 patches them to `from hermes_agent_utils import …`. Verify with grep after Task 7: `grep -rn "from main import" reading-companion/` should return nothing.
6. **`_HERE.parents[2]` index in moved CLI files**: in Task 7.3 we keep it because the directory depth is the same (`reading-companion/skills/reading/url_handler/cli.py` matches `sre-agent/skills/reading/url_handler/cli.py`). Verify by running `python -c "from pathlib import Path; print(Path('reading-companion/skills/reading/url_handler/cli.py').resolve().parent.parents[2])"` — should print `…/reading-companion`.
7. **trafilatura on python:3.11-slim image**: needs libxml2 + libxslt1.1 system libs (Task 9 Dockerfile installs them). If lxml wheel build fails despite this, fall back to pre-built lxml wheel.
8. **Pre-existing dbay-sre-mcp local-venv failure**: the `test_sre_mcp_adapter_decodes_log_search` test fails locally because `dbay-sre-mcp` package isn't installed in the local venv (only in the Docker image). Plan acknowledges this; do not chase it.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-24-reading-companion-independent-service.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review, fast iteration in this session.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

Which approach?
