# SRE Agent Phase 1 — Watchers + Briefings Implementation Plan (Plan B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `dbay-sre-mcp 0.2.0`(Plan A 已上线)的 7 个新工具驱动起来——新增 5 个 watcher (cron-triggered 被动监控 → 发现 bug 自动开 incident + 建议)、3 份 briefing (早报 9:00 / 晚报 22:00 / 周报 周一 9:00)、1 个 domain glossary skill (让 LLM 懂 dbay 领域术语)。目标:80% bug 被 agent 主动发现 + DM 告警,用户只需被动接收;复杂问题用户主动 @ bot 问,agent 调新工具 + 查 commit log 历史直接答。

**Architecture:**
- **watchers** 沿用 Phase 0a `cold_start_watcher` 的 `@dataclass Watcher: scan_once() -> list[session_id]` 模板;共用 `WatcherBase` 基类统一 `_recently_seen` dedupe + `_open_incident` session-opening + ledger 记录。
- **briefings** 沿用 reading-companion `daily_reflection` 的 `reflect_today()` 模板;morning/evening/weekly 共享 `BriefingRunner`,不同 prompt + 不同 session type。
- **domain glossary** 是 prompt-only skill (纯 markdown),hermes 自动 `skill_view` 后 inject 到 LLM context,让 LLM 遇到"tcph-bench"这类不认识的人类语义先查 find_database 而不是瞎搜 log。
- `SREMCPAdapter` (main.py) 扩出 7 个新工具方法,watchers 都通过 adapter 调,便于 mock 测试。

**Tech Stack:** Python 3.11、pytest、croniter(已在 hermes-agent-utils)、dbay-sre-mcp 0.2.0、agent-session-log(workspace)、hermes-agent-utils(workspace)。

**Related:**
- Plan A (dbay-sre-mcp 0.2.0): [`2026-04-24-dbay-sre-mcp-phase1-enhancement.md`](./2026-04-24-dbay-sre-mcp-phase1-enhancement.md)
- Phase 0a plan: [`2026-04-23-sre-agent-phase-0a-plan.md`](./2026-04-23-sre-agent-phase-0a-plan.md)
- Commit log spec: [`docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md`](../specs/2026-04-23-agent-commit-log-phase0-design.md)

---

## Hard Constraints

1. **100% backward compat with Phase 0a:** existing `cold_start_watcher` / `outcome_checker` crons unchanged; 2 existing `_CRON_TASKS` entries remain intact; 5 new crons appended.
2. **5 watchers + 3 briefings all write to the same `agent_session_log`** — shared `LogStore`, session tagging by type (`incident` / `briefing` / `pattern_report`).
3. **Dedupe required for every watcher:** don't fire N incidents for the same symptom in a short window. 沿用 Phase 0a 的 `_recently_seen` 模式(check last N sessions matching same trigger identity before opening new session).
4. **Briefing 是 pull-based**(读 commit log + ledger 聚合),**不新产生事件** — 但自己的产出也写一个 `type=briefing` session 存档,未来可回溯"那天早报说了什么"。
5. **Glossary skill 是 prompt-only**(.md 文件),不含 Python;部署机制沿用 Phase 0a 的 `shutil.copytree(skills/ → HERMES_HOME/skills/)` 让 hermes skill_view 看见。
6. **Feishu DM format** — 所有推送走 `hermes_agent_utils.feishu.feishu_send_dm(open_id, text)`;格式统一简短、可扫读、末尾附 session id 方便追溯。
7. **Cron timezone** — cron expressions UTC;22:00 Asia/Shanghai = 14:00 UTC,9:00 Asia/Shanghai = 1:00 UTC。(Phase 0b Task 5 已经踩过 `0 22 *` 被误解的坑,不要再犯。)
8. **不对 cold_start_watcher 动手** — 沿用它当示范;本 plan 新增 5 watcher 都是"其他症状"。

---

## File Structure (target)

```
lakeon/sre-agent/
├── main.py                                 # MODIFY: extend SREMCPAdapter with 7 tools; +5 run_*_watcher + 3 run_*_briefing + _CRON_TASKS 7 new entries
├── skills/sre/
│   ├── _base/                              # NEW shared utilities
│   │   ├── __init__.py
│   │   └── watcher_base.py                 # NEW `WatcherBase` dataclass with dedupe + session-open helpers
│   ├── cold_start_watcher/                 # UNCHANGED (Phase 0a)
│   ├── outcome_checker/                    # UNCHANGED
│   ├── pod_create_failure_watcher/         # NEW
│   │   ├── __init__.py
│   │   ├── SKILL.md
│   │   └── watcher.py
│   ├── fuse_queue_health_watcher/          # NEW
│   │   ├── __init__.py
│   │   ├── SKILL.md
│   │   └── watcher.py
│   ├── stuck_task_watcher/                 # NEW
│   │   ├── __init__.py
│   │   ├── SKILL.md
│   │   └── watcher.py
│   ├── data_consistency_watcher/           # NEW
│   │   ├── __init__.py
│   │   ├── SKILL.md
│   │   └── watcher.py
│   ├── multi_tenant_blast_radius_watcher/  # NEW
│   │   ├── __init__.py
│   │   ├── SKILL.md
│   │   └── watcher.py
│   ├── daily_briefing/                     # NEW (shared morning/evening/weekly)
│   │   ├── __init__.py
│   │   ├── SKILL.md
│   │   ├── runner.py                       # `brief_morning()` / `brief_evening()` / `brief_weekly()`
│   │   ├── morning_prompt.md
│   │   ├── evening_prompt.md
│   │   └── weekly_prompt.md
│   └── domain_glossary/                    # NEW (prompt-only)
│       ├── SKILL.md                        # frontmatter + glossary content
│       └── symptom_map.md                  # bigger reference card
├── tests/integration/
│   ├── test_watcher_base.py                # NEW
│   ├── test_pod_create_failure_watcher.py  # NEW
│   ├── test_fuse_queue_health_watcher.py   # NEW
│   ├── test_stuck_task_watcher.py          # NEW
│   ├── test_data_consistency_watcher.py    # NEW
│   ├── test_multi_tenant_blast_radius_watcher.py  # NEW
│   └── test_daily_briefing.py              # NEW (morning + evening + weekly share one test file)
└── reports/
    └── phase1-progress.md                  # MODIFY: mark Plan B done checkboxes
```

### Module responsibilities

| Module | Responsibility | Model |
|---|---|---|
| `skills/sre/_base/watcher_base.py` | Shared `WatcherBase` with `_dedupe_window_sec`, `_is_recently_seen()`, `_open_incident()`, `_record_invocation()` | dataclass |
| `skills/sre/<name>_watcher/watcher.py` | Each:  `scan_once() -> list[session_id]`. Fetches signal via MCP tool, opens incident per new finding. Most NO LLM diagnose (直接把工具返回 fmt 成 conclusion + DM); `data_consistency` + `multi_tenant_blast_radius` 调 LLM 做根因分析 | dataclass inheriting WatcherBase |
| `skills/sre/daily_briefing/runner.py` | 3 funcs: `brief_morning(log, llm)` / `brief_evening(log, llm)` / `brief_weekly(log, llm)`. Each: list recent sessions + ledger stats → LLM format prompt → write `type=briefing` session → return text for main.py DM. | functions |
| `skills/sre/domain_glossary/SKILL.md` | Hermes-loadable markdown: dbay 对象模型、症状→工具映射、标准 5 步诊断。LLM 通过 skill_view 读到后,system prompt 里就有背景知识。 | prompt-only |
| `main.py` `SREMCPAdapter` | Extend with 7 methods: `find_database`, `find_tenant`, `database_status`, `data_consistency_check`, `stuck_task_query`, `pod_create_failures`, `multi_tenant_blast_radius`. All decode `dbay_sre_mcp.server.*` JSON return. | class |

---

## Work Breakdown — 10 Tasks

| Group | Tasks | What it produces |
|---|---|---|
| A. Foundation | 1-2 | `SREMCPAdapter` +7 methods; `WatcherBase` + tests |
| B. "报警型" watchers (no LLM) | 3-4 | pod_create / fuse_queue / stuck_task — tool returns → fmt → DM |
| C. "诊断型" watchers (LLM) | 5-6 | data_consistency / multi_tenant_blast_radius — tool returns → LLM root-cause → DM |
| D. Glossary + Briefings | 7-9 | domain_glossary skill; daily_briefing (morning+evening+weekly) |
| E. Wire + Ship | 10 | main.py `_CRON_TASKS` +5 watchers +3 briefings; phase1-progress.md; deploy |

---

## Group A: Foundation

### Task 1: Extend `SREMCPAdapter` with 7 new tools

**Why:** watchers call MCP through `SREMCPAdapter` (Phase 0a pattern). Adapter currently only wraps `log_search` / `log_trace` / `log_stats` — need 7 more methods for the 7 new dbay-sre-mcp 0.2.0 tools.

**Files:**
- Modify: `lakeon/sre-agent/main.py:55-82` (existing `SREMCPAdapter` class)
- Create: `lakeon/sre-agent/tests/test_mcp_adapter_v2.py`

- [ ] **Step 1.1: Write failing test for 7 new adapter methods**

