"""
Tenant & account management E2E tests — covers console login, account settings,
API key management, and trial creation.

These map to the console's Login, Account Settings, and API Key pages.
"""
import time

import pytest

from dbay_cli.client import DbayClient, DbayApiError
from conftest import ENDPOINT, ADMIN_TOKEN, _create_tenant_with_invite


# ═══════════════════════════════════════════════════════════════════════════════
#  Tenant registration & login
# ═══════════════════════════════════════════════════════════════════════════════

class TestTenantAuth:
    """Tests for tenant registration and login flow."""

    @pytest.fixture(scope="class")
    def test_user(self):
        ts = int(time.time())
        return {
            "username": f"e2e-auth-{ts}",
            "password": f"AuthTest@{ts}",
            "name": f"Auth Test {ts}",
        }

    def test_check_username_available(self, test_user):
        """Check that a new username is available."""
        client = DbayClient(endpoint=ENDPOINT)
        result = client.check_username(test_user["username"])
        assert result.get("available") is True

    def test_register_tenant(self, test_user):
        """Register a new tenant with invite code."""
        admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
        invite = admin.admin_create_invite_code(max_uses=1)

        client = DbayClient(endpoint=ENDPOINT)
        tenant = client.create_tenant(
            username=test_user["username"],
            password=test_user["password"],
            name=test_user["name"],
            invite_code=invite["code"],
        )
        assert tenant.get("api_key") is not None
        assert tenant.get("username") == test_user["username"]
        test_user["api_key"] = tenant["api_key"]
        test_user["id"] = tenant.get("id")

    def test_login(self, test_user):
        """Login with username/password should return api_key."""
        client = DbayClient(endpoint=ENDPOINT)
        result = client.login(test_user["username"], test_user["password"])
        assert result.get("api_key") is not None

    def test_login_wrong_password(self, test_user):
        """Login with wrong password should return 401."""
        client = DbayClient(endpoint=ENDPOINT)
        with pytest.raises(DbayApiError) as exc:
            client.login(test_user["username"], "WrongPassword123!")
        assert exc.value.status_code == 401

    def test_check_username_taken(self, test_user):
        """After registration, username should no longer be available."""
        client = DbayClient(endpoint=ENDPOINT)
        result = client.check_username(test_user["username"])
        assert result.get("available") is False

    def test_get_me(self, test_user):
        """GET /tenants/me should return current tenant info."""
        client = DbayClient(endpoint=ENDPOINT, api_key=test_user["api_key"])
        me = client.get_me()
        assert me.get("username") == test_user["username"]
        assert me.get("name") == test_user["name"]

    def test_register_duplicate_username(self, test_user):
        """Registering with an existing username should fail."""
        admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
        invite = admin.admin_create_invite_code(max_uses=1)

        client = DbayClient(endpoint=ENDPOINT)
        with pytest.raises(DbayApiError) as exc:
            client.create_tenant(
                username=test_user["username"],
                password="Another@123",
                invite_code=invite["code"],
            )
        assert exc.value.status_code in (400, 409)

    def test_register_without_invite_code(self):
        """Registering without invite code should fail."""
        client = DbayClient(endpoint=ENDPOINT)
        with pytest.raises(DbayApiError) as exc:
            client.create_tenant(
                username=f"e2e-noinvite-{int(time.time())}",
                password="Test@1234",
            )
        assert exc.value.status_code in (400, 403)


# ═══════════════════════════════════════════════════════════════════════════════
#  API Key management (Console API Key page)
# ═══════════════════════════════════════════════════════════════════════════════

class TestApiKeyManagement:
    """Tests for API key CRUD — console's API key management page."""

    @pytest.fixture(scope="class")
    def key_client(self):
        ts = int(time.time())
        client, _ = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-apikey-{ts}", f"ApiKey@{ts}", f"API Key Test {ts}",
        )
        return client

    def test_list_api_keys(self, key_client):
        """List API keys should include at least the default key."""
        keys = key_client.list_api_keys()
        assert isinstance(keys, list)
        assert len(keys) >= 1

    def test_create_api_key(self, key_client):
        """Create a named API key."""
        key = key_client.create_api_key(name="test-key")
        assert key.get("key") is not None or key.get("api_key") is not None
        assert key.get("name") == "test-key"

    def test_created_key_in_list(self, key_client):
        """Newly created key should appear in list."""
        keys = key_client.list_api_keys()
        names = [k.get("name") for k in keys]
        assert "test-key" in names

    def test_delete_api_key(self, key_client):
        """Delete API key, verify removed from list."""
        # Create a key to delete
        key = key_client.create_api_key(name="to-delete")
        key_id = key.get("id")
        assert key_id is not None

        key_client.delete_api_key(key_id)

        keys = key_client.list_api_keys()
        assert not any(k.get("id") == key_id for k in keys)

    def test_new_key_works_for_auth(self, key_client):
        """A newly created API key should work for authentication."""
        new_key = key_client.create_api_key(name="auth-test")
        raw_key = new_key.get("key") or new_key.get("api_key")
        assert raw_key is not None

        # Use the new key to list databases
        new_client = DbayClient(endpoint=ENDPOINT, api_key=raw_key)
        dbs = new_client.list_databases()
        assert isinstance(dbs, list)


# ═══════════════════════════════════════════════════════════════════════════════
#  Trial (Landing page "立即体验" button)
# ═══════════════════════════════════════════════════════════════════════════════

class TestTrial:
    """Tests for trial account creation — landing page one-click experience."""

    def test_create_trial(self):
        """POST /trial should create a temporary account with a database."""
        client = DbayClient(endpoint=ENDPOINT)
        result = client._request("POST", "/trial")
        # Trial should return api_key and database info
        assert result.get("api_key") is not None or result.get("apiKey") is not None
        # May also return database_id and connection_uri
        assert result.get("database_id") is not None or result.get("databaseId") is not None or result.get("database") is not None
