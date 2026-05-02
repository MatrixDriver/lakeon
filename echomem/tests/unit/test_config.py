from pathlib import Path
import pytest
import tomli_w
from echomem.config import EchomemConfig, _pick_port, load_config, default_config_path


def test_default_when_no_file(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.delenv("ECHOMEM_PORT", raising=False)
    cfg = load_config()
    assert cfg.host == "127.0.0.1"
    assert cfg.port == 8473
    assert cfg.data_dir == tmp_path / ".echomem"
    assert cfg.ollama_url == "http://localhost:11434"
    assert cfg.embedding_model == "qwen3-embedding:0.6b"
    assert cfg.embedding_dim == 1024


def test_invalid_port_raises(monkeypatch):
    monkeypatch.setenv("ECHOMEM_PORT", "not-a-number")
    with pytest.raises(ValueError, match="ECHOMEM_PORT must be an integer"):
        _pick_port()


def test_out_of_range_port_raises(monkeypatch):
    monkeypatch.setenv("ECHOMEM_PORT", "999999")
    with pytest.raises(ValueError, match="out of range"):
        _pick_port()


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


def test_default_generate_model(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.delenv("ECHOMEM_PORT", raising=False)
    cfg = load_config()
    assert cfg.generate_model == "gemma4:e4b"


def test_load_generate_model_from_file(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    cfg_dir = tmp_path / ".echomem"
    cfg_dir.mkdir()
    (cfg_dir / "config.toml").write_bytes(
        tomli_w.dumps({"ollama": {"generate_model": "llama3:8b"}}).encode()
    )
    cfg = load_config()
    assert cfg.generate_model == "llama3:8b"
