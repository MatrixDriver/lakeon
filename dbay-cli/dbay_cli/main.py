import typer

app = typer.Typer(name="dbay", help="DBay Serverless PostgreSQL CLI")

from dbay_cli.commands import auth, db, branch, version, user, kb

app.add_typer(auth.app, name="config", help="CLI configuration")
app.add_typer(db.app, name="db", help="Database management")
app.add_typer(branch.app, name="branch", help="Branch management")
app.add_typer(version.app, name="version", help="Version management")
app.add_typer(user.app, name="user", help="Database user management")
app.add_typer(kb.app, name="kb", help="Knowledge base management")

@app.command()
def login(username: str = typer.Option(..., prompt=True),
          password: str = typer.Option(..., prompt=True, hide_input=True)):
    """Login and save API key."""
    from dbay_cli.client import DbayClient
    from dbay_cli.config import get_endpoint, set as config_set
    client = DbayClient(endpoint=get_endpoint())
    result = client.login(username, password)
    config_set("api_key", result["api_key"])
    typer.echo(f"Logged in as {result.get('username', username)}")

if __name__ == "__main__":
    app()
