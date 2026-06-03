# SRE Agent Phase 0b — Reading Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Phase 0a 已部署的 hermes + agent_session_log 基础上，加入第二个 consumer——reading companion。形态对齐 Phase 0a 实际架构（cron + CLI + push-only feishu，不是 inbound feishu/MCP/personality），用 `python -m skills.reading.url_handler.cli <url>` 喂 URL；每晚 22:00 cron 跑 daily reflection；本地 CLI / 后续 web 入口跑 query。目的是**用一个完全不同的场景 battle-test `agent_session_log` 抽象，验证"80% 通用"命题**。

**Architecture:** 复用 Phase 0a 所有共享组件——`agent_session_log` 库、`main.py` 里的 `DeepseekLLMClient` / `feishu_send_dm` / `_jacky_open_id` / `_CRON_TASKS` / `_make_log_store`、OBS sync subprocess、`shutil.copytree(skills_src, …)` 把 skills 同步给 hermes 的机制。**新增**：`skills/reading/url_handler` / `query_handler` / `daily_reflection` 三个 skill（带 SKILL.md prompt 也带 Python module）；`main.py` 注册 `run_daily_reflection` 到 `_CRON_TASKS`；`trafilatura` 依赖做 URL 正文抽取（不走 MCP）；reading skills 的入口是 CLI（`python -m skills.reading.<name>.cli`）。

**Tech Stack:** 同 Phase 0a + `trafilatura>=1.6`（HTML→cleaned text 抽取）。

**Related spec:** [`docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md`](../specs/2026-04-23-agent-commit-log-phase0-design.md) — "Agent 2: Reading companion" 与 "核心：agent commit log 数据层" 两节。

**Phase 0a dependency:** [`docs/superpowers/plans/2026-04-23-sre-agent-phase-0a-plan.md`](./2026-04-23-sre-agent-phase-0a-plan.md) — 已完成并部署到 Railway/feishu。

---

## Architecture pivot from earlier plan draft

第一版 plan 假定 hermes 提供 personality 路由、MCP stdio、内置 cron、inbound feishu hook——**Phase 0a 实施时全部放弃了**（见 `sre-agent/main.py` 头部注释和 `sre-agent/docs/HERMES_SKILL_DISPATCH.md`）。Phase 0b 必须沿用 Phase 0a 实际通路：

| Old assumption | Phase 0a reality (we follow this) |
|---|---|
| `hermes_config/mcp.json` 注册 fetch-mcp | hermes 没 mcp.json；外部能力直接 `import` 进 main.py（如 `SREMCPAdapter`、`DeepseekLLMClient`） |
| hermes `personalities.reading` 路由 | hermes 没有 personality；所有 entrypoint 走 `main.py` Python module |
| hermes cron 调 SKILL.md 触发 daily_reflection | hermes cron 跑不了 Python；`main.py._CRON_TASKS` 注册 `(cron_expr, callable)` 元组 |
| 飞书 @ bot 发 URL → SKILL 自动响应 | inbound feishu 由 hermes 内部消化，**不**暴露给 main.py。Reading 入口走 **CLI**（你或 cron 触发）；feishu **只 push**（daily_reflection 推送、url_handler 抓完后可选推 confirm DM） |
| `fetch-mcp` 通过 stdio 抓 URL | 直接 `httpx.get(url)` + `trafilatura.extract(html)` 得到正文 |

SKILL.md 文件仍然写——hermes 把它们 copy 到 `$HERMES_HOME/skills/` 后 LLM 可以 `skill_view` 读到，作为人类可读的能力声明，但 hermes 不执行。

## Hard Constraints (开工前自查)

1. **禁止修改或 rename** `agent_session_log` 里 Phase 0a 已定义的任何 class / method / property。
2. 如果 reading 场景逼你想"给 SRE 用过的 API 打补丁"——**停手**，重新统一抽象。冲突写到本 plan 末尾的 "API friction log"。
3. `skills/reading/` 代码只通过 `agent_session_log` 公共 `__init__.py` 导入；**不允许** `from agent_session_log.session import _privatemethod`。
4. `skills/reading/` 不能 `import skills.sre.*`；反之亦然。Reading 和 SRE 是两个独立 consumer，共享的只有 `agent_session_log`。
5. `main.py` 里 reading 相关函数复用 `_make_log_store`/`_make_ledger`/`DeepseekLLMClient`/`feishu_send_dm`/`_jacky_open_id`/`_hermes_home`——**不要重新实现**。

约束目的：Phase 2 把 `agent_session_log` 抽成独立 package 时，零重写。

---

## File Structure (增量，相对于 Phase 0a 已有)

```
sre-agent/                                         # EXISTING
├── pyproject.toml                                 # MODIFY: add trafilatura
├── main.py                                        # MODIFY: register run_daily_reflection cron
├── agent_session_log/
│   └── log.py                                     # MODIFY: list_sessions(+since=)
├── skills/
│   ├── sre/                                       # EXISTING (untouched)
│   └── reading/                                   # NEW
│       ├── __init__.py
│       ├── SKILL.md                               # category-level (LLM context)
│       ├── url_handler/
│       │   ├── __init__.py
│       │   ├── SKILL.md                           # prompt-only declaration
│       │   ├── fetch.py                           # httpx + trafilatura wrapper
│       │   ├── extract.py                         # LLM 提炼要点
│       │   ├── related.py                         # 关联过去 reading session
│       │   ├── reply.py                           # 飞书 markdown 组装
│       │   ├── handler.py                         # main entry: handle_url(url, ...)
│       │   ├── cli.py                             # `python -m skills.reading.url_handler.cli`
│       │   ├── extract_prompt.md
│       │   └── reply_template.md
│       ├── query_handler/
│       │   ├── __init__.py
│       │   ├── SKILL.md
│       │   ├── handler.py                         # answer_question(...)
│       │   ├── cli.py                             # `python -m skills.reading.query_handler.cli "<question>"`
│       │   └── query_prompt.md
│       └── daily_reflection/
│           ├── __init__.py
│           ├── SKILL.md
│           ├── reflect.py                         # reflect_today(...)
│           └── reflect_prompt.md
└── tests/
    ├── test_log_query.py                          # MODIFY: since= cases
    ├── test_import_discipline.py                  # MODIFY: reading↔sre independence
    └── integration/
        ├── test_url_handler.py                    # NEW
        ├── test_query_handler.py                  # NEW
        ├── test_daily_reflection.py               # NEW
        └── test_phase_0b_cross_consumer.py       # NEW
```

### Module responsibilities

| Module | Responsibility | Dependencies |
|---|---|---|
| `agent_session_log/log.py` | 现有 + additive `since=` parser/filter | stdlib (datetime, re) |
| `skills/reading/url_handler/fetch.py` | `fetch_url(url) → FetchedDoc` 用 httpx + trafilatura；离线 fake 友好 | httpx, trafilatura |
| `skills/reading/url_handler/extract.py` | LLM-driven 摘要：body → title/key_points/keywords/quotes | LLM protocol |
| `skills/reading/url_handler/related.py` | keywords → 过去 reading session list | `agent_session_log.LogStore` |
| `skills/reading/url_handler/reply.py` | session → 飞书 markdown text | `agent_session_log.LogStore` |
| `skills/reading/url_handler/handler.py` | full flow: 开 session → fetch → extract → relate → conclude → close | 上述 + `agent_session_log` |
| `skills/reading/url_handler/cli.py` | `argparse` CLI；wires real `DeepseekLLMClient` / `_make_log_store` from main; optional feishu DM ack | main, agent_session_log |
| `skills/reading/query_handler/handler.py` | question → 查 commit log → LLM 回答（不开 session） | LLM, LogStore |
| `skills/reading/query_handler/cli.py` | CLI entry, wires real LLM/LogStore | main, agent_session_log |
| `skills/reading/daily_reflection/reflect.py` | 今日 reading → reflection session → text | LLM, LogStore |
| `main.py` | + `run_daily_reflection` 注册到 `_CRON_TASKS`；推 reflection 到 feishu | reflect, feishu_send_dm |

---

## Work Breakdown — 10 Tasks

| Group | Tasks | What it produces |
|---|---|---|
| A. API additive | 1 | `list_sessions(since=...)` |
| B. URL pipeline | 2-6 | fetch + extract + related + reply + handler + CLI；URL → commit log work end-to-end via CLI |
| C. Query + reflection | 7-8 | query_handler + daily_reflection 单测过 |
| D. main.py wiring + cross-consumer + deploy | 9-10 | cron 挂 reflection；跨 consumer 测试；Railway 验证 |

---

## Group A: Additive API Extension

### Task 1: `LogStore.list_sessions` accepts `since=`

**Why this is the only API change:** Daily reflection 要"今天所有 reading session"；query 要"过去 30 天"。Phase 0a `list_sessions` 没带 since。**Additive** 参数不破坏 SRE 现有调用。

**Files:**
- Modify: `sre-agent/agent_session_log/log.py:25-53`（`list_sessions`）
- Modify: `sre-agent/tests/test_log_query.py`

- [ ] **Step 1.1: Append failing tests**

Append to `sre-agent/tests/test_log_query.py`:

