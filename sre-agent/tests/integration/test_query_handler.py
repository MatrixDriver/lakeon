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
