"""
lakeon config 命令 -- CLI 配置管理
"""

import typer
from rich.console import Console

from lakeon_cli import config as cfg

app = typer.Typer()
console = Console()


@app.command("set")
def config_set(
    api_url: str = typer.Option(..., help="API endpoint URL"),
    api_key: str = typer.Option(..., help="API Key"),
) -> None:
    """配置 CLI 连接参数"""
    cfg.save_config(api_url, api_key)
    console.print("[green]Configuration saved.[/green]")


@app.command("show")
def config_show() -> None:
    """显示当前配置"""
    data = cfg.load_config()
    if not data:
        console.print("[yellow]No configuration found. Run 'lakeon config set' first.[/yellow]")
        return
    console.print(f"API URL : {data.get('api_url', '(not set)')}")
    console.print(f"API Key : {data.get('api_key', '(not set)')[:8]}...")
