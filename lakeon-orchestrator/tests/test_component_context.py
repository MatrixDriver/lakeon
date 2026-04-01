import pytest
from lakeon_orchestrator.component.context import ComponentContext


def test_context_input_and_params():
    ctx = ComponentContext(
        step_id="normalize",
        run_id="run_001",
        input_data={"video": "/path/to/video.mp4"},
        params={"target_resolution": "1080p"},
    )
    assert ctx.input["video"] == "/path/to/video.mp4"
    assert ctx.params["target_resolution"] == "1080p"


def test_context_report():
    ctx = ComponentContext(step_id="split", run_id="run_001", input_data={}, params={})
    ctx.report({"input_count": 1, "output_count": 42})
    assert ctx.metrics == {"input_count": 1, "output_count": 42}


def test_context_fan_out():
    ctx = ComponentContext(step_id="split", run_id="run_001", input_data={}, params={})
    result = ctx.fan_out(["clip1.mp4", "clip2.mp4", "clip3.mp4"])
    assert result["__fan_out__"] is True
    assert len(result["items"]) == 3


def test_context_classify():
    ctx = ComponentContext(step_id="filter", run_id="run_001", input_data={}, params={})
    result = ctx.classify({"clip": "a.mp4"}, "passed")
    assert result["__branch__"] == "passed"
    assert result["data"]["clip"] == "a.mp4"


def test_context_log():
    ctx = ComponentContext(step_id="step1", run_id="run_001", input_data={}, params={})
    ctx.log("Processing started")
    ctx.log("Step complete")
    assert len(ctx.logs) == 2
    assert "Processing started" in ctx.logs[0]


def test_context_checkpoint():
    ctx = ComponentContext(step_id="step1", run_id="run_001", input_data={}, params={})
    ctx.checkpoint({"intermediate": "data"})
    assert ctx.checkpoint_data == {"intermediate": "data"}
