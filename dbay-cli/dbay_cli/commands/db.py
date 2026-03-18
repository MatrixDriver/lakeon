import typer
from dbay_cli.client import DbayClient
from dbay_cli.config import get_endpoint, get_api_key
from dbay_cli.output import print_table, print_item, console

app = typer.Typer()

def _client() -> DbayClient:
    return DbayClient(endpoint=get_endpoint(), api_key=get_api_key())

@app.command("list")
def db_list():
    """List all databases."""
    dbs = _client().list_databases()
    print_table(dbs, ["id", "name", "status", "connection_uri", "created_at"])

@app.command("create")
def db_create(name: str, compute_size: str = "0.25cu"):
    """Create a database."""
    result = _client().create_database(name, compute_size)
    print_item(result)

@app.command("info")
def db_info(name: str):
    """Get database info by name."""
    db = _client().find_database_by_name(name)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    print_item(db)

@app.command("connstr")
def db_connstr(name: str, branch: str | None = None):
    """Output connection string (pipe to psql)."""
    c = _client()
    db = c.find_database_by_name(name)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]", err=True)
        raise typer.Exit(1)
    if branch:
        br = c.find_branch_by_name(db["id"], branch)
        if not br or not br.get("connection_uri"):
            console.print(f"[red]Branch '{branch}' not found[/red]", err=True)
            raise typer.Exit(1)
        uri = br["connection_uri"]
    else:
        uri = db["connection_uri"]
    # Append sslmode=require if not present
    if "sslmode" not in uri:
        sep = "&" if "?" in uri else "?"
        uri = f"{uri}{sep}sslmode=require"
    typer.echo(uri)

@app.command("suspend")
def db_suspend(name: str):
    """Suspend a database."""
    c = _client()
    db = c.find_database_by_name(name)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    c.suspend_database(db["id"])
    console.print(f"Suspended {name}")

@app.command("resume")
def db_resume(name: str):
    """Resume a database."""
    c = _client()
    db = c.find_database_by_name(name)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    c.resume_database(db["id"])
    console.print(f"Resumed {name}")

@app.command("delete")
def db_delete(name: str, yes: bool = typer.Option(False, "--yes", "-y")):
    """Delete a database."""
    if not yes:
        typer.confirm(f"Delete database '{name}'?", abort=True)
    c = _client()
    db = c.find_database_by_name(name)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    c.delete_database(db["id"])
    console.print(f"Deleted {name}")
