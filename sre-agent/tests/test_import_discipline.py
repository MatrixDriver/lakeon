"""Guard: agent_session_log must stay runtime-agnostic."""
import ast
from pathlib import Path


FORBIDDEN_PREFIXES = ("lakeon", "dbay_sre_mcp", "hermes")
ALLOWED_THIRD_PARTY = {"yaml", "httpx", "obs"}  # whitelist
STDLIB_PREFIXES = None  # checked via sys.stdlib_module_names at runtime


def _collect_imports(py_file: Path) -> set[str]:
    tree = ast.parse(py_file.read_text(encoding="utf-8"))
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                names.add(alias.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            if node.module and node.level == 0:
                names.add(node.module.split(".")[0])
    return names


def test_no_forbidden_imports():
    root = Path(__file__).resolve().parents[1] / "agent_session_log"
    violations = []
    for py in root.rglob("*.py"):
        imports = _collect_imports(py)
        for imp in imports:
            if any(imp == p or imp.startswith(p + ".") for p in FORBIDDEN_PREFIXES):
                violations.append(f"{py.relative_to(root.parent)}: {imp}")
    assert not violations, (
        "agent_session_log must not import lakeon/dbay_sre_mcp/hermes:\n  "
        + "\n  ".join(violations)
    )
