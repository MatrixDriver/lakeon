"""LakeonAdminClient — thin httpx wrapper around lakeon-api admin endpoints."""
from __future__ import annotations

import httpx
import pytest

from dbay_sre_mcp.admin_client import LakeonAdminClient


class _FakeHttp:
    """Stand-in for httpx.Client. Records requests, returns canned JSON."""
    def __init__(self, responses: dict[tuple[str, str], dict]):
        self.responses = responses
        self.calls: list[tuple[str, str, dict]] = []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        pass

    def get(self, url, headers=None, params=None, timeout=None):
        self.calls.append(("GET", url, params or {}))
        key = ("GET", url)
        if key not in self.responses:
            class R:
                status_code = 404
                def raise_for_status(self): raise httpx.HTTPStatusError("404", request=None, response=self)
                def json(self): return {}
            return R()
        body = self.responses[key]
        class R:
            status_code = 200
            def raise_for_status(self): pass
            def json(self_inner): return body
        return R()


def test_init_strips_trailing_slash():
    c = LakeonAdminClient(base_url="https://api.dbay.cloud:8443/api/v1/", token="t")
    assert c._base_url == "https://api.dbay.cloud:8443/api/v1"


def test_get_database_by_id(monkeypatch):
    fake = _FakeHttp({
        ("GET", "https://api.example/api/v1/admin/databases/db_xyz"): {
            "id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc",
            "status": "READY", "compute_host": "pod-1",
        },
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://api.example/api/v1", token="lakeon-sre-2026")
    out = c.get_database(db_id="db_xyz")
    assert out["name"] == "tcph-bench"
    assert fake.calls == [("GET", "https://api.example/api/v1/admin/databases/db_xyz", {})]


def test_list_databases_with_filter(monkeypatch):
    fake = _FakeHttp({
        ("GET", "https://api.example/api/v1/admin/databases"): {
            "items": [
                {"id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc"},
                {"id": "db_abc", "name": "perf-test", "tenant_id": "t_abc"},
            ],
            "total": 2,
        },
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://api.example/api/v1", token="t")
    out = c.list_databases(name_contains="tcph")
    assert any(d["name"] == "tcph-bench" for d in out)


def test_admin_token_in_header(monkeypatch):
    captured: dict = {}

    class TrackHttp(_FakeHttp):
        def get(self, url, headers=None, params=None, timeout=None):
            captured["headers"] = headers
            return super().get(url, headers, params, timeout)

    fake = TrackHttp({
        ("GET", "https://x/api/v1/admin/databases/d1"): {"id": "d1"},
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://x/api/v1", token="my-secret-token")
    c.get_database(db_id="d1")
    assert captured["headers"]["Authorization"] == "Bearer my-secret-token"
    assert "Admin-Token" not in captured["headers"]


def test_404_returns_none():
    """Non-existent resource → None (not an exception)."""
    fake = _FakeHttp({})
    import httpx
    import unittest.mock as m
    with m.patch.object(httpx, "Client", lambda *a, **kw: fake):
        c = LakeonAdminClient(base_url="https://x/api/v1", token="t")
        out = c.get_database(db_id="nonexistent")
        assert out is None
