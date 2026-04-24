"""Session and turn id generation."""
from __future__ import annotations

import secrets
from datetime import datetime, timezone


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _compact_ts() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")


def new_session_id() -> str:
    return f"sess_{_compact_ts()}_{secrets.token_hex(3)}"
