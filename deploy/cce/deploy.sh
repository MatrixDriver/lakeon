#!/bin/bash
# Lakeon CCE 部署脚本 — 自动加载凭据 + 冒烟测试
#
# 用法:
#   ./deploy/cce/deploy.sh              # helm upgrade + 冒烟测试
#   ./deploy/cce/deploy.sh --skip-test  # 仅部署，跳过测试

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export KUBECONFIG=${KUBECONFIG:-~/.kube/cce-lakeon-config}

# ── 加载凭据 ──
ENV_FILE="$SCRIPT_DIR/.env.cce"
if [ ! -f "$ENV_FILE" ]; then
  echo "❌ 未找到 $ENV_FILE，请先创建凭据文件"
  exit 1
fi
source "$ENV_FILE"

# 校验必填变量
for var in OBS_AK OBS_SK RDS_PRIVATE_IP RDS_PASSWORD; do
  if [ -z "${!var}" ]; then
    echo "❌ 环境变量 $var 未设置，请检查 $ENV_FILE"
    exit 1
  fi
done

# ── Helm 部署 ──
echo "── Helm 部署 ──"
helm upgrade --install lakeon "$SCRIPT_DIR/../helm/lakeon" \
  -f "$SCRIPT_DIR/values-cce.yaml" \
  --set obs.accessKey=$OBS_AK --set obs.secretKey=$OBS_SK \
  --set metadataDb.host=$RDS_PRIVATE_IP --set metadataDb.password=$RDS_PASSWORD \
  -n lakeon --create-namespace --timeout 5m --no-hooks 2>&1

# ── 等待服务就绪 ──
echo ""
echo "── 等待服务就绪 ──"
for sts in safekeeper; do
  kubectl rollout status statefulset/$sts -n lakeon --timeout=180s 2>/dev/null || true
done
for deploy in pageserver storage-broker lakeon-api proxy; do
  kubectl rollout status deployment/$deploy -n lakeon --timeout=180s 2>/dev/null || true
done

# ── 冒烟测试 ──
if [ "$1" != "--skip-test" ]; then
  echo ""
  source "$SCRIPT_DIR/smoke-test.sh"
fi
