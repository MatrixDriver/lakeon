import typer
from dbay_cli.client import DbayClient
from dbay_cli.config import get_endpoint, get_api_key
from dbay_cli.output import print_table, print_item, console

app = typer.Typer()

def _client() -> DbayClient:
    return DbayClient(endpoint=get_endpoint(), api_key=get_api_key())

def _resolve_db_branch(c, db_name, branch_name):
    db = c.find_database_by_name(db_name)
    if not db:
        console.print(f"[red]Database '{db_name}' not found[/red]")
        raise typer.Exit(1)
    br = c.find_branch_by_name(db["id"], branch_name)
    if not br:
        console.print(f"[red]Branch '{branch_name}' not found[/red]")
        raise typer.Exit(1)
    return db, br

@app.command("list")
def version_list(db: str = typer.Option(..., "--db"), branch: str = typer.Option(..., "--branch")):
    """List versions."""
    c = _client()
    d, br = _resolve_db_branch(c, db, branch)
    versions = c.list_versions(d["id"], br["id"])
    print_table(versions, ["id", "name", "lsn", "description", "created_by", "created_at"])

@app.command("create")
def version_create(db: str = typer.Option(..., "--db"), branch: str = typer.Option(..., "--branch"),
                   name: str = typer.Option(..., "--name"), desc: str = typer.Option(None, "--desc")):
    """Create a version snapshot."""
    c = _client()
    d, br = _resolve_db_branch(c, db, branch)
    result = c.create_version(d["id"], br["id"], name, desc)
    print_item(result)

@app.command("delete")
def version_delete(db: str = typer.Option(..., "--db"), branch: str = typer.Option(..., "--branch"),
                   version_id: str = typer.Option(..., "--id")):
    """Delete a version."""
    c = _client()
    d, br = _resolve_db_branch(c, db, branch)
    c.delete_version(d["id"], br["id"], version_id)
    console.print("Deleted version")