```python
import pytest
from datetime import datetime, timedelta, timezone
from agent_session_log.log import LogStore


def test_list_sessions_since_filters_old(tmp_log_root):
    log = LogStore(tmp_log_root)
    old = log.new_session(type="reading", trigger={}, tags=[])
    old.close()
    m = log.store.read_manifest(old.id)
    m.created_at = (datetime.now(timezone.utc) - timedelta(days=10)).strftime("%Y-%m-%dT%H:%M:%SZ")
    log.store.write_manifest(m)

    recent = log.new_session(type="reading", trigger={}, tags=[])
    recent.close()

    since_3d = log.list_sessions(type="reading", since="3d")
    assert [x["id"] for x in since_3d] == [recent.id]

    since_30d = log.list_sessions(type="reading", since="30d")
    assert {x["id"] for x in since_30d} == {old.id, recent.id}


def test_list_sessions_since_supports_seconds_minutes_hours(tmp_log_root):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.close()
    assert len(log.list_sessions(since="60s")) == 1
    assert len(log.list_sessions(since="5m")) == 1
    assert len(log.list_sessions(since="1h")) == 1
    assert len(log.list_sessions(since="1d")) == 1


def test_list_sessions_since_rejects_bad_format(tmp_log_root):
    log = LogStore(tmp_log_root)
    with pytest.raises(ValueError, match="since"):
        log.list_sessions(since="1 week")
    with pytest.raises(ValueError, match="since"):
        log.list_sessions(since="abc")


def test_list_sessions_since_none_returns_all(tmp_log_root):
    """Phase 0a SRE callers don't pass since — must keep working."""
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.close()
    assert len(log.list_sessions(type="incident")) == 1
    assert len(log.list_sessions(type="incident", since=None)) == 1
```

- [ ] **Step 1.2: Run — fails**

```bash
cd /Users/jacky/code/lakeon/sre-agent
uv run pytest tests/test_log_query.py -v
```
Expected: 4 new tests FAIL (TypeError: unexpected keyword `since`, or assertion).

- [ ] **Step 1.3: Implement `since` parser + filter**

Edit `sre-agent/agent_session_log/log.py`. Add at top of file (after the existing imports):

```python
import re
from datetime import datetime, timedelta, timezone


_SINCE_RE = re.compile(r"^(\d+)(s|m|h|d)$")


def _parse_since(since: str | None) -> datetime | None:
    """Parse since spec like '60s' '5m' '1h' '30d'. None => no filter."""
    if since is None:
        return None
    m = _SINCE_RE.match(since.strip())
    if not m:
        raise ValueError(f"invalid since format {since!r}; use e.g. '30d' or '5m'")
    n, unit = int(m.group(1)), m.group(2)
    delta = {
        "s": timedelta(seconds=n),
        "m": timedelta(minutes=n),
        "h": timedelta(hours=n),
        "d": timedelta(days=n),
    }[unit]
    return datetime.now(timezone.utc) - delta
```

Replace the `list_sessions` method body with the version that filters by `cutoff`:

```python
    def list_sessions(
        self,
        type: str | None = None,
        tags: list[str] | None = None,
        since: str | None = None,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """Return list of manifests (as dicts), newest first, optionally filtered.

        since: relative cutoff like "30d", "1h", "5m", "60s". None = no cutoff.
        """
        cutoff = _parse_since(since)
        ids = self._store.iter_session_ids()
        out: list[dict[str, Any]] = []
        for sid in reversed(ids):
            try:
                m = self._store.read_manifest(sid)
            except FileNotFoundError:
                continue
            if type and m.type != type:
                continue
            if tags and not all(tag in m.tags for tag in tags):
                continue
            if cutoff is not None:
                created = datetime.fromisoformat(m.created_at.replace("Z", "+00:00"))
                if created < cutoff:
                    continue
            out.append({
                "id": m.id,
                "type": m.type,
                "status": m.status,
                "created_at": m.created_at,
                "closed_at": m.closed_at,
                "tags": m.tags,
            })
            if len(out) >= limit:
                break
        return out
```

- [ ] **Step 1.4: Run new tests — pass**

```bash
uv run pytest tests/test_log_query.py -v
```
Expected: 9 passed (5 existing + 4 new).

- [ ] **Step 1.5: Phase 0a regression**

```bash
uv run pytest tests/ -v --ignore=tests/integration
```
Expected: all green; SRE `list_sessions(type=...)` calls unchanged.

- [ ] **Step 1.6: Commit**

```bash
git add sre-agent/agent_session_log/log.py sre-agent/tests/test_log_query.py
git commit -m "feat(agent_session_log): list_sessions(since=) additive filter"
```

---

## Group B: URL Pipeline

### Task 2: `fetch.py` — URL → cleaned body via httpx + trafilatura

**Why direct (not MCP):** Phase 0a `SREMCPAdapter` 的模式是 in-process import；fetch-mcp 走 stdio 反而复杂。`trafilatura` 一行从 HTML 抽干净正文。

**Files:**
- Modify: `sre-agent/pyproject.toml` (add `trafilatura>=1.6`)
- Create: `sre-agent/skills/reading/__init__.py` (empty)
- Create: `sre-agent/skills/reading/SKILL.md` (category-level)
- Create: `sre-agent/skills/reading/url_handler/__init__.py` (empty)
- Create: `sre-agent/skills/reading/url_handler/fetch.py`
- Create: `sre-agent/tests/integration/__init__.py` (already exists from 0a — skip)
- Create: `sre-agent/tests/integration/test_url_handler.py`

- [ ] **Step 2.1: Add dependency**

Edit `sre-agent/pyproject.toml` and add to the `dependencies` list:

```
"trafilatura>=1.6",
```

Then:
```bash
cd /Users/jacky/code/lakeon/sre-agent
uv sync
```
Expected: trafilatura installed.

- [ ] **Step 2.2: Category-level SKILL.md**

Create `sre-agent/skills/reading/SKILL.md`:

```markdown
---
name: reading
description: Reading companion — distill articles you read into commit log, link to past readings, reflect nightly.
category: true
---

# Reading companion

This category houses skills that help Jacky digest what he reads:

- **url_handler**: When a URL is fed (via CLI or future feishu inbound), fetch
  the page body, distill it via LLM, link it to past reading sessions, and
  write a `type=reading` session.
- **query_handler**: Answer free-form questions like "我最近读了什么关于
  agent commit log 的" by searching the reading sessions in commit log.
- **daily_reflection**: Cron at 22:00 — review today's reading sessions, write
  a `type=reflection` session, push the reflection text to Jacky on feishu.

All three skills consume `agent_session_log` only — they share zero code with
the SRE category.
```

- [ ] **Step 2.3: Failing tests for `fetch_url`**

Create `sre-agent/tests/integration/test_url_handler.py`:

```python
"""Tests for reading/url_handler — fetch, extract, related, full flow."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from agent_session_log import LogStore


# ────────── Fakes shared across tests ──────────

class FakeLLM:
    """Record calls; return whatever is enqueued next."""
    def __init__(self, responses: list[dict]):
        self.responses = list(responses)
        self.calls: list[dict] = []

    def complete(self, *, system, user, tools=None):
        self.calls.append({"system": system, "user": user})
        if not self.responses:
            raise AssertionError("FakeLLM ran out of canned responses")
        return self.responses.pop(0)


class StaticHttpClient:
    """Stand-in for httpx.Client.get(): return canned responses by URL."""
    def __init__(self, pages: dict[str, tuple[int, str]]):
        self.pages = pages
        self.calls: list[str] = []

    def get(self, url: str, *args, **kwargs):
        self.calls.append(url)
        if url not in self.pages:
            class R:
                status_code = 404
                text = ""
                def raise_for_status(self): raise RuntimeError("404")
            return R()
        status, html = self.pages[url]
        class R:
            def __init__(self, s, h):
                self.status_code = s
                self.text = h
            def raise_for_status(self):
                if self.status_code >= 400:
                    raise RuntimeError(f"HTTP {self.status_code}")
        return R(status, html)


# ────────── fetch.py tests ──────────

def test_fetch_url_strips_html_keeps_main_text():
    from skills.reading.url_handler.fetch import fetch_url

    html = """
    <html><head><title>My Post</title></head>
    <body>
      <nav>Home About</nav>
      <article>
        <h1>Headline</h1>
        <p>This is the first paragraph of real content.</p>
        <p>And here is a second paragraph with detail.</p>
      </article>
      <footer>(c) 2026</footer>
    </body></html>
    """
    http = StaticHttpClient({"https://x.com/post": (200, html)})

    doc = fetch_url("https://x.com/post", client=http)

    assert doc.url == "https://x.com/post"
    assert "first paragraph" in doc.body
    assert "second paragraph" in doc.body
    # Chrome / nav / footer should not leak into the body
    assert "Home About" not in doc.body
    assert "(c) 2026" not in doc.body
    assert doc.title  # extracted by trafilatura or fallback to <title>


def test_fetch_url_404_raises():
    from skills.reading.url_handler.fetch import fetch_url, FetchError

    http = StaticHttpClient({})
    with pytest.raises(FetchError, match="HTTP"):
        fetch_url("https://x.com/missing", client=http)


def test_fetch_url_empty_body_raises():
    from skills.reading.url_handler.fetch import fetch_url, FetchError

    # HTML with no extractable content (just a <script>)
    http = StaticHttpClient({"https://x": (200, "<html><body><script>x=1</script></body></html>")})
    with pytest.raises(FetchError, match="extract"):
        fetch_url("https://x", client=http)
```

- [ ] **Step 2.4: Run — fails**

```bash
uv run pytest tests/integration/test_url_handler.py -v
```
Expected: ModuleNotFoundError.

- [ ] **Step 2.5: Implement `fetch.py`**

Create `sre-agent/skills/reading/url_handler/fetch.py`:

