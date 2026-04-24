# sre-agent/scripts/verify_env.py
"""Pre-flight: ensure all required env vars are set.

Splits into REQUIRED (hard failures) and OPTIONAL (warn but don't fail).
FEISHU_VERIFICATION_TOKEN / FEISHU_ENCRYPT_KEY are only needed for webhook
transport; default WebSocket transport (FEISHU_CONNECTION_MODE unset or
'websocket') does not need them.
"""
import os
import sys


REQUIRED = [
    "DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL",
    "DBAY_LOGS_DSN",
    "FEISHU_APP_ID", "FEISHU_APP_SECRET",
    "FEISHU_ALLOWED_USERS",
    "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET", "OBS_ENDPOINT",
    # dbay-sre-mcp 0.2.0 — admin REST endpoint access
    "LAKEON_ADMIN_TOKEN",
]

OPTIONAL = [
    ("FEISHU_VERIFICATION_TOKEN", "only required if FEISHU_CONNECTION_MODE=webhook"),
    ("FEISHU_ENCRYPT_KEY", "only required if FEISHU_CONNECTION_MODE=webhook"),
    ("OBS_PREFIX", "defaults to 'agent-log/'"),
    ("LAKEON_API_BASE_URL", "defaults to https://api.dbay.cloud:8443/api/v1 if unset"),
    ("LAKEON_DB_DSN",
     "needed only by data_consistency_check + stuck_task_query tools "
     "(other 5 new tools work without it)"),
]


def main() -> int:
    missing = [k for k in REQUIRED if not os.environ.get(k)]
    if missing:
        print("MISSING required env vars:")
        for k in missing:
            print(f"  - {k}")
        return 1

    unset_optional = [(k, note) for k, note in OPTIONAL if not os.environ.get(k)]
    if unset_optional:
        print("NOTE — optional env vars not set (fine for default config):")
        for k, note in unset_optional:
            print(f"  - {k}: {note}")

    print(f"OK — all {len(REQUIRED)} required env vars set")
    return 0


if __name__ == "__main__":
    sys.exit(main())
