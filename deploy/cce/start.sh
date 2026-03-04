#!/bin/bash
# Lakeon 极致省钱 - 一键启动
# 启动顺序: RDS + 节点 → 等待就绪 → 创建 ELB + EIP → Helm 部署 → 等待服务就绪
#
# 用法: ./deploy/cce/start.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export KUBECONFIG=${KUBECONFIG:-~/.kube/cce-lakeon-config}

echo "=== Lakeon 极致省钱 - 启动 ==="
echo ""

# 1. Start cloud resources (RDS + Nodes + ELB + EIP)
# This also waits for RDS and nodes to be ready, creates ELB/EIP,
# and updates values-cce.yaml with new ELB ID and EIP
python3 "$SCRIPT_DIR/hwcloud.py" start-cloud

# 2. Helm deploy
echo ""
echo "── Helm 部署 ──"
source "$SCRIPT_DIR/.env.cce"

helm upgrade --install lakeon "$SCRIPT_DIR/../helm/lakeon" \
  -f "$SCRIPT_DIR/values-cce.yaml" \
  --set obs.accessKey=$OBS_AK --set obs.secretKey=$OBS_SK \
  --set metadataDb.host=$RDS_PRIVATE_IP --set metadataDb.password=$RDS_PASSWORD \
  -n lakeon --create-namespace --timeout 5m --no-hooks 2>&1

echo ""
echo "── 等待服务就绪 ──"

# Wait for key deployments
for deploy in safekeeper; do
  kubectl rollout status statefulset/$deploy -n lakeon --timeout=180s 2>/dev/null || true
done
for deploy in pageserver storage-broker lakeon-api proxy lakeon-console lakeon-admin; do
  kubectl rollout status deployment/$deploy -n lakeon --timeout=180s 2>/dev/null || true
done

# Get the new EIP from values
NEW_EIP=$(grep 'externalHost:' "$SCRIPT_DIR/values-cce.yaml" | head -1 | sed 's/.*"\(.*\)".*/\1/')

echo ""
echo "✅ Lakeon 已完全启动！"
echo ""
echo "访问地址:"
echo "  Web 控制台:  http://${NEW_EIP}"
echo "  SRE 管理台:  http://${NEW_EIP}:8081"
echo "  PG 连接:     postgresql://${NEW_EIP}:4432"
echo ""
echo "⚠ 注意: IP 已变更为 ${NEW_EIP}，请更新客户端连接配置"