```python
"""Fetch a URL and extract main content using trafilatura.

Uses an httpx client we accept by injection (so tests can use StaticHttpClient).
In production, callers pass a real `httpx.Client` instance.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Protocol

import trafilatura


class HttpClient(Protocol):
    def get(self, url: str, *args: Any, **kwargs: Any) -> Any: ...


class FetchError(RuntimeError):
    pass


@dataclass(frozen=True)
class FetchedDoc:
    url: str
    title: str
    body: str           # cleaned plain text / markdown
    raw_html: str       # full original response (for evidence blob)


_TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.IGNORECASE | re.DOTALL)


def _fallback_title(html: str, url: str) -> str:
    m = _TITLE_RE.search(html)
    if m:
        return re.sub(r"\s+", " ", m.group(1)).strip()[:80]
    return url


def fetch_url(url: str, *, client: HttpClient, timeout: float = 30.0) -> FetchedDoc:
    """Fetch URL and return cleaned body + original html. Raises FetchError on any failure."""
    try:
        resp = client.get(url, timeout=timeout, follow_redirects=True,
                          headers={"User-Agent": "dbay-reading-companion/0.1"})
        resp.raise_for_status()
    except FetchError:
        raise
    except Exception as exc:  # noqa: BLE001
        raise FetchError(f"HTTP fetch failed for {url}: {exc}") from exc

    html = getattr(resp, "text", "") or ""
    if not html.strip():
        raise FetchError(f"empty response from {url}")

    body = trafilatura.extract(
        html,
        include_comments=False,
        include_tables=True,
        favor_recall=True,
        url=url,
    )
    if not body or not body.strip():
        raise FetchError(f"could not extract main text from {url}")

    title = _fallback_title(html, url)
    # trafilatura sometimes returns markdown-ish; strip leading H1 if it duplicates title
    body = body.strip()
    return FetchedDoc(url=url, title=title, body=body, raw_html=html)
```

- [ ] **Step 2.6: Run — passes**

```bash
uv run pytest tests/integration/test_url_handler.py -v
```
Expected: 3 passed.

- [ ] **Step 2.7: Commit**

```bash
git add sre-agent/pyproject.toml sre-agent/uv.lock \
        sre-agent/skills/reading/__init__.py \
        sre-agent/skills/reading/SKILL.md \
        sre-agent/skills/reading/url_handler/__init__.py \
        sre-agent/skills/reading/url_handler/fetch.py \
        sre-agent/tests/integration/test_url_handler.py
git commit -m "feat(sre-agent): reading category + url_handler/fetch.py (httpx+trafilatura)"
```

---

### Task 3: `extract.py` — LLM 提炼要点

**Files:**
- Create: `sre-agent/skills/reading/url_handler/extract.py`
- Create: `sre-agent/skills/reading/url_handler/extract_prompt.md`
- Create: `sre-agent/skills/reading/url_handler/SKILL.md`
- Modify: `sre-agent/tests/integration/test_url_handler.py`

- [ ] **Step 3.1: `SKILL.md` (prompt-only declaration for hermes LLM)**

