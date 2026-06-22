import pytest

from dbay_cli.client import DbayClient, DbayApiError
from conftest import ADMIN_TOKEN, ENDPOINT


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


def test_disabled_tenant(e2e_tenant):
    """Disabled tenant should receive 403, then recover after re-enable."""
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
    tenant_id = e2e_tenant["id"]
    client = e2e_tenant["client"]

    try:
        admin._request("POST", f"/admin/tenants/{tenant_id}/disable")
        with pytest.raises(DbayApiError) as exc_info:
            client.list_databases()
        assert exc_info.value.status_code == 403
    finally:
        admin._request("POST", f"/admin/tenants/{tenant_id}/enable")

    assert isinstance(client.list_databases(), list)
