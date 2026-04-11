"""Tool schemas match OpenAI function-calling format."""
from app.agent.tools import INGEST_FORBIDDEN, TOOL_NAMES, TOOL_SCHEMAS


def test_all_tools_follow_openai_schema():
    for t in TOOL_SCHEMAS:
        assert t["type"] == "function"
        fn = t["function"]
        assert "name" in fn
        assert "description" in fn
        assert isinstance(fn["description"], str) and len(fn["description"]) > 0
        assert "parameters" in fn
        assert fn["parameters"]["type"] == "object"
        assert "properties" in fn["parameters"]
        assert "required" in fn["parameters"]


def test_expected_tools_present():
    expected = {
        "list_pages",
        "read_page",
        "search_pages",
        "read_source",
        "get_schema",
        "create_page",
        "update_page",
        "append_page",
        "delete_page",
        "log_note",
        "done",
    }
    assert expected <= TOOL_NAMES


def test_tool_names_set_matches_schemas():
    assert TOOL_NAMES == {t["function"]["name"] for t in TOOL_SCHEMAS}


def test_delete_page_is_ingest_forbidden():
    assert "delete_page" in INGEST_FORBIDDEN


def test_required_params_present():
    by_name = {t["function"]["name"]: t["function"]["parameters"] for t in TOOL_SCHEMAS}
    # Spot-check a few that must have required params
    assert "title" in by_name["read_page"]["required"]
    assert "query" in by_name["search_pages"]["required"]
    assert "title" in by_name["create_page"]["required"]
    assert "content" in by_name["create_page"]["required"]
    assert "old_text" in by_name["update_page"]["required"]
    assert "new_text" in by_name["update_page"]["required"]
    assert "message" in by_name["log_note"]["required"]
    assert "summary" in by_name["done"]["required"]
    # Tools with no required params still have the key (may be empty list)
    assert by_name["list_pages"]["required"] == []
    assert by_name["get_schema"]["required"] == []
