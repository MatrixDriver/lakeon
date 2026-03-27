"""dbay setup <agent> — inject memory instructions into agent instruction files."""

import typer
import yaml
from pathlib import Path

app = typer.Typer()

_DESCS_FILE = Path(__file__).parent.parent.parent.parent / "dbay-mcp" / "src" / "dbay_mcp" / "tool_descriptions.yaml"
# Fallback: bundled copy
_BUNDLED_DESCS_FILE = Path(__file__).parent / "agent_instructions.yaml"


def _load_agents() -> dict:
    """Load agent definitions from YAML."""
    for f in [_DESCS_FILE, _BUNDLED_DESCS_FILE]:
        if f.exists():
            data = yaml.safe_load(f.read_text()) or {}
            return data.get("agents", {})
    # Inline fallback if no YAML found
    instruction = (
        "<!-- dbay-memory -->\n"
        "## DBay Memory\n"
        'When the user says "记住/remember", call dbay MCP `memory_ingest` to store it '
        "in the user's long-term memory (persists across projects and sessions). "
        "When the user asks about past decisions or context, call `memory_recall` first.\n"
    )
    return {
        "claude-code": {"file": "~/.claude/CLAUDE.md", "marker": "<!-- dbay-memory -->", "instruction": instruction},
        "gemini": {"file": "~/.gemini/GEMINI.md", "marker": "<!-- dbay-memory -->", "instruction": instruction},
        "cursor": {"file": "~/.cursor/rules/dbay.mdc", "marker": "dbay-memory",
                   "instruction": "---\ndescription: DBay memory integration\nglobs:\nalwaysApply: true\n---\n" + instruction},
        "windsurf": {"file": "~/.windsurf/rules/dbay.md", "marker": "<!-- dbay-memory -->", "instruction": instruction},
    }


@app.callback(invoke_without_command=True)
def setup(agent: str = typer.Argument(None, help="Agent name: claude-code, gemini, cursor, windsurf")):
    """Inject DBay memory instructions into an AI agent's instruction file."""
    agents = _load_agents()

    if not agent:
        available = ", ".join(sorted(agents.keys()))
        typer.echo(f"Usage: dbay setup <agent>")
        typer.echo(f"Available agents: {available}")
        raise typer.Exit(1)

    if agent not in agents:
        available = ", ".join(sorted(agents.keys()))
        typer.echo(f"Unknown agent: {agent}")
        typer.echo(f"Available: {available}")
        raise typer.Exit(1)

    cfg = agents[agent]
    target = Path(cfg["file"]).expanduser()
    marker = cfg["marker"]
    instruction = cfg["instruction"]

    # Idempotent: check if already injected
    if target.exists() and marker in target.read_text():
        typer.echo(f"Already configured: {target}")
        return

    # Append instruction
    target.parent.mkdir(parents=True, exist_ok=True)
    with open(target, "a") as f:
        if target.exists() and target.stat().st_size > 0:
            f.write("\n")
        f.write(instruction)

    typer.echo(f"Done: {target}")