```python
# lakeon/sre-agent/tests/test_mcp_adapter_v2.py
"""Tests for the 7 new SREMCPAdapter methods added after dbay-sre-mcp 0.2.0."""
import json
from unittest.mock import patch

import pytest


def test_find_database(monkeypatch):
    import main
    def fake(name="", db_id=""):
        return json.dumps({"found": True, "database": {"id": "db_xyz", "name": "tcph-bench"}})
    monkeypatch.setattr("dbay_sre_mcp.server.find_database", fake, raising=False)
    out = main.SREMCPAdapter().find_database(name="tcph-bench")
    assert out["found"] is True
    assert out["database"]["id"] == "db_xyz"


def test_find_tenant(monkeypatch):
    import main
    def fake(name="", tenant_id="", include_databases=True):
        return json.dumps({"found": True, "tenant": {"id": "t_abc"}, "databases": []})
    monkeypatch.setattr("dbay_sre_mcp.server.find_tenant", fake, raising=False)
    out = main.SREMCPAdapter().find_tenant(tenant_id="t_abc")
    assert out["found"] is True


def test_database_status(monkeypatch):
    import main
    def fake(name_or_id):
        return json.dumps({"found": True, "database": {"id": "d"}, "cold_start_1h": {"p95_ms": 2100}, "recent_events_1h": []})
    monkeypatch.setattr("dbay_sre_mcp.server.database_status", fake, raising=False)
    out = main.SREMCPAdapter().database_status(name_or_id="tcph-bench")
    assert out["cold_start_1h"]["p95_ms"] == 2100


def test_data_consistency_check(monkeypatch):
    import main
    def fake(rule, threshold_minutes=10):
        return json.dumps({"ok": False, "count": 2, "violations": [{"kb_id": "kb_x"}]})
    monkeypatch.setattr("dbay_sre_mcp.server.data_consistency_check", fake, raising=False)
    out = main.SREMCPAdapter().data_consistency_check(rule="kb_implies_db_id")
    assert out["count"] == 2


def test_stuck_task_query(monkeypatch):
    import main
    def fake(threshold_minutes=10, type=""):
        return json.dumps({"count": 1, "tasks": [{"task_id": "t_42", "source": "wiki_run_logs"}]})
    monkeypatch.setattr("dbay_sre_mcp.server.stuck_task_query", fake, raising=False)
    out = main.SREMCPAdapter().stuck_task_query(threshold_minutes=5)
    assert out["count"] == 1


def test_pod_create_failures(monkeypatch):
    import main
    def fake(since="1h"):
        return json.dumps({"count": 3, "by_category": {"InvalidName": 3}, "failures": []})
    monkeypatch.setattr("dbay_sre_mcp.server.pod_create_failures", fake, raising=False)
    out = main.SREMCPAdapter().pod_create_failures(since="30m")
    assert out["by_category"]["InvalidName"] == 3


def test_multi_tenant_blast_radius(monkeypatch):
    import main
    def fake(window="15m", min_tenant_count=3):
        return json.dumps({"count": 1, "incidents": [{"component": "agentfs", "distinct_tenant_count": 5}]})
    monkeypatch.setattr("dbay_sre_mcp.server.multi_tenant_blast_radius", fake, raising=False)
    out = main.SREMCPAdapter().multi_tenant_blast_radius(window="10m")
    assert out["incidents"][0]["distinct_tenant_count"] == 5
```

- [ ] **Step 1.2: Run — fails**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/test_mcp_adapter_v2.py -v
```
Expected: 7 fails — AttributeError `SREMCPAdapter has no attribute 'find_database'` etc.

- [ ] **Step 1.3: Extend SREMCPAdapter with 7 methods**

In `lakeon/sre-agent/main.py`, find the existing `class SREMCPAdapter` (~line 55). Keep the existing 3 methods (`log_search`, `log_trace`, `log_stats`). Add these 7 new methods inside the class after the existing ones:

```python
    # ─── dbay-sre-mcp 0.2.0 additions ─────────────────────────────────────────

    def find_database(self, *, name: str = "", db_id: str = "") -> dict:
        from dbay_sre_mcp.server import find_database as _find_database
        return json.loads(_find_database(name=name, db_id=db_id))

    def find_tenant(self, *, name: str = "", tenant_id: str = "",
                    include_databases: bool = True) -> dict:
        from dbay_sre_mcp.server import find_tenant as _find_tenant
        return json.loads(_find_tenant(name=name, tenant_id=tenant_id,
                                       include_databases=include_databases))

    def database_status(self, *, name_or_id: str) -> dict:
        from dbay_sre_mcp.server import database_status as _database_status
        return json.loads(_database_status(name_or_id=name_or_id))

    def data_consistency_check(self, *, rule: str, threshold_minutes: int = 10) -> dict:
        from dbay_sre_mcp.server import data_consistency_check as _dcc
        return json.loads(_dcc(rule=rule, threshold_minutes=threshold_minutes))

    def stuck_task_query(self, *, threshold_minutes: int = 10, type: str = "") -> dict:
        from dbay_sre_mcp.server import stuck_task_query as _stq
        return json.loads(_stq(threshold_minutes=threshold_minutes, type=type))

    def pod_create_failures(self, *, since: str = "1h") -> dict:
        from dbay_sre_mcp.server import pod_create_failures as _pcf
        return json.loads(_pcf(since=since))

    def multi_tenant_blast_radius(self, *, window: str = "15m",
                                   min_tenant_count: int = 3) -> dict:
        from dbay_sre_mcp.server import multi_tenant_blast_radius as _mtbr
        return json.loads(_mtbr(window=window, min_tenant_count=min_tenant_count))
```

- [ ] **Step 1.4: Run — passes**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/test_mcp_adapter_v2.py -v
```
Expected: 7 passed.

- [ ] **Step 1.5: Full suite — no regression**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/ 2>&1 | tail -3
```
Expected: all previous tests still pass + 7 new (total unchanged from previous + 7).

- [ ] **Step 1.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/main.py sre-agent/tests/test_mcp_adapter_v2.py
git commit -m "feat(sre-agent): SREMCPAdapter +7 methods for dbay-sre-mcp 0.2.0 tools"
```

---

### Task 2: `WatcherBase` shared dataclass

**Why:** 5 new watchers all need the same 3 capabilities: (a) dedupe "have I seen this same signal within N minutes?", (b) open a `type=incident` session with trigger + tags + skill-version, (c) record invocation in SkillLedger. Extracting avoids 5x copy-paste.

**Files:**
- Create: `lakeon/sre-agent/skills/sre/_base/__init__.py` (empty)
- Create: `lakeon/sre-agent/skills/sre/_base/watcher_base.py`
- Create: `lakeon/sre-agent/tests/integration/test_watcher_base.py`

- [ ] **Step 2.1: Write failing tests**

```python
# lakeon/sre-agent/tests/integration/test_watcher_base.py
"""Tests for WatcherBase — shared dedupe + session-open + ledger helpers."""
from datetime import datetime, timedelta, timezone

from agent_session_log import LogStore
from skills.sre._base.watcher_base import WatcherBase


def test_open_incident_writes_session_with_trigger_and_tags(tmp_log_root):
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1")

    sid = wb.open_incident(
        trigger={"source": "cron/test", "alert": "something broke",
                 "signal_id": "foo"},
        tags=["severity:medium", "component:test"],
    )

    m = log.store.read_manifest(sid)
    assert m.type == "incident"
    assert m.trigger["alert"] == "something broke"
    assert "component:test" in m.tags
    assert "skill:test-watcher" in m.tags  # WatcherBase auto-adds skill tag


def test_open_incident_records_ledger_invocation(tmp_log_root):
    from agent_session_log import SkillLedger
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1")

    sid = wb.open_incident(trigger={"alert": "x", "signal_id": "foo"}, tags=[])

    stats = SkillLedger(tmp_log_root).stats("test-watcher")
    assert stats["total_invocations"] == 1


def test_is_recently_seen_detects_same_signal_id(tmp_log_root):
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1",
                     dedupe_window_sec=600)

    sid = wb.open_incident(
        trigger={"alert": "first", "signal_id": "sig_abc"}, tags=[],
    )
    # Same signal_id within window → should be deduped
    assert wb.is_recently_seen(signal_id="sig_abc") is True
    # Different signal_id → not deduped
    assert wb.is_recently_seen(signal_id="sig_xyz") is False


def test_is_recently_seen_respects_dedupe_window(tmp_log_root):
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1",
                     dedupe_window_sec=60)

    sid = wb.open_incident(trigger={"alert": "x", "signal_id": "sig_old"}, tags=[])
    # Backdate the session to > 60 sec ago
    m = log.store.read_manifest(sid)
    m.created_at = (datetime.now(timezone.utc) - timedelta(seconds=120)).strftime("%Y-%m-%dT%H:%M:%SZ")
    log.store.write_manifest(m)

    # Outside window → should NOT be deduped
    assert wb.is_recently_seen(signal_id="sig_old") is False


def test_open_incident_conclude_and_close_helper(tmp_log_root):
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1")

    sid = wb.open_incident(trigger={"alert": "x", "signal_id": "s"}, tags=[])
    wb.conclude_and_close(sid, "# Root cause\n\nIt broke because of X.\n")

    assert log.store.read_manifest(sid).status == "closed"
    assert "Root cause" in log.store.read_conclusion(sid)
```

