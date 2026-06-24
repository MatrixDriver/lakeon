#!/usr/bin/env bash
#
# Build the Lakeon data proxy from the Neon-derived proxy checkout and push it
# to Huawei Cloud SWR. The proxy binary must be built inside a Linux container;
# copying a macOS host binary into the runtime image will fail with exec format
# errors in Kubernetes.
#
# Usage:
#   SITE=hwstaff ./deploy/cce/build-and-push-proxy.sh
#   NEON_ROOT=~/code/neon IMAGE_TAG=0.1.0 ./deploy/cce/build-and-push-proxy.sh

set -euo pipefail

export no_proxy="*"
export NO_PROXY="*"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
NEON_ROOT="${NEON_ROOT:-$HOME/code/neon}"

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-flex}"

if [ -z "${IMAGE_TAG:-}" ]; then
  SHORT_SHA="$(cd "$NEON_ROOT" && git rev-parse --short=8 HEAD)"
  DIRTY=""
  if ! (cd "$NEON_ROOT" && git diff-index --quiet HEAD --); then
    DIRTY="-dirty"
  fi
  IMAGE_TAG="0.1.0-${SHORT_SHA}${DIRTY}"
  echo "Auto IMAGE_TAG: ${IMAGE_TAG}"
fi

IMAGE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-proxy:${IMAGE_TAG}"
LINUX_TARGET_DIR="$NEON_ROOT/target/lakeon-linux"
PROXY_BIN="$LINUX_TARGET_DIR/release/proxy"
CONTEXT_BIN="$PROJECT_DIR/deploy/docker/proxy"

echo "=== Build and push Lakeon data proxy ==="
echo "Neon root: $NEON_ROOT"
echo "Image: $IMAGE"

echo "[1/3] Cargo build in linux/amd64 container..."
docker run --rm --platform linux/amd64 \
  -v "$NEON_ROOT:/workspace" \
  -w /workspace \
  -e CARGO_TARGET_DIR=/workspace/target/lakeon-linux \
  rust:1.88-bookworm \
  bash -c 'set -euo pipefail
    export PATH="/usr/local/cargo/bin:$PATH"
    apt-get update
    apt-get install -y --no-install-recommends \
      autoconf automake build-essential ca-certificates clang git libssl-dev libtool make pkg-config protobuf-compiler
    cargo build -p proxy --bin proxy --release
    strip "$CARGO_TARGET_DIR/release/proxy"
  '

echo "[2/3] Docker build..."
cp "$PROXY_BIN" "$CONTEXT_BIN"
trap 'rm -f "$CONTEXT_BIN"' EXIT
docker build -f "$PROJECT_DIR/deploy/docker/Dockerfile.lakeon-proxy" \
  -t "$IMAGE" \
  "$PROJECT_DIR/deploy/docker"

echo "[3/3] Push..."
docker push "$IMAGE"

echo ""
echo "Done: $IMAGE"
