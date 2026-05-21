from typer.testing import CliRunner
from unittest.mock import MagicMock, patch
from dbay_cli.main import app

runner = CliRunner()


@patch('dbay_cli.commands.db.DbayClient')
def test_pitr_calls_api_with_iso_timestamp(MockClient):
    client = MockClient.return_value
    client.post.return_value = MagicMock(
        status_code=200,
        json=lambda: {"new_db_id": "db_new", "lsn": "0/AB12", "status": "ready"}
    )

    result = runner.invoke(app, [
        "db", "pitr", "db_old",
        "--time", "2026-05-21T14:30:00Z",
        "--new-name", "restored"
    ])

    assert result.exit_code == 0
    assert "db_new" in result.stdout
    client.post.assert_called_once_with(
        "/databases/db_old/pitr",
        json={"target_time": "2026-05-21T14:30:00Z", "new_db_name": "restored"}
    )


@patch('dbay_cli.commands.db.DbayClient')
def test_pitr_supports_relative_time(MockClient):
    client = MockClient.return_value
    client.post.return_value = MagicMock(
        status_code=200,
        json=lambda: {"new_db_id": "db_new", "lsn": "0/AB12", "status": "ready"}
    )

    result = runner.invoke(app, ["db", "pitr", "db_old", "--time", "5min ago"])
    assert result.exit_code == 0
    call = client.post.call_args
    assert "T" in call.kwargs["json"]["target_time"]
    assert call.kwargs["json"]["target_time"].endswith("Z")
