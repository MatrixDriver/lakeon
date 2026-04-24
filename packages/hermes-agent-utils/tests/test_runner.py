from hermes_agent_utils.runner import _CHILD_PROCS, cron_loop, start_subprocess


def test_start_subprocess_tracks_for_cleanup(monkeypatch):
    """start_subprocess appends to _CHILD_PROCS so shutdown_children can terminate it."""
    import subprocess

    class FakePopen:
        def __init__(self, cmd, env=None): self.cmd = cmd
        def terminate(self): pass

    monkeypatch.setattr(subprocess, "Popen", FakePopen)
    _CHILD_PROCS.clear()
    proc = start_subprocess(["echo", "hi"], "test")
    assert proc in _CHILD_PROCS


def test_cron_loop_signature():
    """Smoke: cron_loop accepts list of (str, callable) tuples."""
    import inspect
    sig = inspect.signature(cron_loop)
    assert list(sig.parameters) == ["tasks"]
