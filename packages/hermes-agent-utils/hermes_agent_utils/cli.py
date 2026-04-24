"""hermes-agent-utils CLI: `python -m hermes_agent_utils.cli sync`

Subcommands:
  sync — long-running OBS sync loop.

Reads HERMES_HOME, OBS_ACCESS_KEY, OBS_SECRET_KEY, OBS_ENDPOINT, OBS_BUCKET,
optional OBS_PREFIX (default "agent-log/") and OBS_SYNC_INTERVAL_SEC (default 60).
"""
from __future__ import annotations

import argparse
import logging
import os
import time

from agent_session_log import LogStore
from agent_session_log.obs_sync import HuaweiObsAdapter, ObsSync

from hermes_agent_utils.factory import hermes_home


_log = logging.getLogger("hermes_agent_utils.cli")


def cmd_sync(_args: argparse.Namespace) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    root = hermes_home() / "data"
    log_store = LogStore(root)
    adapter = HuaweiObsAdapter(
        access_key=os.environ["OBS_ACCESS_KEY"],
        secret_key=os.environ["OBS_SECRET_KEY"],
        endpoint=os.environ["OBS_ENDPOINT"],
    )
    sync = ObsSync(
        log_store.store,
        client=adapter,
        bucket=os.environ["OBS_BUCKET"],
        prefix=os.environ.get("OBS_PREFIX", "agent-log/"),
    )
    interval = int(os.environ.get("OBS_SYNC_INTERVAL_SEC", "60"))
    _log.info(
        "obs_sync: starting, root=%s, bucket=%s, prefix=%s, interval=%ds",
        root, sync._bucket, sync._prefix, interval,
    )
    while True:
        try:
            uploaded = sync.upload_pending(limit=20)
            if uploaded:
                _log.info("obs_sync: uploaded %d sessions", len(uploaded))
        except Exception as exc:
            _log.error("obs_sync: loop error: %s", exc)
        time.sleep(interval)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="hermes-agent-utils")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_sync = sub.add_parser("sync", help="Run OBS sync loop (long-running).")
    p_sync.set_defaults(func=cmd_sync)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
