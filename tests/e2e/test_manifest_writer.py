"""
E2E tests for ManifestWriter (Task 19, Phase 1.4).

Verifies that after creating tenants / databases, the corresponding objects
appear in OBS:

  * tenants/<tenant_id>/_manifest.json  — per-tenant manifest (TenantManifest)
  * _global/owners/<shard>.idx          — owner email -> [tenant_id] index
                                          (shard = sha256(email)[0:2] hex)

JSON wire format is snake_case (matches the Jackson @JsonProperty annotations
on ManifestObjects.TenantManifest / OwnersIndex).

Manifest is written AFTER_COMMIT + @Async, so we poll OBS for a few seconds
before declaring failure.

Environment:
  LAKEON_OBS_ENDPOINT     — required, e.g. ``obs.cn-north-4.myhuaweicloud.com``
  LAKEON_OBS_BUCKET       — default ``neon`` (matches application.yml default)
  LAKEON_OBS_ACCESS_KEY   — required
  LAKEON_OBS_SECRET_KEY   — required

The whole module is skipped if OBS env vars are missing so the test is safe
in environments (CI, local dev) that don't have OBS access.

NOTE: This test relies on the staging API actually having ManifestWriter wired
up. If the deployed API doesn't yet emit TenantChangedEvent / write OBS, the
manifest test will fail with a clear timeout message rather than silently pass.
"""
import hashlib
import json
import os
import time

import pytest


# ---------------------------------------------------------------------------
# Env / module-level skip
# ---------------------------------------------------------------------------

OBS_ENDPOINT = os.environ.get("LAKEON_OBS_ENDPOINT")
OBS_BUCKET = os.environ.get("LAKEON_OBS_BUCKET", "neon")
OBS_AK = os.environ.get("LAKEON_OBS_ACCESS_KEY")
OBS_SK = os.environ.get("LAKEON_OBS_SECRET_KEY")

