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
        "title": "Hi", "key_points": ["agent commit log is file-based"], "keywords": ["agent commit log"], "quotes": []
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
