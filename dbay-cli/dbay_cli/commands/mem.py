import json
import typer

app = typer.Typer()


def _client():
    from dbay_cli.config import get_endpoint, get as config_get
    from dbay_cli.client import DbayClient
    return DbayClient(endpoint=get_endpoint(), api_key=config_get("api_key"))


@app.command("list")
def list_bases():
    """List memory bases."""
    bases = _client().list_memory_bases()
    for b in bases:
        mode = "agent-extract" if b.get("one_llm_mode") else "normal"
        typer.echo(f"{b['id']}  {b['name']}  [{b['status']}]  mode={mode}")


@app.command("create")
def create(name: str, desc: str = typer.Option(None, "--desc"),
           agent_extract: bool = typer.Option(False, "--agent-extract")):
    """Create a memory base."""
    result = _client().create_memory_base(name, desc, one_llm_mode=agent_extract)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("info")
def info(mem_id: str):
    """Show memory base details."""
    result = _client().get_memory_base(mem_id)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("delete")
def delete(mem_id: str, yes: bool = typer.Option(False, "-y")):
    """Delete a memory base."""
    if not yes:
        typer.confirm(f"Delete memory base {mem_id}?", abort=True)
    _client().delete_memory_base(mem_id)
    typer.echo("Deleted.")


@app.command("ingest")
def ingest(mem_id: str, content: str,
           role: str = typer.Option("user", "--role"),
           no_extract: bool = typer.Option(False, "--no-extract")):
    """Ingest content into memory base."""
    auto_extract = False if no_extract else None
    result = _client().mem_ingest(mem_id, content, role, auto_extract)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("ingest-extracted")
def ingest_extracted(mem_id: str,
                     message_id: str = typer.Option(..., "--message-id"),
                     data: str = typer.Option(..., "--data")):
    """Store pre-extracted memories."""
    result = _client().mem_ingest_extracted(mem_id, message_id, json.loads(data))
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("recall")
def recall(mem_id: str, query: str,
           types: str = typer.Option(None, "--types"),
           limit: int = typer.Option(10, "--limit")):
    """Recall memories by semantic search."""
    memory_types = types.split(",") if types else None
    result = _client().mem_recall(mem_id, query, limit, memory_types)
    for m in result.get("memories", []):
        typer.echo(f"  [{m.get('memory_type', '?')}] {m.get('content', '')}")


@app.command("list-memories")
def list_memories(mem_id: str,
                  type: str = typer.Option(None, "--type"),
                  limit: int = typer.Option(20, "--limit"),
                  offset: int = typer.Option(0, "--offset")):
    """List memories in a base."""
    result = _client().mem_list(mem_id, type, offset, limit)
    typer.echo(f"Total: {result.get('total', 0)}")
    for m in result.get("memories", []):
        typer.echo(f"  #{m['id']} [{m['memory_type']}] {m['content'][:80]}")


@app.command("delete-memory")
def delete_memory(mem_id: str, memory_id: int):
    """Delete a single memory."""
    _client().mem_delete(mem_id, memory_id)
    typer.echo("Deleted.")


@app.command("stats")
def stats(mem_id: str):
    """Show memory statistics."""
    result = _client().mem_stats(mem_id)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("digest")
def digest(mem_id: str):
    """Run digest (reflection) on unreflected memories."""
    result = _client().mem_digest(mem_id)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("digest-extracted")
def digest_extracted(mem_id: str,
                     data: str = typer.Option(..., "--data")):
    """Store pre-extracted digest traits."""
    result = _client().mem_digest_extracted(mem_id, json.loads(data))
    typer.echo(json.dumps(result, indent=2, default=str))
