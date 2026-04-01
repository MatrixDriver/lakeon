import pytest
from lakeon_orchestrator.dag.parser import DAGParser
from lakeon_orchestrator.dag.branch_router import BranchRouter

BRANCH_YAML = """
name: branch-test
data_type: VIDEO
steps:
  - id: filter
    component: rule_filter
    component_version: 1
    inputs: { clip: "$input.clip" }
    output_branches: [passed, needs_crop, dropped]
    outputs: { passed: passed_clip, needs_crop: crop_clip }

  - id: crop
    component: video_crop
    component_version: 1
    condition: "filter.needs_crop"
    inputs: { clip: filter.crop_clip }
    outputs: { clip: cropped_clip }

  - id: merge
    type: merge
    inputs: [filter.passed_clip, crop.clip]
    outputs: { clips: merged }

  - id: drop_log
    component: logger
    component_version: 1
    condition: "filter.dropped"
    inputs: { clip: filter.passed_clip }
    outputs: { log: drop_log_out }
"""


@pytest.fixture
def router():
    dag = DAGParser.parse(BRANCH_YAML)
    return BranchRouter(dag)


def test_route_to_matching_branch(router):
    classify_result = {"__branch__": "needs_crop", "data": {"clip": "a.mp4"}}
    routes = router.route("filter", classify_result)
    assert "crop" in routes
    assert "drop_log" not in routes


def test_route_passed_branch(router):
    classify_result = {"__branch__": "passed", "data": {"clip": "b.mp4"}}
    routes = router.route("filter", classify_result)
    # "passed" items go to merge (no condition = accepts passed)
    # "crop" has condition "filter.needs_crop" -> not matched
    assert "crop" not in routes


def test_route_dropped_branch(router):
    classify_result = {"__branch__": "dropped", "data": {"clip": "c.mp4"}}
    routes = router.route("filter", classify_result)
    assert "drop_log" in routes
    assert "crop" not in routes


def test_get_skipped_steps(router):
    # If no items were routed to "crop", it should be skipped
    active_branches = {"passed"}
    skipped = router.get_skipped_steps("filter", active_branches)
    assert "crop" in skipped
    assert "drop_log" in skipped


def test_is_branch_result():
    assert BranchRouter.is_branch_result({"__branch__": "passed", "data": {}}) is True
    assert BranchRouter.is_branch_result({"data": "normal"}) is False
    assert BranchRouter.is_branch_result(None) is False
