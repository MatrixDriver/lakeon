"""
LakeOn CLI — config 命令单元测试

测试用例 ID: UT-CLI-011
"""

import pytest
from typer.testing import CliRunner
from unittest.mock import patch
from pathlib import Path

runner = CliRunner()


class TestConfigSet:
    """UT-CLI-011: lakeon config set"""

    def test_config_set_success(self, tmp_path):
        """UT-CLI-011: 正常设置 — 保存配置到本地文件"""
        from lakeon_cli.main import app

        config_file = tmp_path / "config.toml"

        with patch("lakeon_cli.config.get_config_path", return_value=str(config_file)):
            result = runner.invoke(app, [
                "config", "set",
                "--api-url", "https://api.lakeon.example.com",
                "--api-key", "my-secret-api-key-1234567890abcdef",
            ])

        assert result.exit_code == 0
        # 验证配置文件已创建
        assert config_file.exists()
        content = config_file.read_text()
        assert "api.lakeon.example.com" in content
        # API key 应保存（可能加密或明文，取决于实现）
        assert "my-secret-api-key" in content or "api_key" in content

    def test_config_set_creates_directory(self, tmp_path):
        """UT-CLI-011a: 配置目录不存在时自动创建"""
        from lakeon_cli.main import app

        config_file = tmp_path / "subdir" / "config.toml"

        with patch("lakeon_cli.config.get_config_path", return_value=str(config_file)):
            result = runner.invoke(app, [
                "config", "set",
                "--api-url", "http://localhost:8080",
                "--api-key", "test-key-32chars-long-enough!!!!!!",
            ])

        assert result.exit_code == 0
        assert config_file.exists()

    def test_config_set_overwrites_existing(self, tmp_path):
        """UT-CLI-011b: 覆盖已有配置"""
        from lakeon_cli.main import app

        config_file = tmp_path / "config.toml"
        config_file.write_text(
            '[default]\napi_url = "http://old-url"\napi_key = "old-key"\n'
        )

        with patch("lakeon_cli.config.get_config_path", return_value=str(config_file)):
            result = runner.invoke(app, [
                "config", "set",
                "--api-url", "http://new-url",
                "--api-key", "new-key-32chars-long-enough!!!!!!",
            ])

        assert result.exit_code == 0
        content = config_file.read_text()
        assert "new-url" in content
        assert "old-url" not in content
