#!/usr/bin/env bash
#
# 构建 lakeon-wiki-agent 镜像并推送到华为云 SWR
#
# 用法:
#   ./deploy/cce/build-and-push-wiki-agent.sh                   # 默认站点 (hwstaff)
#   IMAGE_TAG=0.1.1 ./deploy/cce/build-and-push-wiki-agent.sh
#
# 前置条件:
#   - docker login swr.cn-north-4.myhuaweicloud.com 已完成
#   - Python 3.11+ 已安装
#
set -euo pipefail

export no_proxy="*"
export NO_PROXY="*"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# 加载站点配置获取 SWR_ORG
if [ -z "${SWR_ORG:-}" ] && [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-flex}"
IMAGE_TAG="${IMAGE_TAG:-0.1.0}"
IMAGE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-wiki-agent:${IMAGE_TAG}"

PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
WIKI_AGENT_DIR="$PROJECT_DIR/lakeon-wiki-agent"

echo "=== 构建 lakeon-wiki-agent 并推送到 SWR ==="
echo "镜像: $IMAGE"
echo ""

# 1. Docker 构建
echo "[1/2] Docker 构建..."
docker build --platform linux/amd64 -t "$IMAGE" "$WIKI_AGENT_DIR/"
echo "  构建完成"

# 2. 推送
echo "[2/2] 推送到 SWR..."
docker push "$IMAGE"
echo ""
echo "=== 完成: $IMAGE ==="
echo ""
echo "更新 values-hwstaff.yaml 中的 wikiAgent.image.tag 为 ${IMAGE_TAG}"
