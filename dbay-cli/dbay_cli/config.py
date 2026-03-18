import json
from pathlib import Path
from typing import Optional

CONFIG_DIR = Path.home() / ".dbay"
CONFIG_FILE = CONFIG_DIR / "config.json"

def _load() -> dict:
    if CONFIG_FILE.exists():
        return json.loads(CONFIG_FILE.read_text())
    return {}

def _save(data: dict):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(data, indent=2))

def get(key: str) -> Optional[str]:
    return _load().get(key)

def set(key: str, value: str):
    data = _load()
    data[key] = value
    _save(data)

def show() -> dict:
    return _load()

def get_endpoint() -> str:
    return get("endpoint") or "https://api.dbay.cloud:8443"

def get_api_key() -> Optional[str]:
    return get("api_key")
