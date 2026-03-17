from mem0_dbay.utils import sanitize_relationship, build_filter_conditions


def test_sanitize_relationship_spaces():
    assert sanitize_relationship("works at") == "works_at"


def test_sanitize_relationship_special_chars():
    assert sanitize_relationship("loves/hates") == "loves_hates"


def test_sanitize_relationship_unicode():
    assert sanitize_relationship("lives in 北京") == "lives_in"


def test_sanitize_relationship_uppercase():
    assert sanitize_relationship("WORKS_AT") == "works_at"


def test_build_filter_basic():
    sql, params = build_filter_conditions({"user_id": "alice"})
    assert "user_id = %s" in sql
    assert params == ["alice"]


def test_build_filter_with_agent():
    sql, params = build_filter_conditions({"user_id": "alice", "agent_id": "bot1"})
    assert "user_id = %s" in sql
    assert "agent_id = %s" in sql
    assert len(params) == 2


def test_build_filter_ignores_none():
    sql, params = build_filter_conditions({"user_id": "alice", "agent_id": None})
    assert "agent_id" not in sql
    assert len(params) == 1


def test_build_filter_with_alias():
    sql, params = build_filter_conditions({"user_id": "alice"}, "n")
    assert "n.user_id = %s" in sql


def test_build_filter_empty():
    sql, params = build_filter_conditions({})
    assert sql == "TRUE"
    assert params == []
