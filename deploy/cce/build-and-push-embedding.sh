#!/usr/bin/env bash
#
# 构建 lakeon-embedding 镜像并推送到华为云 SWR
#
# 用法:
#   ./deploy/cce/build-and-push-embedding.sh
#   IMAGE_TAG=0.3.1 ./deploy/cce/build-and-push-embedding.sh
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
IMAGE_TAG="${IMAGE_TAG:-0.3.0}"
IMAGE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-embedding:${IMAGE_TAG}"
EMBED_DIR="$(cd "$SCRIPT_DIR/../../knowledge/embedding-service" && pwd)"

echo "=== 构建 lakeon-embedding 并推送到 SWR ==="
echo "镜像: $IMAGE"
echo ""

echo "[1/2] Docker 构建..."
docker build -t "$IMAGE" "$EMBED_DIR"
echo "  构建完成"

echo "[2/2] 推送到 SWR..."
docker push "$IMAGE"
echo ""
echo "=== 完成: $IMAGE ==="
