"""Long-running OBS sync loop. Run as a sidecar thread or separate process.

Env:
    HERMES_HOME: commit log root (default: ~/.hermes)
    OBS_ACCESS_KEY, OBS_SECRET_KEY, OBS_ENDPOINT, OBS_BUCKET
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

# Allow running from project root
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent_session_log import LogStore
from agent_session_log.obs_sync import HuaweiObsAdapter, ObsSync


def main() -> None:
    root = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))) / "data"
    log = LogStore(root)
    adapter = HuaweiObsAdapter(
        access_key=os.environ["OBS_ACCESS_KEY"],
        secret_key=os.environ["OBS_SECRET_KEY"],
        endpoint=os.environ["OBS_ENDPOINT"],
    )
    sync = ObsSync(
        log.store,
        client=adapter,
        bucket=os.environ["OBS_BUCKET"],
        prefix=os.environ.get("OBS_PREFIX", "agent-log/"),
    )

    interval = int(os.environ.get("OBS_SYNC_INTERVAL_SEC", "60"))
    print(f"obs_sync: starting, root={root}, bucket={sync._bucket}, interval={interval}s")
    while True:
        try:
            uploaded = sync.upload_pending(limit=20)
            if uploaded:
                print(f"obs_sync: uploaded {len(uploaded)} sessions")
        except Exception as exc:  # noqa: BLE001
            print(f"obs_sync: loop error: {exc}")
        time.sleep(interval)


if __name__ == "__main__":
    main()
