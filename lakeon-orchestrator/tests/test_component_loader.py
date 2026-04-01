import pytest
from lakeon_orchestrator.component.loader import ComponentLoader


def test_load_builtin_component():
    """Load a module-level function by dotted path."""
    # Use a stdlib function as test target
    func = ComponentLoader.load("json.dumps")
    assert callable(func)
    assert func({"a": 1}) == '{"a": 1}'


def test_load_nonexistent_module():
    with pytest.raises(ImportError):
        ComponentLoader.load("nonexistent.module.func")


def test_load_nonexistent_function():
    with pytest.raises(AttributeError):
        ComponentLoader.load("json.nonexistent_func")


def test_load_caches_result():
    func1 = ComponentLoader.load("json.dumps")
    func2 = ComponentLoader.load("json.dumps")
    assert func1 is func2


def test_clear_cache():
    ComponentLoader.load("json.dumps")
    ComponentLoader.clear_cache()
    # After clear, should still work but load fresh
    func = ComponentLoader.load("json.dumps")
    assert callable(func)
