from __future__ import annotations

import logging
from typing import Any, Callable, Optional

import ray

logger = logging.getLogger(__name__)


class RayClient:
    """Manages a persistent Ray cluster connection for pipeline execution.

    Lifecycle:
    - connect() at pipeline run start (or reuse existing)
    - submit_task() for each component step
    - get_result() to retrieve output (blocking or async)
    - put_object() / get_object() for inter-step data in object store
    - disconnect() when pipeline completes or pauses for HUMAN_REVIEW
    """

    def __init__(self, address: str = "auto", namespace: str = "lakeon-pipeline"):
        self._address = address
        self._namespace = namespace
        self._connected = False

    def connect(self) -> None:
        if ray.is_initialized():
            logger.info("Ray already initialized, reusing connection")
            self._connected = True
            return
        ray.init(address=self._address, namespace=self._namespace, ignore_reinit_error=True)
        self._connected = True
        logger.info(f"Connected to Ray cluster at {self._address}")

    def disconnect(self) -> None:
        if self._connected:
            ray.shutdown()
            self._connected = False
            logger.info("Disconnected from Ray cluster")

    @property
    def is_connected(self) -> bool:
        return self._connected

    def _ensure_connected(self) -> None:
        if not self._connected:
            raise RuntimeError("Not connected to Ray cluster. Call connect() first.")

    def submit_task(
        self,
        func: Callable,
        *args: Any,
        num_cpus: Optional[int] = None,
        num_gpus: Optional[int] = None,
        **kwargs: Any,
    ) -> ray.ObjectRef:
        """Submit a function as a Ray remote task.

        The function is wrapped with ray.remote() and submitted. Returns an ObjectRef
        that can be used to retrieve the result or pass to downstream steps.
        """
        self._ensure_connected()
        remote_options = {}
        if num_cpus is not None:
            remote_options["num_cpus"] = num_cpus
        if num_gpus is not None:
            remote_options["num_gpus"] = num_gpus

        if remote_options:
            remote_func = ray.remote(**remote_options)(func)
        else:
            remote_func = ray.remote(func)

        return remote_func.remote(*args, **kwargs)

    def get_result(self, ref: ray.ObjectRef, timeout: Optional[int] = None) -> Any:
        """Block until a Ray task completes and return its result."""
        self._ensure_connected()
        return ray.get(ref, timeout=timeout)

    def put_object(self, data: Any) -> ray.ObjectRef:
        """Put data into the Ray object store. Returns an ObjectRef."""
        self._ensure_connected()
        return ray.put(data)

    def get_object(self, ref: ray.ObjectRef) -> Any:
        """Get data from the Ray object store."""
        self._ensure_connected()
        return ray.get(ref)
