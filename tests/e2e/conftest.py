import sys
import os
import time
import subprocess

import pytest

# Allow importing dbay_cli without pip install -e
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "dbay-cli"))

from dbay_cli.client import DbayClient  # noqa: E402

ENDPOINT = os.environ.get("DBAY_ENDPOINT", "https://api.dbay.cloud:8443")
ADMIN_TOKEN = os.environ.get("DBAY_ADMIN_TOKEN", "lakeon-sre-2026")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def poll_until(fetch_fn, condition, timeout=120, interval=3):
    """Poll fetch_fn() until condition(result) is True or timeout expires."""
    deadline = time.time() + timeout
    result = None
    while time.time() < deadline:
        result = fetch_fn()
        if condition(result):
            return result
        time.sleep(interval)
    raise TimeoutError(f"Condition not met within {timeout}s, last result: {result}")


def run_psql(connstr: str, sql: str, password: str = None) -> str:
    """Run a SQL statement via psql subprocess and return stdout.

    Automatically appends ``sslmode=require`` if not present and sets
    ``no_proxy=pg.dbay.cloud`` so that a local HTTP proxy does not
    interfere with the PostgreSQL connection.
    """
    env = {**os.environ, "no_proxy": "pg.dbay.cloud"}
    if password:
        env["PGPASSWORD"] = password

    # Ensure sslmode=require is present
    if "sslmode=" not in connstr:
        sep = "&" if "?" in connstr else "?"
        connstr += f"{sep}sslmode=require"

    result = subprocess.run(
        ["psql", connstr, "-c", sql, "-t", "-A"],
        capture_output=True,
        text=True,
        timeout=60,
        env=env,
    )
    if result.returncode != 0:
        raise RuntimeError(f"psql failed: {result.stderr}")
    return result.stdout.strip()


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

def _create_tenant_with_invite(endpoint: str, admin_token: str,
                                username: str, password: str, name: str) -> tuple:
    """Create invite code via admin API, then register tenant.
    Returns (DbayClient, tenant_dict).
    """
    admin = DbayClient(endpoint=endpoint, api_key=admin_token)
    invite = admin.admin_create_invite_code(max_uses=1)
    invite_code = invite.get("code")

    client = DbayClient(endpoint=endpoint)
    tenant = client.create_tenant(
        username=username,
        password=password,
        name=name,
        invite_code=invite_code,
    )
    client.api_key = tenant["api_key"]

    # Increase quota for test tenant
    tenant_id = tenant.get("id")
    if tenant_id:
        admin.admin_update_quota(tenant_id, max_databases=20)

    return client, tenant


@pytest.fixture(scope="session")
def e2e_tenant():
    """Create a disposable test tenant for the entire session.

    Yields a dict with keys: client, api_key, username, password, and
    anything else the create_tenant API returns.

    On teardown, deletes every database owned by the tenant.
    """
    ts = int(time.time())
    username = f"e2e-{ts}"
    password = f"E2eTest@{ts}"

    client, tenant = _create_tenant_with_invite(
        ENDPOINT, ADMIN_TOKEN, username, password, f"E2E Test {ts}"
    )

    info = {
        "client": client,
        "username": username,
        "password": password,
        **tenant,
    }

    yield info

    # Cleanup: delete all databases created during the session
    for db in client.list_databases():
        try:
            client.delete_database(db["id"])
        except Exception:
            pass


@pytest.fixture(scope="session")
def e2e_client(e2e_tenant):
    """Return the authenticated DbayClient from e2e_tenant."""
    return e2e_tenant["client"]


@pytest.fixture
def test_db(e2e_client):
    """Create a temporary database, poll until RUNNING, yield it, then delete.

    The returned dict includes the ``password`` field captured from the
    creation response (GET won't return it).
    """
    db = e2e_client.create_database(name=f"e2e-db-{int(time.time())}")
    # Capture password from creation response before polling overwrites it
    creation_password = db.get("password")

    db = poll_until(
        lambda: e2e_client.get_database(db["id"]),
        condition=lambda d: d["status"].lower() in ("running", "error"),
        timeout=120,
        interval=3,
    )
    assert db["status"].lower() == "running", f"Database creation failed: {db}"

    # Re-attach password since GET doesn't return it
    db["password"] = creation_password

    yield db

    try:
        e2e_client.delete_database(db["id"])
    except Exception:
        pass
