"""
LakeOn CLI — db 命令单元测试

测试用例 ID: UT-CLI-001 ~ UT-CLI-007, UT-CLI-012 ~ UT-CLI-015
"""

import json
import pytest
from typer.testing import CliRunner
from unittest.mock import patch, MagicMock

runner = CliRunner()


# ========== Helper ==========

DB_LIST_WITH_TEST_DB = [
    {
        "id": "db_abc123",
        "name": "test-db",
        "status": "running",
        "compute_size": "1cu",
        "created_at": "2026-03-03T10:00:00Z",
    }
]


def mock_find_db(httpx_mock):
    """Add a mock for GET /databases that returns test-db so find_database_by_name works."""
    httpx_mock.add_response(
        method="GET",
        url="http://localhost:8080/api/v1/databases",
        json=DB_LIST_WITH_TEST_DB,
        status_code=200,
    )


# ========== Fixtures ==========

@pytest.fixture
def mock_config(tmp_path):
    """模拟 CLI 配置文件"""
    config_file = tmp_path / "config.toml"
    config_file.write_text(
        '[default]\napi_url = "http://localhost:8080"\napi_key = "test-api-key-valid"\n'
    )
    with patch("lakeon_cli.config.get_config_path", return_value=str(config_file)):
        yield


@pytest.fixture
def db_create_response():
    return {
        "id": "db_abc123",
        "name": "test-db",
        "status": "creating",
        "connection_uri": "postgres://user_xxx:pass_xxx@proxy.lakeon.example.com/test-db",
        "compute_size": "1cu",
        "suspend_timeout": "5m",
        "storage_limit_gb": 10,
        "storage_used_gb": 0,
        "branches": [
            {"id": "br_main", "name": "main", "is_default": True}
        ],
        "created_at": "2026-03-03T10:00:00Z",
    }


@pytest.fixture
def db_list_response():
    return [
        {
            "id": "db_001",
            "name": "app-db-1",
            "status": "running",
            "compute_size": "1cu",
            "created_at": "2026-03-01T10:00:00Z",
        },
        {
            "id": "db_002",
            "name": "app-db-2",
            "status": "suspended",
            "compute_size": "2cu",
            "created_at": "2026-03-02T10:00:00Z",
        },
    ]


@pytest.fixture
def db_status_response():
    return {
        "id": "db_abc123",
        "name": "test-db",
        "status": "running",
        "connection_uri": "postgres://user:pass@proxy/test-db",
        "compute_size": "1cu",
        "suspend_timeout": "5m",
        "storage_limit_gb": 10,
        "storage_used_gb": 1.5,
        "branches": [{"id": "br_main", "name": "main", "is_default": True}],
        "created_at": "2026-03-03T10:00:00Z",
    }


# ========== Tests ==========

class TestDbCreate:
    """UT-CLI-001: lakeon db create"""

    def test_db_create_success(self, httpx_mock, mock_config, db_create_response):
        """UT-CLI-001: 正常创建 — 发送 POST，输出连接串"""
        from lakeon_cli.main import app

        httpx_mock.add_response(
            method="POST",
            url="http://localhost:8080/api/v1/databases",
            json=db_create_response,
            status_code=201,
        )

        result = runner.invoke(app, ["db", "create", "--name", "test-db"])

        assert result.exit_code == 0
        assert "postgres://user_xxx:pass_xxx@proxy.lakeon.example.com/test-db" in result.stdout
        assert "test-db" in result.stdout

    def test_db_create_with_options(self, httpx_mock, mock_config, db_create_response):
        """UT-CLI-001a: 带参数创建"""
        from lakeon_cli.main import app

        db_create_response["compute_size"] = "2cu"
        db_create_response["suspend_timeout"] = "10m"
        httpx_mock.add_response(
            method="POST",
            url="http://localhost:8080/api/v1/databases",
            json=db_create_response,
            status_code=201,
        )

        result = runner.invoke(app, [
            "db", "create",
            "--name", "test-db",
            "--compute-size", "2cu",
            "--suspend-timeout", "10m",
        ])

        assert result.exit_code == 0

    def test_db_create_conflict(self, httpx_mock, mock_config):
        """UT-CLI-001b: 名称冲突 — 输出错误"""
        from lakeon_cli.main import app

        httpx_mock.add_response(
            method="POST",
            url="http://localhost:8080/api/v1/databases",
            json={
                "error": {
                    "code": "CONFLICT",
                    "message": "Database 'test-db' already exists",
                }
            },
            status_code=409,
        )

        result = runner.invoke(app, ["db", "create", "--name", "test-db"])

        assert result.exit_code != 0
        assert "already exists" in result.stdout or "already exists" in result.stderr


class TestDbList:
    """UT-CLI-002: lakeon db list"""

    def test_db_list_success(self, httpx_mock, mock_config, db_list_response):
        """UT-CLI-002: 正常列出 — 输出表格"""
        from lakeon_cli.main import app

        httpx_mock.add_response(
            method="GET",
            url="http://localhost:8080/api/v1/databases",
            json=db_list_response,
            status_code=200,
        )

        result = runner.invoke(app, ["db", "list"])

        assert result.exit_code == 0
        assert "app-db-1" in result.stdout
        assert "app-db-2" in result.stdout
        assert "running" in result.stdout
        assert "suspended" in result.stdout

    def test_db_list_empty(self, httpx_mock, mock_config):
        """UT-CLI-002a: 空列表"""
        from lakeon_cli.main import app

        httpx_mock.add_response(
            method="GET",
            url="http://localhost:8080/api/v1/databases",
            json=[],
            status_code=200,
        )

        result = runner.invoke(app, ["db", "list"])

        assert result.exit_code == 0


