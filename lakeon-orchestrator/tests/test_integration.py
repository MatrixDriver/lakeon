"""Integration test: DAG parse -> schedule -> component execute (in-memory, no Ray)."""
import pytest
from lakeon_orchestrator.dag.parser import DAGParser
from lakeon_orchestrator.dag.scheduler import DAGScheduler
from lakeon_orchestrator.dag.fan_out_handler import FanOutHandler
from lakeon_orchestrator.dag.fan_in_handler import FanInHandler
from lakeon_orchestrator.dag.branch_router import BranchRouter
from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component, get_component_meta


# Define test components inline
@Component(
    name="double",
    display_name="Double",
    category="DATA_PREP",
    data_type="TEXT",
)
def double_component(ctx: ComponentContext) -> dict:
    value = ctx.input.get("value", 0)
    result = value * 2
    ctx.report({"input": value, "output": result})
    return {"value": result}


@Component(
    name="splitter",
    display_name="Splitter",
    category="EXTRACT",
    data_type="TEXT",
)
def splitter_component(ctx: ComponentContext) -> dict:
    items = ctx.input.get("items", [])
    return ctx.fan_out(items)


@Component(
    name="classifier",
    display_name="Classifier",
    category="FILTER",
    data_type="TEXT",
    output_branches=["big", "small"],
)
def classifier_component(ctx: ComponentContext) -> dict:
    value = ctx.input.get("value", 0)
    if value > 5:
        return ctx.classify({"value": value}, "big")
    return ctx.classify({"value": value}, "small")


PIPELINE_YAML = """
name: integration-test
data_type: TEXT
steps:
  - id: double
    component: double
    component_version: 1
    inputs: { value: "$input.value" }
    outputs: { value: doubled }
  - id: double_again
    component: double
    component_version: 1
    inputs: { value: double.value }
    outputs: { value: quadrupled }
"""


def test_full_dag_parse_and_schedule():
    """Test complete flow: parse YAML -> topological sort -> identify ready steps."""
    dag = DAGParser.parse(PIPELINE_YAML)
    scheduler = DAGScheduler(dag)

    # Topological sort
    order = scheduler.topological_sort()
    assert order == ["double", "double_again"]

    # Initial: only "double" is ready
    statuses = {"double": "PENDING", "double_again": "PENDING"}
    assert scheduler.get_ready_steps(statuses) == ["double"]

    # After double succeeds: double_again is ready
    statuses["double"] = "SUCCEEDED"
    assert scheduler.get_ready_steps(statuses) == ["double_again"]

    # After both succeed: complete
    statuses["double_again"] = "SUCCEEDED"
    assert scheduler.is_complete(statuses)
    assert scheduler.aggregate_status(statuses) == "SUCCEEDED"


def test_component_execution_with_context():
    """Test component decorated function execution via ComponentContext."""
    ctx = ComponentContext(
        step_id="double",
        run_id="run_integ_001",
        input_data={"value": 5},
        params={},
    )
    result = double_component(ctx)
    assert result == {"value": 10}
    assert ctx.metrics["output"] == 10


def test_fan_out_flow():
    """Test fan-out: splitter -> expansion."""
    ctx = ComponentContext(
        step_id="split",
        run_id="run_integ_002",
        input_data={"items": [1, 2, 3]},
        params={},
    )
    result = splitter_component(ctx)

    handler = FanOutHandler()
    assert handler.is_fan_out(result)

    expanded = handler.expand(
        fan_out_result=result,
        source_step_id="split",
        downstream_step_id="process",
        run_id="run_integ_002",
    )
    assert len(expanded) == 3
    assert expanded[0]["input_data"] == 1


def test_branch_routing_flow():
    """Test classification -> branch routing -> merge."""
    # Classify big
    ctx_big = ComponentContext(
        step_id="classify",
        run_id="run_integ_003",
        input_data={"value": 10},
        params={},
    )
    result_big = classifier_component(ctx_big)
    assert BranchRouter.is_branch_result(result_big)
    assert result_big["__branch__"] == "big"

    # Classify small
    ctx_small = ComponentContext(
        step_id="classify",
        run_id="run_integ_003",
        input_data={"value": 3},
        params={},
    )
    result_small = classifier_component(ctx_small)
    assert result_small["__branch__"] == "small"

    # Fan-in merge
    fan_in = FanInHandler()
    merged = fan_in.merge([
        {"data": result_big["data"], "branch": "big"},
        {"data": result_small["data"], "branch": "small"},
    ])
    assert merged["count"] == 2


def test_aggregate_status_transitions():
    """Test pipeline-level status aggregation."""
    dag = DAGParser.parse(PIPELINE_YAML)
    scheduler = DAGScheduler(dag)

    # RUNNING: at least one RUNNING
    assert scheduler.aggregate_status({"double": "RUNNING", "double_again": "PENDING"}) == "RUNNING"

    # FAILED: any FAILED
    assert scheduler.aggregate_status({"double": "FAILED", "double_again": "PENDING"}) == "FAILED"

    # SUCCEEDED: all done
    assert scheduler.aggregate_status({"double": "SUCCEEDED", "double_again": "SUCCEEDED"}) == "SUCCEEDED"
