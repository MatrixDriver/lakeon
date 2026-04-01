import pytest
from lakeon_orchestrator.dag.fan_in_handler import FanInHandler


def test_merge_results():
    handler = FanInHandler()
    branch_results = [
        {"data": "clip1.mp4", "source": "filter_0"},
        {"data": "clip3.mp4", "source": "filter_2"},
    ]
    merged = handler.merge(branch_results)
    assert len(merged["items"]) == 2
    assert merged["items"][0] == "clip1.mp4"
    assert merged["items"][1] == "clip3.mp4"


def test_merge_empty():
    handler = FanInHandler()
    merged = handler.merge([])
    assert merged["items"] == []


def test_merge_preserves_order():
    handler = FanInHandler()
    results = [
        {"data": "c", "source": "s_2"},
        {"data": "a", "source": "s_0"},
        {"data": "b", "source": "s_1"},
    ]
    merged = handler.merge(results)
    # Order preserved as given
    assert merged["items"] == ["c", "a", "b"]


def test_merge_with_branch_labels():
    handler = FanInHandler()
    results = [
        {"data": "clip1.mp4", "branch": "passed"},
        {"data": "clip2.mp4", "branch": "passed"},
    ]
    merged = handler.merge(results, expected_branches=["passed"])
    assert len(merged["items"]) == 2
