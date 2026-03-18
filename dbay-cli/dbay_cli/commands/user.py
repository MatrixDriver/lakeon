import typer
from dbay_cli.client import DbayClient
from dbay_cli.config import get_endpoint, get_api_key
from dbay_cli.output import print_table, print_item, console

app = typer.Typer()

def _client() -> DbayClient:
    return DbayClient(endpoint=get_endpoint(), api_key=get_api_key())

@app.command("list")
def user_list(db: str = typer.Option(..., "--db")):
    """List database users."""
    c = _client()
    d = c.find_database_by_name(db)
    if not d:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    users = c.list_users(d["id"])
    print_table(users, ["id", "username", "role", "created_at"])

@app.command("create")
def user_create(db: str = typer.Option(..., "--db"), username: str = typer.Option(..., "--username"),
                role: str = typer.Option("READER", "--role")):
    """Create a database user."""
    c = _client()
    d = c.find_database_by_name(db)
    if not d:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    result = c.create_user(d["id"], username, role)
    print_item(result)

@app.command("delete")
def user_delete(db: str = typer.Option(..., "--db"), user_id: str = typer.Option(..., "--id")):
    """Delete a database user."""
    c = _client()
    d = c.find_database_by_name(db)
    if not d:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    c.delete_user(d["id"], user_id)
    console.print("Deleted user")
