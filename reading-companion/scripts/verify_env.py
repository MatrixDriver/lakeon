"""Pre-flight env check for reading-companion."""
import os
import sys


REQUIRED = [
    "DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL",
    "FEISHU_APP_ID", "FEISHU_APP_SECRET", "FEISHU_ALLOWED_USERS",
    "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET", "OBS_ENDPOINT",
]
OPTIONAL = ["OBS_PREFIX", "HERMES_HOME"]


def main() -> int:
    missing = [k for k in REQUIRED if not os.environ.get(k)]
    if missing:
        print("MISSING required env vars:")
        for k in missing:
            print(f"  - {k}")
        return 1
    print(f"OK — all {len(REQUIRED)} required env vars set")
    for k in OPTIONAL:
        v = os.environ.get(k)
        print(f"  optional {k} = {v if v else '(unset, using default)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
