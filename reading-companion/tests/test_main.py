"""Smoke test for reading-companion main module."""


def test_cron_tasks_only_daily_reflection():
    import main
    exprs = [expr for expr, _ in main._CRON_TASKS]
    assert exprs == ["0 14 * * *"], f"expected only daily_reflection cron, got {exprs}"


def test_run_daily_reflection_callable():
    import main
    assert callable(main.run_daily_reflection)


def test_main_module_has_no_sre_imports():
    """reading-companion/main.py must not import any skills.sre.*"""
    import inspect
    import main
    src = inspect.getsource(main)
    assert "skills.sre" not in src
    assert "from skills.sre" not in src
    assert "dbay_sre_mcp" not in src
    assert "SREMCPAdapter" not in src
