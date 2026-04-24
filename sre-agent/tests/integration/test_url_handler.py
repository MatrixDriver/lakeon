"""Tests for reading/url_handler — fetch, extract, related, full flow."""
from __future__ import annotations

import json  # noqa: F401  reused by tests in Tasks 3-7
from pathlib import Path  # noqa: F401  reused by tests in Tasks 4-7

import pytest

from agent_session_log import LogStore  # noqa: F401  reused by tests in Tasks 4-7


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


# ────────── extract.py tests ──────────

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


def test_extract_url_substring_collision():
    """Regression: URL containing the literal '{body}' must not corrupt body substitution."""
    from skills.reading.url_handler.extract import extract

    weird_url = "https://x.com/path/{body}/page"
    llm = FakeLLM([{"text": json.dumps({
        "title": "T", "key_points": ["p"], "keywords": ["k"], "quotes": []
    }), "model": "x", "tokens_in": 1, "tokens_out": 1, "cost_usd": None}])

    extract(url=weird_url, body="real body content", llm=llm)

    # The user prompt must contain the original URL exactly once and the body exactly once.
    assert len(llm.calls) == 1
    rendered = llm.calls[0]["user"]
    assert weird_url in rendered
    assert "real body content" in rendered
    # The literal '{body}' from the URL must NOT have been replaced
    assert "{body}" in rendered


# ────────── related.py tests ──────────

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


# ────────── reply.py tests ──────────

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


def test_url_handler_ref_turn_invariant(tmp_log_root):
    """Every tool_result.ref_turn must point at a tool_call turn."""
    from skills.reading.url_handler.handler import handle_url

    log = LogStore(tmp_log_root)
    http = StaticHttpClient({
        "https://x.com/post": (200, "<html><body><article>"
                                    "<h1>T</h1><p>real body content here</p>"
                                    "</article></body></html>"),
    })
    llm = FakeLLM([{
        "text": json.dumps({
            "title": "T", "key_points": ["a"], "keywords": ["k"], "quotes": []
        }),
        "model": "x", "tokens_in": 1, "tokens_out": 1, "cost_usd": None,
    }])

    result = handle_url(
        log=log, http=http, llm=llm,
        url="https://x.com/post", user_open_id=None,
        received_at="2026-04-24T10:00:00Z", source="cli",
    )

    events = log.store.read_events(result.session_id, "main")
    by_turn = {e["turn"]: e for e in events}
    for e in events:
        if e.get("type") == "tool_result":
            ref = e.get("ref_turn")
            assert ref is not None, f"tool_result missing ref_turn: {e}"
            assert ref in by_turn, f"ref_turn={ref} not in events: {e}"
            assert by_turn[ref].get("type") == "tool_call", \
                f"tool_result ref_turn={ref} points at {by_turn[ref].get('type')}, not tool_call: {e}"


def test_build_reply_handles_braces_in_url(tmp_log_root):
    """Regression: build_reply must not crash on URL with literal '{' or '}'."""
    from skills.reading.url_handler.reply import build_reply

    log = LogStore(tmp_log_root)
    s = log.new_session(
        type="reading",
        trigger={"url": "https://x.com/path/{a}/page", "source": "cli"},
        tags=["source:cli"],
    )
    s.conclude(
        "# T\n\n## 要点\n- a {literal} b\n\n## 相关阅读\n- (none)\n"
    )
    s.close()

    card = build_reply(log, s.id)  # must not raise
    assert "https://x.com/path/{a}/page" in card
    assert "{literal}" in card
