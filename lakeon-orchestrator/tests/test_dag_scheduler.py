# tests/test_dag_scheduler.py
import pytest
from lakeon_orchestrator.dag.parser import DAGParser
from lakeon_orchestrator.dag.scheduler import DAGScheduler

LINEAR_YAML = """
name: linear
data_type: TEXT
steps:
  - id: step_a
    component: comp_a
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: a_out }
  - id: step_b
    component: comp_b
    component_version: 1
    inputs: { text: step_a.text }
    outputs: { text: b_out }
  - id: step_c
    component: comp_c
    component_version: 1
    inputs: { text: step_b.text }
    outputs: { text: c_out }
"""

DIAMOND_YAML = """
name: diamond
data_type: TEXT
steps:
  - id: start
    component: comp_a
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: a_out }
  - id: branch_1
    component: comp_b
    component_version: 1
    inputs: { text: start.text }
    outputs: { text: b1_out }
  - id: branch_2
    component: comp_c
    component_version: 1
    inputs: { text: start.text }
    outputs: { text: b2_out }
  - id: join
    type: merge
    inputs: [branch_1.text, branch_2.text]
    outputs: { text: merged }
"""


def test_topological_sort_linear():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    order = scheduler.topological_sort()
    assert order == ["step_a", "step_b", "step_c"]


def test_topological_sort_diamond():
    dag = DAGParser.parse(DIAMOND_YAML)
    scheduler = DAGScheduler(dag)
    order = scheduler.topological_sort()
    assert order.index("start") < order.index("branch_1")
    assert order.index("start") < order.index("branch_2")
    assert order.index("branch_1") < order.index("join")
    assert order.index("branch_2") < order.index("join")


def test_get_ready_steps_initial():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    step_statuses = {"step_a": "PENDING", "step_b": "PENDING", "step_c": "PENDING"}
    ready = scheduler.get_ready_steps(step_statuses)
    assert ready == ["step_a"]


def test_get_ready_steps_after_first():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    step_statuses = {"step_a": "SUCCEEDED", "step_b": "PENDING", "step_c": "PENDING"}
    ready = scheduler.get_ready_steps(step_statuses)
    assert ready == ["step_b"]


def test_get_ready_steps_parallel():
    dag = DAGParser.parse(DIAMOND_YAML)
    scheduler = DAGScheduler(dag)
    step_statuses = {
        "start": "SUCCEEDED",
        "branch_1": "PENDING",
        "branch_2": "PENDING",
        "join": "PENDING",
    }
    ready = scheduler.get_ready_steps(step_statuses)
    assert set(ready) == {"branch_1", "branch_2"}


def test_get_ready_steps_join_waits():
    dag = DAGParser.parse(DIAMOND_YAML)
    scheduler = DAGScheduler(dag)
    step_statuses = {
        "start": "SUCCEEDED",
        "branch_1": "SUCCEEDED",
        "branch_2": "RUNNING",
        "join": "PENDING",
    }
    ready = scheduler.get_ready_steps(step_statuses)
    assert ready == []


def test_is_dag_complete():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    assert scheduler.is_complete({"step_a": "SUCCEEDED", "step_b": "SUCCEEDED", "step_c": "SUCCEEDED"})
    assert not scheduler.is_complete({"step_a": "SUCCEEDED", "step_b": "RUNNING", "step_c": "PENDING"})


def test_is_dag_failed():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    assert scheduler.has_failed({"step_a": "SUCCEEDED", "step_b": "FAILED", "step_c": "PENDING"})
    assert not scheduler.has_failed({"step_a": "SUCCEEDED", "step_b": "RUNNING", "step_c": "PENDING"})


def test_cycle_detection():
    cycle_yaml = """
name: cycle
data_type: TEXT
steps:
  - id: a
    component: comp_a
    component_version: 1
    inputs: { text: b.text }
    outputs: { text: a_out }
  - id: b
    component: comp_b
    component_version: 1
    inputs: { text: a.text }
    outputs: { text: b_out }
"""
    dag = DAGParser.parse(cycle_yaml)
    scheduler = DAGScheduler(dag)
    with pytest.raises(ValueError, match="cycle"):
        scheduler.topological_sort()
