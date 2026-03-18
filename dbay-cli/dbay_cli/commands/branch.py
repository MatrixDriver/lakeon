import typer
from dbay_cli.client import DbayClient
from dbay_cli.config import get_endpoint, get_api_key
from dbay_cli.output import print_table, print_item, console

app = typer.Typer()

def _client() -> DbayClient:
    return DbayClient(endpoint=get_endpoint(), api_key=get_api_key())

def _resolve_db(c: DbayClient, db_name: str) -> dict:
    db = c.find_database_by_name(db_name)
    if not db:
        console.print(f"[red]Database '{db_name}' not found[/red]")
        raise typer.Exit(1)
    return db

@app.command("list")
def branch_list(db: str = typer.Option(..., "--db")):
    """List branches."""
    c = _client()
    d = _resolve_db(c, db)
    branches = c.list_branches(d["id"])
    print_table(branches, ["id", "name", "is_default", "status", "compute_status", "connection_uri"])

@app.command("create")
def branch_create(db: str = typer.Option(..., "--db"), name: str = typer.Option(..., "--name")):
    """Create a branch."""
    c = _client()
    d = _resolve_db(c, db)
    result = c.create_branch(d["id"], name)
    print_item(result)

@app.command("delete")
def branch_delete(db: str = typer.Option(..., "--db"), branch: str = typer.Option(..., "--branch")):
    """Delete a branch."""
    c = _client()
    d = _resolve_db(c, db)
    br = c.find_branch_by_name(d["id"], branch)
    if not br:
        console.print(f"[red]Branch '{branch}' not found[/red]")
        raise typer.Exit(1)
    c.delete_branch(d["id"], br["id"])
    console.print(f"Deleted branch {branch}")

@app.command("promote")
def branch_promote(db: str = typer.Option(..., "--db"), branch: str = typer.Option(..., "--branch")):
    """Promote branch to default."""
    c = _client()
    d = _resolve_db(c, db)
    br = c.find_branch_by_name(d["id"], branch)
    if not br:
        console.print(f"[red]Branch '{branch}' not found[/red]")
        raise typer.Exit(1)
    c.promote_branch(d["id"], br["id"])
    console.print(f"Promoted {branch} to default")

@app.command("restore")
def branch_restore(db: str = typer.Option(..., "--db"), branch: str = typer.Option(..., "--branch"),
                   version_id: str = typer.Option(None, "--version-id"),
                   lsn: str = typer.Option(None, "--lsn")):
    """Restore branch to a version or LSN."""
    c = _client()
    d = _resolve_db(c, db)
    br = c.find_branch_by_name(d["id"], branch)
    if not br:
        console.print(f"[red]Branch '{branch}' not found[/red]")
        raise typer.Exit(1)
    c.restore_branch(d["id"], br["id"], target_version_id=version_id, target_lsn=lsn)
    console.print(f"Restored {branch}")