```markdown
---
name: reading_url_handler
description: When a URL is fed (CLI), fetch it, distill key points, link to past readings, write a reading session.
version: v0.1
---

# reading/url_handler

**Trigger:** CLI `python -m skills.reading.url_handler.cli <url>`
(future: feishu inbound when hermes exposes message hooks).

**Flow:**
1. Open `type=reading` session with trigger `{source, url, received_at, user_open_id}`.
2. `fetch.fetch_url(url)` → cleaned body. Attach raw_html as evidence.
3. `extract.extract(url, body, llm)` → title/key_points/keywords/quotes JSON.
   Attach as evidence; append `llm_completion` turn.
4. `related.find_related(log, keywords, since=30d)` → past reading sessions.
5. Write `conclusion.md` (title, URL, 要点, 相关阅读, 摘抄).
6. Push optional confirmation DM to Jacky via `feishu_send_dm`.
7. `session.close()`.

This skill consumes `agent_session_log` only — does not import any `skills/sre/*`.
```

- [ ] **Step 3.2: `extract_prompt.md`**

```markdown
你是阅读助手。下面是 Jacky 刚刚读到的一篇文章。请完成四件事:

1. 提取 title(一行,不超过 40 字)。
2. 列出 3-5 条 key_points(每条一句话,中文,不超过 40 字,基于原文可验证)。
3. 给出 3-8 个 keywords(用于后续跨 session 关联,中英文均可,不要太泛)。
4. 挑 1-2 句原文里最有代表性的 quote(保留原文,标注上下文几个字让后面能回忆)。

以 JSON 输出,字段固定:

{
  "title": "...",
  "key_points": ["...", "..."],
  "keywords": ["...", "..."],
  "quotes": [{"text": "...", "context": "..."}]
}

只输出 JSON,不要解释。

---

URL: {url}

正文(可能含 markdown):
{body}
```

- [ ] **Step 3.3: Append failing tests**

Append to `sre-agent/tests/integration/test_url_handler.py`:

```python
def test_extract_parses_llm_json():
    from skills.reading.url_handler.extract import extract

    llm = FakeLLM([{
        "text": json.dumps({
            "title": "On Commit Logs",
            "key_points": ["a", "b", "c"],
            "keywords": ["commit log", "agent"],
            "quotes": [{"text": "...", "context": "..."}],
        }),
        "model": "deepseek-chat",
        "tokens_in": 1000,
        "tokens_out": 200,
        "cost_usd": None,
    }])

    out = extract(url="https://x.com", body="body text", llm=llm)

    assert out.title == "On Commit Logs"
    assert out.key_points == ["a", "b", "c"]
    assert out.keywords == ["commit log", "agent"]
    assert out.quotes[0]["text"] == "..."
    assert out.parse_ok is True
    assert out.llm_meta.model == "deepseek-chat"


def test_extract_strips_markdown_fence():
    from skills.reading.url_handler.extract import extract

    text_with_fence = "```json\n" + json.dumps({
        "title": "T", "key_points": ["x"], "keywords": ["k"], "quotes": []
    }) + "\n```"
    llm = FakeLLM([{"text": text_with_fence, "model": "x",
                    "tokens_in": 1, "tokens_out": 1, "cost_usd": None}])
    out = extract(url="https://x", body="b", llm=llm)
    assert out.title == "T"
    assert out.parse_ok is True


def test_extract_handles_invalid_json_with_fallback():
    from skills.reading.url_handler.extract import extract

    llm = FakeLLM([{"text": "this is not JSON", "model": "x",
                    "tokens_in": 1, "tokens_out": 1, "cost_usd": None}])
    out = extract(url="https://x.com", body="First sentence here.\nMore text.", llm=llm)
    assert out.title  # fallback
    assert out.key_points == []
    assert out.parse_ok is False
```

- [ ] **Step 3.4: Run — fails**

Expected: ModuleNotFoundError for `skills.reading.url_handler.extract`.

- [ ] **Step 3.5: Implement `extract.py`**

```python
"""LLM-driven extraction of title / key points / keywords / quotes."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol


_PROMPT = (Path(__file__).parent / "extract_prompt.md").read_text(encoding="utf-8")


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


@dataclass
class LLMMeta:
    model: str
    tokens_in: int | None
    tokens_out: int | None
    cost_usd: float | None


@dataclass
class Extraction:
    title: str
    key_points: list[str] = field(default_factory=list)
    keywords: list[str] = field(default_factory=list)
    quotes: list[dict[str, str]] = field(default_factory=list)
    parse_ok: bool = True
    llm_meta: LLMMeta | None = None

    def as_json(self) -> str:
        return json.dumps(
            {
                "title": self.title,
                "key_points": self.key_points,
                "keywords": self.keywords,
                "quotes": self.quotes,
                "parse_ok": self.parse_ok,
            },
            ensure_ascii=False,
            indent=2,
        )


def _first_nonempty_line(text: str) -> str:
    for line in text.splitlines():
        line = line.strip(" #>-*\t")
        if line:
            return line[:60]
    return "(untitled)"


def _strip_json_fence(s: str) -> str:
    m = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", s, re.DOTALL)
    if m:
        return m.group(1)
    return s


def extract(*, url: str, body: str, llm: LLMClient) -> Extraction:
    prompt = _PROMPT.replace("{url}", url).replace("{body}", body[:12000])
    resp = llm.complete(system="你是精确的阅读助手。", user=prompt)
    meta = LLMMeta(
        model=resp.get("model", ""),
        tokens_in=resp.get("tokens_in"),
        tokens_out=resp.get("tokens_out"),
        cost_usd=resp.get("cost_usd"),
    )
    text = (resp.get("text") or "").strip()
    payload_str = _strip_json_fence(text)

    try:
        payload = json.loads(payload_str)
    except Exception:
        return Extraction(title=_first_nonempty_line(body), parse_ok=False, llm_meta=meta)

    return Extraction(
        title=str(payload.get("title") or _first_nonempty_line(body)),
        key_points=list(payload.get("key_points") or []),
        keywords=list(payload.get("keywords") or []),
        quotes=list(payload.get("quotes") or []),
        parse_ok=True,
        llm_meta=meta,
    )
```

- [ ] **Step 3.6: Run — passes**

```bash
uv run pytest tests/integration/test_url_handler.py -v
```
Expected: 6 passed.

- [ ] **Step 3.7: Commit**

```bash
git add sre-agent/skills/reading/url_handler/extract.py \
        sre-agent/skills/reading/url_handler/extract_prompt.md \
        sre-agent/skills/reading/url_handler/SKILL.md \
        sre-agent/tests/integration/test_url_handler.py
git commit -m "feat(sre-agent): reading/url_handler/extract.py (LLM distillation)"
```

---

### Task 4: `related.py` — keyword linkage to past reading sessions

**Files:**
- Create: `sre-agent/skills/reading/url_handler/related.py`
- Modify: `sre-agent/tests/integration/test_url_handler.py`

- [ ] **Step 4.1: Append failing tests**

```python
def test_find_related_by_keywords(tmp_log_root):
    from skills.reading.url_handler.related import find_related

    log = LogStore(tmp_log_root)

    past_a = log.new_session(type="reading", trigger={"url": "https://a.com", "source": "cli"}, tags=[])
    past_a.conclude(
        "# Git for Agents\n\nURL: https://a.com\n\n## 要点\n- agent commit log 是 LLM-native 数据层\n"
    )
    past_a.close()

    past_b = log.new_session(type="reading", trigger={"url": "https://b.com"}, tags=[])
    past_b.conclude("# Cooking Pasta\n\n## 要点\n- boil water first\n")
    past_b.close()

    related = find_related(
        log=log,
        keywords=["agent commit log", "LLM-native"],
        since="30d",
        limit=5,
        exclude_session_id=None,
    )

    assert len(related) == 1
    hit = related[0]
    assert hit["id"] == past_a.id
    assert hit["title"] == "Git for Agents"
    assert "agent commit log" in hit["matched_keywords"]


def test_find_related_excludes_self(tmp_log_root):
    from skills.reading.url_handler.related import find_related

    log = LogStore(tmp_log_root)
    s = log.new_session(type="reading", trigger={}, tags=[])
    s.conclude("# Self\n\n## 要点\n- agent commit log\n")
    s.close()

    out = find_related(log=log, keywords=["agent commit log"], since="30d",
                      limit=5, exclude_session_id=s.id)
    assert out == []


def test_find_related_respects_since(tmp_log_root):
    from datetime import datetime, timedelta, timezone
    from skills.reading.url_handler.related import find_related

    log = LogStore(tmp_log_root)
    old = log.new_session(type="reading", trigger={}, tags=[])
    old.conclude("# Old\n\n## 要点\n- agent commit log\n")
    old.close()
    m = log.store.read_manifest(old.id)
    m.created_at = (datetime.now(timezone.utc) - timedelta(days=60)).strftime("%Y-%m-%dT%H:%M:%SZ")
    log.store.write_manifest(m)

    assert find_related(log=log, keywords=["agent commit log"], since="30d",
                        limit=5, exclude_session_id=None) == []
    recent = find_related(log=log, keywords=["agent commit log"], since="90d",
                          limit=5, exclude_session_id=None)
    assert len(recent) == 1
```

- [ ] **Step 4.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 4.3: Implement `related.py`**

```python
"""Find past reading sessions related to a set of keywords (Phase 0: substring match)."""
from __future__ import annotations

import re
from typing import Any

from agent_session_log import LogStore


_TITLE_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)


def _extract_title(concl: str, fallback: str) -> str:
    m = _TITLE_RE.search(concl or "")
    return (m.group(1).strip() if m else fallback)[:80]


def find_related(
    *,
    log: LogStore,
    keywords: list[str],
    since: str = "30d",
    limit: int = 5,
    exclude_session_id: str | None,
) -> list[dict[str, Any]]:
    if not keywords:
        return []
    kw_lower = [k.strip().lower() for k in keywords if k.strip()]
    if not kw_lower:
        return []

    metas = log.list_sessions(type="reading", since=since, limit=200)

    out: list[dict[str, Any]] = []
    for meta in metas:
        sid = meta["id"]
        if exclude_session_id and sid == exclude_session_id:
            continue
        concl = log.store.read_conclusion(sid) or ""
        manifest = log.store.read_manifest(sid)
        hay = (concl + " " + str(manifest.trigger)).lower()
        matched = [kw for kw in kw_lower if kw in hay]
        if not matched:
            continue
        out.append({
            "id": sid,
            "title": _extract_title(concl, fallback="(untitled)"),
            "created_at": meta["created_at"],
            "matched_keywords": matched,
            "url": manifest.trigger.get("url"),
        })
        if len(out) >= limit:
            break
    return out
```

- [ ] **Step 4.4: Run — passes**

```bash
uv run pytest tests/integration/test_url_handler.py -v
```
Expected: 9 passed.

- [ ] **Step 4.5: Commit**

```bash
git add sre-agent/skills/reading/url_handler/related.py \
        sre-agent/tests/integration/test_url_handler.py
git commit -m "feat(sre-agent): reading/url_handler/related.py (keyword linkage)"
```

---

### Task 5: `reply.py` + `handler.py` — full URL flow end-to-end

**Files:**
- Create: `sre-agent/skills/reading/url_handler/reply.py`
- Create: `sre-agent/skills/reading/url_handler/reply_template.md`
- Create: `sre-agent/skills/reading/url_handler/handler.py`
- Modify: `sre-agent/tests/integration/test_url_handler.py`

- [ ] **Step 5.1: `reply_template.md`**

```markdown
📖 **{title}**

URL: {url}

**要点**
{key_points}

**相关阅读**
{related}

_session: `{session_id}`_
```

- [ ] **Step 5.2: Append failing tests**

```python
def test_build_reply_from_closed_session(tmp_log_root):
    from skills.reading.url_handler.reply import build_reply

    log = LogStore(tmp_log_root)
    s = log.new_session(
        type="reading",
        trigger={"url": "https://x.com", "source": "cli"},
        tags=["source:cli"],
    )
    s.conclude(
        "# On Commit Logs\n\n"
        "URL: https://x.com\n\n"
        "## 要点\n- LLM-native data layer\n- file-based\n- OBS synced\n\n"
        "## 相关阅读\n"
        "- sess_prev_id: [《Git for Agents》](https://a.com) — 关键词 commit log\n"
    )
    s.close()
    card = build_reply(log, s.id)
    assert "📖" in card
    assert "On Commit Logs" in card
    assert "https://x.com" in card
    assert "LLM-native data layer" in card
    assert s.id in card


def test_url_handler_full_flow(tmp_log_root):
    """End-to-end with fakes: open → fetch → extract → relate → conclude → close."""
    from skills.reading.url_handler.handler import handle_url

    log = LogStore(tmp_log_root)

    past = log.new_session(type="reading", trigger={"url": "https://a.com"}, tags=[])
    past.conclude("# Git for Agents\n\n## 要点\n- agent commit log 是 LLM-native 层\n")
    past.close()

    http = StaticHttpClient({
        "https://x.com/post": (200, "<html><body><article>"
                                    "<h1>On Commit Logs</h1>"
                                    "<p>Body about agent commit log being LLM-native.</p>"
                                    "</article></body></html>"),
    })
    llm = FakeLLM([{
        "text": json.dumps({
            "title": "On Commit Logs",
            "key_points": ["LLM-native data layer", "file-based", "OBS synced"],
            "keywords": ["agent commit log", "OBS", "LLM-native"],
            "quotes": [{"text": "一个 LLM-native 数据层", "context": "开篇"}],
        }),
        "model": "deepseek-chat",
        "tokens_in": 1200,
        "tokens_out": 260,
        "cost_usd": None,
    }])

    result = handle_url(
        log=log,
        http=http,
        llm=llm,
        url="https://x.com/post",
        user_open_id="ou_jacky",
        received_at="2026-04-24T10:00:00Z",
        source="cli",
    )

    m = log.store.read_manifest(result.session_id)
    assert m.type == "reading"
    assert m.status == "closed"
    assert m.trigger["url"] == "https://x.com/post"
    assert m.trigger["user_open_id"] == "ou_jacky"
    assert m.trigger["source"] == "cli"
    assert "source:cli" in m.tags

    # http called
    assert http.calls == ["https://x.com/post"]

    concl = log.store.read_conclusion(result.session_id)
    assert "On Commit Logs" in concl
    assert "LLM-native data layer" in concl
    assert past.id in concl  # related cited

    # Evidence: raw_html blob + extraction JSON blob
    events = log.store.read_events(result.session_id, "main")
    tool_results = [e for e in events if e.get("type") == "tool_result"]
    assert tool_results
    assert any(e.get("evidence") for e in tool_results)

    # Reply shaped
    assert "📖" in result.feishu_reply
    assert "On Commit Logs" in result.feishu_reply
    assert result.status == "closed"


def test_url_handler_handles_fetch_failure(tmp_log_root):
    """If fetch raises FetchError, session is closed status=abandoned."""
    from skills.reading.url_handler.handler import handle_url

    log = LogStore(tmp_log_root)
    http = StaticHttpClient({})  # 404
    llm = FakeLLM([])

    result = handle_url(
        log=log, http=http, llm=llm,
        url="https://broken.example", user_open_id="ou_jacky",
        received_at="2026-04-24T10:00:00Z", source="cli",
    )
    m = log.store.read_manifest(result.session_id)
    assert m.status == "abandoned"
    assert "fetch failed" in (log.store.read_conclusion(result.session_id) or "").lower()
    assert result.status == "abandoned"
```

- [ ] **Step 5.3: Run — fails**

Expected: ModuleNotFoundError for `reply` / `handler`.

- [ ] **Step 5.4: Implement `reply.py`**

```python
"""Build a feishu markdown reply from a closed reading session."""
from __future__ import annotations

import re
from pathlib import Path

from agent_session_log import LogStore


_TEMPLATE = (Path(__file__).parent / "reply_template.md").read_text(encoding="utf-8")
_TITLE_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
_KEY_POINTS_SECTION_RE = re.compile(r"##\s+要点\s*\n(.*?)(?=\n##\s|\Z)", re.DOTALL)
_RELATED_SECTION_RE = re.compile(r"##\s+相关阅读\s*\n(.*?)(?=\n##\s|\Z)", re.DOTALL)


def build_reply(log: LogStore, session_id: str) -> str:
    m = log.store.read_manifest(session_id)
    concl = log.store.read_conclusion(session_id) or ""

    title_m = _TITLE_RE.search(concl)
    title = title_m.group(1).strip() if title_m else "(无标题)"

    kp_m = _KEY_POINTS_SECTION_RE.search(concl)
    key_points = kp_m.group(1).strip() if kp_m else "- (无)"

    rel_m = _RELATED_SECTION_RE.search(concl)
    related = rel_m.group(1).strip() if rel_m else "- (首次读到这个主题)"

    return _TEMPLATE.format(
        title=title,
        url=m.trigger.get("url", ""),
        key_points=key_points,
        related=related,
        session_id=session_id,
    )
```

- [ ] **Step 5.5: Implement `handler.py`**

```python
"""Full URL handling flow: open → fetch → extract → relate → conclude → close.

Pure function of injected dependencies (LogStore, http client, LLM client). The CLI
wires real DeepseekLLMClient + httpx.Client + LogStore from main.py.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Protocol

from agent_session_log import LogStore

from skills.reading.url_handler.extract import extract
from skills.reading.url_handler.fetch import fetch_url, FetchError, HttpClient
from skills.reading.url_handler.related import find_related
from skills.reading.url_handler.reply import build_reply


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


@dataclass
class HandleResult:
    session_id: str
    feishu_reply: str
    status: str  # "closed" | "abandoned"


def handle_url(
    *,
    log: LogStore,
    http: HttpClient,
    llm: LLMClient,
    url: str,
    user_open_id: str | None,
    received_at: str,
    source: str = "cli",
) -> HandleResult:
    session = log.new_session(
        type="reading",
        trigger={
            "source": source,
            "url": url,
            "received_at": received_at,
            "user_open_id": user_open_id,
        },
        tags=[f"source:{source}", "type:web"],
        model="deepseek-chat",
        runtime="hermes@0.10.0",
    )

    # ---- Fetch ----
    session.append_turn(type="thought", content=f"fetching {url}")
    try:
        doc = fetch_url(url, client=http)
    except FetchError as exc:
        session.append_turn(type="tool_result", ref_turn=0, error=f"fetch failed: {exc}")
        session.conclude(
            f"# (fetch failed)\n\nURL: {url}\n\n## 错误\n- fetch failed: {exc}\n"
        )
        session.close(status="abandoned")
        return HandleResult(
            session_id=session.id,
            feishu_reply=f"📖 抓取失败:{url}\n{exc}",
            status="abandoned",
        )

    raw_blob = session.attach_evidence(
        doc.raw_html.encode("utf-8"), mime="text/plain", source=f"httpx.get({url})"
    )
    session.append_turn(type="tool_call", tool="httpx.get", args={"url": url})
    session.append_turn(
        type="tool_result", ref_turn=1,
        evidence=[raw_blob.sha256],
        truncated=(len(doc.raw_html) > 200000),
    )

    # ---- Extract ----
    extraction = extract(url=url, body=doc.body, llm=llm)
    extraction_blob = session.attach_evidence(
        extraction.as_json().encode("utf-8"),
        mime="application/json",
        source="extract_prompt.md",
    )
    if extraction.llm_meta:
        session.append_turn(
            type="llm_completion",
            model=extraction.llm_meta.model,
            tokens_in=extraction.llm_meta.tokens_in,
            tokens_out=extraction.llm_meta.tokens_out,
            cost_usd=extraction.llm_meta.cost_usd,
            content=extraction.title,
            evidence=[extraction_blob.sha256],
            skill="reading/url_handler",
            skill_version="v0.1",
        )

    # ---- Relate ----
    related = find_related(
        log=log,
        keywords=extraction.keywords,
        since="30d",
        limit=5,
        exclude_session_id=session.id,
    )
    session.append_turn(
        type="tool_call",
        tool="agent_session_log.list_sessions+search",
        args={"keywords": extraction.keywords, "since": "30d"},
    )
    session.append_turn(
        type="tool_result", ref_turn=3,
        content=[{"id": r["id"], "title": r["title"], "matched": r["matched_keywords"]} for r in related],
    )

    # ---- Conclude ----
    session.conclude(_build_conclusion(url=url, extraction=extraction, related=related))
    session.close()

    return HandleResult(
        session_id=session.id,
        feishu_reply=build_reply(log, session.id),
        status="closed",
    )


def _build_conclusion(*, url: str, extraction, related: list[dict[str, Any]]) -> str:
    kp_lines = "\n".join(f"- {p}" for p in extraction.key_points) or "- (无)"
    if related:
        rel_lines = "\n".join(
            f"- {r['id']}: [《{r['title']}》]({r.get('url') or ''}) — 关键词 {', '.join(r['matched_keywords'])}"
            for r in related
        )
    else:
        rel_lines = "- (首次读到这个主题)"
    quotes_block = ""
    if extraction.quotes:
        quotes_block = "\n## 摘抄\n" + "\n".join(
            f"> {q.get('text', '')} — {q.get('context', '')}" for q in extraction.quotes
        ) + "\n"
    return (
        f"# {extraction.title}\n\n"
        f"URL: {url}\n\n"
        f"## 要点\n{kp_lines}\n\n"
        f"## 相关阅读\n{rel_lines}\n"
        f"{quotes_block}"
    )
```

- [ ] **Step 5.6: Run — passes**

```bash
uv run pytest tests/integration/test_url_handler.py -v
```
Expected: 12 passed.

- [ ] **Step 5.7: Commit**

```bash
git add sre-agent/skills/reading/url_handler/reply.py \
        sre-agent/skills/reading/url_handler/reply_template.md \
        sre-agent/skills/reading/url_handler/handler.py \
        sre-agent/tests/integration/test_url_handler.py
git commit -m "feat(sre-agent): reading/url_handler full flow handler + reply"
```

---

### Task 6: `cli.py` — `python -m skills.reading.url_handler.cli <url>`

**Files:**
- Create: `sre-agent/skills/reading/url_handler/cli.py`
- Modify: `sre-agent/tests/integration/test_url_handler.py`

- [ ] **Step 6.1: Append failing test**

```python
def test_cli_main_writes_session_and_optionally_pushes(tmp_log_root, monkeypatch):
    """CLI smoke test: --no-push avoids feishu, uses fake http+LLM via env-injected hooks."""
    from skills.reading.url_handler import cli

    # Pre-arm fake http + LLM
    pages = {"https://x/post": (200,
        "<html><body><article><h1>T</h1><p>agent commit log content here.</p></article></body></html>")}
    monkeypatch.setattr(cli, "_TEST_HTTP", StaticHttpClient(pages))
    monkeypatch.setattr(cli, "_TEST_LLM", FakeLLM([{"text": json.dumps({
        "title": "T", "key_points": ["a"], "keywords": ["k"], "quotes": []
    }), "model": "x", "tokens_in": 1, "tokens_out": 1, "cost_usd": None}]))

    # Force LogStore root to tmp
    monkeypatch.setenv("HERMES_HOME", str(tmp_log_root.parent))

    rc = cli.main(["--url", "https://x/post", "--no-push"])
    assert rc == 0

    log = LogStore(tmp_log_root.parent / "data")
    metas = log.list_sessions(type="reading")
    assert len(metas) == 1
```

- [ ] **Step 6.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 6.3: Implement `cli.py`**

```python
"""CLI entry: python -m skills.reading.url_handler.cli --url <url> [--no-push]

Wires real DeepseekLLMClient + httpx.Client + LogStore from main.py helpers.
For tests, set module-level _TEST_HTTP / _TEST_LLM to inject fakes.
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import httpx

# Allow running both `python -m skills.reading.url_handler.cli` and direct script.
_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE.parents[2]))  # sre-agent/

from agent_session_log import LogStore

from skills.reading.url_handler.handler import handle_url


# Test injection points (module-level, monkey-patched by tests).
_TEST_HTTP = None
_TEST_LLM = None


log = logging.getLogger("reading.url_handler.cli")


def _real_http():
    return httpx.Client(timeout=30.0)


def _real_llm():
    # Import lazily so tests can monkeypatch without needing DEEPSEEK_API_KEY.
    from main import DeepseekLLMClient  # type: ignore[import-not-found]
    return DeepseekLLMClient()


def _make_log_store_local() -> LogStore:
    home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    return LogStore(home / "data")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Ingest a URL into the reading commit log.")
    parser.add_argument("--url", required=True, help="The URL to read.")
    parser.add_argument("--no-push", action="store_true",
                        help="Skip feishu DM (default: push if FEISHU_ALLOWED_USERS set).")
    parser.add_argument("--user", default=None,
                        help="Override target open_id (default: first FEISHU_ALLOWED_USERS).")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    log_store = _make_log_store_local()
    http = _TEST_HTTP if _TEST_HTTP is not None else _real_http()
    llm = _TEST_LLM if _TEST_LLM is not None else _real_llm()

    # Resolve target open_id
    open_id = args.user
    if open_id is None and not args.no_push:
        users = os.environ.get("FEISHU_ALLOWED_USERS", "")
        parts = [u.strip() for u in users.split(",") if u.strip()]
        open_id = parts[0] if parts else None

    received_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    result = handle_url(
        log=log_store, http=http, llm=llm,
        url=args.url, user_open_id=open_id,
        received_at=received_at, source="cli",
    )
    log.info("[cli] session %s status=%s", result.session_id, result.status)
    log.info("[cli] reply preview:\n%s", result.feishu_reply)

    if not args.no_push and open_id:
        try:
            from main import feishu_send_dm  # type: ignore[import-not-found]
            feishu_send_dm(open_id, result.feishu_reply)
            log.info("[cli] feishu DM sent to %s", open_id)
        except Exception as exc:  # noqa: BLE001
            log.warning("[cli] feishu DM failed: %s", exc)

    return 0 if result.status == "closed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 6.4: Run — passes**

```bash
uv run pytest tests/integration/test_url_handler.py -v
```
Expected: 13 passed.

- [ ] **Step 6.5: Commit**

```bash
git add sre-agent/skills/reading/url_handler/cli.py \
        sre-agent/tests/integration/test_url_handler.py
git commit -m "feat(sre-agent): reading/url_handler/cli.py CLI entry"
```

---

## Group C: Query + Daily Reflection

### Task 7: `query_handler` — "我最近读了什么关于 X"

**Files:**
- Create: `sre-agent/skills/reading/query_handler/__init__.py` (empty)
- Create: `sre-agent/skills/reading/query_handler/SKILL.md`
- Create: `sre-agent/skills/reading/query_handler/query_prompt.md`
- Create: `sre-agent/skills/reading/query_handler/handler.py`
- Create: `sre-agent/skills/reading/query_handler/cli.py`
- Create: `sre-agent/tests/integration/test_query_handler.py`

- [ ] **Step 7.1: `SKILL.md`**

```markdown
---
name: reading_query_handler
description: Answer free-form questions about reading history by searching commit log.
version: v0.1
---

# reading/query_handler

**Trigger:** CLI `python -m skills.reading.query_handler.cli "<question>"`
(future: feishu inbound).

**Flow:**
1. Cheap keyword extraction from the question.
2. `log.search_text(term, type="reading")` per term; merge + dedupe.
3. Fall back to "5 most recent readings" if no hits.
4. Pass hits (title + snippet + date) to LLM with `query_prompt.md`.
5. Return answer text. Does **NOT** open a session — recall is one-shot.
```

- [ ] **Step 7.2: `query_prompt.md`**

```markdown
用户问了一个关于他过去阅读的问题。请给他一个自然、精确的回答。

问题:
{question}

过去 30 天相关的阅读(JSON,新→旧):
{hits}

## 回答规范

1. 如果有命中,列出 2-5 条最相关的阅读,每条附日期和一句定位(例:"4/12 读了《…》讲的是…")。
2. 如果没命中,说"这个主题在你最近 30 天的阅读里没找到",不要瞎编。
3. 语气口语,简短,总长 ≤ 200 字。
4. 末尾不用加套话。
```

- [ ] **Step 7.3: Failing tests**

Create `sre-agent/tests/integration/test_query_handler.py`:

```python
from __future__ import annotations

import json
from pathlib import Path

from agent_session_log import LogStore


class FakeLLM:
    def __init__(self, resp: dict):
        self.resp = resp
        self.last_prompt: str | None = None

    def complete(self, *, system, user, tools=None):
        self.last_prompt = user
        return self.resp


def test_query_handler_returns_answer(tmp_log_root: Path):
    from skills.reading.query_handler.handler import answer_question

    log = LogStore(tmp_log_root)
    s = log.new_session(type="reading", trigger={"url": "https://x"}, tags=[])
    s.conclude("# On Agent Commit Logs\n\n## 要点\n- LLM-native\n- file-based\n")
    s.close()

    llm = FakeLLM({"text": "4/23 你读了《On Agent Commit Logs》,讲 LLM-native 数据层。",
                   "model": "deepseek-chat", "tokens_in": 800, "tokens_out": 120, "cost_usd": None})

    answer = answer_question(log=log, llm=llm,
                             question="我最近读了什么关于 agent commit log 的")
    assert "On Agent Commit Logs" in answer
    assert "On Agent Commit Logs" in (llm.last_prompt or "")


def test_query_handler_empty_gracefully(tmp_log_root: Path):
    from skills.reading.query_handler.handler import answer_question

    log = LogStore(tmp_log_root)
    llm = FakeLLM({"text": "这个主题在你最近 30 天的阅读里没找到",
                   "model": "x", "tokens_in": 200, "tokens_out": 40, "cost_usd": None})

    answer = answer_question(log=log, llm=llm, question="Rust 生命周期")
    assert "没找到" in answer


def test_query_handler_does_not_open_session(tmp_log_root: Path):
    from skills.reading.query_handler.handler import answer_question

    log = LogStore(tmp_log_root)
    llm = FakeLLM({"text": "no hits", "model": "x", "tokens_in": 1, "tokens_out": 1, "cost_usd": None})

    before = len(log.list_sessions())
    answer_question(log=log, llm=llm, question="anything")
    after = len(log.list_sessions())
    assert before == after
```

- [ ] **Step 7.4: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 7.5: Implement `handler.py`**

```python
"""Answer free-form reading-history questions without opening a new session."""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Protocol

from agent_session_log import LogStore


_PROMPT = (Path(__file__).parent / "query_prompt.md").read_text(encoding="utf-8")

_STOPWORDS = {
    "我", "你", "他", "的", "在", "是", "了", "吗", "什么", "关于", "最近", "过去", "那个",
    "a", "an", "the", "of", "about", "recent", "any", "something", "on",
}


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


def _extract_terms(question: str, limit: int = 4) -> list[str]:
    tokens = re.findall(r"[\w\u4e00-\u9fff]+", question)
    filtered = [t for t in tokens if t.lower() not in _STOPWORDS and len(t) >= 2]
    filtered.sort(key=lambda t: -len(t))
    seen: set[str] = set()
    out: list[str] = []
    for t in filtered:
        k = t.lower()
        if k in seen:
            continue
        seen.add(k)
        out.append(t)
        if len(out) >= limit:
            break
    return out


def _hits_for_prompt(log: LogStore, terms: list[str], limit: int = 10) -> list[dict[str, Any]]:
    seen: set[str] = set()
    hits: list[dict[str, Any]] = []
    for term in terms:
        for h in log.search_text(term, type="reading", limit=limit):
            if h["id"] in seen:
                continue
            seen.add(h["id"])
            concl = log.store.read_conclusion(h["id"]) or ""
            title_m = re.search(r"^#\s+(.+?)\s*$", concl, re.MULTILINE)
            hits.append({
                "id": h["id"],
                "title": (title_m.group(1) if title_m else "(无标题)")[:80],
                "created_at": h["created_at"],
                "snippet": h.get("snippet", "")[:200],
            })
    if not hits:
        for meta in log.list_sessions(type="reading", since="30d", limit=5):
            concl = log.store.read_conclusion(meta["id"]) or ""
            title_m = re.search(r"^#\s+(.+?)\s*$", concl, re.MULTILINE)
            hits.append({
                "id": meta["id"],
                "title": (title_m.group(1) if title_m else "(无标题)")[:80],
                "created_at": meta["created_at"],
                "snippet": concl[:200],
            })
    return hits[:limit]


def answer_question(*, log: LogStore, llm: LLMClient, question: str) -> str:
    terms = _extract_terms(question)
    hits = _hits_for_prompt(log, terms)
    prompt = _PROMPT.replace("{question}", question).replace(
        "{hits}", json.dumps(hits, ensure_ascii=False, indent=2)
    )
    resp = llm.complete(system="你是 Jacky 的阅读伙伴,简短准确。", user=prompt)
    return (resp.get("text") or "").strip()
```

- [ ] **Step 7.6: Implement `cli.py`**

Create `sre-agent/skills/reading/query_handler/cli.py`:

```python
"""CLI: python -m skills.reading.query_handler.cli "我最近读了什么关于 X" """
from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE.parents[2]))  # sre-agent/

from agent_session_log import LogStore

from skills.reading.query_handler.handler import answer_question


_TEST_LLM = None


def _real_llm():
    from main import DeepseekLLMClient  # type: ignore[import-not-found]
    return DeepseekLLMClient()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Ask the reading commit log.")
    parser.add_argument("question", help="Free-form question.")
    parser.add_argument("--no-push", action="store_true", help="Print only, skip feishu DM.")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    log_store = LogStore(home / "data")
    llm = _TEST_LLM if _TEST_LLM is not None else _real_llm()
    answer = answer_question(log=log_store, llm=llm, question=args.question)
    print(answer)

    if not args.no_push:
        users = os.environ.get("FEISHU_ALLOWED_USERS", "")
        parts = [u.strip() for u in users.split(",") if u.strip()]
        if parts:
            try:
                from main import feishu_send_dm  # type: ignore[import-not-found]
                feishu_send_dm(parts[0], answer)
            except Exception as exc:  # noqa: BLE001
                print(f"warning: feishu DM failed: {exc}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 7.7: Run — passes**

```bash
uv run pytest tests/integration/test_query_handler.py -v
```
Expected: 3 passed.

- [ ] **Step 7.8: Commit**

```bash
git add sre-agent/skills/reading/query_handler/ \
        sre-agent/tests/integration/test_query_handler.py
git commit -m "feat(sre-agent): reading/query_handler + cli for recall queries"
```

---

### Task 8: `daily_reflection` — 22:00 cron skill

**Files:**
- Create: `sre-agent/skills/reading/daily_reflection/__init__.py` (empty)
- Create: `sre-agent/skills/reading/daily_reflection/SKILL.md`
- Create: `sre-agent/skills/reading/daily_reflection/reflect_prompt.md`
- Create: `sre-agent/skills/reading/daily_reflection/reflect.py`
- Create: `sre-agent/tests/integration/test_daily_reflection.py`

- [ ] **Step 8.1: `SKILL.md`**

```markdown
---
name: reading_daily_reflection
description: Every night at 22:00, review today's reading sessions and produce a reflection.
version: v0.1
---

# reading/daily_reflection

**Trigger:** main.py `_CRON_TASKS` cron `0 22 * * *` (Asia/Shanghai = UTC+8 → set TZ env).

**Flow:**
1. `log.list_sessions(type="reading", since="24h")`. If empty → skip.
2. Pack (title + key_points) of each into LLM prompt.
3. LLM writes ≤ 150 字 reflection.
4. Open `type=reflection` session with `parent_sessions = [...]`; one llm_completion turn; conclude+close.
5. Return reflection text — main.py pushes to feishu via `feishu_send_dm`.
```

- [ ] **Step 8.2: `reflect_prompt.md`**

```markdown
下面是 Jacky 今天(Asia/Shanghai 时区)读过的文章。请写一条 ≤ 150 字的反思,风格像他对自己的私密总结,有三个要素:

1. 今天读了什么(一句)。
2. 这些内容里共同的线索或相互呼应的点。
3. 还有哪个问题今晚留着没想透(哪怕一个具体的 follow-up)。

不要堆砌书名,不要客套,不要用"今天真充实"这种话。

今日阅读(JSON):
{readings}
```

- [ ] **Step 8.3: Failing tests**

Create `sre-agent/tests/integration/test_daily_reflection.py`:

```python
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

from agent_session_log import LogStore


class FakeLLM:
    def __init__(self, text: str):
        self.text = text
        self.last_prompt: str | None = None

    def complete(self, *, system, user, tools=None):
        self.last_prompt = user
        return {"text": self.text, "model": "deepseek-chat", "tokens_in": 900,
                "tokens_out": 140, "cost_usd": None}


def test_reflect_no_reading_today(tmp_log_root: Path):
    from skills.reading.daily_reflection.reflect import reflect_today

    log = LogStore(tmp_log_root)
    result = reflect_today(log=log, llm=FakeLLM("should not be called"))
    assert result.reflection_text is None
    assert result.session_id is None
    assert result.skipped_reason == "no reading sessions in last 24h"


def test_reflect_creates_reflection_session(tmp_log_root: Path):
    from skills.reading.daily_reflection.reflect import reflect_today

    log = LogStore(tmp_log_root)
    a = log.new_session(type="reading", trigger={"url": "https://a"}, tags=[])
    a.conclude("# On Commit Logs\n\n## 要点\n- LLM-native\n- file-based\n")
    a.close()
    b = log.new_session(type="reading", trigger={"url": "https://b"}, tags=[])
    b.conclude("# Git Internals\n\n## 要点\n- content-addressable\n")
    b.close()

    llm = FakeLLM("今天读了两篇关于 commit log 的。共同线索是 content-addressable。明天想想 OBS 同步失败重试怎么设计。")
    result = reflect_today(log=log, llm=llm)

    assert result.reflection_text
    assert "content-addressable" in result.reflection_text
    assert result.session_id
    m = log.store.read_manifest(result.session_id)
    assert m.type == "reflection"
    assert set(m.parent_sessions) == {a.id, b.id}
    assert m.status == "closed"
    assert "content-addressable" in log.store.read_conclusion(result.session_id)
    assert "On Commit Logs" in (llm.last_prompt or "")
    assert "Git Internals" in (llm.last_prompt or "")


def test_reflect_ignores_non_reading_sessions(tmp_log_root: Path):
    from skills.reading.daily_reflection.reflect import reflect_today

    log = LogStore(tmp_log_root)
    inc = log.new_session(type="incident", trigger={}, tags=["component:compute"])
    inc.conclude("# cold start\n")
    inc.close()
    read = log.new_session(type="reading", trigger={"url": "https://x"}, tags=[])
    read.conclude("# Fresh Article\n\n## 要点\n- a\n")
    read.close()

    llm = FakeLLM("今天只读了《Fresh Article》。")
    result = reflect_today(log=log, llm=llm)

    prompt = llm.last_prompt or ""
    assert "Fresh Article" in prompt
    assert "cold start" not in prompt.lower()
    m = log.store.read_manifest(result.session_id)
    assert m.parent_sessions == [read.id]
    assert inc.id not in m.parent_sessions
```

- [ ] **Step 8.4: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 8.5: Implement `reflect.py`**

```python
"""22:00 daily reflection over today's reading sessions."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from agent_session_log import LogStore


_PROMPT = (Path(__file__).parent / "reflect_prompt.md").read_text(encoding="utf-8")
_TITLE_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
_KP_RE = re.compile(r"##\s+要点\s*\n(.*?)(?=\n##\s|\Z)", re.DOTALL)


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


@dataclass
class ReflectionResult:
    session_id: str | None
    reflection_text: str | None
    skipped_reason: str | None = None


def _readings_payload(log: LogStore) -> tuple[list[dict], list[str]]:
    metas = log.list_sessions(type="reading", since="24h", limit=50)
    payload: list[dict] = []
    ids: list[str] = []
    for meta in metas:
        concl = log.store.read_conclusion(meta["id"]) or ""
        title_m = _TITLE_RE.search(concl)
        kp_m = _KP_RE.search(concl)
        payload.append({
            "id": meta["id"],
            "title": (title_m.group(1) if title_m else "(无标题)")[:80],
            "created_at": meta["created_at"],
            "key_points": (kp_m.group(1).strip() if kp_m else ""),
        })
        ids.append(meta["id"])
    return payload, ids


def reflect_today(*, log: LogStore, llm: LLMClient) -> ReflectionResult:
    readings, ids = _readings_payload(log)
    if not readings:
        return ReflectionResult(
            session_id=None, reflection_text=None,
            skipped_reason="no reading sessions in last 24h",
        )

    prompt = _PROMPT.replace("{readings}", json.dumps(readings, ensure_ascii=False, indent=2))
    resp = llm.complete(system="你是 Jacky 的阅读伙伴,帮他写一句简短的夜晚总结。", user=prompt)
    text = (resp.get("text") or "").strip()

    session = log.new_session(
        type="reflection",
        trigger={"source": "cron/daily-reflection", "readings_count": len(readings)},
        tags=["type:reflection", "skill:daily-reflection"],
        parent_sessions=ids,
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
        skill="reading/daily_reflection",
        skill_version="v0.1",
    )
    session.conclude(f"# 今日反思\n\n{text}\n")
    session.close()

    return ReflectionResult(session_id=session.id, reflection_text=text)
```

- [ ] **Step 8.6: Run — passes**

```bash
uv run pytest tests/integration/test_daily_reflection.py -v
```
Expected: 3 passed.

- [ ] **Step 8.7: Commit**

```bash
git add sre-agent/skills/reading/daily_reflection/ \
        sre-agent/tests/integration/test_daily_reflection.py
git commit -m "feat(sre-agent): reading/daily_reflection module"
```

---

## Group D: main.py wiring + Cross-consumer + Deploy

### Task 9: Register reflection cron in main.py + cross-consumer test + import discipline

**Files:**
- Modify: `sre-agent/main.py` (add `run_daily_reflection`, register in `_CRON_TASKS`)
- Modify: `sre-agent/tests/test_import_discipline.py`
- Create: `sre-agent/tests/integration/test_phase_0b_cross_consumer.py`

- [ ] **Step 9.1: Modify `main.py` — add reflection task**

After `run_outcome_checker` (around line 293), add:

```python
def run_daily_reflection() -> None:
    """0 22 * * * cron task — review today's reading, push reflection to Jacky."""
    from skills.reading.daily_reflection.reflect import reflect_today

    log.info("[daily_reflection] reflect_today starting")
    log_store = _make_log_store()
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

    open_id = _jacky_open_id()
    if open_id and result.reflection_text:
        try:
            feishu_send_dm(open_id, f"📖 今日反思\n\n{result.reflection_text}")
            log.info("[daily_reflection] feishu DM sent to %s", open_id)
        except Exception as exc:  # noqa: BLE001
            log.warning("[daily_reflection] feishu DM failed: %s", exc)
```

Then update `_CRON_TASKS`:

```python
_CRON_TASKS = [
    ("*/2 * * * *", run_cold_start_watcher),
    ("0 9 * * *",   run_outcome_checker),
    ("0 22 * * *",  run_daily_reflection),
]
```

- [ ] **Step 9.2: Verify main.py imports cleanly + test_main.py still passes**

```bash
cd /Users/jacky/code/lakeon/sre-agent
uv run python -c "import main; print(main._CRON_TASKS)"
uv run pytest tests/test_main.py -v
```
Expected: prints 3 tuples; test_main passes.

- [ ] **Step 9.3: Extend import discipline test**

Append to `sre-agent/tests/test_import_discipline.py`:

```python
def test_reading_skills_do_not_import_sre_skills():
    from pathlib import Path
    reading_root = Path(__file__).resolve().parents[1] / "skills" / "reading"
    if not reading_root.exists():
        return
    violations = []
    for py in reading_root.rglob("*.py"):
        text = py.read_text(encoding="utf-8")
        for needle in ("skills.sre", "from skills.sre", "sre.cold_start", "sre.outcome"):
            if needle in text:
                violations.append(f"{py}: {needle}")
    assert not violations, "\n".join(violations)


