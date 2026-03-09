#!/bin/bash
# Lakeon 状态总览 — 云资源 + K8s 资源用量
#
# 用法: ./deploy/cce/status.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export KUBECONFIG=${KUBECONFIG:-~/.kube/cce-lakeon-config}

echo "=== Lakeon 状态总览 ==="

# 1. 云资源状态
echo ""
python3 "$SCRIPT_DIR/hwcloud.py" status

# 2. 节点资源用量
echo ""
echo "── 节点资源用量 ──"
kubectl top nodes 2>/dev/null || echo "  ⚠ metrics-server 不可用"

# 3. Pod 资源用量
echo ""
echo "── Pod 资源用量 (lakeon) ──"
kubectl top pods -n lakeon 2>/dev/null || echo "  ⚠ metrics-server 不可用"

# 4. Compute pods
COMPUTE_PODS=$(kubectl get pods -n lakeon-compute --no-headers 2>/dev/null | wc -l | tr -d ' ')
if [ "$COMPUTE_PODS" -gt 0 ]; then
  echo ""
  echo "── Compute 节点 (lakeon-compute) ──"
  kubectl get pods -n lakeon-compute -o wide 2>/dev/null
  kubectl top pods -n lakeon-compute 2>/dev/null || true
fi

# 5. Pod 状态概览
echo ""
echo "── Pod 状态 ──"
kubectl get pods -n lakeon -o wide 2>/dev/null

# 6. 冒烟测试
echo ""
source "$SCRIPT_DIR/smoke-test.sh"
