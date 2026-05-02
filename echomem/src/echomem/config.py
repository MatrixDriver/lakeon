from __future__ import annotations

import os
import tomllib
from pathlib import Path
from typing import Any
from pydantic import BaseModel, Field


def default_data_dir() -> Path:
    return Path(os.environ.get("HOME", "/tmp")) / ".echomem"


def default_config_path() -> Path:
    return default_data_dir() / "config.toml"


def _pick_port() -> int:
    env = os.environ.get("ECHOMEM_PORT")
    if not env:
        return 8473  # echomem 默认端口
    try:
        port = int(env)
    except ValueError:
        raise ValueError(f"ECHOMEM_PORT must be an integer, got {env!r}") from None
    if not 1 <= port <= 65535:
        raise ValueError(f"ECHOMEM_PORT out of range (1..65535), got {port}")
    return port


class EchomemConfig(BaseModel):
    host: str = "127.0.0.1"
    port: int = Field(default_factory=_pick_port, ge=1, le=65535)
    data_dir: Path = Field(default_factory=default_data_dir)
    ollama_url: str = "http://localhost:11434"
    embedding_model: str = "qwen3-embedding:0.6b"
    embedding_dim: int = 1024
    generate_model: str = "gemma4:e4b"
    log_level: str = "INFO"


def load_config(path: Path | None = None) -> EchomemConfig:
    file = path or default_config_path()
    if not file.exists():
        return EchomemConfig()
    raw = tomllib.loads(file.read_text("utf-8"))
    daemon = raw.get("daemon", {})
    ollama = raw.get("ollama", {})
    storage = raw.get("storage", {})
    log = raw.get("log", {})
    return EchomemConfig(
        host=daemon.get("host", "127.0.0.1"),
        port=daemon.get("port", _pick_port()),
        data_dir=Path(storage.get("data_dir", default_data_dir())),
        ollama_url=ollama.get("url", "http://localhost:11434"),
        embedding_model=ollama.get("embedding_model", "qwen3-embedding:0.6b"),
        embedding_dim=ollama.get("embedding_dim", 1024),
        generate_model=ollama.get("generate_model", "gemma4:e4b"),
        log_level=log.get("level", "INFO"),
    )


def write_config(cfg: EchomemConfig, path: Path | None = None) -> Path:
    import tomli_w

    file = path or default_config_path()
    file.parent.mkdir(parents=True, exist_ok=True)
    payload: dict[str, Any] = {
        "daemon": {"host": cfg.host, "port": cfg.port},
        "ollama": {
            "url": cfg.ollama_url,
            "embedding_model": cfg.embedding_model,
            "embedding_dim": cfg.embedding_dim,
            "generate_model": cfg.generate_model,
        },
        "storage": {"data_dir": str(cfg.data_dir)},
        "log": {"level": cfg.log_level},
    }
    file.write_text(tomli_w.dumps(payload), encoding="utf-8")
    return file