def test_sre_skills_do_not_import_reading_skills():
    from pathlib import Path
    sre_root = Path(__file__).resolve().parents[1] / "skills" / "sre"
    violations = []
    for py in sre_root.rglob("*.py"):
        text = py.read_text(encoding="utf-8")
        for needle in ("skills.reading", "from skills.reading"):
            if needle in text:
                violations.append(f"{py}: {needle}")
    assert not violations, "\n".join(violations)


def test_reading_skills_use_only_public_agent_session_log_api():
    """Reading code must go through agent_session_log public exports."""
    import ast
    from pathlib import Path

    reading_root = Path(__file__).resolve().parents[1] / "skills" / "reading"
    if not reading_root.exists():
        return
    violations = []
    for py in reading_root.rglob("*.py"):
        tree = ast.parse(py.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module:
                if node.module.startswith("agent_session_log."):
                    violations.append(f"{py}: from {node.module} (private submodule)")
    assert not violations, "\n".join(violations)
```

- [ ] **Step 9.4: Cross-consumer scenario test**

Create `sre-agent/tests/integration/test_phase_0b_cross_consumer.py`:

```python
"""Prove SRE + reading share one LogStore cleanly. The 80%-generic claim, test-ified."""
from __future__ import annotations

import json
from pathlib import Path

from agent_session_log import LogStore


class _FakeLLM:
    def __init__(self, text):
        self.text = text
    def complete(self, *, system, user, tools=None):
        return {"text": self.text, "model": "deepseek-chat",
                "tokens_in": 100, "tokens_out": 30, "cost_usd": None}


class _StaticHttp:
    def __init__(self, pages):
        self.pages = pages
        self.calls: list[str] = []
    def get(self, url, *args, **kwargs):
        self.calls.append(url)
        if url not in self.pages:
            class R:
                status_code = 404; text = ""
                def raise_for_status(self): raise RuntimeError("404")
            return R()
        s, h = self.pages[url]
        class R:
            def __init__(self, s, h):
                self.status_code = s; self.text = h
            def raise_for_status(self):
                if self.status_code >= 400:
                    raise RuntimeError(f"HTTP {self.status_code}")
        return R(s, h)


def test_sre_and_reading_share_logstore_without_interference(tmp_log_root: Path):
    from skills.sre.cold_start_watcher.watcher import Watcher
    from skills.reading.url_handler.handler import handle_url

    log = LogStore(tmp_log_root)

    # SRE consumer writes an incident
    class MockMCP:
        def log_search(self, **_):
            return [{
                "ts": "2026-04-24T09:00:00Z",
                "msg": "compute started in 8234ms for tenant=t_abc db=db_xyz",
                "tenant_id": "t_abc", "db_id": "db_xyz",
            }]
    w = Watcher(log=log, mcp=MockMCP())
    sre_ids = w.scan_once()
    assert len(sre_ids) == 1

    # Reading consumer ingests a URL
    http = _StaticHttp({"https://x": (200,
        "<html><body><article><h1>Hi</h1>"
        "<p>This is a longer body about agent commit log so trafilatura keeps it.</p>"
        "</article></body></html>")})
    llm_extract = _FakeLLM(json.dumps({
        "title": "Hi", "key_points": ["a"], "keywords": ["agent commit log"], "quotes": []
    }))
    result = handle_url(
        log=log, http=http, llm=llm_extract,
        url="https://x", user_open_id="ou_jacky",
        received_at="2026-04-24T10:00:00Z", source="cli",
    )

    # Both sessions present
    all_ids = {m["id"] for m in log.list_sessions(limit=100)}
    assert sre_ids[0] in all_ids
    assert result.session_id in all_ids

    # type filter clean
    assert [x["id"] for x in log.list_sessions(type="incident")] == [sre_ids[0]]
    assert [x["id"] for x in log.list_sessions(type="reading")] == [result.session_id]

    # tag filter clean
    assert [x["id"] for x in log.list_sessions(tags=["component:compute"])] == [sre_ids[0]]
    assert [x["id"] for x in log.list_sessions(tags=["source:cli"])] == [result.session_id]

    # search_text scoped: searching "pageserver" in incident type must NOT find reading
    hits_ps = log.search_text("pageserver", type="incident")
    assert result.session_id not in [h["id"] for h in hits_ps]

    # global search for "agent commit log" finds reading but not incident
    hits_acl = log.search_text("agent commit log")
    assert result.session_id in [h["id"] for h in hits_acl]
    assert sre_ids[0] not in [h["id"] for h in hits_acl]


def test_skill_ledger_records_two_skills_independently(tmp_log_root: Path):
    from agent_session_log import SkillLedger
    ledger = SkillLedger(tmp_log_root)
    ledger.record_invocation("cold-start-watcher", version="v0.1",
                             session_id="s_a", triggered_at="2026-04-24T09:00:00Z")
    ledger.record_invocation("reading/url_handler", version="v0.1",
                             session_id="s_b", triggered_at="2026-04-24T10:00:00Z")
    ledger.record_outcome("cold-start-watcher", session_id="s_a", did_work=True)

    cs = ledger.stats("cold-start-watcher")
    rh = ledger.stats("reading/url_handler")
    assert cs["total_invocations"] == 1
    assert rh["total_invocations"] == 1
    assert cs["did_work_rate"] == 1.0
    assert rh["did_work_rate"] is None
```

- [ ] **Step 9.5: Run full suite**

```bash
cd /Users/jacky/code/lakeon/sre-agent
uv run pytest -v
```
Expected: every Phase 0a + Phase 0b test passes (≈ 35-45 total).

- [ ] **Step 9.6: Commit**

```bash
git add sre-agent/main.py \
        sre-agent/tests/test_import_discipline.py \
        sre-agent/tests/integration/test_phase_0b_cross_consumer.py
git commit -m "feat(sre-agent): wire daily_reflection cron + cross-consumer tests"
```

---

### Task 10: Local smoke + Railway deploy + Phase 0b report

**Prerequisites:** Phase 0a runs on Railway (it does); FEISHU_ALLOWED_USERS / DEEPSEEK_API_KEY / OBS env all set.

- [ ] **Step 10.1: Local smoke — CLI URL with real httpx + LLM**

Use a known-stable, public, short article URL (e.g., a Wikipedia article or your own static page). From the project root with `.env` loaded:

```bash
cd /Users/jacky/code/lakeon/sre-agent
export $(grep -v '^#' .env | xargs)
HERMES_HOME=/tmp/hermes_smoke uv run python -m skills.reading.url_handler.cli \
  --url "https://en.wikipedia.org/wiki/Stub_(distributed_computing)" \
  --no-push
```
Expected:
- Exit 0
- Log shows `[cli] session sess_... status=closed`
- `/tmp/hermes_smoke/data/sessions/.../sess_...` exists with manifest, events, conclusion (containing 要点), evidence (raw_html + extraction JSON).

- [ ] **Step 10.2: Local smoke — query**

```bash
HERMES_HOME=/tmp/hermes_smoke uv run python -m skills.reading.query_handler.cli \
  "我最近读了什么关于 stub 的" --no-push
```
Expected: prints an answer mentioning the article you ingested.

- [ ] **Step 10.3: Local smoke — daily reflection**

```bash
HERMES_HOME=/tmp/hermes_smoke uv run python -c "
from main import run_daily_reflection
run_daily_reflection()
"
```
Expected: logs `[daily_reflection] wrote session sess_...`; new `type=reflection` session under `/tmp/hermes_smoke/data/...`.

- [ ] **Step 10.4: Deploy to Railway**

```bash
cd /Users/jacky/code/lakeon
git push  # auto-deploys via Railway
```
Watch Railway build logs:
- Verify `trafilatura` installs cleanly (it has a `lxml` C-ext — usually fine on python:3.11-slim, but may need `apt-get install libxml2 libxslt`; Phase 0a Dockerfile already has `apt-get install -y git curl ca-certificates` — add `libxml2 libxslt1.1` if build fails).
- Verify `[cron] loop started with 3 task(s)` line in startup logs.

- [ ] **Step 10.5: Trigger production URL ingestion (optional, manual)**

Open Railway shell or `railway run`:
```bash
railway run -- python -m skills.reading.url_handler.cli \
  --url "https://your-test-url.example/post"
```
Expected: feishu DM with the 📖 card, session under `$HERMES_HOME/data/...`, OBS sync within 1 min.

- [ ] **Step 10.6: Wait for 22:00 daily reflection**

Confirm Railway TZ is `Asia/Shanghai` (`echo $TZ` in shell). At 22:00:
- Logs show `[cron] firing 0 22 * * * → run_daily_reflection`.
- If you've fed at least one URL today, expect `wrote session sess_...` and a feishu DM.
- Otherwise expect `skipped: no reading sessions in last 24h`.

- [ ] **Step 10.7: Phase 0b report skeleton**

Create `sre-agent/reports/phase-0b-report.md` (fill in over the week of dogfooding):

```markdown
# Phase 0b Report — Reading Companion

## Acceptance criteria

- [ ] 仅一处 additive API change(`list_sessions(since=)`),零破坏性
- [ ] 三个新 skill (url_handler / query_handler / daily_reflection) 全部单测过
- [ ] reading + sre 共享 LogStore 互不干扰(test_phase_0b_cross_consumer 绿)
- [ ] 一周内 ≥ 7 天投喂 URL,daily reflection 推送可读
- [ ] query_handler 一周内至少帮一次"想起来读过什么"
- [ ] 跨 session 关联(related)至少一次命中有用 link

## 80%-generic verdict

- (pass / fail)
- 举证: API friction log 中的条目
- 触及破坏性改动?  (yes / no)

## API friction log

记录任何"想给 SRE-only 方法打补丁但忍住了"的瞬间:
- (date) - 想做 X - 实际选择 Y - 是否暴露抽象问题

## Usage stats

- Reading sessions: N
- Reflections: N
- Fetch failures: N (URLs)
- Query invocations: N
- Avg tokens / extraction: X
- Total deepseek cost (Phase 0b): $X

## Surprising findings

- ...

## Phase 1 decisions

- (extract agent_session_log → standalone package?)
- (third agent for further genericity test?)
- (any concrete abstraction problems found?)
- (re-evaluate "no inbound feishu" assumption — does query_handler need it?)
```

Commit:
```bash
git add sre-agent/reports/phase-0b-report.md
git commit -m "docs(sre-agent): Phase 0b report skeleton"
```

---

## Self-Review Checklist

- [x] Spec "Agent 2: Reading companion" mapped: URL ingest + summary (Tasks 2-6), 跨 session 关联 (Task 4), daily reflection (Task 8), query (Task 7). `type=reflection` 套娃 covered Task 8.
- [x] Spec "核心:agent commit log 数据层" respected — only `list_sessions` additive `since=` (Task 1), no rename/schema change.
- [x] Phase 0a tests stay green — Tasks 1.5 / 9.5 explicit regression runs.
- [x] Phase 0b acceptance criteria mapped to Task 10 sub-steps.
- [x] No TBD / placeholder / "implement later" — every Step has complete code.
- [x] Identifier consistency: `Session` / `LogStore` / `SkillLedger` / `Blob` / `handle_url` / `answer_question` / `reflect_today` / `Watcher` / `OutcomeChecker`.
- [x] Exact file paths under `sre-agent/`.
- [x] Each task ends with a commit step; messages are concrete.
- [x] Task count = 10 (8-12 band).
- [x] Architecture pivot from initial draft acknowledged + applied (no MCP, no personality, CLI entry, cron via main.py).
- [x] Import discipline enforced by Task 9 tests.

## Open Risks During Execution

1. **`trafilatura` install on Railway.** If `lxml` build fails, add `libxml2-dev libxslt1-dev` (build-time) or pre-built wheels to Dockerfile.
2. **Reading TZ.** Confirm Railway `TZ=Asia/Shanghai` is set; otherwise `0 22 * * *` fires at 06:00 local.
3. **CLI on Railway.** Reading URL ingestion is manually triggered until inbound feishu hook lands. Expected for Phase 0b — record discomfort in API friction log so Phase 1 can prioritize fixing it.
4. **Substring-based related linkage.** Will miss "LLM-native" vs "LLM 原生". Don't hack embeddings in here; Phase 1 work.
5. **Body truncation.** `extract.py` caps body at 12000 chars. Long articles lose tail; record in friction log if it bites.
6. **Cost spike.** Monitor `SkillLedger.stats("reading/url_handler")` weekly.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-23-sre-agent-phase-0b-plan.md`. Use `superpowers:subagent-driven-development` to execute task-by-task.
