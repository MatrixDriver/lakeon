#!/usr/bin/env bash
#
# 构建 lakeon-orchestrator 镜像并推送到华为云 SWR
#
# 用法:
#   IMAGE_TAG=0.1.0 ./deploy/cce/build-and-push-orchestrator.sh
#
set -euo pipefail

export no_proxy="*"
export NO_PROXY="*"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${SWR_ORG:-}" ] && [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-flex}"
IMAGE_TAG="${IMAGE_TAG:-0.1.0}"
IMAGE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-orchestrator:${IMAGE_TAG}"

PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ORCH_DIR="$PROJECT_DIR/lakeon-orchestrator"

echo "=== 构建 lakeon-orchestrator 并推送到 SWR ==="
echo "镜像: $IMAGE"
echo ""

# 1. Docker 构建
echo "[1/2] Docker 构建..."
docker build -t "$IMAGE" "$ORCH_DIR/"
echo "  构建完成"

# 2. 推送
echo "[2/2] 推送到 SWR..."
docker push "$IMAGE"
echo ""
echo "=== 完成: $IMAGE ==="