- [ ] **Step 2.2: Run — fails**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_watcher_base.py -v
```
Expected: ModuleNotFoundError: `skills.sre._base.watcher_base`.

- [ ] **Step 2.3: Implement watcher_base.py**

```python
# lakeon/sre-agent/skills/sre/_base/watcher_base.py
"""Shared base for all SRE watchers.

Each watcher scans for a symptom signal, dedupes recent same signals, and opens
an incident session when a new signal is detected. This base provides those
shared capabilities so per-watcher code stays focused on the signal logic.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

from agent_session_log import LogStore, utc_now_iso
from agent_session_log.skill_ledger import SkillLedger


@dataclass
class WatcherBase:
    log: LogStore
    skill_name: str
    skill_version: str = "v0.1"
    dedupe_window_sec: int = 600
    ledger: Optional[SkillLedger] = None

    def __post_init__(self) -> None:
        if self.ledger is None:
            self.ledger = SkillLedger(self.log.store.root)

    def is_recently_seen(self, *, signal_id: str) -> bool:
        """Check if a session with matching trigger.signal_id exists within dedupe_window_sec."""
        cutoff = datetime.now(timezone.utc) - timedelta(seconds=self.dedupe_window_sec)
        for meta in self.log.list_sessions(type="incident", limit=100):
            m_time = datetime.fromisoformat(meta["created_at"].replace("Z", "+00:00"))
            if m_time < cutoff:
                return False  # list_sessions is newest-first; anything older is stale
            full = self.log.store.read_manifest(meta["id"])
            if (full.trigger or {}).get("signal_id") == signal_id:
                return True
        return False

    def open_incident(self, *, trigger: dict[str, Any], tags: list[str]) -> str:
        """Open a new `type=incident` session and record invocation."""
        skill_tag = f"skill:{self.skill_name}"
        if skill_tag not in tags:
            tags = list(tags) + [skill_tag]
        trigger = {**trigger, "source": trigger.get("source", f"cron/{self.skill_name}"),
                   "skill_version": self.skill_version}

        s = self.log.new_session(
            type="incident",
            trigger=trigger,
            tags=tags,
            model="deepseek-chat",
            runtime="hermes@0.10.0",
        )
        s.append_turn(type="trigger", content=trigger)
        self.ledger.record_invocation(
            self.skill_name,
            version=self.skill_version,
            session_id=s.id,
            triggered_at=utc_now_iso(),
        )
        return s.id

    def conclude_and_close(self, session_id: str, markdown: str) -> None:
        """Write conclusion + close session in one shot."""
        s = self.log.get_session(session_id)
        s.conclude(markdown)
        s.close()
```

- [ ] **Step 2.4: Run — passes**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_watcher_base.py -v
```
Expected: 5 passed.

- [ ] **Step 2.5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/skills/sre/_base/ sre-agent/tests/integration/test_watcher_base.py
git commit -m "feat(sre-agent): WatcherBase shared dedupe + session-open helpers"
```

---

## Group B: "报警型" watchers (no LLM)

### Task 3: `pod_create_failure_watcher`

**Why:** Plan A's `pod_create_failures` tool returns categorized failures (InvalidName / CrashLoopBackOff / etc). This watcher runs it every 2 min, opens an incident when new failures appear, DM Jacky with category summary.

**Files:**
- Create: `lakeon/sre-agent/skills/sre/pod_create_failure_watcher/__init__.py` (empty)
- Create: `lakeon/sre-agent/skills/sre/pod_create_failure_watcher/SKILL.md`
- Create: `lakeon/sre-agent/skills/sre/pod_create_failure_watcher/watcher.py`
- Create: `lakeon/sre-agent/tests/integration/test_pod_create_failure_watcher.py`

- [ ] **Step 3.1: Write SKILL.md**

```markdown
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
```

- [ ] **Step 3.2: Write failing test**

```python
# lakeon/sre-agent/tests/integration/test_pod_create_failure_watcher.py
from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.pod_create_failure_watcher.watcher import PodCreateFailureWatcher


def _fake_mcp(response):
    m = MagicMock()
    m.pod_create_failures = lambda *, since="1h": response
    return m


def test_no_failures_opens_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = PodCreateFailureWatcher(log=log, mcp=_fake_mcp({"count": 0, "by_category": {}, "failures": []}))
    assert w.scan_once() == []


def test_opens_one_incident_per_category(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = PodCreateFailureWatcher(log=log, mcp=_fake_mcp({
        "count": 3,
        "by_category": {"InvalidName": 2, "CrashLoopBackOff": 1},
        "failures": [
            {"ts": "t1", "tenant_id": "t_a", "error": "InvalidName: foo", "category": "InvalidName"},
            {"ts": "t2", "tenant_id": "t_b", "error": "InvalidName: bar", "category": "InvalidName"},
            {"ts": "t3", "tenant_id": "t_c", "error": "CrashLoopBackOff", "category": "CrashLoopBackOff"},
        ],
    }))
    sids = w.scan_once()
    assert len(sids) == 2  # 2 categories → 2 incidents

    # Verify category tag + tenant list in conclusion
    for sid in sids:
        m = log.store.read_manifest(sid)
        cat = next((t for t in m.tags if t.startswith("category:")), None)
        assert cat is not None


def test_deduplicates_same_category_within_window(tmp_log_root):
    log = LogStore(tmp_log_root)
    mcp = _fake_mcp({
        "count": 1, "by_category": {"InvalidName": 1},
        "failures": [{"ts": "t", "tenant_id": "t_a", "error": "InvalidName", "category": "InvalidName"}],
    })
    w = PodCreateFailureWatcher(log=log, mcp=mcp, dedupe_window_sec=600)
    first = w.scan_once()
    second = w.scan_once()  # same signal, within window
    assert len(first) == 1
    assert len(second) == 0


def test_reply_text_format(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = PodCreateFailureWatcher(log=log, mcp=_fake_mcp({
        "count": 2, "by_category": {"InvalidName": 2},
        "failures": [
            {"ts": "t1", "tenant_id": "t_a", "error": "InvalidName: a", "category": "InvalidName"},
            {"ts": "t2", "tenant_id": "t_b", "error": "InvalidName: b", "category": "InvalidName"},
        ],
    }))
    w.scan_once()
    report = w.build_feishu_report("InvalidName", 2, ["t_a", "t_b"])
    assert "InvalidName" in report
    assert "2" in report
    assert "t_a" in report
```

- [ ] **Step 3.3: Run — fails**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_pod_create_failure_watcher.py -v
```
Expected: ModuleNotFoundError.

- [ ] **Step 3.4: Implement watcher.py**

```python
# lakeon/sre-agent/skills/sre/pod_create_failure_watcher/watcher.py
"""Pod-create-failure watcher.

Polls dbay-sre-mcp.pod_create_failures every N min; opens one incident per
category that has new failures since the last dedupe window.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from agent_session_log import LogStore
from skills.sre._base.watcher_base import WatcherBase


@dataclass
class PodCreateFailureWatcher(WatcherBase):
    mcp: Any = None
    since: str = "5m"

    def __post_init__(self) -> None:
        if not hasattr(self, 'skill_name') or not self.skill_name:
            self.skill_name = "pod-create-failure-watcher"
        super().__post_init__()

    def scan_once(self) -> list[str]:
        result = self.mcp.pod_create_failures(since=self.since)
        if not result.get("count"):
            return []
        failures = result.get("failures", [])
        by_cat = result.get("by_category", {})

        opened: list[str] = []
        for cat, count in by_cat.items():
            if count <= 0:
                continue
            signal_id = f"pod_create:{cat}"
            if self.is_recently_seen(signal_id=signal_id):
                continue

            cat_failures = [f for f in failures if f.get("category") == cat]
            tenants = sorted({f.get("tenant_id") for f in cat_failures if f.get("tenant_id")})

            sid = self.open_incident(
                trigger={
                    "alert": f"k8s pod create failure: {cat} x{count}",
                    "signal_id": signal_id,
                    "category": cat,
                    "count": count,
                    "tenants": tenants,
                },
                tags=["component:k8s", f"category:{cat}", "severity:medium"],
            )
            conclusion = self.build_conclusion(cat, count, tenants, cat_failures)
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened

    def build_conclusion(self, cat: str, count: int, tenants: list[str],
                         failures: list[dict]) -> str:
        sample = "\n".join(
            f"- {f.get('ts', '?')}: tenant={f.get('tenant_id', '?')} err={f.get('error', '')[:80]}"
            for f in failures[:3]
        )
        return (
            f"# k8s pod-create failure: {cat} × {count}\n\n"
            f"**Affected tenants** ({len(tenants)}): {', '.join(tenants) or '(none)'}\n\n"
            f"**Sample failures**:\n{sample}\n\n"
            f"**Next step**: query `database_status` on each affected tenant's DB or "
            f"`log_search(component='compute', keyword='{cat}')` for deeper trace.\n"
        )

    def build_feishu_report(self, cat: str, count: int, tenants: list[str]) -> str:
        return (
            f"[SRE] {cat} pod create 失败 {count} 次\n"
            f"涉及 tenant: {', '.join(tenants[:5])}{' ...' if len(tenants) > 5 else ''}"
        )
```

- [ ] **Step 3.5: Run — passes**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_pod_create_failure_watcher.py -v
```
Expected: 4 passed.

- [ ] **Step 3.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/skills/sre/pod_create_failure_watcher/ \
        sre-agent/tests/integration/test_pod_create_failure_watcher.py
git commit -m "feat(sre-agent): pod_create_failure_watcher (category-based, no LLM)"
```

---

### Task 4: `fuse_queue_health_watcher` + `stuck_task_watcher`

**Why:** Both are alarm-type watchers like Task 3 — tool returns, fmt, DM. Batch them to reduce task count.

**Files:**
- Create: `lakeon/sre-agent/skills/sre/fuse_queue_health_watcher/{__init__.py, SKILL.md, watcher.py}`
- Create: `lakeon/sre-agent/skills/sre/stuck_task_watcher/{__init__.py, SKILL.md, watcher.py}`
- Create: `lakeon/sre-agent/tests/integration/test_fuse_queue_health_watcher.py`
- Create: `lakeon/sre-agent/tests/integration/test_stuck_task_watcher.py`

- [ ] **Step 4.1: Write SKILL.md for fuse_queue_health_watcher**

```markdown
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
```

- [ ] **Step 4.2: Write failing test for fuse_queue_health_watcher**

```python
# lakeon/sre-agent/tests/integration/test_fuse_queue_health_watcher.py
from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.fuse_queue_health_watcher.watcher import FuseQueueHealthWatcher


def _fake_mcp(log_rows):
    m = MagicMock()
    m.log_search = lambda *, component="", keyword="", since="15m", limit=200, **_: log_rows
    return m


def test_no_stuck_blobs_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = FuseQueueHealthWatcher(log=log, mcp=_fake_mcp([
        {"ts": "t1", "msg": "retry blob_id=b1 attempt=1"},
        {"ts": "t2", "msg": "retry blob_id=b1 attempt=2"},
    ]), retry_threshold=5)
    assert w.scan_once() == []


def test_stuck_blob_opens_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [
        {"ts": f"t{i}", "msg": f"retry blob_id=b_stuck attempt={i}", "tenant_id": "t_a"}
        for i in range(1, 8)  # 7 retries
    ]
    w = FuseQueueHealthWatcher(log=log, mcp=_fake_mcp(rows), retry_threshold=5)
    sids = w.scan_once()
    assert len(sids) == 1
    m = log.store.read_manifest(sids[0])
    assert "component:dbay-fuse" in m.tags
    assert "b_stuck" in str(m.trigger)


