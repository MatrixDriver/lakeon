import httpx
import pytest

from dbay_branch_version.dbay_client import DbayApiError, DbayClient, UnsafeResourceError


def test_create_branch_sends_start_compute_flag():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = request.content.decode()
        return httpx.Response(201, json={"id": "br_1", "name": "b1"})

    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(handler),
    )

    branch, sample = client.create_branch("db_1", "b1", start_compute=True)

    assert branch["id"] == "br_1"
    assert seen["url"].endswith("/databases/db_1/branches")
    assert '"start_compute":true' in seen["body"].replace(" ", "")
    assert sample.http_status == 201
    assert sample.api_latency_ms is not None


def test_delete_database_rejects_non_benchmark_name():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(lambda request: httpx.Response(204)),
    )

    with pytest.raises(UnsafeResourceError):
        client.delete_database("db_1", "customer-prod")


def test_delete_branch_rejects_non_benchmark_database_name():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(lambda request: httpx.Response(204)),
    )

    with pytest.raises(UnsafeResourceError):
        client.delete_branch(
            "db_1",
            database_name="customer-prod",
            branch_id="br_1",
            branch_name="feature",
        )


def test_delete_branch_rejects_main_branch_without_request():
    seen = {"requests": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["requests"] += 1
        return httpx.Response(204)

    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(UnsafeResourceError):
        client.delete_branch(
            "db_1",
            database_name="bench-branch-version-x",
            branch_id="br_1",
            branch_name="main",
        )

    assert seen["requests"] == 0


def test_delete_branch_rejects_default_branch_without_request():
    seen = {"requests": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["requests"] += 1
        return httpx.Response(204)

    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(UnsafeResourceError):
        client.delete_branch(
            "db_1",
            database_name="bench-branch-version-x",
            branch_id="br_1",
            branch_name="feature",
            is_default=True,
        )

    assert seen["requests"] == 0


def test_delete_version_rejects_non_benchmark_database_name():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(lambda request: httpx.Response(204)),
    )

    with pytest.raises(UnsafeResourceError):
        client.delete_version(
            "db_1",
            database_name="customer-prod",
            branch_id="br_1",
            version_id="ver_1",
        )


def test_squash_versions_rejects_non_benchmark_database_name():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=[])),
    )

    with pytest.raises(UnsafeResourceError):
        client.squash_versions(
            "db_1",
            database_name="customer-prod",
            branch_id="br_1",
            from_version_id="ver_1",
            to_version_id="ver_2",
        )


def test_api_error_captures_status_and_body():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                409,
                json={"error": {"code": "CONFLICT", "message": "Bearer secret-token"}},
            )
        ),
    )

    with pytest.raises(DbayApiError) as exc:
        client.create_database("bench-branch-version-x", "1cu")

    assert exc.value.status_code == 409
    assert exc.value.body["error"]["code"] == "CONFLICT"
    assert exc.value.sample is not None
    assert exc.value.sample.success is False
    assert exc.value.sample.http_status == 409
    assert exc.value.sample.api_latency_ms is not None
    assert exc.value.sample.error_message
    assert "secret-token" not in exc.value.sample.error_message


def test_transport_error_captures_failure_sample():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("Bearer secret-token failed", request=request)

    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(DbayApiError) as exc:
        client.get_database("db_1")

    assert exc.value.status_code == 0
    assert exc.value.sample is not None
    assert exc.value.sample.success is False
    assert exc.value.sample.error_code == "ConnectError"
    assert exc.value.sample.api_latency_ms is not None
    assert "secret-token" not in exc.value.sample.error_message


def test_non_json_response_falls_back_to_text_body():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                content=b"ok",
                headers={"Content-Type": "text/plain"},
            )
        ),
    )

    body, sample = client.get_database("db_1")

    assert body == "ok"
    assert sample.success is True
    assert sample.http_status == 200
