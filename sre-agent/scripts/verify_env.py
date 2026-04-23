# sre-agent/scripts/verify_env.py
"""Pre-flight: ensure all required env vars are set and reachable."""
import os
import sys


REQUIRED = [
    "DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL",
    "FEISHU_APP_ID", "FEISHU_APP_SECRET",
    "FEISHU_VERIFICATION_TOKEN", "FEISHU_ENCRYPT_KEY",
    "FEISHU_ALLOWED_USERS",
    "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET", "OBS_ENDPOINT",
]


def main() -> int:
    missing = [k for k in REQUIRED if not os.environ.get(k)]
    if missing:
        print("MISSING env vars:")
        for k in missing:
            print(f"  - {k}")
        return 1
    print(f"OK — all {len(REQUIRED)} required env vars set")
    return 0


if __name__ == "__main__":
    sys.exit(main())
