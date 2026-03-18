import typer
from dbay_cli import config
from dbay_cli.output import console

app = typer.Typer()

@app.command("set")
def config_set(key: str, value: str):
    """Set a config value (endpoint, api_key)."""
    config.set(key, value)
    console.print(f"Set {key}")

@app.command("get")
def config_get(key: str):
    """Get a config value."""
    val = config.get(key)
    console.print(val or "[dim]not set[/dim]")

@app.command("show")
def config_show():
    """Show all config."""
    from dbay_cli.output import print_item
    print_item(config.show())
