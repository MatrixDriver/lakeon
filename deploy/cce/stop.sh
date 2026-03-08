#!/bin/bash
# Lakeon 极致省钱 - 一键关停
#
# 两种模式:
#   ./deploy/cce/stop.sh          # 关停 ECS+RDS，保留 CCE+ELB+EIP（省 ~¥65/天）
#   ./deploy/cce/stop.sh --full   # 关停 ECS+RDS+删 ELB+释放 EIP（省 ~¥89/天）

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export KUBECONFIG=${KUBECONFIG:-~/.kube/cce-lakeon-config}
FULL_FLAG=""
[[ "$1" == "--full" ]] && FULL_FLAG="--full"

echo "=== Lakeon 极致省钱 - 关停 ==="
[[ -n "$FULL_FLAG" ]] && echo "模式: 极致省钱（删除一切）" || echo "模式: 保留集群 EIP"
echo ""

# 1. Scale K8s workloads to 0 first (graceful)
echo "── 缩容 K8s 工作负载 ──"
for deploy in lakeon-api proxy pageserver storage-broker; do
  kubectl scale deployment/$deploy --replicas=0 -n lakeon 2>/dev/null && echo "  ✓ $deploy → 0" || true
done
kubectl scale statefulset/safekeeper --replicas=0 -n lakeon 2>/dev/null && echo "  ✓ safekeeper → 0" || true
kubectl delete pods --all -n lakeon-compute --ignore-not-found 2>/dev/null || true

echo "  等待 Pod 终止..."
kubectl wait --for=delete pod --all -n lakeon --timeout=60s 2>/dev/null || true

# 2. Helm uninstall (removes Services → CCE cleans ELB listeners)
echo ""
echo "── 卸载 Helm release ──"
helm uninstall lakeon -n lakeon 2>/dev/null && echo "  ✓ helm uninstall 完成" || echo "  - 无 release 可卸载"

# Wait for CCE to clean up ELB listeners
sleep 5

# 3. Cloud resources teardown (ELB → EIP → Nodes → RDS)
echo ""
python3 "$SCRIPT_DIR/hwcloud.py" stop-cloud $FULL_FLAG

echo ""
if [[ -n "$FULL_FLAG" ]]; then
  echo "💤 晚安！所有资源已关停，每天节省 ~¥89"
  echo "   ⚠ 启动前需先在华为云控制台为 CCE 集群绑定 EIP，再更新 kubeconfig"
else
  echo "💤 晚安！所有资源已关停，每天节省 ~¥65（仍计费 CCE+ELB+EIP ≈ ¥24/天）"
fi
echo "   启动命令: ./deploy/cce/start.sh"
