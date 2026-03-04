#!/usr/bin/env bash
#
# 构建 lakeon-console 镜像并推送到华为云 SWR
#
# 用法:
#   ./deploy/cce/build-and-push-console.sh
#
# 前置条件:
#   - docker login swr.cn-north-4.myhuaweicloud.com 已完成
#   - Node.js 20+ 已安装（Docker 内构建，本地无需）
#

set -euo pipefail

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-lakeon}"
IMAGE_TAG="${IMAGE_TAG:-0.1.0}"
IMAGE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-console:${IMAGE_TAG}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONSOLE_DIR="$(cd "$SCRIPT_DIR/../../lakeon-console" && pwd)"

echo "=== 构建 lakeon-console 并推送到 SWR ==="
echo "镜像: $IMAGE"
echo ""

# 1. Docker 构建（多阶段：Node 编译 + Nginx 运行）
echo "[1/2] Docker 构建..."
docker build -t "$IMAGE" "$CONSOLE_DIR"
echo "  构建完成"

# 2. 推送
echo "[2/2] 推送到 SWR..."
docker push "$IMAGE"
echo ""
echo "=== 完成: $IMAGE ==="
echo ""
echo "部署到 CCE:"
echo "  helm upgrade --install lakeon deploy/helm/lakeon \\"
echo "    -f deploy/cce/values-cce.yaml \\"
echo "    --set obs.accessKey=\$OBS_AK --set obs.secretKey=\$OBS_SK \\"
echo "    --set metadataDb.host=\$RDS_PRIVATE_IP --set metadataDb.password=\$RDS_PASSWORD \\"
echo "    -n lakeon --timeout 5m --no-hooks"
echo ""
echo "访问地址: http://116.63.13.156:30080"
