"""Upload closed sessions to OBS as tar.gz archives.

Defines a thin protocol for the OBS client so tests can inject a fake.
In production, pass an esdk-obs-python ObsClient wrapped to match.
"""
from __future__ import annotations

import io
import tarfile
from pathlib import Path
from typing import Any, Protocol

from agent_session_log.store import FilesystemStore


class ObsClientLike(Protocol):
    def put_object(self, bucket: str, key: str, data: bytes) -> Any: ...


class FakeObsClient:
    """In-memory stand-in; tests assert against .objects."""

    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}

    def put_object(self, bucket: str, key: str, data: bytes) -> None:
        self.objects[key] = data


class ObsSync:
    def __init__(
        self,
        store: FilesystemStore,
        *,
        client: ObsClientLike,
        bucket: str,
        prefix: str = "agent-log/",
    ):
        self._store = store
        self._client = client
        self._bucket = bucket
        self._prefix = prefix.rstrip("/") + "/"

    def upload_session(self, session_id: str) -> str:
        manifest = self._store.read_manifest(session_id)
        if manifest.obs_ref:
            return manifest.obs_ref
        if manifest.status == "open":
            raise ValueError(f"refuse to upload open session {session_id}")

        d = self._store.session_dir(session_id)
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tf:
            tf.add(d, arcname=session_id)
        data = buf.getvalue()

        # Key: agent-log/YYYY/MM/DD/<id>.tar.gz
        parts = session_id.split("_")[1]
        key = f"{self._prefix}{parts[:4]}/{parts[4:6]}/{parts[6:8]}/{session_id}.tar.gz"

        self._client.put_object(self._bucket, key, data)

        manifest.obs_ref = key
        self._store.write_manifest(manifest)
        return key

    def upload_pending(self, limit: int = 50) -> list[str]:
        """Upload all closed sessions that don't yet have obs_ref."""
        uploaded = []
        for sid in self._store.iter_session_ids():
            try:
                m = self._store.read_manifest(sid)
            except FileNotFoundError:
                continue
            if m.status == "open" or m.obs_ref:
                continue
            try:
                uploaded.append(self.upload_session(sid))
            except Exception as exc:  # noqa: BLE001
                # Log and continue; don't stop on transient failures
                print(f"obs_sync: failed {sid}: {exc}")
            if len(uploaded) >= limit:
                break
        return uploaded


# ---- Real OBS adapter (requires esdk-obs-python) ----


class HuaweiObsAdapter:
    """Wrap esdk-obs-python ObsClient to match ObsClientLike.

    Import lazily so tests don't need the SDK.
    """

    def __init__(self, access_key: str, secret_key: str, endpoint: str) -> None:
        from obs import ObsClient  # noqa: PLC0415

        self._client = ObsClient(
            access_key_id=access_key,
            secret_access_key=secret_key,
            server=endpoint,
        )

    def put_object(self, bucket: str, key: str, data: bytes) -> None:
        resp = self._client.putObject(bucket, key, content=data)
        if resp.status >= 300:
            raise RuntimeError(f"OBS put failed {resp.status}: {resp.errorMessage}")
