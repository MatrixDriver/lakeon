from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional

import yaml


@dataclass
class DAGNode:
    id: str
    component: Optional[str] = None
    component_version: Optional[int] = None
    params: dict[str, Any] = field(default_factory=dict)
    inputs: dict[str, str] | list[str] = field(default_factory=dict)
    outputs: dict[str, str] = field(default_factory=dict)
    fan_out: bool = False
    checkpoint: bool = False
    condition: Optional[str] = None
    output_branches: list[str] = field(default_factory=list)
    execution_mode: str = "FUNCTION"
    depends_on: list[str] = field(default_factory=list)
    node_type: str = "component"  # "component" | "merge"
    output_dataset: Optional[dict[str, str]] = None


@dataclass
class DAG:
    name: str
    data_type: str
    description: str = ""
    nodes: dict[str, DAGNode] = field(default_factory=dict)
    edges: dict[str, list[str]] = field(default_factory=dict)  # parent -> [children]
    reverse_edges: dict[str, list[str]] = field(default_factory=dict)  # child -> [parents]

    def get_roots(self) -> list[str]:
        """Return node IDs with no incoming edges."""
        return [nid for nid in self.nodes if not self.reverse_edges.get(nid)]

    def get_children(self, node_id: str) -> list[str]:
        return self.edges.get(node_id, [])

    def get_parents(self, node_id: str) -> list[str]:
        return self.reverse_edges.get(node_id, [])


class DAGParser:
    @staticmethod
    def parse(yaml_str: str) -> DAG:
        data = yaml.safe_load(yaml_str)
        if not data or "steps" not in data:
            raise ValueError("Invalid pipeline YAML: missing 'steps' key")

        dag = DAG(
            name=data.get("name", ""),
            data_type=data.get("data_type", ""),
            description=data.get("description", ""),
        )

        # Pass 1: create nodes
        for step in data["steps"]:
            node = DAGNode(
                id=step["id"],
                component=step.get("component"),
                component_version=step.get("component_version"),
                params=step.get("params", {}),
                inputs=step.get("inputs", {}),
                outputs=step.get("outputs", {}),
                fan_out=step.get("fan_out", False),
                checkpoint=step.get("checkpoint", False),
                condition=step.get("condition"),
                output_branches=step.get("output_branches", []),
                execution_mode=step.get("execution_mode", "FUNCTION"),
                depends_on=step.get("depends_on", []),
                node_type=step.get("type", "component"),
                output_dataset=step.get("output_dataset"),
            )
            dag.nodes[node.id] = node

        # Pass 2: build edges from input references and depends_on
        for node in dag.nodes.values():
            parents = set(node.depends_on)

            # Parse input references like "normalize.video" -> depends on "normalize"
            input_values = (
                node.inputs.values() if isinstance(node.inputs, dict) else node.inputs
            )
            for ref in input_values:
                if isinstance(ref, str) and "." in ref and not ref.startswith("$"):
                    parent_id = ref.split(".")[0]
                    if parent_id in dag.nodes:
                        parents.add(parent_id)

            # Parse condition reference like "filter.needs_crop"
            if node.condition and "." in node.condition:
                parent_id = node.condition.split(".")[0]
                if parent_id in dag.nodes:
                    parents.add(parent_id)

            for parent_id in parents:
                dag.edges.setdefault(parent_id, [])
                if node.id not in dag.edges[parent_id]:
                    dag.edges[parent_id].append(node.id)
                dag.reverse_edges.setdefault(node.id, [])
                if parent_id not in dag.reverse_edges[node.id]:
                    dag.reverse_edges[node.id].append(parent_id)

        return dag
