"""
lakeon tenant 命令 -- 租户管理
"""

import typer
from rich.console import Console

from lakeon_cli.client import LakeonClient

app = typer.Typer()
console = Console()


@app.command("create")
def create(name: str = typer.Option(..., help="租户名称")) -> None:
    """创建租户"""
    client = LakeonClient()
    try:
        result = client.create_tenant(name)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    console.print(f"[green]Tenant created: {result['id']}[/green]")
    console.print(f"API Key: {result['api_key']}")
