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


def test_api_error_captures_status_and_body():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(409, json={"error": {"code": "CONFLICT"}})
        ),
    )

    with pytest.raises(DbayApiError) as exc:
        client.create_database("bench-branch-version-x", "1cu")

    assert exc.value.status_code == 409
    assert exc.value.body["error"]["code"] == "CONFLICT"


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
