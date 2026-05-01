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


mem_app = typer.Typer(help="memory subcommands")
app.add_typer(mem_app, name="mem")


def _base_url() -> str:
    cfg = load_config()
    return f"http://{cfg.host}:{cfg.port}"


@mem_app.command("ingest")
def mem_ingest(
    text: str = typer.Argument(..., help="memory text"),
    agent: str = typer.Option("cli", "--agent", "-a"),
    source_kind: str = typer.Option("explicit", "--kind"),
) -> None:
    with httpx.Client(timeout=30.0) as c:
        r = c.post(
            f"{_base_url()}/memory/ingest",
            json={"text": text, "agent_id": agent, "source_kind": source_kind},
        )
        r.raise_for_status()
        body = r.json()
        console.print(f"[green]✓[/] {body['id']}  agent={body['agent_id']}")


@mem_app.command("recall")
def mem_recall(
    query: str = typer.Argument(...),
    k: int = typer.Option(5, "--k"),
    agent: str | None = typer.Option(None, "--agent"),
) -> None:
    payload: dict = {"query": query, "k": k}
    if agent:
        payload["agent_id"] = agent
    with httpx.Client(timeout=30.0) as c:
        r = c.post(f"{_base_url()}/memory/recall", json=payload)
        r.raise_for_status()
        for h in r.json()["hits"]:
            console.print(f"[cyan]{h['score']:.3f}[/]  {h['id'][:8]}…  {h['text']}")


@mem_app.command("list")
def mem_list(
    agent: str | None = typer.Option(None, "--agent"),
    limit: int = typer.Option(50, "--limit"),
) -> None:
    params: dict = {"limit": limit}
    if agent:
        params["agent_id"] = agent
    with httpx.Client(timeout=10.0) as c:
        r = c.get(f"{_base_url()}/memory/list", params=params)
        r.raise_for_status()
        for m in r.json()["items"]:
            console.print(f"  {m['id'][:8]}…  [dim]{m['agent_id']}[/]  {m['text'][:80]}")


@mem_app.command("get")
def mem_get(memory_id: str) -> None:
    with httpx.Client(timeout=10.0) as c:
        r = c.get(f"{_base_url()}/memory/{memory_id}")
        if r.status_code == 404:
            console.print("[red]not found[/]")
            raise typer.Exit(1)
        r.raise_for_status()
        m = r.json()
        console.print(m)


@mem_app.command("delete")
def mem_delete(memory_id: str) -> None:
    with httpx.Client(timeout=10.0) as c:
        r = c.delete(f"{_base_url()}/memory/{memory_id}")
        if r.status_code == 404:
            console.print("[red]not found[/]")
            raise typer.Exit(1)
        r.raise_for_status()
        console.print(f"[green]✓[/] deleted {memory_id}")
