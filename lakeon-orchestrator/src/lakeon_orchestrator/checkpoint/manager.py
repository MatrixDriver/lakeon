from __future__ import annotations

import logging
import pickle
from typing import Any, Optional
from urllib.parse import urlparse

logger = logging.getLogger(__name__)


class CheckpointManager:
    """Manages checkpoint persistence to OBS (S3-compatible).

    Checkpoints are used for:
    1. Async intermediate data snapshots (non-blocking, for preview)
    2. HUMAN_REVIEW pause — full data materialization before releasing Ray
    3. Fault recovery — resume from last checkpoint on failure

    Storage layout:
        obs://{bucket}/{prefix}/{run_id}/{step_id}/checkpoint.pkl
    """

    def __init__(self, obs_client, bucket: str, prefix: str = "checkpoints"):
        self._client = obs_client
        self._bucket = bucket
        self._prefix = prefix

    def _make_key(self, run_id: str, step_id: str) -> str:
        return f"{self._prefix}/{run_id}/{step_id}/checkpoint.pkl"

    def _make_obs_path(self, key: str) -> str:
        return f"obs://{self._bucket}/{key}"

    async def save(self, run_id: str, step_id: str, data: Any) -> str:
        """Serialize data and upload to OBS.

        Returns the full obs:// path for the checkpoint.
        """
        key = self._make_key(run_id, step_id)
        body = pickle.dumps(data)

        logger.info(f"Saving checkpoint: {key} ({len(body)} bytes)")
        self._client.put_object(
            Bucket=self._bucket,
            Key=key,
            Body=body,
        )

        path = self._make_obs_path(key)
        logger.info(f"Checkpoint saved: {path}")
        return path

    async def load(self, obs_path: str) -> Any:
        """Download and deserialize checkpoint from OBS."""
        bucket, key = self.parse_obs_path(obs_path)

        logger.info(f"Loading checkpoint: {obs_path}")
        response = self._client.get_object(Bucket=bucket, Key=key)
        body = response["Body"].read()
        data = pickle.loads(body)

        logger.info(f"Checkpoint loaded: {obs_path}")
        return data

    @staticmethod
    def parse_obs_path(obs_path: str) -> tuple[str, str]:
        """Parse obs://bucket/key into (bucket, key)."""
        parsed = urlparse(obs_path)
        bucket = parsed.netloc
        key = parsed.path.lstrip("/")
        return bucket, key
