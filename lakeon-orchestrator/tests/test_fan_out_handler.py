import pytest
from lakeon_orchestrator.dag.fan_out_handler import FanOutHandler


def test_expand_fan_out_result():
    handler = FanOutHandler()
    result = {
        "__fan_out__": True,
        "items": ["clip1.mp4", "clip2.mp4", "clip3.mp4"],
    }
    expanded = handler.expand(
        fan_out_result=result,
        source_step_id="scene_split",
        downstream_step_id="rule_filter",
        run_id="run_001",
    )
    assert len(expanded) == 3
    assert expanded[0]["step_run_id"] == "sr_run_001_rule_filter_0"
    assert expanded[0]["input_data"] == "clip1.mp4"
    assert expanded[1]["step_run_id"] == "sr_run_001_rule_filter_1"
    assert expanded[2]["input_data"] == "clip3.mp4"


def test_expand_empty_items():
    handler = FanOutHandler()
    result = {"__fan_out__": True, "items": []}
    expanded = handler.expand(
        fan_out_result=result,
        source_step_id="split",
        downstream_step_id="filter",
        run_id="run_002",
    )
    assert expanded == []


def test_is_fan_out_result():
    handler = FanOutHandler()
    assert handler.is_fan_out({"__fan_out__": True, "items": []}) is True
    assert handler.is_fan_out({"data": "normal"}) is False
    assert handler.is_fan_out(None) is False


def test_generate_step_run_id():
    handler = FanOutHandler()
    sid = handler.generate_step_run_id("run_001", "filter", 5)
    assert sid == "sr_run_001_filter_5"