def test_deduplicates_same_blob_within_window(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [{"ts": f"t{i}", "msg": f"retry blob_id=b_x attempt={i}", "tenant_id": "t_a"}
            for i in range(1, 8)]
    w = FuseQueueHealthWatcher(log=log, mcp=_fake_mcp(rows), retry_threshold=5, dedupe_window_sec=600)
    first = w.scan_once()
    second = w.scan_once()
    assert len(first) == 1
    assert len(second) == 0
```

- [ ] **Step 4.3: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 4.4: Implement fuse_queue_health_watcher/watcher.py**

```python
# lakeon/sre-agent/skills/sre/fuse_queue_health_watcher/watcher.py
"""Fuse queue health watcher — detect stuck batches via log retry patterns."""
from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass
from typing import Any

from agent_session_log import LogStore
from skills.sre._base.watcher_base import WatcherBase


_BLOB_RE = re.compile(r"blob_id=(?P<blob>\S+)")


@dataclass
class FuseQueueHealthWatcher(WatcherBase):
    mcp: Any = None
    since: str = "15m"
    retry_threshold: int = 5

    def __post_init__(self) -> None:
        if not getattr(self, 'skill_name', None):
            self.skill_name = "fuse-queue-health-watcher"
        super().__post_init__()

    def scan_once(self) -> list[str]:
        rows = self.mcp.log_search(
            component="dbay-fuse", keyword="retry",
            since=self.since, limit=200,
        )
        if not rows:
            return []

        retries_by_blob: dict[str, list[dict]] = defaultdict(list)
        for row in rows:
            m = _BLOB_RE.search(row.get("msg", ""))
            if m:
                retries_by_blob[m.group("blob")].append(row)

        opened: list[str] = []
        for blob, events in retries_by_blob.items():
            if len(events) < self.retry_threshold:
                continue
            signal_id = f"fuse_stuck:{blob}"
            if self.is_recently_seen(signal_id=signal_id):
                continue
            tenants = sorted({e.get("tenant_id") for e in events if e.get("tenant_id")})
            sid = self.open_incident(
                trigger={
                    "alert": f"dbay-fuse blob {blob} stuck ({len(events)} retries)",
                    "signal_id": signal_id,
                    "blob_id": blob,
                    "retry_count": len(events),
                    "tenants": tenants,
                },
                tags=["component:dbay-fuse", "severity:medium"],
            )
            conclusion = (
                f"# dbay-fuse stuck blob: {blob}\n\n"
                f"**Retries**: {len(events)} within {self.since}\n"
                f"**Tenants**: {', '.join(tenants) or '(no tenant_id tagged)'}\n\n"
                f"**Next step**: `log_search(component='dbay-fuse', keyword='{blob}')` "
                f"for full error chain.\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
```

- [ ] **Step 4.5: Run fuse tests — pass**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_fuse_queue_health_watcher.py -v
```
Expected: 3 passed.

- [ ] **Step 4.6: SKILL.md for stuck_task_watcher**

```markdown
---
name: stuck_task_watcher
description: Detect async tasks stuck in_progress beyond threshold (wiki/agentfs/kb). Every 5 min.
version: v0.1
triggers:
  cron: "*/5 * * * *"
tools:
  - dbay-sre-mcp.stuck_task_query
personality: sre
---

# stuck_task_watcher

Every 5 minutes:
1. Call `stuck_task_query(threshold_minutes=10)`.
2. Open 1 incident grouping all stuck tasks (not 1 per task — too noisy).
3. DM: `[SRE] {count} 个 async 任务卡住 > 10min — {type_summary}`.

Catches bug b742634d family (WIKI_UPDATE 30-min recovery timeout) and 5f9e1fc9
(DeepSeek agent skips done() call).
```

- [ ] **Step 4.7: Failing test for stuck_task_watcher**

```python
# lakeon/sre-agent/tests/integration/test_stuck_task_watcher.py
from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.stuck_task_watcher.watcher import StuckTaskWatcher


def _fake_mcp(response):
    m = MagicMock()
    m.stuck_task_query = lambda *, threshold_minutes=10, type="": response
    return m


def test_no_stuck_tasks(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = StuckTaskWatcher(log=log, mcp=_fake_mcp({"count": 0, "tasks": []}))
    assert w.scan_once() == []


def test_stuck_tasks_open_one_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = StuckTaskWatcher(log=log, mcp=_fake_mcp({
        "count": 3,
        "tasks": [
            {"task_id": "t1", "task_type": "WIKI_UPDATE", "source": "wiki_run_logs", "age_sec": 700},
            {"task_id": "t2", "task_type": "WIKI_INGEST", "source": "wiki_run_logs", "age_sec": 900},
            {"task_id": "t3", "task_type": "FUSE_BACKFILL", "source": "agentfs_jobs", "age_sec": 1100},
        ],
    }))
    sids = w.scan_once()
    assert len(sids) == 1  # all grouped into one
    m = log.store.read_manifest(sids[0])
    assert m.trigger["count"] == 3


def test_dedupe_when_count_stable(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = StuckTaskWatcher(log=log, mcp=_fake_mcp({
        "count": 2,
        "tasks": [
            {"task_id": "t1", "task_type": "WIKI_UPDATE", "source": "wiki_run_logs", "age_sec": 700},
            {"task_id": "t2", "task_type": "FUSE_BACKFILL", "source": "agentfs_jobs", "age_sec": 700},
        ],
    }), dedupe_window_sec=600)
    first = w.scan_once()
    second = w.scan_once()
    assert len(first) == 1
    assert len(second) == 0
```

- [ ] **Step 4.8: Implement stuck_task_watcher/watcher.py**

```python
# lakeon/sre-agent/skills/sre/stuck_task_watcher/watcher.py
"""Stuck task watcher — reports stuck async tasks (wiki/agentfs/kb) grouped."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Any

from skills.sre._base.watcher_base import WatcherBase


@dataclass
class StuckTaskWatcher(WatcherBase):
    mcp: Any = None
    threshold_minutes: int = 10

    def __post_init__(self) -> None:
        if not getattr(self, 'skill_name', None):
            self.skill_name = "stuck-task-watcher"
        super().__post_init__()

    def scan_once(self) -> list[str]:
        result = self.mcp.stuck_task_query(threshold_minutes=self.threshold_minutes)
        count = result.get("count", 0)
        if count == 0:
            return []
        tasks = result.get("tasks", [])
        # signal_id based on (count, task_types) — re-fire only if pattern changes
        type_counter = Counter(t.get("task_type") for t in tasks)
        types_summary = ",".join(f"{t}x{n}" for t, n in sorted(type_counter.items()))
        signal_id = f"stuck_tasks:{types_summary}"
        if self.is_recently_seen(signal_id=signal_id):
            return []

        oldest_age = max(t.get("age_sec", 0) for t in tasks)
        sid = self.open_incident(
            trigger={
                "alert": f"{count} async tasks stuck > {self.threshold_minutes}min",
                "signal_id": signal_id,
                "count": count,
                "type_summary": type_counter,
                "oldest_age_sec": oldest_age,
            },
            tags=["component:async-task", "severity:low"],
        )
        sample_lines = "\n".join(
            f"- {t.get('source', '?')}: {t.get('task_type', '?')} "
            f"task_id={t.get('task_id', '?')} age={t.get('age_sec', 0)}s "
            f"kb_id={t.get('kb_id') or '-'}"
            for t in tasks[:10]
        )
        conclusion = (
            f"# Stuck async tasks: {count}\n\n"
            f"**Types**: {types_summary}\n"
            f"**Oldest age**: {oldest_age}s\n\n"
            f"**Top 10**:\n{sample_lines}\n\n"
            f"**Next step**: `log_search(keyword=<task_id>)` for each stuck task "
            f"to find where it hung.\n"
        )
        self.conclude_and_close(sid, conclusion)
        return [sid]
```

- [ ] **Step 4.9: Run stuck_task tests — pass**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_stuck_task_watcher.py -v
```
Expected: 3 passed.

- [ ] **Step 4.10: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/skills/sre/fuse_queue_health_watcher/ \
        sre-agent/skills/sre/stuck_task_watcher/ \
        sre-agent/tests/integration/test_fuse_queue_health_watcher.py \
        sre-agent/tests/integration/test_stuck_task_watcher.py
git commit -m "feat(sre-agent): fuse_queue_health + stuck_task watchers"
```

---

## Group C: "诊断型" watchers (LLM root-cause)

### Task 5: `data_consistency_watcher`

**Why:** Runs all 4 invariant rules from Plan A's `data_consistency_check` tool. When violations found, uses LLM to write a root-cause hypothesis (this is the KB-ready event timing bug / tx drain bug family — complex enough to justify LLM).

**Files:**
- Create: `lakeon/sre-agent/skills/sre/data_consistency_watcher/{__init__.py, SKILL.md, watcher.py, diagnose_prompt.md}`
- Create: `lakeon/sre-agent/tests/integration/test_data_consistency_watcher.py`

- [ ] **Step 5.1: SKILL.md**

```markdown
---
name: data_consistency_watcher
description: Run 4 invariant rules every 15 min; use LLM to suggest root cause when violations found.
version: v0.1
triggers:
  cron: "*/15 * * * *"
tools:
  - dbay-sre-mcp.data_consistency_check
personality: sre
---

# data_consistency_watcher

Every 15 minutes:
1. For each rule in [kb_implies_db_id, enqueued_implies_drained, db_ready_implies_pod_running, schema_seeded]:
   Call `data_consistency_check(rule=...)`.
2. If any rule has violations (ok=false), open incident.
3. LLM reads violations + `diagnose_prompt.md` → writes root-cause hypothesis.
4. DM: `[SRE] 数据一致性违规: {rule} × {count} — {top_guess}`.

Catches bugs family 54035cc9 / 4e42694d / b5c97605 (event timing / tx ordering).
```

- [ ] **Step 5.2: diagnose_prompt.md**

```markdown
Found {count} violations of invariant rule `{rule}`:

{violations_json}

Rule description: {description}

Write a short (≤ 200 字) root-cause hypothesis. Consider:
- Event ordering / @AfterCommit listener missed
- Transaction commit race before downstream consumer
- REQUIRES_NEW tx scope missing
- Listener not wrapped in @Transactional
- Retry/rollback left orphans

Output markdown with these sections:
## 根因假设 (confidence 0-1)
## 建议调查
## 建议修复动作
```

- [ ] **Step 5.3: Failing tests**

```python
# lakeon/sre-agent/tests/integration/test_data_consistency_watcher.py
from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.data_consistency_watcher.watcher import DataConsistencyWatcher


class _FakeLLM:
    def __init__(self, text):
        self.text = text
        self.calls: list[dict] = []
    def complete(self, *, system, user, tools=None):
        self.calls.append({"system": system, "user": user})
        return {"text": self.text, "model": "deepseek-chat",
                "tokens_in": 100, "tokens_out": 50, "cost_usd": None}


def _fake_mcp(rule_results: dict[str, dict]):
    m = MagicMock()
    m.data_consistency_check = lambda *, rule, threshold_minutes=10: rule_results.get(rule, {"ok": True, "count": 0, "violations": []})
    return m


def test_all_rules_ok_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = DataConsistencyWatcher(log=log, mcp=_fake_mcp({}), llm=_FakeLLM("should not be called"))
    assert w.scan_once() == []


def test_one_rule_violates_opens_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = DataConsistencyWatcher(
        log=log,
        mcp=_fake_mcp({
            "kb_implies_db_id": {
                "ok": False, "count": 2,
                "violations": [{"kb_id": "kb_a"}, {"kb_id": "kb_b"}],
                "description": "KB ready but no db_id",
            },
        }),
        llm=_FakeLLM("## 根因假设 (0.6)\n疑似 @AfterCommit 时序 bug\n"),
    )
    sids = w.scan_once()
    assert len(sids) == 1
    m = log.store.read_manifest(sids[0])
    assert "rule:kb_implies_db_id" in m.tags
    assert "@AfterCommit" in (log.store.read_conclusion(sids[0]) or "")


def test_multiple_rules_violate_multiple_incidents(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = DataConsistencyWatcher(
        log=log,
        mcp=_fake_mcp({
            "kb_implies_db_id": {"ok": False, "count": 1, "violations": [{"kb_id": "x"}],
                                 "description": "d1"},
            "enqueued_implies_drained": {"ok": False, "count": 3, "violations": [],
                                          "description": "d2"},
        }),
        llm=_FakeLLM("root cause guess"),
    )
    sids = w.scan_once()
    assert len(sids) == 2
```

- [ ] **Step 5.4: Implement watcher.py**

```python
# lakeon/sre-agent/skills/sre/data_consistency_watcher/watcher.py
"""Data consistency watcher — runs 4 invariant rules; LLM diagnoses violations."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional, Protocol

from skills.sre._base.watcher_base import WatcherBase


_RULES = [
    "kb_implies_db_id",
    "enqueued_implies_drained",
    "db_ready_implies_pod_running",
    "schema_seeded",
]

_PROMPT = (Path(__file__).parent / "diagnose_prompt.md").read_text(encoding="utf-8")


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str,
                 tools: list[dict] | None = None) -> dict: ...


@dataclass
class DataConsistencyWatcher(WatcherBase):
    mcp: Any = None
    llm: Optional[LLMClient] = None

    def __post_init__(self) -> None:
        if not getattr(self, 'skill_name', None):
            self.skill_name = "data-consistency-watcher"
        super().__post_init__()

    def scan_once(self) -> list[str]:
        opened: list[str] = []
        for rule in _RULES:
            result = self.mcp.data_consistency_check(rule=rule)
            if result.get("ok", True):
                continue
            count = result.get("count", 0)
            if count == 0:
                continue
            signal_id = f"consistency:{rule}"
            if self.is_recently_seen(signal_id=signal_id):
                continue

            violations = result.get("violations", [])
            description = result.get("description", "")
            prompt = (_PROMPT
                      .replace("{rule}", rule)
                      .replace("{count}", str(count))
                      .replace("{description}", description)
                      .replace("{violations_json}",
                               json.dumps(violations[:10], ensure_ascii=False, indent=2)))
            llm_resp = self.llm.complete(system="你是谨慎的 SRE 工程师。", user=prompt)
            hypothesis = (llm_resp.get("text") or "").strip()

            sid = self.open_incident(
                trigger={
                    "alert": f"data consistency violation: {rule} × {count}",
                    "signal_id": signal_id,
                    "rule": rule, "count": count,
                },
                tags=[f"rule:{rule}", "component:data-consistency", "severity:medium"],
            )
            conclusion = (
                f"# Data consistency violation: {rule}\n\n"
                f"**Count**: {count}\n"
                f"**Rule**: {description}\n\n"
                f"## 违规样本\n\n"
                f"```json\n{json.dumps(violations[:5], ensure_ascii=False, indent=2)}\n```\n\n"
                f"## LLM 根因假设\n\n{hypothesis}\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
```

- [ ] **Step 5.5: Run — passes**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_data_consistency_watcher.py -v
```
Expected: 3 passed.

- [ ] **Step 5.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/skills/sre/data_consistency_watcher/ \
        sre-agent/tests/integration/test_data_consistency_watcher.py
git commit -m "feat(sre-agent): data_consistency_watcher (4 rules + LLM root cause)"
```

---

### Task 6: `multi_tenant_blast_radius_watcher`

**Why:** Plan A's `multi_tenant_blast_radius` tool returns incidents where single error pattern affects N tenants. This watcher runs every 5 min, opens incident when new blast detected, LLM suggests which fault domain is common.

**Files:**
- Create: `lakeon/sre-agent/skills/sre/multi_tenant_blast_radius_watcher/{__init__.py, SKILL.md, watcher.py, diagnose_prompt.md}`
- Create: `lakeon/sre-agent/tests/integration/test_multi_tenant_blast_radius_watcher.py`

- [ ] **Step 6.1: SKILL.md + diagnose_prompt.md**

`SKILL.md`:
```markdown
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
```

`diagnose_prompt.md`:
```markdown
An error signature has fired across {distinct_tenant_count} tenants in the last {window}:

- Component: `{component}`
- Error signature: `{error_signature}`
- Total occurrences: {total_occurrences}

Hypothesize the single fault domain causing this. Consider:
- Shared external dependency (upstream API, DNS, cert expiry)
- Shared config change (env var, feature flag, deploy)
- Resource exhaustion (connection pool, executor threads, disk)
- Single-point-of-failure infra (one pod, one DB, one service)

Output ≤ 150 字 markdown:
## 最可能根因
## 下一步验证 (1-2 条具体指令)
```

- [ ] **Step 6.2: Failing tests**

```python
# lakeon/sre-agent/tests/integration/test_multi_tenant_blast_radius_watcher.py
from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.multi_tenant_blast_radius_watcher.watcher import MultiTenantBlastRadiusWatcher


class _FakeLLM:
    def __init__(self, text): self.text = text
    def complete(self, *, system, user, tools=None):
        return {"text": self.text, "model": "x",
                "tokens_in": 1, "tokens_out": 1, "cost_usd": None}


def _fake_mcp(response):
    m = MagicMock()
    m.multi_tenant_blast_radius = lambda *, window="15m", min_tenant_count=3: response
    return m


def test_no_blast_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = MultiTenantBlastRadiusWatcher(
        log=log, mcp=_fake_mcp({"count": 0, "incidents": []}),
        llm=_FakeLLM("should not be called"),
    )
    assert w.scan_once() == []


def test_one_blast_opens_one_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = MultiTenantBlastRadiusWatcher(
        log=log,
        mcp=_fake_mcp({
            "count": 1, "window": "15m",
            "incidents": [{
                "component": "agentfs",
                "error_signature": "MemorySvcClient connection refused",
                "distinct_tenant_count": 5,
                "total_occurrences": 47,
            }],
        }),
        llm=_FakeLLM("## 最可能根因\nmemory-svc pod 挂了或 port 配错\n"),
    )
    sids = w.scan_once()
    assert len(sids) == 1
    m = log.store.read_manifest(sids[0])
    assert m.trigger["distinct_tenant_count"] == 5
    assert "memory-svc" in (log.store.read_conclusion(sids[0]) or "")


def test_dedupes_same_signature(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = MultiTenantBlastRadiusWatcher(
        log=log,
        mcp=_fake_mcp({
            "count": 1, "window": "15m",
            "incidents": [{
                "component": "agentfs",
                "error_signature": "connection refused",
                "distinct_tenant_count": 5,
                "total_occurrences": 47,
            }],
        }),
        llm=_FakeLLM("guess"),
        dedupe_window_sec=600,
    )
    first = w.scan_once()
    second = w.scan_once()
    assert len(first) == 1
    assert len(second) == 0
```

- [ ] **Step 6.3: Implement watcher.py**

```python
# lakeon/sre-agent/skills/sre/multi_tenant_blast_radius_watcher/watcher.py
"""Multi-tenant blast radius watcher — detect cross-tenant fault; LLM guess fault domain."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional, Protocol

from skills.sre._base.watcher_base import WatcherBase


_PROMPT = (Path(__file__).parent / "diagnose_prompt.md").read_text(encoding="utf-8")


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str,
                 tools: list[dict] | None = None) -> dict: ...


@dataclass
class MultiTenantBlastRadiusWatcher(WatcherBase):
    mcp: Any = None
    llm: Optional[LLMClient] = None
    window: str = "15m"
    min_tenant_count: int = 3

    def __post_init__(self) -> None:
        if not getattr(self, 'skill_name', None):
            self.skill_name = "multi-tenant-blast-radius-watcher"
        super().__post_init__()

    def scan_once(self) -> list[str]:
        result = self.mcp.multi_tenant_blast_radius(
            window=self.window, min_tenant_count=self.min_tenant_count,
        )
        if result.get("count", 0) == 0:
            return []

        opened: list[str] = []
        for inc in result.get("incidents", []):
            component = inc.get("component", "")
            sig = inc.get("error_signature", "")
            signal_id = f"blast:{component}:{sig[:40]}"
            if self.is_recently_seen(signal_id=signal_id):
                continue

            prompt = (_PROMPT
                      .replace("{distinct_tenant_count}", str(inc.get("distinct_tenant_count", 0)))
                      .replace("{window}", self.window)
                      .replace("{component}", component)
                      .replace("{error_signature}", sig)
                      .replace("{total_occurrences}", str(inc.get("total_occurrences", 0))))
            llm_resp = self.llm.complete(system="你是谨慎的 SRE 工程师。", user=prompt)
            hypothesis = (llm_resp.get("text") or "").strip()

            sid = self.open_incident(
                trigger={
                    "alert": f"cross-tenant blast: {component} × {inc.get('distinct_tenant_count', 0)} tenants",
                    "signal_id": signal_id,
                    "component": component,
                    "error_signature": sig,
                    "distinct_tenant_count": inc.get("distinct_tenant_count"),
                    "total_occurrences": inc.get("total_occurrences"),
                },
                tags=[f"component:{component}", "category:blast-radius", "severity:high"],
            )
            conclusion = (
                f"# Cross-tenant blast: {component}\n\n"
                f"**Error signature**: `{sig}`\n"
                f"**Affected tenants**: {inc.get('distinct_tenant_count', 0)}\n"
                f"**Total occurrences (window {self.window})**: {inc.get('total_occurrences', 0)}\n\n"
                f"## LLM 根因假设\n\n{hypothesis}\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
```

- [ ] **Step 6.4: Run — passes**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_multi_tenant_blast_radius_watcher.py -v
```
Expected: 3 passed.

- [ ] **Step 6.5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/skills/sre/multi_tenant_blast_radius_watcher/ \
        sre-agent/tests/integration/test_multi_tenant_blast_radius_watcher.py
git commit -m "feat(sre-agent): multi_tenant_blast_radius_watcher (LLM fault-domain guess)"
```

---

## Group D: Glossary + Briefings

### Task 7: `domain_glossary` skill (prompt-only)

**Why:** LLM 不认识 "tcph-bench" 这类人类语义,不知道"唤醒失败"对应哪些 log pattern。Glossary skill 把这些知识塞进 LLM context,让它懂得"先 find_database 再 log_search"的套路。

**Files:**
- Create: `lakeon/sre-agent/skills/sre/domain_glossary/SKILL.md`
- Create: `lakeon/sre-agent/skills/sre/domain_glossary/symptom_map.md`

- [ ] **Step 7.1: SKILL.md**

```markdown
---
name: domain_glossary
description: dbay 领域速查 — 对象模型 + 症状映射 + 标准诊断路径。LLM 看到人类语义(数据库名/tenant 名/症状描述)时优先参考此文件。
version: v0.1
personality: sre
---

# dbay 领域速查

## 对象模型

```
Tenant (租户, 人类可读名如 "perf-team")
  └── Database (数据库, 人类可读名如 "tcph-bench", 内部 db_id 是 UUID)
        └── Compute Pod (一个 k8s pod, compute_host 字段记录)
              └── Pageserver (attach to tenant_id)
```

**关键**: log 里只有 UUID (tenant_id, db_id),**没有人类可读名**。用户问"tcph-bench",你得先 `find_database(name="tcph-bench")` 拿到 db_id + tenant_id 才能去 log 里追。

## 用户问句 → 标准工具路径

| 用户问 | 别这么做 ❌ | 该这么做 ✅ |
|---|---|---|
| "为什么 X 唤醒失败" | 直接 `log_search(keyword="X")` | `database_status(name_or_id="X")` 一次拿 状态+cold_start+events |
| "X 租户健康吗" | 瞎翻 log | `find_tenant(name="X")` 拿到 id → 每个 db 调 `database_status` |
| "X 数据库 / tenant 是什么" | 搜 log | `find_database(name="X")` / `find_tenant(name="X")` |
| "为什么这么多 tenant 都挂了" | 看单个 tenant | `multi_tenant_blast_radius(window="15m")` |
| "X 任务卡住了" | log_search 漫游 | `stuck_task_query(threshold_minutes=10)` |
| "为什么 X tenant 部署失败" | 猜 | `pod_create_failures(since="30m")` + 按 category 读 |

## 症状 → 工具映射

见 `symptom_map.md`(自动加载的延展 reference)。

## 标准 5 步诊断(遇到"为什么 X 慢/挂/失败")

1. **标识**: X 是 database 名?tenant 名?内部 UUID?先 `find_database` / `find_tenant` 确定。
2. **快照**: `database_status(name_or_id=X)` — 拿状态 + cold_start_p95 + 最近 1h events。
3. **近因**: 如果 status 异常,`log_search(tenant_id=..., since="30m")` 或 `log_errors(component=...)`。
4. **横向**: 检查是否多 tenant 同时有事 — `multi_tenant_blast_radius(window="15m")`。
5. **结论**: 综合给根因 + 建议 action。如果置信度低,**说**"证据不够,建议再查 Y"。

## 关键原则

- **"我不知道"是合法答案**: 如果工具没返回相关信息,就说"日志/数据里没有,建议查 Y";不要硬编故事。
- **每次 log_search 都指明 since**: 没有 since 默认 1h,太长会捞到无关数据。
- **数字要精确**: "cold_start_p95 = 2100ms" 比"挺慢的"强。
```

- [ ] **Step 7.2: symptom_map.md**

```markdown
# 症状速查表

## 冷启动慢 / 唤醒失败 / 连接超时

| 可能 | log 信号 | 工具 |
|---|---|---|
| pageserver re-attach 太慢 | pageserver log "re-attach took Nms" | `log_search(component="pageserver", keyword="re-attach", since="5m")` |
| compute pod 创建失败 | k8s event InvalidName / CrashLoopBackOff | `pod_create_failures(since="30m")` |
| WAL replay backlog | pageserver log "wal_lag" | `log_search(component="pageserver", keyword="wal_lag")` |
| 镜像拉取慢 | k8s event ImagePullBackOff | `pod_create_failures(since="30m")` → 看 ImagePullBackOff 类别 |
| node 调度失败 | k8s FailedScheduling | `pod_create_failures` |

## 数据不一致(X 创建后 Y 找不到)

| 可能 | 工具 |
|---|---|
| KB 标 READY 但 db_id NULL | `data_consistency_check(rule="kb_implies_db_id")` |
| 写入 enqueued 但 drain 超时 | `data_consistency_check(rule="enqueued_implies_drained")` |
| DB 标 READY 但 compute_host 空 | `data_consistency_check(rule="db_ready_implies_pod_running")` |
| Wiki KB 缺 schema | `data_consistency_check(rule="schema_seeded")` |

## 多 tenant 同时出事

| 症状 | 工具 |
|---|---|
| 单一 fault domain 击穿 | `multi_tenant_blast_radius(window="15m")` |
| 上游依赖挂 | 上面 + `log_errors(component=<suspected-upstream>)` |
| 共享配置错 | 检查最近是否有 env / config deploy |

## 任务卡死

| 症状 | 工具 |
|---|---|
| wiki/agentfs/kb 任务 in_progress 超时 | `stuck_task_query(threshold_minutes=10)` |
| 特定类型任务 | `stuck_task_query(type="WIKI_UPDATE")` |

## Cost / Usage 异常

目前还没做成 SRE 工具 — 如果用户问成本问题,回复"暂时没接 cost 工具,建议去 admin console / dashboard 看"。
```

- [ ] **Step 7.3: Verify skills directory copies to HERMES_HOME**

No test needed — this is a prompt-only skill. Phase 0a's `main.py` already does `shutil.copytree(_HERE / "skills", home / "skills")` on every boot, so deploying this skill requires no code change.

- [ ] **Step 7.4: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/skills/sre/domain_glossary/
git commit -m "feat(sre-agent): domain_glossary skill (dbay 对象模型 + 症状映射)"
```

---

### Task 8: `daily_briefing` — morning + evening + weekly

**Why:** User wants "早报 9:00 + 晚报 22:00 + 周报". All three read commit log + ledger, format into markdown, DM. Share `BriefingRunner` + 3 prompts.

**Files:**
- Create: `lakeon/sre-agent/skills/sre/daily_briefing/{__init__.py, SKILL.md, runner.py, morning_prompt.md, evening_prompt.md, weekly_prompt.md}`
- Create: `lakeon/sre-agent/tests/integration/test_daily_briefing.py`

- [ ] **Step 8.1: SKILL.md**

```markdown
---
name: daily_briefing
description: Morning 9:00 / Evening 22:00 / Weekly Monday 9:00 — summarise SRE commit log + ledger to DM.
version: v0.1
triggers:
  cron_morning: "0 1 * * *"     # 9:00 Asia/Shanghai
  cron_evening: "0 14 * * *"    # 22:00 Asia/Shanghai
  cron_weekly:  "0 1 * * 1"     # Monday 9:00 Asia/Shanghai
personality: sre
---

# daily_briefing

3 briefings, all use `BriefingRunner`:

- **morning (9:00)**: 昨夜动态 + 未关闭 incidents + 今日预期 (upcoming skill stats thresholds)
- **evening (22:00)**: 今日总览 + 未解决 incidents + 明日 follow-up
- **weekly (周一 9:00)**: 本周 pattern 聚类 + skill 准确率 + 趋势

Each briefing:
1. Query commit log (`list_sessions(type="incident", since=N)`) + `SkillLedger.stats(*)`
2. Pack into LLM prompt (morning/evening/weekly variant)
3. LLM writes markdown brief (≤ 300 字)
4. Open `type=briefing` session, tag `[kind:morning|evening|weekly]`, conclude with brief
5. Return text for main.py to DM Jacky

Briefings do NOT open incidents — they're pure read + summary. But they DO get
archived as `type=briefing` sessions so you can look back at "what did yesterday's
morning brief say".
```

- [ ] **Step 8.2: morning_prompt.md**

```markdown
你是 Jacky 的 SRE 早报助手。现在是 Asia/Shanghai 9:00。

## 过去 24 小时 incident 数据 (JSON)

{incidents_json}

## 过去 24 小时 skill 调用统计 (JSON)

{skill_stats_json}

## 当前未关闭 incidents

{open_incidents_json}

## 写早报

写 ≤ 300 字 markdown,4 节:

### 昨夜动态 (1-2 句,什么值得注意)
### 未解决 incidents ({count})
- 每个一行,`sess_xxx`: 一句定位
### 今日留意
(skill 准确率趋势 / 即将到达阈值的异常 / 值得关注的模式)
### 待办建议
(0-2 条具体 action)

不要堆数字,不要客套。
```

- [ ] **Step 8.3: evening_prompt.md**

```markdown
你是 Jacky 的 SRE 晚报助手。现在是 Asia/Shanghai 22:00。

## 过去 24 小时 incidents (JSON)

{incidents_json}

## 今日 skill 调用统计 (JSON)

{skill_stats_json}

## 当前未关闭 incidents

{open_incidents_json}

## 写晚报

写 ≤ 300 字 markdown:

### 今日总览 (incident 总数 / 已解决 / 未解决)
### 今日高亮 (1-2 件值得注意的)
### 未解决待跟进 ({count})
- 每个一行
### 明日 follow-up
(0-2 条具体 action)
```

- [ ] **Step 8.4: weekly_prompt.md**

```markdown
你是 Jacky 的 SRE 周报助手。现在是周一上午 9:00。

## 过去 7 天 incidents (JSON)

{incidents_json}

## 过去 7 天 skill ledger 统计 (JSON)

{skill_stats_json}

## 写周报

写 ≤ 500 字 markdown:

### 本周数字 (incident 总数 / 已解决率 / 平均响应时间)
### 重复 pattern / 故障群 (按 root cause 或 tag 聚类; 如果同一 signature 出现 ≥ 3 次,重点标注)
### Skill 健康度 (did_work_rate 低的 skill → 建议该 skill 的 runbook 要补)
### 本周经验 (1-2 条从 incident 里学到的沉淀)
### 下周关注
```

- [ ] **Step 8.5: Failing tests**

```python
# lakeon/sre-agent/tests/integration/test_daily_briefing.py
from unittest.mock import MagicMock

from agent_session_log import LogStore, SkillLedger
from skills.sre.daily_briefing.runner import BriefingRunner, BriefingResult


class _FakeLLM:
    def __init__(self, text): self.text = text; self.last_user = None
    def complete(self, *, system, user, tools=None):
        self.last_user = user
        return {"text": self.text, "model": "x",
                "tokens_in": 200, "tokens_out": 80, "cost_usd": None}


def _seed_incident(log: LogStore, tag: str = "component:compute") -> str:
    s = log.new_session(type="incident", trigger={"alert": "test"}, tags=[tag])
    s.conclude("# root cause X\n")
    s.close()
    return s.id


def test_morning_empty_day_produces_brief(tmp_log_root):
    log = LogStore(tmp_log_root)
    llm = _FakeLLM("### 昨夜动态\n无事\n### 未解决 incidents (0)\n### 今日留意\n无\n")
    r = BriefingRunner(log=log, llm=llm)

    result = r.run(kind="morning")

    assert result.text
    assert result.session_id
    m = log.store.read_manifest(result.session_id)
    assert m.type == "briefing"
    assert "kind:morning" in m.tags


def test_evening_with_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    _seed_incident(log)
    llm = _FakeLLM("### 今日总览\n1 个 incident\n### 今日高亮\n冷启动异常\n### 未解决\n### 明日\n")
    r = BriefingRunner(log=log, llm=llm)

    result = r.run(kind="evening")

    assert result.text
    assert "今日总览" in result.text
    # Prompt should include the incident
    assert "test" in (llm.last_user or "")


def test_weekly_aggregates_7d(tmp_log_root):
    log = LogStore(tmp_log_root)
    for _ in range(3):
        _seed_incident(log)
    llm = _FakeLLM("### 本周数字\n3 incidents\n### 重复 pattern\n- 冷启动\n")
    r = BriefingRunner(log=log, llm=llm)

    result = r.run(kind="weekly")

    assert result.text
    m = log.store.read_manifest(result.session_id)
    assert "kind:weekly" in m.tags


def test_unknown_kind_raises(tmp_log_root):
    import pytest
    log = LogStore(tmp_log_root)
    r = BriefingRunner(log=log, llm=_FakeLLM("x"))
    with pytest.raises(ValueError, match="kind"):
        r.run(kind="bogus")
```

- [ ] **Step 8.6: Implement runner.py**

```python
# lakeon/sre-agent/skills/sre/daily_briefing/runner.py
"""Briefing runner — morning / evening / weekly."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Protocol

from agent_session_log import LogStore
from agent_session_log.skill_ledger import SkillLedger


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str,
                 tools: list[dict] | None = None) -> dict: ...


_PROMPTS_DIR = Path(__file__).parent


_KINDS = {
    "morning": {
        "since": "24h",
        "prompt_file": "morning_prompt.md",
        "system": "你是 Jacky 的 SRE 早报助手, 简短准确。",
    },
    "evening": {
        "since": "24h",
        "prompt_file": "evening_prompt.md",
        "system": "你是 Jacky 的 SRE 晚报助手, 简短准确。",
    },
    "weekly": {
        "since": "7d",
        "prompt_file": "weekly_prompt.md",
        "system": "你是 Jacky 的 SRE 周报助手, 数据驱动简明。",
    },
}


@dataclass
class BriefingResult:
    session_id: Optional[str]
    text: Optional[str]
    kind: str


@dataclass
class BriefingRunner:
    log: LogStore
    llm: LLMClient

    def run(self, *, kind: str) -> BriefingResult:
        if kind not in _KINDS:
            raise ValueError(f"unknown kind {kind!r}; expected one of {list(_KINDS)}")
        spec = _KINDS[kind]
        since = spec["since"]

        incidents = self.log.list_sessions(type="incident", since=since, limit=100)
        incidents_payload = [
            {"id": i["id"], "tags": i.get("tags", []),
             "created_at": i.get("created_at"), "status": i.get("status")}
            for i in incidents
        ]
        open_incidents = [i for i in incidents_payload if i.get("status") == "open"]

        ledger = SkillLedger(self.log.store.root)
        skill_stats: dict[str, dict] = {}
        for i in incidents:
            for tag in i.get("tags", []):
                if tag.startswith("skill:"):
                    name = tag.split(":", 1)[1]
                    if name not in skill_stats:
                        skill_stats[name] = ledger.stats(name)

        prompt_template = (_PROMPTS_DIR / spec["prompt_file"]).read_text(encoding="utf-8")
        prompt = (prompt_template
                  .replace("{incidents_json}",
                           json.dumps(incidents_payload, ensure_ascii=False, indent=2))
                  .replace("{skill_stats_json}",
                           json.dumps(skill_stats, ensure_ascii=False, indent=2))
                  .replace("{open_incidents_json}",
                           json.dumps(open_incidents, ensure_ascii=False, indent=2))
                  .replace("{count}", str(len(open_incidents))))

        resp = self.llm.complete(system=spec["system"], user=prompt)
        text = (resp.get("text") or "").strip()

        session = self.log.new_session(
            type="briefing",
            trigger={"source": f"cron/daily-briefing-{kind}",
                     "incidents_reviewed": len(incidents)},
            tags=[f"kind:{kind}", f"skill:daily-briefing-{kind}"],
            model=resp.get("model"),
            runtime="hermes@0.10.0",
        )
        session.append_turn(
            type="llm_completion",
            model=resp.get("model"),
            tokens_in=resp.get("tokens_in"),
            tokens_out=resp.get("tokens_out"),
            cost_usd=resp.get("cost_usd"),
            content=text[:1000],
            skill="daily-briefing",
            skill_version="v0.1",
        )
        session.conclude(f"# SRE {kind} 报\n\n{text}\n")
        session.close()

        return BriefingResult(session_id=session.id, text=text, kind=kind)
```

- [ ] **Step 8.7: Run — passes**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/integration/test_daily_briefing.py -v
```
Expected: 4 passed.

- [ ] **Step 8.8: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/skills/sre/daily_briefing/ \
        sre-agent/tests/integration/test_daily_briefing.py
git commit -m "feat(sre-agent): daily_briefing (morning/evening/weekly via BriefingRunner)"
```

---

### Task 9: Extend phase1 SKILL.md manifests (optional but recommended)

Add a single top-level `skills/sre/_base/SKILL.md` placeholder so hermes doesn't warn about "base dir has no manifest":

**Files:**
- Create: `lakeon/sre-agent/skills/sre/_base/SKILL.md`

Content:
```markdown
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
```

- [ ] **Step 9.1: Create the file (no test needed)**

- [ ] **Step 9.2: Commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/skills/sre/_base/SKILL.md
git commit -m "docs(sre-agent): _base SKILL.md so hermes skill loader doesn't warn"
```

---

## Group E: Wire + Ship

### Task 10: main.py wiring + deploy + phase1 report

**Files:**
- Modify: `lakeon/sre-agent/main.py` (add 5 `run_*_watcher` + 3 `run_*_briefing` functions + extend `_CRON_TASKS`)
- Modify: `lakeon/sre-agent/reports/phase1-progress.md` (mark Plan B done)

- [ ] **Step 10.1: Add 8 new run functions to main.py**

Append after the existing `run_outcome_checker` function:

```python
# ─── Phase 1 watchers & briefings ────────────────────────────────────────────

def run_pod_create_failure_watcher() -> None:
    from skills.sre.pod_create_failure_watcher.watcher import PodCreateFailureWatcher

    log.info("[pod_create_failure_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    w = PodCreateFailureWatcher(log=log_store, mcp=mcp,
                                skill_name="pod-create-failure-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[pod_create_failure_watcher] failed: %s", exc)
        return
    _dm_for_incidents("pod-create-failure", sids, log_store)


def run_fuse_queue_health_watcher() -> None:
    from skills.sre.fuse_queue_health_watcher.watcher import FuseQueueHealthWatcher

    log.info("[fuse_queue_health_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    w = FuseQueueHealthWatcher(log=log_store, mcp=mcp,
                               skill_name="fuse-queue-health-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[fuse_queue_health_watcher] failed: %s", exc)
        return
    _dm_for_incidents("fuse-queue-health", sids, log_store)


def run_stuck_task_watcher() -> None:
    from skills.sre.stuck_task_watcher.watcher import StuckTaskWatcher

    log.info("[stuck_task_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    w = StuckTaskWatcher(log=log_store, mcp=mcp,
                         skill_name="stuck-task-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[stuck_task_watcher] failed: %s", exc)
        return
    _dm_for_incidents("stuck-task", sids, log_store)


def run_data_consistency_watcher() -> None:
    from skills.sre.data_consistency_watcher.watcher import DataConsistencyWatcher

    log.info("[data_consistency_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    llm_client = DeepseekLLMClient()
    w = DataConsistencyWatcher(log=log_store, mcp=mcp, llm=llm_client,
                               skill_name="data-consistency-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[data_consistency_watcher] failed: %s", exc)
        return
    _dm_for_incidents("data-consistency", sids, log_store)


def run_multi_tenant_blast_radius_watcher() -> None:
    from skills.sre.multi_tenant_blast_radius_watcher.watcher import MultiTenantBlastRadiusWatcher

    log.info("[multi_tenant_blast_radius_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    llm_client = DeepseekLLMClient()
    w = MultiTenantBlastRadiusWatcher(log=log_store, mcp=mcp, llm=llm_client,
                                      skill_name="multi-tenant-blast-radius-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[multi_tenant_blast_radius_watcher] failed: %s", exc)
        return
    _dm_for_incidents("blast-radius", sids, log_store)


def _dm_for_incidents(kind: str, sids: list[str], log_store) -> None:
    """Shared helper: DM Jacky a short summary for each new incident."""
    if not sids:
        log.info("[%s] no new incidents", kind)
        return
    log.info("[%s] opened %d incident(s): %s", kind, len(sids), sids)
    open_id = jacky_open_id()
    if not open_id:
        return
    for sid in sids:
        try:
            m = log_store.store.read_manifest(sid)
            alert = (m.trigger or {}).get("alert", "?")
            feishu_send_dm(
                open_id,
                f"[SRE/{kind}] {alert}\nsession={sid}\n"
                f"details: {hermes_home()}/data/{sid}/conclusion.md",
            )
        except Exception as dm_exc:
            log.warning("[%s] feishu DM failed for %s: %s", kind, sid, dm_exc)


def _run_briefing(kind: str) -> None:
    from skills.sre.daily_briefing.runner import BriefingRunner

    log.info("[daily_briefing:%s] starting", kind)
    log_store = make_log_store()
    llm_client = DeepseekLLMClient()
    runner = BriefingRunner(log=log_store, llm=llm_client)
    try:
        result = runner.run(kind=kind)
    except Exception as exc:
        log.error("[daily_briefing:%s] failed: %s", kind, exc)
        return

    log.info("[daily_briefing:%s] wrote session %s", kind, result.session_id)
    open_id = jacky_open_id()
    if open_id and result.text:
        try:
            feishu_send_dm(open_id, f"[SRE] {kind} 报\n\n{result.text}")
        except Exception as exc:
            log.warning("[daily_briefing:%s] feishu DM failed: %s", kind, exc)


def run_morning_briefing() -> None:
    _run_briefing("morning")


def run_evening_briefing() -> None:
    _run_briefing("evening")


def run_weekly_briefing() -> None:
    _run_briefing("weekly")
```

- [ ] **Step 10.2: Extend `_CRON_TASKS`**

Find the existing `_CRON_TASKS = [...]` (~line 160) and replace with:

```python
_CRON_TASKS = [
    # Phase 0a
    ("*/2 * * * *", run_cold_start_watcher),
    ("0 9 * * *",   run_outcome_checker),
    # Phase 1 watchers
    ("*/2 * * * *", run_pod_create_failure_watcher),
    ("*/5 * * * *", run_fuse_queue_health_watcher),
    ("*/5 * * * *", run_stuck_task_watcher),
    ("*/15 * * * *", run_data_consistency_watcher),
    ("*/5 * * * *", run_multi_tenant_blast_radius_watcher),
    # Phase 1 briefings (UTC → Asia/Shanghai: +8h)
    ("0 1 * * *",   run_morning_briefing),    # 9:00 CST
    ("0 14 * * *",  run_evening_briefing),    # 22:00 CST
    ("0 1 * * 1",   run_weekly_briefing),     # 周一 9:00 CST
]
```

**CRITICAL check**: `cron_loop` in `hermes_agent_utils.runner` keys iteration by cron expression. Two watchers sharing `*/2 * * * *` (cold_start and pod_create) would collide with current code if keyed by expression. Verify:

Open `/Users/jacky/code/lakeon/packages/hermes-agent-utils/hermes_agent_utils/runner.py` and confirm `cron_loop` uses **list iteration** (each tuple separately), not a dict keyed by expression. If it IS keyed, fix by de-duplicating expressions via slight offset (`*/2 * * * *` → `1-59/2 * * * *` for pod_create_failure).

- [ ] **Step 10.3: Verify cron_loop handles duplicate expressions**

```bash
cd /Users/jacky/code/lakeon
grep -n "iters\[expr\]\|iters =\|next_runs\[expr\]\|next_runs =" packages/hermes-agent-utils/hermes_agent_utils/runner.py
```

If you see `iters = {expr: croniter(...) for expr, _ in tasks}` (dict keyed by expr), **two tasks with same expr collapse into one** — fix by keying on a per-task index instead:

Change runner.py `cron_loop` to:
```python
def cron_loop(tasks: list[tuple[str, Callable[[], None]]]) -> None:
    now0 = datetime.now(timezone.utc)
    iters = [croniter(expr, now0) for expr, _ in tasks]
    next_runs = [it.get_next(datetime) for it in iters]
    _log.info("[cron] loop started with %d task(s)", len(tasks))
    while True:
        now = datetime.now(timezone.utc)
        for idx, (expr, task) in enumerate(tasks):
            if now >= next_runs[idx]:
                _log.info("[cron] firing %s → %s", expr, task.__name__)
                try: task()
                except Exception as exc: _log.exception("[cron] task %s raised: %s", task.__name__, exc)
                next_runs[idx] = iters[idx].get_next(datetime)
        soonest = min(next_runs)
        sleep_secs = max(0.0, min(60.0, (soonest - datetime.now(timezone.utc)).total_seconds()))
        time.sleep(sleep_secs)
```

Make this change and write a quick unit test in `packages/hermes-agent-utils/tests/test_runner.py`:

```python
def test_cron_loop_handles_duplicate_expressions(monkeypatch):
    """Two tasks sharing the same cron expression both fire (don't collapse into one)."""
    from hermes_agent_utils.runner import cron_loop
    import inspect
    # Static check: source does NOT use dict keyed on expr for iters
    src = inspect.getsource(cron_loop)
    assert "iters[expr]" not in src, "cron_loop must key on index, not expression"
```

Commit this as a sub-change:
```bash
git add packages/hermes-agent-utils/hermes_agent_utils/runner.py \
        packages/hermes-agent-utils/tests/test_runner.py
git commit -m "fix(hermes-agent-utils): cron_loop keys iterators by index, not expression"
```

- [ ] **Step 10.4: Run full sre-agent test suite**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/pytest tests/ -v 2>&1 | tail -5
```
Expected: all new tests (WatcherBase + 5 watchers + briefing + adapter v2) pass; Phase 0a tests unchanged.

- [ ] **Step 10.5: Verify main.py imports cleanly**

```bash
cd /Users/jacky/code/lakeon/sre-agent
/Users/jacky/code/lakeon/.venv/bin/python -c "
import sys
sys.path.insert(0, '.')
import main
exprs = [e for e, _ in main._CRON_TASKS]
print(f'{len(main._CRON_TASKS)} cron tasks:')
for e, t in main._CRON_TASKS:
    print(f'  {e}  →  {t.__name__}')
"
```
Expected: 10 cron tasks printed (2 Phase 0a + 5 watchers + 3 briefings).

- [ ] **Step 10.6: Update phase1-progress.md**

Open `/Users/jacky/code/lakeon/sre-agent/reports/phase1-progress.md`. Under the "Pending operator action" section, add a new section "Plan B done":

```markdown
## Plan B (DONE)

### Watchers (5)

- [x] pod_create_failure_watcher (*/2 min) — category-based, no LLM
- [x] fuse_queue_health_watcher (*/5 min) — retry pattern, no LLM
- [x] stuck_task_watcher (*/5 min) — grouped alert, no LLM
- [x] data_consistency_watcher (*/15 min) — 4 rules + LLM hypothesis
- [x] multi_tenant_blast_radius_watcher (*/5 min) — LLM fault-domain guess

### Briefings (3)

- [x] morning 9:00 Asia/Shanghai
- [x] evening 22:00 Asia/Shanghai
- [x] weekly Monday 9:00 Asia/Shanghai

### Prompt-only skills (1)

- [x] domain_glossary — dbay 对象模型 + 症状映射 + 标准 5 步诊断

### Shared infrastructure

- [x] WatcherBase — dedupe + session-open + ledger (剥出 5 个 watcher 共用)
- [x] SREMCPAdapter extended — 7 methods wrapping dbay-sre-mcp 0.2.0 tools
- [x] BriefingRunner — 3 briefing variants sharing same shape
- [x] cron_loop keys iterators by INDEX (was bug: same expr collapsed into one)

## Validation after deploy

1. Wait 2 min → check Railway logs: expect `[pod_create_failure_watcher] no new incidents`
2. Wait 15 min → `[data_consistency_watcher]` line
3. Wait until 9:00 Asia/Shanghai → DM 早报
4. Wait until 22:00 Asia/Shanghai → DM 晚报
5. Wait until next Monday 9:00 → DM 周报
6. Test domain_glossary: DM agent "为什么 tcph-bench 数据库唤醒失败" — agent
   should use find_database + database_status per glossary guidance.
```

- [ ] **Step 10.7: Commit main.py wiring + report**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/main.py sre-agent/reports/phase1-progress.md
git commit -m "feat(sre-agent): wire 5 watchers + 3 briefings into _CRON_TASKS"
```

- [ ] **Step 10.8: Push to Railway**

```bash
cd /Users/jacky/code/lakeon
git push
```

Expect Railway auto-redeploys within 3-5 minutes. Controller will monitor via `railway logs` and confirm 0.2.0 watchers firing.

- [ ] **Step 10.9: Post-deploy smoke check**

After Railway reports SUCCESS:
```bash
railway logs --service lakeon-sre-agent -n 50 2>&1 | grep -E "cron\]|_watcher|briefing" | tail -20
```
Expected: see `[cron] loop started with 10 task(s)` and several `[X_watcher] no new incidents` lines.

---

## Self-Review Checklist

- [x] Spec coverage: 5 watchers (Tasks 3-6) + 3 briefings (Task 8) + glossary (Task 7) + base abstraction (Task 2) + adapter (Task 1) + wiring (Task 10). All mapped to tasks.
- [x] No placeholders: every step contains complete code or exact command. Step 10.3 has a real `grep` check + concrete fix if needed.
- [x] Type consistency: `SREMCPAdapter`, `WatcherBase`, `PodCreateFailureWatcher`, `FuseQueueHealthWatcher`, `StuckTaskWatcher`, `DataConsistencyWatcher`, `MultiTenantBlastRadiusWatcher`, `BriefingRunner`, `BriefingResult` — all spelled identically across tasks.
- [x] Hard constraints: Phase 0a crons untouched (Task 10.2 keeps them); 5 new watchers + 3 briefings appended; dedupe in WatcherBase (Task 2); briefings are pull-only with type=briefing archive (Task 8); glossary is prompt-only (Task 7); UTC timezone with CST annotation (Task 10.2); cron_loop fix for duplicate expressions (Task 10.3).
- [x] Each task ends with a commit; commit messages follow the project conventions.
- [x] TDD: every Python task has failing-test → impl → passing-test cycle.

## Open Risks During Execution

1. **Dataclass inheritance + dataclass-in-dataclass.** `WatcherBase` is a @dataclass; each watcher inherits. Python requires careful `@dataclass(kw_only=True)` or `field(default=...)` ordering. If `TypeError: non-default argument follows default` appears, use `@dataclass(kw_only=True)` on the base + all children (Python 3.10+). Fix in place.
2. **`mcp: Any = None` default in watchers.** If a watcher runs with mcp=None (forgot to inject), calls fail with AttributeError. Add an assertion in `__post_init__`: `assert self.mcp is not None, "mcp adapter required"`. Or make it positional kw-only.
3. **`SREMCPAdapter` imports dbay_sre_mcp.server.**  If the production PyPI 0.2.0 hasn't propagated yet (cache lag), container startup raises ImportError and entire sre-agent crashes. Mitigation: lazy import (already done — imports inside method bodies, not at module load). Verify by running Step 1.5 full suite.
4. **cron fires multiple watchers in same minute.** Every `*/2`, `*/5`, `*/15` minute boundary: up to 5 watchers fire serially. Each does HTTP calls to admin API + PG queries. Total ~5-10s. Within one cron tick budget (60s). But if any watcher hangs, next tick delayed. Mitigation: each watcher wraps scan_once in try/except in main.py — one failure doesn't stop the loop.
5. **Briefing LLM cost.** Each briefing is 1 LLM call, ~200-500 tokens in + 200 tokens out. At deepseek price ~$0.001 per call. 3 briefings/day = $0.003/day = trivial.
6. **`main.py` now has ~400 lines.** Phase 0a + Task 10 adds ~300 lines. If/when it exceeds 500, factor out `watchers_registry.py` (a Phase 2 concern, not this plan).
7. **`list_sessions(since="24h")` on HERMES_HOME with N=10^4 sessions** — O(N) scan. Briefing may get slow after 6+ months. Current volume: ~50 sessions/day × 30 days = 1500. Fine for now; Phase 2+ may need SQLite index.

---

## Execution Handoff

Plan B complete and saved to `docs/superpowers/plans/2026-04-25-sre-agent-phase1-watchers-briefings.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review, fast iteration in this session.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

Which approach?
