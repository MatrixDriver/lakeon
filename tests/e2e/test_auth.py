import pytest

from dbay_cli.client import DbayClient, DbayApiError
from conftest import ENDPOINT


def test_invalid_api_key():
    """A request with an invalid API key should return 401."""
    client = DbayClient(endpoint=ENDPOINT, api_key="lk_invalid_key_000000")
    with pytest.raises(DbayApiError) as exc_info:
        client.list_databases()
    assert exc_info.value.status_code == 401


def test_missing_auth():
    """A request with no Authorization header should return 401."""
    client = DbayClient(endpoint=ENDPOINT)  # no api_key
    with pytest.raises(DbayApiError) as exc_info:
        client.list_databases()
    assert exc_info.value.status_code == 401


def test_disabled_tenant(e2e_client):
    """Disabled tenant should receive 403. Requires admin API to disable."""
    pytest.skip("Requires admin API to disable tenant")
