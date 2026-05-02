from __future__ import annotations

import time
from echomem.drivers.sqlite import SQLiteDriver
from echomem.logging import get_logger

log = get_logger("echomem.reflector")

# MVP placeholder. P1 will wire gemma to extract procedural skills from session
# clusters and synthesize episodic event titles/summaries.


class ReflectorWorker:
    def __init__(self, driver: SQLiteDriver):
        self.driver = driver

    async def reflect_once(self) -> dict:
        # Count recent events as a sanity probe; do nothing else.
        now = int(time.time() * 1000)
        rows = self.driver.query_timeline(start_ms=now - 24 * 3600_000, end_ms=now + 1)
        log.info("reflect.noop", events_in_24h=len(rows))
        return {"status": "noop", "considered": len(rows), "ts": now}
