"""
LakeOn CLI — branch 命令单元测试

测试用例 ID: UT-CLI-008 ~ UT-CLI-010
"""

import pytest
from typer.testing import CliRunner
from unittest.mock import patch

runner = CliRunner()

DB_LIST_WITH_MY_DB = [
    {
        "id": "db_abc123",
        "name": "my-db",
        "status": "running",
        "compute_size": "1cu",
        "created_at": "2026-03-03T10:00:00Z",
    }
]

BRANCH_LIST = [
    {
        "id": "br_main",
        "name": "main",
        "parent_branch": None,
        "status": "active",
        "is_default": True,
    },
    {
        "id": "br_feat001",
        "name": "feature-test",
        "parent_branch": "main",
        "status": "active",
        "is_default": False,
    },
]


def mock_find_db(httpx_mock):
    """Mock GET /databases to let find_database_by_name succeed."""
    httpx_mock.add_response(
        method="GET",
        url="http://localhost:8080/api/v1/databases",
        json=DB_LIST_WITH_MY_DB,
        status_code=200,
    )


def mock_find_branch(httpx_mock):
    """Mock GET /databases/db_abc123/branches to let find_branch_by_name succeed."""
    httpx_mock.add_response(
        method="GET",
        url="http://localhost:8080/api/v1/databases/db_abc123/branches",
        json=BRANCH_LIST,
        status_code=200,
    )


@pytest.fixture
def mock_config(tmp_path):
    """模拟 CLI 配置文件"""
    config_file = tmp_path / "config.toml"
    config_file.write_text(
        '[default]\napi_url = "http://localhost:8080"\napi_key = "test-api-key-valid"\n'
    )
    with patch("lakeon_cli.config.get_config_path", return_value=str(config_file)):
        yield


class TestBranchCreate:
    """UT-CLI-008: lakeon branch create"""

    def test_branch_create_success(self, httpx_mock, mock_config):
        """UT-CLI-008: 正常创建分支"""
        from lakeon_cli.main import app

        mock_find_db(httpx_mock)

        httpx_mock.add_response(
            method="POST",
            url="http://localhost:8080/api/v1/databases/db_abc123/branches",
            json={
                "id": "br_feat001",
                "name": "feature-test",
                "parent_branch": "main",
                "status": "creating",
                "connection_uri": "postgres://user:pass@proxy/my-db?branch=feature-test",
                "created_at": "2026-03-03T10:05:00Z",
            },
            status_code=201,
        )

        result = runner.invoke(app, [
            "branch", "create",
            "--db", "my-db",
            "--name", "feature-test",
        ])

        assert result.exit_code == 0
        assert "feature-test" in result.stdout


class TestBranchList:
    """UT-CLI-009: lakeon branch list"""

    def test_branch_list_success(self, httpx_mock, mock_config):
        """UT-CLI-009: 正常列出分支"""
        from lakeon_cli.main import app

        mock_find_db(httpx_mock)

        httpx_mock.add_response(
            method="GET",
            url="http://localhost:8080/api/v1/databases/db_abc123/branches",
            json=BRANCH_LIST,
            status_code=200,
        )

        result = runner.invoke(app, ["branch", "list", "--db", "my-db"])

        assert result.exit_code == 0
        assert "main" in result.stdout
        assert "feature-test" in result.stdout


class TestBranchDelete:
    """UT-CLI-010: lakeon branch delete"""

    def test_branch_delete_success(self, httpx_mock, mock_config):
        """UT-CLI-010: 正常删除分支"""
        from lakeon_cli.main import app

        mock_find_db(httpx_mock)
        mock_find_branch(httpx_mock)

        httpx_mock.add_response(
            method="DELETE",
            url="http://localhost:8080/api/v1/databases/db_abc123/branches/br_feat001",
            status_code=204,
        )

        result = runner.invoke(app, [
            "branch", "delete",
            "--db", "my-db",
            "--name", "feature-test",
        ])

        assert result.exit_code == 0

    def test_branch_delete_default_rejected(self, httpx_mock, mock_config):
        """UT-CLI-010a: 删除默认分支 — 返回错误"""
        from lakeon_cli.main import app

        mock_find_db(httpx_mock)
        mock_find_branch(httpx_mock)

        httpx_mock.add_response(
            method="DELETE",
            url="http://localhost:8080/api/v1/databases/db_abc123/branches/br_main",
            json={
                "error": {
                    "code": "BAD_REQUEST",
                    "message": "Cannot delete default branch",
                }
            },
            status_code=400,
        )

        result = runner.invoke(app, [
            "branch", "delete",
            "--db", "my-db",
            "--name", "main",
        ])

        assert result.exit_code != 0
        output = result.stdout + (result.stderr or "")
        assert "default" in output.lower() or "400" in output
