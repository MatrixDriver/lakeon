#!/bin/bash
# Lakeon 极致省钱 - 一键启动
# 启动顺序: ECS开机 + RDS启动 → 等待就绪 → 创建 ELB+EIP → 删旧 Service → Helm 部署
#
# 用法:
#   ./deploy/cce/start.sh                  # 启动默认站点 (hwstaff)
#   SITE=jackylk ./deploy/cce/start.sh     # 启动 jackylk 站点

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

echo "=== Lakeon 极致省钱 - 启动 ==="
echo ""

# 1. Start cloud resources (RDS + Nodes + ELB + EIP)
python3 "$SCRIPT_DIR/hwcloud.py" start-cloud

# 1b. Generate cloud resources JSON for admin console
echo ""
echo "── 生成云资源清单 ──"
python3 "$SCRIPT_DIR/hwcloud.py" list-resources > /tmp/cloud-resources.json 2>/dev/null \
  && echo "  ✓ 已生成 /tmp/cloud-resources.json" \
  || echo "  ⚠ 生成失败，跳过"
if [ -f /tmp/cloud-resources.json ]; then
  kubectl create configmap lakeon-cloud-resources \
    --from-file=resources.json=/tmp/cloud-resources.json \
    -n lakeon --dry-run=client -o yaml | kubectl apply -f -
  echo "  ✓ ConfigMap lakeon-cloud-resources 已更新"
fi

# 2. Delete old LoadBalancer services (CCE forbids modifying elb.id annotation)
echo ""
echo "── 清理旧 Service ──"
for svc in proxy; do
  kubectl delete svc $svc -n lakeon 2>/dev/null && echo "  ✓ deleted $svc" || true
done

# 3. Helm deploy
echo ""
echo "── Helm 部署 ──"

helm upgrade --install lakeon "$SCRIPT_DIR/../helm/lakeon" \
  -f "$SITE_VALUES" \
  --set obs.accessKey=$HWCLOUD_AK --set obs.secretKey=$HWCLOUD_SK \
  --set metadataDb.host=$RDS_PRIVATE_IP --set metadataDb.password=$RDS_PASSWORD \
  -n lakeon --create-namespace --timeout 5m --no-hooks 2>&1

echo ""
echo "── 等待服务就绪 ──"

for deploy in safekeeper; do
  kubectl rollout status statefulset/$deploy -n lakeon --timeout=180s 2>/dev/null || true
done
for deploy in pageserver storage-broker lakeon-api proxy; do
  kubectl rollout status deployment/$deploy -n lakeon --timeout=180s 2>/dev/null || true
done

# 4. 冒烟测试
echo ""
source "$SCRIPT_DIR/smoke-test.sh"

# Get the new EIP from values
NEW_EIP=$(grep 'externalHost:' "$SITE_VALUES" | head -1 | sed 's/.*"\(.*\)".*/\1/')

echo ""
echo "✅ Lakeon 已完全启动！"
echo ""
echo "访问地址:"
echo "  API (HTTPS): https://api.dbay.cloud:8443"
echo "  PG 连接:     postgresql://${NEW_EIP}:4432"
echo "  Web 控制台:  https://dbay.cloud (Railway)"
echo "  SRE 管理台:  https://admin.dbay.cloud (Railway)"
