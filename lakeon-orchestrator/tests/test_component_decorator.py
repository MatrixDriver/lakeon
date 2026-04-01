import pytest
from lakeon_orchestrator.component.decorator import Component, get_component_meta


def test_component_decorator_stores_metadata():
    @Component(
        name="test_comp",
        display_name="Test Component",
        category="EXTRACT",
        data_type="VIDEO",
        params_schema={"threshold": {"type": "number", "default": 27}},
        input_schema={"type": "video"},
        output_schema={"type": "video_clips"},
    )
    def my_component(ctx):
        return {"result": "ok"}

    meta = get_component_meta(my_component)
    assert meta["name"] == "test_comp"
    assert meta["display_name"] == "Test Component"
    assert meta["category"] == "EXTRACT"
    assert meta["data_type"] == "VIDEO"
    assert meta["params_schema"]["threshold"]["default"] == 27


def test_component_decorator_with_branches():
    @Component(
        name="filter",
        display_name="Filter",
        category="FILTER",
        data_type="VIDEO",
        output_branches=["passed", "dropped"],
    )
    def my_filter(ctx):
        pass

    meta = get_component_meta(my_filter)
    assert meta["output_branches"] == ["passed", "dropped"]


def test_component_decorator_preserves_callable():
    @Component(name="simple", display_name="Simple", category="DATA_PREP", data_type="TEXT")
    def simple_comp(ctx):
        return {"data": ctx.input}

    # The decorated function should still be callable
    assert callable(simple_comp)


def test_get_component_meta_undecorated():
    def plain_func(ctx):
        pass

    with pytest.raises(ValueError, match="not a registered component"):
        get_component_meta(plain_func)
