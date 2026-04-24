"""CLI entry: python -m skills.reading.url_handler.cli --url <url> [--no-push]

Wires real DeepseekLLMClient + httpx.Client + LogStore from main.py helpers.
For tests, set module-level _TEST_HTTP / _TEST_LLM to inject fakes.
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import httpx

# Allow running both `python -m skills.reading.url_handler.cli` and direct script.
_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE.parents[2]))  # reading-companion/

from agent_session_log import LogStore

from skills.reading.url_handler.handler import handle_url


# Test injection points (module-level, monkey-patched by tests).
_TEST_HTTP = None
_TEST_LLM = None


log = logging.getLogger("reading.url_handler.cli")


def _real_http():
    return httpx.Client(timeout=30.0)


def _real_llm():
    # Import lazily so tests can monkeypatch without needing DEEPSEEK_API_KEY.
    from hermes_agent_utils import DeepseekLLMClient  # type: ignore[import-not-found]
    return DeepseekLLMClient()


def _make_log_store_local() -> LogStore:
    home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    return LogStore(home / "data")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Ingest a URL into the reading commit log.")
    parser.add_argument("--url", required=True, help="The URL to read.")
    parser.add_argument("--no-push", action="store_true",
                        help="Skip feishu DM (default: push if FEISHU_ALLOWED_USERS set).")
    parser.add_argument("--user", default=None,
                        help="Override target open_id (default: first FEISHU_ALLOWED_USERS).")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    log_store = _make_log_store_local()
    http = _TEST_HTTP if _TEST_HTTP is not None else _real_http()
    llm = _TEST_LLM if _TEST_LLM is not None else _real_llm()

    # Resolve target open_id
    open_id = args.user
    if open_id is None and not args.no_push:
        users = os.environ.get("FEISHU_ALLOWED_USERS", "")
        parts = [u.strip() for u in users.split(",") if u.strip()]
        open_id = parts[0] if parts else None

    received_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    result = handle_url(
        log=log_store, http=http, llm=llm,
        url=args.url, user_open_id=open_id,
        received_at=received_at, source="cli",
    )
    log.info("[cli] session %s status=%s", result.session_id, result.status)
    log.info("[cli] reply preview:\n%s", result.feishu_reply)

    if not args.no_push and open_id:
        try:
            from hermes_agent_utils import feishu_send_dm  # type: ignore[import-not-found]
            feishu_send_dm(open_id, result.feishu_reply)
            log.info("[cli] feishu DM sent to %s", open_id)
        except Exception as exc:  # noqa: BLE001
            log.warning("[cli] feishu DM failed: %s", exc)

    return 0 if result.status == "closed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
