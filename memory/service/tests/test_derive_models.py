"""Unit tests for DeriveRequest Pydantic model."""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from pydantic import ValidationError
from models import DeriveRequest


def test_derive_request_minimal_create():
    r = DeriveRequest(
        tenant_id="tn_abc",
        op="create",
        path="/memory/feedback_x.md",
        content="body text",
        memory_type="procedural",
        source_etag="abc123" * 8,
        source_agent="claude",
        source_frontmatter={"type": "feedback"},
    )
    assert r.op == "create"
    assert r.path.startswith("/")


def test_derive_request_delete_has_no_content():
    r = DeriveRequest(
        tenant_id="tn_abc",
        op="delete",
        path="/memory/gone.md",
        source_etag="old-etag",
        source_agent="claude",
    )
    assert r.content is None
    assert r.memory_type is None


def test_derive_request_rejects_invalid_op():
    with pytest.raises(ValidationError):
        DeriveRequest(
            tenant_id="t", op="banana",
            path="/memory/x.md",
            source_etag="e", source_agent="claude",
        )
