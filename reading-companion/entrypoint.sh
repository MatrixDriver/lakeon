#!/usr/bin/env bash
set -euo pipefail

echo "[entrypoint] verifying env..."
python /app/scripts/verify_env.py

mkdir -p "${HERMES_HOME:-/data/hermes}/data"

echo "[entrypoint] launching reading-companion main.py..."
exec python /app/main.py
