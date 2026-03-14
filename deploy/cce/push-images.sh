#!/usr/bin/env bash
#
# 推送 Lakeon 所需镜像到华为云 SWR
#
# 前置条件:
#   1. 在 SWR 控制台创建组织（如 lakeon）
#   2. 登录 SWR:
#      docker login -u cn-north-4@<AK> -p <login-key> swr.cn-north-4.myhuaweicloud.com
#
# 用法:
#   SWR_ORG=lakeon ./deploy/cce/push-images.sh
#
# 环境变量:
#   SWR_ORG       — SWR 组织名（必需）
#   SWR_REGION    — SWR region（默认 cn-north-4）

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${SWR_ORG:-}" ] && [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_REGISTRY="swr.${SWR_REGION}.myhuaweicloud.com"

if [[ -z "${SWR_ORG:-}" ]]; then
    echo "ERROR: SWR_ORG is required. Usage: SWR_ORG=lakeon $0 或 SITE=jackylk $0" >&2
    exit 1
fi

SWR_PREFIX="${SWR_REGISTRY}/${SWR_ORG}"

# Source → Target image pairs (src dst src dst ...)
IMAGES=(
    "ghcr.io/neondatabase/neon:latest"              "${SWR_PREFIX}/neon:latest"
    "ghcr.io/neondatabase/compute-node-v17:latest"  "${SWR_PREFIX}/compute-node-v17:latest"
    "busybox:1.36"                                  "${SWR_PREFIX}/busybox:1.36"
    "lakeon/lakeon-api:0.1.0"                       "${SWR_PREFIX}/lakeon-api:0.1.0"
)

echo "═══════════════════════════════════════════════════════════════"
echo "  Push images to SWR: ${SWR_PREFIX}"
echo "═══════════════════════════════════════════════════════════════"
echo ""

for (( i=0; i<${#IMAGES[@]}; i+=2 )); do
    src="${IMAGES[$i]}"
    dst="${IMAGES[$((i+1))]}"
    echo "── ${src}"
    echo "   → ${dst}"

    docker pull "$src"
    docker tag "$src" "$dst"
    docker push "$dst"

    echo "   ✓ Done"
    echo ""
done

echo "All images pushed to SWR."
