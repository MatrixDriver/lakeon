from pathlib import Path
import tomli_w
from echomem.config import EchomemConfig, load_config, default_config_path


def test_default_when_no_file(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    cfg = load_config()
    assert cfg.host == "127.0.0.1"
    assert 1024 <= cfg.port <= 65535
    assert cfg.data_dir == tmp_path / ".echomem"
    assert cfg.ollama_url == "http://localhost:11434"
    assert cfg.embedding_model == "qwen3-embedding:0.6b"
    assert cfg.embedding_dim == 1024


def test_load_from_file(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    cfg_dir = tmp_path / ".echomem"
    cfg_dir.mkdir()
    (cfg_dir / "config.toml").write_bytes(
        tomli_w.dumps(
            {
                "daemon": {"host": "0.0.0.0", "port": 7777},
                "ollama": {"url": "http://1.2.3.4:11434", "embedding_model": "x:y"},
            }
        ).encode()
    )
    cfg = load_config()
    assert cfg.host == "0.0.0.0"
    assert cfg.port == 7777
    assert cfg.ollama_url == "http://1.2.3.4:11434"
    assert cfg.embedding_model == "x:y"


def test_default_config_path(monkeypatch, tmp_path):
    monkeypatch.setenv("HOME", str(tmp_path))
    assert default_config_path() == tmp_path / ".echomem" / "config.toml"
