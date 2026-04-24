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
