import os
import base64
import re
import subprocess
import time
import uuid

import pytest

from conftest import ADMIN_TOKEN, ENDPOINT, poll_until
from dbay_cli.client import DbayClient


pytestmark = pytest.mark.skipif(
    os.environ.get("RUN_DESTRUCTIVE_PAGESERVER_SQL_E2E") != "1",
    reason="Set RUN_DESTRUCTIVE_PAGESERVER_SQL_E2E=1 to delete pageserver-0 and verify SQL failover.",
)


CONTROL_KUBECONFIG = os.environ.get(
    "CONTROL_KUBECONFIG",
    os.path.expanduser("~/.kube/cce-dbay-control-plane-config"),
)
DATA_KUBECONFIG = os.environ.get(
    "DATA_KUBECONFIG",
    os.path.expanduser("~/.kube/cce-lakeon-config"),
)
NAMESPACE = os.environ.get("DBAY_K8S_NAMESPACE", "lakeon")
COMPUTE_NAMESPACE = os.environ.get("DBAY_COMPUTE_NAMESPACE", "lakeon-compute")


def _run(cmd: list[str], *, timeout: int = 120) -> str:
    env = {**os.environ, "NO_PROXY": "*", "no_proxy": "*"}
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env)
    if result.returncode != 0:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(cmd)}\n"
            f"stdout={result.stdout}\nstderr={result.stderr}"
        )
    return result.stdout.strip()


def _kubectl(kubeconfig: str, args: list[str], *, timeout: int = 120) -> str:
    return _run(["kubectl", "--kubeconfig", kubeconfig, *args], timeout=timeout)


def _config_value(name: str) -> str:
    return _kubectl(
        CONTROL_KUBECONFIG,
        [
            "-n",
            NAMESPACE,
            "get",
            "configmap",
            "lakeon-api-config",
            "-o",
            f"jsonpath={{.data.{name}}}",
        ],
    )


def _secret_value_b64(name: str) -> str:
    return _kubectl(
        CONTROL_KUBECONFIG,
        [
            "-n",
            NAMESPACE,
            "get",
            "secret",
            "api-credentials",
            "-o",
            f"jsonpath={{.data.{name}}}",
        ],
    )


def _metadata_connection() -> dict[str, str]:
    dsn = _config_value("LAKEON_DB_DSN")
    match = re.match(r"jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)", dsn)
    if not match:
        raise RuntimeError(f"Unsupported LAKEON_DB_DSN: {dsn}")
    return {
        "host": match.group(1),
        "port": match.group(2) or "5432",
        "database": match.group(3),
        "user": _config_value("LAKEON_DB_USER"),
        "password_b64": _secret_value_b64("db-password"),
    }


def _psql_metadata(sql: str) -> str:
    pod = f"psql-e2e-{uuid.uuid4().hex[:8]}"
    conn = _metadata_connection()
    sql_b64 = base64.b64encode(sql.encode()).decode()
    return _kubectl(
        CONTROL_KUBECONFIG,
        [
            "-n",
            NAMESPACE,
            "run",
            pod,
            "--rm",
            "-i",
            "--restart=Never",
            "--image=postgres:16-alpine",
            "--env",
            f"PGHOST={conn['host']}",
            "--env",
            f"PGPORT={conn['port']}",
            "--env",
            f"PGDATABASE={conn['database']}",
            "--env",
            f"PGUSER={conn['user']}",
            "--env",
            f"PGPASSWORD_B64={conn['password_b64']}",
            "--env",
            f"SQL_B64={sql_b64}",
            "--",
            "sh",
            "-c",
            "PGPASSWORD=\"$(printf '%s' \"$PGPASSWORD_B64\" | base64 -d)\" "
            "psql -h \"$PGHOST\" -p \"$PGPORT\" -U \"$PGUSER\" -d \"$PGDATABASE\" "
            "-tA -v ON_ERROR_STOP=1 -c \"$(printf '%s' \"$SQL_B64\" | base64 -d)\"",
        ],
        timeout=180,
    )


def _force_assignment_to_ps0(neon_tenant_id: str):
    sql = f"""
INSERT INTO pageserver_assignments
  (id, tenant_id, shard_id, node_id, epoch, source, status, updated_reason, created_at, updated_at)
VALUES
  ('{neon_tenant_id}:0', '{neon_tenant_id}', 0, 'ps-0', 1, 'e2e-forced', 'ACTIVE',
   'destructive-sql-failover-e2e', now(), now())
ON CONFLICT (id) DO UPDATE SET
  node_id = 'ps-0',
  epoch = pageserver_assignments.epoch + 1,
  source = 'e2e-forced',
  status = 'ACTIVE',
  updated_reason = 'destructive-sql-failover-e2e',
  updated_at = now();
"""
    _psql_metadata(sql)


