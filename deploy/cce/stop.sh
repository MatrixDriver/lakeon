#!/bin/bash
# Lakeon 极致省钱 - 一键关停
# 关停顺序: K8s 工作负载 → helm 卸载 → 删 ELB → 释放 EIP → 节点缩0 → 停 RDS
# 每天节省: ~¥111 (节点¥72 + RDS¥17 + ELB¥12 + EIP¥10)
#
# 用法: ./deploy/cce/stop.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export KUBECONFIG=${KUBECONFIG:-~/.kube/cce-lakeon-config}

echo "=== Lakeon 极致省钱 - 关停 ==="
echo ""

# 1. Scale K8s workloads to 0 first (graceful)
echo "── 缩容 K8s 工作负载 ──"
for deploy in lakeon-api lakeon-admin lakeon-console proxy pageserver storage-broker; do
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
python3 "$SCRIPT_DIR/hwcloud.py" stop-cloud

echo ""
echo "💤 晚安！所有资源已关停，每天节省 ~¥111"
echo "   启动命令: ./deploy/cce/start.sh"
