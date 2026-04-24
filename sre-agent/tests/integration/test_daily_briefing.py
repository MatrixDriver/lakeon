# lakeon/sre-agent/tests/integration/test_daily_briefing.py
from agent_session_log import LogStore
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
