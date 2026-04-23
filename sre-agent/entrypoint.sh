#!/usr/bin/env bash
set -euo pipefail

echo "[entrypoint] verifying env..."
python /app/scripts/verify_env.py

mkdir -p "${HERMES_HOME}/data"

echo "[entrypoint] starting obs sync loop in background..."
python /app/scripts/sync_loop.py &
OBS_SYNC_PID=$!
trap 'kill "${OBS_SYNC_PID}" 2>/dev/null || true' EXIT

echo "[entrypoint] launching hermes gateway..."
exec hermes gateway start --config "${HERMES_CONFIG}"
