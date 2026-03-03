"""
lakeon branch 命令 -- 分支管理
"""

import typer
from rich.console import Console
from rich.table import Table

from lakeon_cli.client import LakeonClient

app = typer.Typer()
console = Console()


@app.command("create")
def create(
    db: str = typer.Option(..., help="数据库名称"),
    name: str = typer.Option(..., help="分支名称"),
) -> None:
    """创建分支"""
    client = LakeonClient()
    try:
        db_obj = client.find_database_by_name(db)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not db_obj:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    try:
        result = client.create_branch(db_obj["id"], name, start_compute=True)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    console.print(f"[green]Branch created: {result['id']}[/green]")
    console.print(f"Name: {result['name']}")
    if result.get("connection_uri"):
        console.print(f"Connection URI: {result['connection_uri']}")


@app.command("list")
def list_branches(db: str = typer.Option(..., help="数据库名称")) -> None:
    """列出分支"""
    client = LakeonClient()
    try:
        db_obj = client.find_database_by_name(db)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not db_obj:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    try:
        branches = client.list_branches(db_obj["id"])
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    table = Table(title=f"Branches of {db}")
    table.add_column("ID")
    table.add_column("Name")
    table.add_column("Status")
    table.add_column("Default")
    for br in branches:
        table.add_row(
            br["id"],
            br["name"],
            br.get("status", ""),
            str(br.get("is_default", False)),
        )
    console.print(table)


@app.command("delete")
def delete(
    db: str = typer.Option(..., help="数据库名称"),
    name: str = typer.Option(..., help="分支名称"),
) -> None:
    """删除分支"""
    client = LakeonClient()
    try:
        db_obj = client.find_database_by_name(db)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not db_obj:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    try:
        br = client.find_branch_by_name(db_obj["id"], name)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not br:
        console.print(f"[red]Branch '{name}' not found in database '{db}'[/red]")
        raise typer.Exit(1)
    try:
        client.delete_branch(db_obj["id"], br["id"])
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    console.print(f"[green]Branch '{name}' deleted.[/green]")
