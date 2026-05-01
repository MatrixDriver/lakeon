from __future__ import annotations

import logging
import sys
import structlog
from structlog.typing import FilteringBoundLogger


def setup_logging(level: str = "INFO") -> None:
    """Configure stdlib + structlog. Call once at process startup.

    Subsequent calls update the level for new loggers only; already-cached
    loggers are unaffected because cache_logger_on_first_use=True.
    """
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stderr,
        level=getattr(logging, level.upper(), logging.INFO),
    )
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.dev.ConsoleRenderer(colors=False),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            getattr(logging, level.upper(), logging.INFO)
        ),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str) -> FilteringBoundLogger:
    """Return a logger; matches the wrapper class set by setup_logging."""
    return structlog.get_logger(name)
