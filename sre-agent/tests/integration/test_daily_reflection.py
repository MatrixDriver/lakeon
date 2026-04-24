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
