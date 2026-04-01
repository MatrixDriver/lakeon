"""Structured JSON logging handler and HTTP batch shipper for Lakeon CCI components."""
import json
import logging
import threading
from datetime import datetime, timezone
from typing import List


class LakeonJsonFormatter(logging.Formatter):
    """Formats log records as structured JSON for Lakeon CCI components."""

    def __init__(self, component: str, request_id: str = "", tenant_id: str = ""):
        super().__init__()
        self.component = component
        self.request_id = request_id
        self.tenant_id = tenant_id

    def format(self, record: logging.LogRecord) -> str:
        # Base message — include exception info if present
        message = record.getMessage()
        if record.exc_info:
            message = message + "\n" + self.formatException(record.exc_info)

        ts = datetime.fromtimestamp(record.created, tz=timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%S.%f"
        )[:-3] + "Z"

        payload: dict = {
            "ts": ts,
            "level": record.levelname,
            "component": self.component,
            "logger": record.name,
            "msg": message,
            "thread": record.thread,
        }

        if self.request_id:
            payload["requestId"] = self.request_id
        if self.tenant_id:
            payload["tenantId"] = self.tenant_id

        duration_ms = getattr(record, "duration_ms", None)
        if duration_ms is not None:
            payload["durationMs"] = duration_ms

        extra_data = getattr(record, "extra_data", None)
        if extra_data is not None:
            payload["extra"] = extra_data

        return json.dumps(payload, ensure_ascii=False)


class HttpBatchHandler(logging.Handler):
    """Buffers log records and ships them in batches via HTTP POST."""

    def __init__(
        self,
        endpoint: str,
        batch_size: int = 100,
        flush_interval: float = 2.0,
    ):
        super().__init__()
        self.endpoint = endpoint
        self.batch_size = batch_size
        self.flush_interval = flush_interval
        self._buffer: List[str] = []
        self._lock = threading.Lock()
        self._timer: threading.Timer | None = None
        self._closed = False
        self._schedule_timer()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _schedule_timer(self) -> None:
        if self._closed:
            return
        self._timer = threading.Timer(self.flush_interval, self._timer_flush)
        self._timer.daemon = True
        self._timer.start()

    def _timer_flush(self) -> None:
        self._do_flush()
        self._schedule_timer()

    def _do_flush(self) -> None:
        with self._lock:
            if not self._buffer:
                return
            entries = self._buffer[:]
            self._buffer.clear()
        self._send(entries)

    def _send(self, entries: List[str]) -> None:
        """POST a JSON array of log entry strings to the endpoint.

        Exceptions are silently swallowed — log collection must never crash the job.
        """
        try:
            import requests  # local import keeps startup cost low

            requests.post(
                self.endpoint,
                data="[" + ",".join(entries) + "]",
                headers={"Content-Type": "application/json"},
                timeout=5,
            )
        except Exception:
            pass

    # ------------------------------------------------------------------
    # logging.Handler interface
    # ------------------------------------------------------------------

    def emit(self, record: logging.LogRecord) -> None:
        try:
            formatted = self.format(record)
        except Exception:
            self.handleError(record)
            return

        flush_needed = False
        with self._lock:
            self._buffer.append(formatted)
            if len(self._buffer) >= self.batch_size:
                flush_needed = True

        if flush_needed:
            self._do_flush()

    def close(self) -> None:
        self._closed = True
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None
        self._do_flush()
        super().close()
