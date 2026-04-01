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
