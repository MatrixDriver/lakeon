import pytest


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    """Prevent tests from accidentally hitting real services."""
    for key in ("DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL",
                "FEISHU_APP_ID", "FEISHU_APP_SECRET", "FEISHU_ALLOWED_USERS",
                "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET", "OBS_ENDPOINT",
                "DBAY_LOGS_DSN", "LOG_DB_DSN", "HERMES_HOME"):
        monkeypatch.delenv(key, raising=False)
