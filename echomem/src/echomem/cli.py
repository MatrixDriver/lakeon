from __future__ import annotations

import typer
from rich.console import Console

from echomem.config import EchomemConfig, default_config_path, load_config, write_config

app = typer.Typer(help="echomem — local agent memory hub", no_args_is_help=True, invoke_without_command=True)
console = Console()


@app.command("version")
def version() -> None:
    """Show echomem version."""
    from importlib.metadata import version as _version, PackageNotFoundError
    try:
        console.print(_version("echomem"))
    except PackageNotFoundError:
        console.print("unknown")


@app.command()
def init() -> None:
    """Create ~/.echomem/ skeleton + default config."""
    cfg_path = default_config_path()
    if cfg_path.exists():
        console.print(f"[yellow]echomem already initialized at[/] {cfg_path}")
        return

    cfg = EchomemConfig()
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    (cfg.data_dir / "blobs").mkdir(exist_ok=True)
    (cfg.data_dir / "logs").mkdir(exist_ok=True)
    (cfg.data_dir / "sessions").mkdir(exist_ok=True)
    (cfg.data_dir / "cache").mkdir(exist_ok=True)
    write_config(cfg)
    console.print(f"[green]initialized echomem[/] at {cfg.data_dir}")
    console.print(f"  config:    {cfg_path}")
    console.print(f"  daemon:    http://{cfg.host}:{cfg.port}")
    console.print(f"  ollama:    {cfg.ollama_url}")
    console.print(f"  embedding: {cfg.embedding_model} (dim={cfg.embedding_dim})")
