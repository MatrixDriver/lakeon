"""
LakeOn CLI 配置管理

配置文件格式 (TOML):
  [default]
  api_url = "http://localhost:8080"
  api_key = "your-api-key"

默认路径: ~/.lakeon/config.toml
"""

import sys
from pathlib import Path
from typing import Optional

if sys.version_info >= (3, 11):
    import tomllib
else:
    try:
        import tomllib  # type: ignore[import]
    except ImportError:
        tomllib = None  # type: ignore[assignment]


_DEFAULT_CONFIG_PATH = str(Path.home() / ".lakeon" / "config.toml")

# 允许测试覆盖
_config_path_override: Optional[str] = None


def get_config_path() -> str:
    """获取配置文件路径"""
    if _config_path_override is not None:
        return _config_path_override
    return _DEFAULT_CONFIG_PATH


def _parse_toml(text: str) -> dict[str, str]:
    """解析 TOML [default] 段，优先使用标准库 tomllib"""
    if tomllib is not None:
        data = tomllib.loads(text)
        default = data.get("default", {})
        return {k: str(v) for k, v in default.items()}
    # Fallback: 简易解析（Python < 3.11 且无 tomllib）
    return _parse_toml_simple(text)


def _parse_toml_simple(text: str) -> dict[str, str]:
    """简易 TOML [default] 段解析（仅支持简单键值对）"""
    result: dict[str, str] = {}
    in_default = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == "[default]":
            in_default = True
            continue
        if stripped.startswith("[") and stripped.endswith("]"):
            in_default = False
            continue
        if in_default and "=" in stripped and not stripped.startswith("#"):
            key, _, value = stripped.partition("=")
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            result[key] = value
    return result


def _serialize_toml_simple(data: dict[str, str]) -> str:
    """简易 TOML 序列化"""
    lines = ["[default]"]
    for k, v in data.items():
        lines.append(f'{k} = "{v}"')
    return "\n".join(lines) + "\n"


def load_config() -> dict[str, str]:
    """加载配置文件，返回键值对"""
    path = Path(get_config_path())
    if path.exists():
        return _parse_toml(path.read_text())
    return {}


def save_config(api_url: str, api_key: str) -> None:
    """保存配置到文件"""
    path = Path(get_config_path())
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {"api_url": api_url, "api_key": api_key}
    path.write_text(_serialize_toml_simple(data))


def get_api_url() -> str:
    """获取 API URL"""
    cfg = load_config()
    return cfg.get("api_url", "http://localhost:8080")


def get_api_key() -> str:
    """获取 API Key"""
    cfg = load_config()
    return cfg.get("api_key", "")