pytestmark = pytest.mark.skipif(
    not (OBS_ENDPOINT and OBS_AK and OBS_SK),
    reason=(
        "OBS env vars not set (need LAKEON_OBS_ENDPOINT, LAKEON_OBS_ACCESS_KEY, "
        "LAKEON_OBS_SECRET_KEY) — ManifestWriter E2E requires direct OBS read access"
    ),
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _endpoint_url() -> str:
    """Return a fully-qualified https URL for the configured OBS endpoint."""
    ep = OBS_ENDPOINT or ""
    if ep.startswith("http://") or ep.startswith("https://"):
        return ep
    return f"https://{ep}"


@pytest.fixture(scope="module")
def obs_client():
    """boto3 S3 client configured for Huawei OBS (S3-compatible).

    Module-scoped so all three tests share the same HTTP connection pool.
    """
    import boto3
    from botocore.config import Config

    return boto3.client(
        "s3",
        endpoint_url=_endpoint_url(),
        aws_access_key_id=OBS_AK,
        aws_secret_access_key=OBS_SK,
        config=Config(signature_version="s3v4", retries={"max_attempts": 3}),
        region_name=os.environ.get("LAKEON_OBS_REGION", "cn-north-4"),
    )


def _get_json_if_exists(obs_client, key: str):
    """Fetch the JSON object at ``key`` and return the parsed dict.

    Returns ``None`` when the key is missing (HTTP 404 / NoSuchKey). Any other
    error propagates so the test fails with a useful message rather than a
    silent skip.
    """
    from botocore.exceptions import ClientError

    try:
        obj = obs_client.get_object(Bucket=OBS_BUCKET, Key=key)
    except ClientError as e:
        code = e.response.get("Error", {}).get("Code", "")
        status = e.response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        if code in ("NoSuchKey", "404") or status == 404:
            return None
        raise
    return json.loads(obj["Body"].read().decode("utf-8"))


def _wait_for_manifest(obs_client, key: str, predicate=None, timeout: int = 30,
                       interval: float = 1.5):
    """Poll OBS for ``key`` until it exists (and optionally satisfies ``predicate``).

    Returns the parsed JSON dict on success. Calls ``pytest.fail`` on timeout
    with the last-seen state so failures are easy to diagnose.
    """
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = _get_json_if_exists(obs_client, key)
        if last is not None and (predicate is None or predicate(last)):
            return last
        time.sleep(interval)
    pytest.fail(
        f"OBS key {OBS_BUCKET}/{key} did not satisfy condition within {timeout}s; "
        f"last state: {last!r}"
    )


def _email_shard(email: str) -> str:
    """Mirror ManifestWriter.emailShard — first byte of sha256(email) as hex."""
    return hashlib.sha256(email.encode("utf-8")).hexdigest()[:2]


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestManifestWriter:
    """Verify ManifestWriter writes correct OBS objects after tenant/db events."""

    def test_create_tenant_writes_manifest_to_obs(self, e2e_tenant, obs_client):
        """After session-fixture tenant creation, its OBS manifest exists.

        The ``e2e_tenant`` fixture has already created a tenant via the admin
        invite + register flow, so by the time this test runs the
        TenantChangedEvent.CREATED has fired and the @Async manifest write
        should be in flight (or done). We poll OBS up to 30s for it.
        """
        tenant_id = e2e_tenant["id"]
        key = f"tenants/{tenant_id}/_manifest.json"

        manifest = _wait_for_manifest(obs_client, key, timeout=30)

        # Required snake_case keys per ManifestObjects.TenantManifest
        assert manifest.get("manifest_version") == 1, manifest
        assert manifest.get("tenant_id") == tenant_id, manifest
        assert "created_at" in manifest, manifest
        assert "updated_at" in manifest, manifest
        assert "databases" in manifest and isinstance(manifest["databases"], list), manifest
        # owner_email may be None for tenants registered via /tenants (only OAuth
        # sets the email column today). Just ensure the key is present or absent
        # consistently rather than asserting a value.
        if manifest.get("owner_email"):
            assert isinstance(manifest["owner_email"], str)

    def test_create_database_updates_tenant_manifest(self, e2e_tenant, e2e_client,
                                                       obs_client):
        """Creating a database under an existing tenant updates its manifest.

        Polls until the new db_id shows up in ``manifest["databases"]``.
        Cleans up the database afterwards even if the assertion fails.
        """
        tenant_id = e2e_tenant["id"]
        key = f"tenants/{tenant_id}/_manifest.json"

        db = e2e_client.create_database(name=f"mfw-{int(time.time())}")
        db_id = db["id"]

        try:
            manifest = _wait_for_manifest(
                obs_client,
                key,
                predicate=lambda m: any(
                    d.get("db_id") == db_id for d in (m.get("databases") or [])
                ),
                timeout=45,
            )
            entry = next(d for d in manifest["databases"] if d["db_id"] == db_id)
            # snake_case fields per ManifestObjects.DatabaseEntry
            assert entry.get("name", "").startswith("mfw-"), entry
            assert "created_at" in entry, entry
            # branches list may be empty until BranchService publishes its event,
            # but the key itself must be present (NON_NULL only suppresses nulls,
            # not empty lists).
            assert "branches" in entry, entry
        finally:
            try:
                e2e_client.delete_database(db_id)
            except Exception:
                pass

    def test_owners_idx_updated_after_tenant_create(self, e2e_tenant, obs_client):
        """When the tenant has an email, the owners.idx shard should map it
        email -> [tenant_id, ...].

        Tenants registered via the public /tenants endpoint do NOT set the
        ``email`` column (only OAuth does), so for the e2e_tenant fixture the
        expected behaviour is: the corresponding owners.idx shard is either
        absent OR present but missing this tenant. We assert the contract that
        ManifestWriter never writes a malformed shard, and — when an email is
        present — that it appears under the right shard.
        """
        tenant_id = e2e_tenant["id"]
        # The fixture's tenant dict echoes the create response; pull email if it
        # exposed one (future-proof: OAuth-created tenants would have this set).
        email = e2e_tenant.get("email")

        if not email:
            pytest.skip(
                "e2e_tenant has no email set (regular /tenants register flow); "
                "owners.idx is only written for tenants with an email column."
            )

        shard = _email_shard(email)
        key = f"_global/owners/{shard}.idx"

        idx = _wait_for_manifest(
            obs_client,
            key,
            predicate=lambda d: tenant_id in (d.get("owners", {}).get(email) or []),
            timeout=30,
        )

        # Validate the index_version + structure
        assert idx.get("index_version") == 1, idx
        assert "updated_at" in idx, idx
        assert "owners" in idx and isinstance(idx["owners"], dict), idx
        assert tenant_id in idx["owners"][email], idx
