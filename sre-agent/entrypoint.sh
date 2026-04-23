#!/usr/bin/env bash
set -euo pipefail

echo "[entrypoint] verifying env..."
python /app/scripts/verify_env.py

mkdir -p "${HERMES_HOME}/data"

# main.py manages both subprocesses (obs_sync_loop + hermes_gateway)
# and runs the croniter dispatch loop itself.
echo "[entrypoint] launching sre-agent main.py..."
exec python /app/main.py
