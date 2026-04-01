"""lakeon_log — one-line structured logging setup for Lakeon CCI job components."""
import logging
import os
import sys

from .handler import HttpBatchHandler, LakeonJsonFormatter

__all__ = ["setup_logging"]


def setup_logging(component: str, level: int = logging.INFO) -> logging.Logger:
    """Configure and return a structured JSON logger for a CCI component.

    Reads environment variables:
      LAKEON_REQUEST_ID   — forwarded as requestId in every log line
      LAKEON_TENANT_ID    — forwarded as tenantId in every log line
      LAKEON_LOG_ENDPOINT — if set, logs are also shipped via HttpBatchHandler

    Args:
        component: Short name identifying this CCI component (e.g. "knowledge-pipeline").
        level:     Python logging level (default INFO).

    Returns:
        A configured :class:`logging.Logger` instance.
    """
    request_id = os.environ.get("LAKEON_REQUEST_ID", "")
    tenant_id = os.environ.get("LAKEON_TENANT_ID", "")
    endpoint = os.environ.get("LAKEON_LOG_ENDPOINT", "")

    logger = logging.getLogger(component)
    logger.setLevel(level)

    # Clear any handlers added by previous calls (e.g. in tests)
    logger.handlers.clear()
    logger.propagate = False

    formatter = LakeonJsonFormatter(
        component=component,
        request_id=request_id,
        tenant_id=tenant_id,
    )

    # Always emit to stdout
    stream_handler = logging.StreamHandler(sys.stdout)
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)

    # Optionally ship via HTTP
    if endpoint:
        http_handler = HttpBatchHandler(endpoint=endpoint)
        http_handler.setFormatter(formatter)
        logger.addHandler(http_handler)

    return logger
