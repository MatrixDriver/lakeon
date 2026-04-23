"""Verify we can connect to dbay-logs PG from the current host.

Run from Railway shell (or local with DBAY_LOGS_DSN pointing at the real prod PG)
to confirm network path before committing to deployment.
"""
import os
import sys
import time


def main() -> int:
    dsn = os.environ.get("DBAY_LOGS_DSN")
    if not dsn:
        print("FAIL: DBAY_LOGS_DSN not set")
        return 1
    try:
        import psycopg2  # noqa: PLC0415
    except ImportError:
        print("FAIL: psycopg2-binary not installed in this env")
        return 1

    t0 = time.time()
    try:
        conn = psycopg2.connect(dsn, connect_timeout=5)
    except Exception as exc:  # noqa: BLE001
        print(f"FAIL: connect error: {exc}")
        return 1

    try:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM logs WHERE ts >= NOW() - INTERVAL '1 hour'")
            count = cur.fetchone()[0]
    except Exception as exc:  # noqa: BLE001
        print(f"FAIL: query error: {exc}")
        conn.close()
        return 1

    dt_ms = int((time.time() - t0) * 1000)
    print(f"OK: {count} log rows in last 1h, connect+query took {dt_ms}ms")
    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