class TestDbStatus:
    """UT-CLI-003: lakeon db status"""

    def test_db_status_success(self, httpx_mock, mock_config, db_status_response):
        """UT-CLI-003: 正常查看 — 输出状态详情"""
        from lakeon_cli.main import app

        # find_database_by_name needs list first
        mock_find_db(httpx_mock)

        httpx_mock.add_response(
            method="GET",
            url="http://localhost:8080/api/v1/databases/db_abc123",
            json=db_status_response,
            status_code=200,
        )

        result = runner.invoke(app, ["db", "status", "--name", "test-db"])

        assert result.exit_code == 0
        assert "running" in result.stdout
        assert "test-db" in result.stdout


class TestDbSuspend:
    """UT-CLI-004: lakeon db suspend"""

    def test_db_suspend_success(self, httpx_mock, mock_config):
        """UT-CLI-004: 正常休眠"""
        from lakeon_cli.main import app

        mock_find_db(httpx_mock)

        httpx_mock.add_response(
            method="POST",
            url="http://localhost:8080/api/v1/databases/db_abc123/suspend",
            json={"status": "suspended"},
            status_code=200,
        )

        result = runner.invoke(app, ["db", "suspend", "--name", "test-db"])

        assert result.exit_code == 0


class TestDbResume:
    """UT-CLI-005: lakeon db resume"""

    def test_db_resume_success(self, httpx_mock, mock_config):
        """UT-CLI-005: 正常唤醒"""
        from lakeon_cli.main import app

        mock_find_db(httpx_mock)

        httpx_mock.add_response(
            method="POST",
            url="http://localhost:8080/api/v1/databases/db_abc123/resume",
            json={"status": "running"},
            status_code=200,
        )

        result = runner.invoke(app, ["db", "resume", "--name", "test-db"])

        assert result.exit_code == 0


class TestDbUpdate:
    """UT-CLI-006: lakeon db update"""

    def test_db_update_success(self, httpx_mock, mock_config, db_status_response):
        """UT-CLI-006: 正常更新配置"""
        from lakeon_cli.main import app

        mock_find_db(httpx_mock)

        db_status_response["compute_size"] = "2cu"
        httpx_mock.add_response(
            method="PATCH",
            url="http://localhost:8080/api/v1/databases/db_abc123",
            json=db_status_response,
            status_code=200,
        )

        result = runner.invoke(app, [
            "db", "update",
            "--name", "test-db",
            "--compute-size", "2cu",
        ])

        assert result.exit_code == 0


class TestDbDelete:
    """UT-CLI-007: lakeon db delete"""

    def test_db_delete_success(self, httpx_mock, mock_config):
        """UT-CLI-007: 正常删除"""
        from lakeon_cli.main import app

        mock_find_db(httpx_mock)

        # delete_database sends ?force=True query param
        httpx_mock.add_response(
            method="DELETE",
            url="http://localhost:8080/api/v1/databases/db_abc123?force=true",
            status_code=204,
        )

        result = runner.invoke(app, ["db", "delete", "--name", "test-db", "--force"])

        assert result.exit_code == 0


class TestDbErrorHandling:
    """UT-CLI-012 ~ UT-CLI-014: 错误处理"""

    def test_api_returns_401(self, httpx_mock, mock_config):
        """UT-CLI-012: API 返回 401 — 输出认证失败"""
        from lakeon_cli.main import app

        httpx_mock.add_response(
            method="GET",
            url="http://localhost:8080/api/v1/databases",
            json={"error": {"code": "UNAUTHORIZED", "message": "Invalid API key"}},
            status_code=401,
        )

        result = runner.invoke(app, ["db", "list"])

        assert result.exit_code != 0
        # 验证输出包含认证相关的错误信息
        output = result.stdout + (result.stderr or "")
        assert "401" in output or "unauthorized" in output.lower() or "认证" in output

    def test_api_returns_404(self, httpx_mock, mock_config):
        """UT-CLI-013: API 返回 404 — 输出资源不存在"""
        from lakeon_cli.main import app

        # find_database_by_name returns None when db name not in list
        httpx_mock.add_response(
            method="GET",
            url="http://localhost:8080/api/v1/databases",
            json=[],
            status_code=200,
        )

        result = runner.invoke(app, ["db", "status", "--name", "nonexist"])

        assert result.exit_code != 0
        output = result.stdout + (result.stderr or "")
        assert "not found" in output.lower() or "404" in output

    def test_network_error(self, mock_config):
        """UT-CLI-014: 网络错误 — 输出连接错误信息"""
        from lakeon_cli.main import app

        # 不 mock httpx，让它真正连接失败
        with patch("lakeon_cli.config.get_api_url", return_value="http://localhost:19999"):
            result = runner.invoke(app, ["db", "list"])

        assert result.exit_code != 0


class TestDbMissingParams:
    """UT-CLI-015: 缺少必填参数"""

    def test_create_missing_name(self, mock_config):
        """UT-CLI-015: 缺少 --name 参数"""
        from lakeon_cli.main import app

        result = runner.invoke(app, ["db", "create"])

        assert result.exit_code != 0
        output = result.stdout + (result.stderr or "")
        assert "name" in output.lower() or "missing" in output.lower() or "required" in output.lower()