def _attach_tenant_to_ps0(neon_tenant_id: str):
    pod = f"curl-e2e-{uuid.uuid4().hex[:8]}"
    body = '{"mode":"AttachedSingle","generation":1,"tenant_conf":{}}'
    _kubectl(
        DATA_KUBECONFIG,
        [
            "-n",
            NAMESPACE,
            "run",
            pod,
            "--rm",
            "-i",
            "--restart=Never",
            "--image=curlimages/curl:8.10.1",
            "--",
            "curl",
            "-sfS",
            "-X",
            "PUT",
            "-H",
            "Content-Type: application/json",
            "--data",
            body,
            f"http://pageserver-0.pageserver-headless.{NAMESPACE}.svc.cluster.local:9898/v1/tenant/{neon_tenant_id}/location_config",
        ],
        timeout=180,
    )


def _delete_compute_pod_if_present(admin: DbayClient, database_id: str):
    detail = admin._request("GET", f"/admin/databases/{database_id}")
    pod_name = detail.get("compute_pod_name")
    if not pod_name:
        return
    _kubectl(
        DATA_KUBECONFIG,
        ["-n", COMPUTE_NAMESPACE, "delete", "pod", pod_name, "--ignore-not-found=true"],
        timeout=120,
    )
    try:
        _kubectl(
            DATA_KUBECONFIG,
            ["-n", COMPUTE_NAMESPACE, "wait", "--for=delete", f"pod/{pod_name}", "--timeout=120s"],
            timeout=150,
        )
    except RuntimeError:
        pass


def _delete_pageserver_0():
    old_uid = _kubectl(
        DATA_KUBECONFIG,
        ["-n", NAMESPACE, "get", "pod", "pageserver-0", "-o", "jsonpath={.metadata.uid}"],
        timeout=60,
    )
    _kubectl(DATA_KUBECONFIG, ["-n", NAMESPACE, "delete", "pod", "pageserver-0", "--wait=false"], timeout=120)
    poll_until(
        lambda: _kubectl(
            DATA_KUBECONFIG,
            ["-n", NAMESPACE, "get", "pod", "pageserver-0", "-o", "jsonpath={.metadata.uid}"],
            timeout=30,
        ),
        condition=lambda uid: uid and uid != old_uid,
        timeout=180,
        interval=3,
    )


def _query_scalar(client: DbayClient, db_id: str, sql: str):
    result = client.execute_query(db_id, sql)
    rows = result.get("rows") or result.get("data") or result
    first_row = rows[0] if rows else []
    if isinstance(first_row, list):
        return first_row[0] if first_row else None
    if isinstance(first_row, dict):
        return next(iter(first_row.values()))
    return first_row


def test_pageserver_0_delete_sql_fails_over_to_another_pageserver(e2e_tenant):
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
    client: DbayClient = e2e_tenant["client"]
    db = client.create_database(name=f"e2e-ps-sql-{int(time.time())}-{uuid.uuid4().hex[:6]}")
    db_id = db["id"]

    try:
        db = poll_until(
            lambda: client.get_database(db_id),
            condition=lambda item: item["status"] in ("RUNNING", "ERROR"),
            timeout=360,
            interval=3,
        )
        assert db["status"] == "RUNNING", db

        client.execute_query(
            db_id,
            "CREATE TABLE IF NOT EXISTS dicer_failover_probe(id int primary key, value text)",
        )
        client.execute_query(
            db_id,
            "INSERT INTO dicer_failover_probe(id, value) VALUES (1, 'after-ps0-delete') "
            "ON CONFLICT (id) DO UPDATE SET value = EXCLUDED.value",
        )

        detail = admin._request("GET", f"/admin/databases/{db_id}")
        neon_tenant_id = detail["neon_tenant_id"]
        _force_assignment_to_ps0(neon_tenant_id)
        _attach_tenant_to_ps0(neon_tenant_id)
        _delete_compute_pod_if_present(admin, db_id)

        baseline = poll_until(
            lambda: _query_scalar(client, db_id, "SELECT value FROM dicer_failover_probe WHERE id = 1"),
            condition=lambda value: value == "after-ps0-delete",
            timeout=240,
            interval=5,
        )
        assert baseline == "after-ps0-delete"
        forced_detail = admin._request("GET", f"/admin/databases/{db_id}")
        assert forced_detail["pageserver_placement"]["node_id"] == "ps-0"

        _delete_pageserver_0()
        _delete_compute_pod_if_present(admin, db_id)

        def failover_state():
            current = admin._request("GET", f"/admin/databases/{db_id}")
            node_id = (current.get("pageserver_placement") or {}).get("node_id")
            try:
                value = _query_scalar(client, db_id, "SELECT value FROM dicer_failover_probe WHERE id = 1")
            except Exception as exc:
                value = f"query-error:{exc}"
            return {"node_id": node_id, "value": value}

        final_state = poll_until(
            failover_state,
            condition=lambda state: state["node_id"] not in (None, "ps-0")
            and state["value"] == "after-ps0-delete",
            timeout=360,
            interval=5,
        )

        assert final_state["node_id"] in {"ps-1", "ps-2"}
        assert final_state["value"] == "after-ps0-delete"
    finally:
        try:
            client.delete_database(db_id)
        except Exception:
            pass
