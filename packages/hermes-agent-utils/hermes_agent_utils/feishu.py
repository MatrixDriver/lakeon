"""Feishu outbound DM via REST API.

Both agents push messages to Jacky from cron tasks; nobody listens to inbound.
"""
from __future__ import annotations

import json
import os

import httpx


def _app_access_token() -> str:
    app_id = os.environ["FEISHU_APP_ID"]
    app_secret = os.environ["FEISHU_APP_SECRET"]
    resp = httpx.post(
        "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal",
        json={"app_id": app_id, "app_secret": app_secret},
        timeout=10.0,
    )
    resp.raise_for_status()
    return resp.json()["app_access_token"]


def feishu_send_dm(open_id: str, text: str) -> None:
    """Send a plain-text DM to a feishu user by open_id."""
    token = _app_access_token()
    resp = httpx.post(
        "https://open.feishu.cn/open-apis/im/v1/messages",
        params={"receive_id_type": "open_id"},
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        json={
            "receive_id": open_id,
            "msg_type": "text",
            "content": json.dumps({"text": text}),
        },
        timeout=15.0,
    )
    resp.raise_for_status()


def jacky_open_id() -> str | None:
    """First open_id from FEISHU_ALLOWED_USERS env (comma-separated)."""
    users = os.environ.get("FEISHU_ALLOWED_USERS", "")
    parts = [u.strip() for u in users.split(",") if u.strip()]
    return parts[0] if parts else None
