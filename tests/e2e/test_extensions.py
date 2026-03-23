"""
Extension & parameter management E2E tests — covers the console extensions page.

Tests: list extensions, enable, disable.
"""
import time

import pytest

from dbay_cli.client import DbayApiError
from conftest import poll_until


class TestExtensions:
    """PostgreSQL extension management tests."""

    @pytest.fixture(scope="class")
    def ext_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-ext-{int(time.time())}")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"
        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_list_extensions(self, e2e_client, ext_db):
        """List available extensions."""
        extensions = e2e_client.list_extensions(ext_db["id"])
        assert isinstance(extensions, list)
        assert len(extensions) > 0
        # pgvector should be in the list
        names = [e.get("name") for e in extensions]
        assert "vector" in names or "pgvector" in names

    def test_enable_extension(self, e2e_client, ext_db):
        """Enable pgcrypto extension."""
        result = e2e_client.enable_extension(ext_db["id"], "pgcrypto")
        assert isinstance(result, dict)

    def test_disable_extension(self, e2e_client, ext_db):
        """Disable pgcrypto extension."""
        result = e2e_client.disable_extension(ext_db["id"], "pgcrypto")
        assert isinstance(result, dict)

    def test_enable_invalid_extension(self, e2e_client, ext_db):
        """Enabling nonexistent extension should fail."""
        with pytest.raises(DbayApiError) as exc:
            e2e_client.enable_extension(ext_db["id"], "nonexistent_ext_xyz")
        assert exc.value.status_code in (400, 404, 500)
