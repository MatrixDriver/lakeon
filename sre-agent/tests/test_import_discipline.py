"""sre-agent code (skills/sre/, main.py) must not import lakeon internals.

After B2 refactor:
- agent_session_log lives at packages/agent-session-log/, installed via uv workspace
- skills/reading/ moved to reading-companion/ entirely
- main.py imports hermes_agent_utils (workspace package), which is allowed

The "hermes" prefix is intentionally NOT in the forbidden list because:
- hermes-agent (Nous Research runtime) is invoked as a subprocess, not imported
- hermes-agent-utils (our internal workspace package) is legitimately imported

If those rules change, update FORBIDDEN_PREFIXES.
"""
import ast
from pathlib import Path


FORBIDDEN_PREFIXES = ("lakeon",)  # only block lakeon-internal cross-imports


def _scan(py: Path, violations: list[str]) -> None:
    text = py.read_text(encoding="utf-8")
    tree = ast.parse(text)
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if any(alias.name == p or alias.name.startswith(p + ".") for p in FORBIDDEN_PREFIXES):
                    violations.append(f"{py}: import {alias.name}")
        elif isinstance(node, ast.ImportFrom) and node.module:
            if any(node.module == p or node.module.startswith(p + ".") for p in FORBIDDEN_PREFIXES):
                violations.append(f"{py}: from {node.module}")


def test_no_forbidden_imports():
    """sre-agent code must not import lakeon-internal packages directly."""
    sre_skills_root = Path(__file__).resolve().parents[1] / "skills" / "sre"
    main_py = Path(__file__).resolve().parents[1] / "main.py"

    violations: list[str] = []
    for py in sre_skills_root.rglob("*.py"):
        _scan(py, violations)
    if main_py.exists():
        _scan(main_py, violations)

    assert not violations, "sre-agent has forbidden imports:\n  " + "\n  ".join(violations)


def test_sre_skills_do_not_import_reading_skills():
    """SRE category must stay isolated from reading category (now in reading-companion service)."""
    sre_root = Path(__file__).resolve().parents[1] / "skills" / "sre"
    violations = []
    for py in sre_root.rglob("*.py"):
        text = py.read_text(encoding="utf-8")
        for needle in ("skills.reading", "from skills.reading"):
            if needle in text:
                violations.append(f"{py}: {needle}")
    assert not violations, "\n".join(violations)
