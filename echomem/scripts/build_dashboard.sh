#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/.."

cd dashboard
npm ci
npm run build

DIST_TARGET="../src/echomem/_dashboard_dist"
rm -rf "$DIST_TARGET"
mkdir -p "$DIST_TARGET"
cp -R dist/. "$DIST_TARGET/"
git rev-parse --short HEAD > "$DIST_TARGET/_BUILD_INFO" 2>/dev/null || echo "unknown" > "$DIST_TARGET/_BUILD_INFO"

echo "Dashboard built into $DIST_TARGET"
