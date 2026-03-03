"""
lakeon db 命令 -- 数据库实例管理
"""

import typer
from rich.console import Console
from rich.table import Table

from lakeon_cli.client import LakeonClient

app = typer.Typer()
console = Console()


@app.command("create")
def create(
    name: str = typer.Option(..., help="数据库名称"),
    compute_size: str = typer.Option(None, help="Compute 规格 (1cu/2cu/4cu/8cu)"),
    suspend_timeout: str = typer.Option(None, help="休眠超时 (如 5m, 10m)"),
) -> None:
    """创建数据库实例"""
    client = LakeonClient()
    try:
        result = client.create_database(
            name, compute_size=compute_size, suspend_timeout=suspend_timeout
        )
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    console.print(f"[green]Database created: {result['id']}[/green]")
    console.print(f"Name: {result['name']}")
    console.print(f"Connection URI: {result['connection_uri']}")


@app.command("list")
def list_dbs() -> None:
    """列出所有数据库实例"""
    client = LakeonClient()
    try:
        databases = client.list_databases()
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    table = Table(title="Databases")
    table.add_column("ID")
    table.add_column("Name")
    table.add_column("Status")
    table.add_column("Compute Size")
    table.add_column("Created At")
    for db in databases:
        table.add_row(
            db["id"],
            db["name"],
            db["status"],
            db.get("compute_size", ""),
            db.get("created_at", ""),
        )
    console.print(table)


@app.command("status")
def status(name: str = typer.Option(..., help="数据库名称")) -> None:
    """查看数据库实例状态"""
    client = LakeonClient()
    try:
        db = client.find_database_by_name(name)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    try:
        detail = client.get_database(db["id"])
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    from rich.pretty import pprint
    pprint(detail)


@app.command("suspend")
def suspend(name: str = typer.Option(..., help="数据库名称")) -> None:
    """休眠 compute"""
    client = LakeonClient()
    try:
        db = client.find_database_by_name(name)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    try:
        client.suspend_database(db["id"])
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    console.print(f"[green]Database '{name}' suspended.[/green]")


@app.command("resume")
def resume(name: str = typer.Option(..., help="数据库名称")) -> None:
    """唤醒 compute"""
    client = LakeonClient()
    try:
        db = client.find_database_by_name(name)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    try:
        client.resume_database(db["id"])
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    console.print(f"[green]Database '{name}' resumed.[/green]")


@app.command("update")
def update(
    name: str = typer.Option(..., help="数据库名称"),
    compute_size: str = typer.Option(None, help="Compute 规格"),
    suspend_timeout: str = typer.Option(None, help="休眠超时"),
) -> None:
    """更新实例配置"""
    client = LakeonClient()
    try:
        db = client.find_database_by_name(name)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    kwargs = {}
    if compute_size:
        kwargs["compute_size"] = compute_size
    if suspend_timeout:
        kwargs["suspend_timeout"] = suspend_timeout
    try:
        client.update_database(db["id"], **kwargs)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    console.print(f"[green]Database '{name}' updated.[/green]")


@app.command("delete")
def delete(
    name: str = typer.Option(..., help="数据库名称"),
    force: bool = typer.Option(False, help="跳过确认"),
) -> None:
    """删除数据库实例"""
    client = LakeonClient()
    try:
        db = client.find_database_by_name(name)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    if not force:
        typer.confirm(f"确定要删除数据库 '{name}'?", abort=True)
    try:
        client.delete_database(db["id"], force=force)
    except Exception as e:
        console.print(f"[red]{e}[/red]")
        raise typer.Exit(1)
    console.print(f"[green]Database '{name}' deleted.[/green]")
