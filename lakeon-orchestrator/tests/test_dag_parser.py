# tests/test_dag_parser.py
import pytest
from lakeon_orchestrator.dag.parser import DAGParser, DAGNode, DAG


SIMPLE_YAML = """
name: test-pipeline
data_type: VIDEO
steps:
  - id: normalize
    component: video_normalize
    component_version: 1
    params: { target_resolution: "1080p" }
    inputs: { video: "$input.dataset" }
    outputs: { video: normalized }

  - id: scene_split
    component: video_scene_split
    component_version: 1
    params: { threshold: 27 }
    inputs: { video: normalize.video }
    fan_out: true
    checkpoint: true
    outputs: { clips: split_clips }
"""

BRANCH_YAML = """
name: branch-pipeline
data_type: VIDEO
steps:
  - id: filter
    component: rule_filter
    component_version: 1
    inputs: { clip: scene_split.clips }
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
    outputs: { clips: merged_clips }
"""


def test_parse_simple_dag():
    dag = DAGParser.parse(SIMPLE_YAML)
    assert isinstance(dag, DAG)
    assert dag.name == "test-pipeline"
    assert len(dag.nodes) == 2
    assert "normalize" in dag.nodes
    assert "scene_split" in dag.nodes


def test_parse_edges():
    dag = DAGParser.parse(SIMPLE_YAML)
    # scene_split depends on normalize (inputs reference normalize.video)
    assert "normalize" in dag.edges
    assert "scene_split" in dag.edges["normalize"]


def test_parse_fan_out_flag():
    dag = DAGParser.parse(SIMPLE_YAML)
    assert dag.nodes["scene_split"].fan_out is True
    assert dag.nodes["normalize"].fan_out is False


def test_parse_checkpoint_flag():
    dag = DAGParser.parse(SIMPLE_YAML)
    assert dag.nodes["scene_split"].checkpoint is True
    assert dag.nodes["normalize"].checkpoint is False


def test_parse_node_attributes():
    dag = DAGParser.parse(SIMPLE_YAML)
    node = dag.nodes["normalize"]
    assert node.component == "video_normalize"
    assert node.component_version == 1
    assert node.params == {"target_resolution": "1080p"}
    assert node.inputs == {"video": "$input.dataset"}
    assert node.outputs == {"video": "normalized"}


def test_parse_condition_branch():
    dag = DAGParser.parse(BRANCH_YAML)
    crop = dag.nodes["crop"]
    assert crop.condition == "filter.needs_crop"


def test_parse_output_branches():
    dag = DAGParser.parse(BRANCH_YAML)
    filter_node = dag.nodes["filter"]
    assert filter_node.output_branches == ["passed", "needs_crop", "dropped"]


def test_parse_merge_node():
    dag = DAGParser.parse(BRANCH_YAML)
    merge_node = dag.nodes["merge"]
    assert merge_node.node_type == "merge"
    assert merge_node.component is None


def test_roots():
    dag = DAGParser.parse(SIMPLE_YAML)
    roots = dag.get_roots()
    assert len(roots) == 1
    assert roots[0] == "normalize"


def test_invalid_yaml():
    with pytest.raises(ValueError, match="steps"):
        DAGParser.parse("name: bad\nno_steps: true")


# --- execution_engine tests ---

EXECUTION_ENGINE_YAML = """
name: engine-pipeline
data_type: VIDEO
execution_engine: python

steps:
  - id: normalize
    component: video_normalize
    inputs: { video: "$input.dataset" }

  - id: scene_split
    component: video_scene_split
    execution_engine: ray
    inputs: { video: normalize.video }

  - id: export
    component: video_export
    execution_engine: python
    inputs: { video: scene_split.clips }
"""


def test_parse_default_engine():
    dag = DAGParser.parse(EXECUTION_ENGINE_YAML)
    assert dag.default_engine == "python"


def test_step_inherits_pipeline_engine():
    dag = DAGParser.parse(EXECUTION_ENGINE_YAML)
    # normalize has no execution_engine set, should inherit pipeline default
    assert dag.nodes["normalize"].execution_engine == "python"


def test_step_overrides_engine():
    dag = DAGParser.parse(EXECUTION_ENGINE_YAML)
    # scene_split explicitly sets ray
    assert dag.nodes["scene_split"].execution_engine == "ray"
    # export explicitly sets python
    assert dag.nodes["export"].execution_engine == "python"


def test_default_engine_when_not_specified():
    """When pipeline YAML has no execution_engine, default to python."""
    dag = DAGParser.parse(SIMPLE_YAML)
    assert dag.default_engine == "python"
    assert dag.nodes["normalize"].execution_engine == "python"
    assert dag.nodes["scene_split"].execution_engine == "python"


RAY_DEFAULT_YAML = """
name: ray-pipeline
data_type: TEXT
execution_engine: ray

steps:
  - id: step_a
    component: comp_a
    inputs: { text: "$input.dataset" }

  - id: step_b
    component: comp_b
    execution_engine: python
    inputs: { text: step_a.text }
"""


def test_ray_default_engine():
    dag = DAGParser.parse(RAY_DEFAULT_YAML)
    assert dag.default_engine == "ray"
    assert dag.nodes["step_a"].execution_engine == "ray"
    assert dag.nodes["step_b"].execution_engine == "python"
