from __future__ import annotations

import sys
import typer
import httpx
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


@app.command()
def start(
    foreground: bool = typer.Option(
        True, "--foreground/--background", help="run in foreground (default)"
    ),
) -> None:
    """Start the echomem daemon."""
    import uvicorn

    cfg = load_config()
    if foreground:
        uvicorn.run(
            "echomem.daemon.app:make_default_app",
            factory=True,
            host=cfg.host,
            port=cfg.port,
            log_level=cfg.log_level.lower(),
        )
    else:
        # 简单后台：fork + detach (POSIX)
        import os

        pid = os.fork()
        if pid > 0:
            console.print(f"[green]echomem started[/] pid={pid}")
            sys.exit(0)
        os.setsid()
        uvicorn.run(
            "echomem.daemon.app:make_default_app",
            factory=True,
            host=cfg.host,
            port=cfg.port,
            log_level=cfg.log_level.lower(),
        )


@app.command()
def status() -> None:
    """Probe the daemon's /health and print a one-line summary."""
    cfg = load_config()
    url = f"http://{cfg.host}:{cfg.port}/health"
    try:
        with httpx.Client(timeout=2.0) as c:
            r = c.get(url)
            if r.status_code == 200:
                body = r.json()
                console.print(
                    f"[green]up[/]  version={body['version']}  "
                    f"model={body['embedding_model']}  dim={body['embedding_dim']}"
                )
                return
    except Exception:
        pass
    console.print(f"[red]down[/] (daemon at {url} not reachable)")
