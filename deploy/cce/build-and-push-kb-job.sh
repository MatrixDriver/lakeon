#!/usr/bin/env bash
#
# 构建 lakeon-knowledge-job 镜像并推送到华为云 SWR
#
# 用法:
#   ./deploy/cce/build-and-push-kb-job.sh                   # 默认站点 (hwstaff)
#   IMAGE_TAG=0.2.5 ./deploy/cce/build-and-push-kb-job.sh   # 指定版本号
#   SITE=jackylk ./deploy/cce/build-and-push-kb-job.sh      # jackylk 站点
#
# 前置条件:
#   - docker login swr.cn-north-4.myhuaweicloud.com 已完成
#

set -euo pipefail

# Disable proxy for SWR access
export no_proxy="*"
export NO_PROXY="*"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${SWR_ORG:-}" ] && [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-flex}"
IMAGE_TAG="${IMAGE_TAG:-0.2.4}"
IMAGE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-knowledge-job:${IMAGE_TAG}"
JOB_DIR="$(cd "$SCRIPT_DIR/../../knowledge/job" && pwd)"

echo "=== 构建 lakeon-knowledge-job 并推送到 SWR ==="
echo "镜像: $IMAGE"
echo ""

# 1. Docker 构建
echo "[1/2] Docker 构建..."
docker build -t "$IMAGE" "$JOB_DIR"
echo "  构建完成"

# 2. 推送
echo "[2/2] 推送到 SWR..."
docker push "$IMAGE"
echo ""
echo "=== 完成: $IMAGE ==="
echo ""
echo "更新 values.yaml:"
echo "  knowledgeJobImage: \"$IMAGE\""
