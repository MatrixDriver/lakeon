"""Reading-companion code must not import sre-agent code (no shared physical files)
and must use only public agent_session_log API.
"""
import ast
from pathlib import Path


def test_reading_skills_do_not_import_sre_skills():
    reading_root = Path(__file__).resolve().parents[1] / "skills" / "reading"
    violations = []
    for py in reading_root.rglob("*.py"):
        text = py.read_text(encoding="utf-8")
        for needle in ("skills.sre", "from skills.sre", "sre.cold_start", "sre.outcome"):
            if needle in text:
                violations.append(f"{py}: {needle}")
    assert not violations, "\n".join(violations)


def test_reading_skills_use_only_public_agent_session_log_api():
    reading_root = Path(__file__).resolve().parents[1] / "skills" / "reading"
    violations = []
    for py in reading_root.rglob("*.py"):
        tree = ast.parse(py.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module:
                if node.module.startswith("agent_session_log."):
                    violations.append(f"{py}: from {node.module} (private submodule)")
    assert not violations, "\n".join(violations)
