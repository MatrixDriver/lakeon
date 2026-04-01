#!/usr/bin/env bash
#
# CCI Ray Head Pod 启动耗时测试
#
# 在 CCI (virtual-kubelet) 上创建 Ray head pod，分解各阶段耗时：
#   1. Pending（调度 + 镜像拉取）
#   2. ContainerCreating → Running
#   3. Ray head 就绪（ray start --head 完成）
#
# 用法:
#   export KUBECONFIG=~/.kube/cce-lakeon-config
#   ./deploy/cce/test-cci-startup-time.sh [image-key]
#
# image-key: ray (default), python-slim, python-data
#
# 测试完成后自动清理 pod 和 namespace

set -euo pipefail

# ── 配置 ────────────────────────────────────────────────────────────
NS="cci-startup-test"
POD_NAME="ray-head-timing-test"
POLL_INTERVAL=1      # 秒
TIMEOUT=180          # 最大等待秒数

IMAGE_KEY="${1:-ray}"
case "$IMAGE_KEY" in
    ray)         IMAGE="swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data" ;;
    python-slim) IMAGE="swr.cn-north-4.myhuaweicloud.com/lakeon/python:3.11-slim" ;;
    python-data) IMAGE="swr.cn-north-4.myhuaweicloud.com/flex/python:3.11-data" ;;
    *)
        echo "Unknown image key: $IMAGE_KEY"
        echo "Available: ray, python-slim, python-data"
        exit 1
        ;;
esac

# 跨平台秒级时间戳
now_sec() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

echo "=== CCI Pod 启动耗时测试 ==="
echo "镜像: $IMAGE"
echo "命名空间: $NS"
echo ""

# ── 清理函数 ────────────────────────────────────────────────────────
cleanup() {
    echo ""
    echo "--- 清理测试资源 ---"
    kubectl delete pod "$POD_NAME" -n "$NS" --ignore-not-found --wait=false 2>/dev/null || true
    kubectl delete namespace "$NS" --ignore-not-found --wait=false 2>/dev/null || true
    echo "清理完成"
}
trap cleanup EXIT

# ── 创建 namespace ──────────────────────────────────────────────────
echo "[1/5] 创建测试 namespace..."
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# 给 CCI namespace 打标签（华为云 CCI 需要）
kubectl label namespace "$NS" \
    virtual-node-affinity-injection=enabled \
    --overwrite >/dev/null 2>&1 || true

# 创建 SWR 拉取凭证（从 lakeon namespace 复制，并 patch 到 default SA）
if kubectl get secret swr-secret -n lakeon >/dev/null 2>&1; then
    kubectl get secret swr-secret -n lakeon -o json \
        | jq '.metadata.namespace="'"$NS"'" | del(.metadata.resourceVersion,.metadata.uid,.metadata.creationTimestamp)' \
        | kubectl apply -f - >/dev/null 2>&1
    # 等待 default SA 存在
    for i in $(seq 1 10); do
        kubectl get sa default -n "$NS" >/dev/null 2>&1 && break
        sleep 1
    done
    # patch default SA imagePullSecrets（CCI burst namespace 需要）
    kubectl patch sa default -n "$NS" -p '{"imagePullSecrets":[{"name":"swr-secret"}]}' >/dev/null 2>&1 || true
    echo "  SWR secret 已复制到 $NS 并 patch 到 default SA"
fi

# ── 创建 Pod ────────────────────────────────────────────────────────
echo "[2/5] 创建 Ray head pod..."

T_CREATE=$(now_sec)

if [[ "$IMAGE_KEY" == ray* ]]; then
    CMD="ray start --head --port=6379 --dashboard-host=0.0.0.0 --num-cpus=0 && echo RAY_HEAD_READY && sleep infinity"
else
    CMD="echo KERNEL_READY && sleep infinity"
fi

cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: $POD_NAME
  namespace: $NS
spec:
  nodeSelector:
    type: virtual-kubelet
  tolerations:
    - key: virtual-kubelet.io/provider
      operator: Exists
      effect: NoSchedule
  imagePullSecrets:
    - name: swr-secret
  containers:
    - name: repl
      image: $IMAGE
      command: ["bash", "-c", "$CMD"]
      resources:
        requests:
          cpu: "500m"
          memory: "2Gi"
        limits:
          cpu: "2"
          memory: "4Gi"
  restartPolicy: Never
EOF

echo "  Pod 创建请求已提交 (t=0)"
echo ""

# ── 轮询各阶段 ──────────────────────────────────────────────────────
echo "[3/5] 轮询 Pod 状态..."

T_PULLING=""
T_RUNNING=""
T_READY=""
PREV_STATE=""
ELAPSED=0

while (( ELAPSED < TIMEOUT )); do
    # 获取 pod 状态 JSON
    POD_JSON=$(kubectl get pod "$POD_NAME" -n "$NS" -o json 2>/dev/null || echo "")

    if [[ -z "$POD_JSON" ]]; then
        sleep "$POLL_INTERVAL"
        NOW=$(now_sec)
        ELAPSED=$(python3 -c "print(int($NOW - $T_CREATE))")
        continue
    fi

    PHASE=$(echo "$POD_JSON" | jq -r '.status.phase // "Unknown"')
    CONTAINER_STATE=$(echo "$POD_JSON" | jq -r '
        .status.containerStatuses[0].state | keys[0] // "unknown"' 2>/dev/null || echo "unknown")
    REASON=$(echo "$POD_JSON" | jq -r '
        .status.containerStatuses[0].state.waiting.reason // ""' 2>/dev/null || echo "")

    NOW=$(now_sec)
    ELAPSED_S=$(python3 -c "print(f'{$NOW - $T_CREATE:.1f}')")

    # 状态变化检测
    CURRENT_STATE="${PHASE}/${CONTAINER_STATE}/${REASON}"
    if [[ "$CURRENT_STATE" != "$PREV_STATE" ]]; then
        printf "  [%6ss] Phase=%-12s Container=%-12s Reason=%s\n" \
            "$ELAPSED_S" "$PHASE" "$CONTAINER_STATE" "$REASON"
        PREV_STATE="$CURRENT_STATE"
    fi

    # 记录关键时间点
    if [[ -z "$T_PULLING" && "$REASON" == "ContainerCreating" ]]; then
        T_PULLING=$NOW
    fi

    if [[ -z "$T_RUNNING" && "$PHASE" == "Running" ]]; then
        T_RUNNING=$NOW
    fi

    # 检查 Ray 是否就绪
    if [[ -n "$T_RUNNING" && -z "$T_READY" ]]; then
        LOGS=$(kubectl logs "$POD_NAME" -n "$NS" --tail=20 2>/dev/null || echo "")
        if echo "$LOGS" | grep -q "RAY_HEAD_READY\|KERNEL_READY"; then
            T_READY=$(now_sec)
        fi
    fi

    # 完成条件
    if [[ -n "$T_READY" ]]; then
        break
    fi

    # 失败检测
    if [[ "$PHASE" == "Failed" || "$CONTAINER_STATE" == "terminated" ]]; then
        echo ""
        echo "!!! Pod 启动失败 !!!"
        kubectl describe pod "$POD_NAME" -n "$NS" | tail -20
        exit 1
    fi

    sleep "$POLL_INTERVAL"
    NOW=$(now_sec)
    ELAPSED=$(python3 -c "print(int($NOW - $T_CREATE))")
done

# ── 超时检查 ─────────────────────────────────────────────────────────
if [[ -z "$T_READY" ]]; then
    echo ""
    echo "!!! 超时 (${TIMEOUT}s) — Pod 未就绪 !!!"
    echo ""
    echo "--- Pod describe ---"
    kubectl describe pod "$POD_NAME" -n "$NS" | tail -30
    echo ""
    echo "--- Pod logs ---"
    kubectl logs "$POD_NAME" -n "$NS" --tail=30 2>/dev/null || echo "(no logs)"
    exit 1
fi

# ── 输出结果 ──────────────────────────────────────────────────────────
echo ""
echo "[4/5] 获取 Pod Events..."
echo ""
kubectl get events -n "$NS" --sort-by='.lastTimestamp' \
    --field-selector "involvedObject.name=$POD_NAME" 2>/dev/null || true

echo ""
echo "============================================"
echo "  CCI 启动耗时分解 — $IMAGE_KEY"
echo "============================================"

diff_sec() {
    local start=$1 end=$2
    if [[ -n "$start" && -n "$end" ]]; then
        python3 -c "print(f'{$end - $start:.1f}s')"
    else
        echo "N/A"
    fi
}

TOTAL=$(diff_sec "$T_CREATE" "$T_READY")

echo ""
echo "  镜像: $IMAGE"
echo ""
echo "  ┌──────────────────────────────────────────┐"
echo "  │ 阶段                          耗时       │"
echo "  ├──────────────────────────────────────────┤"

if [[ -n "$T_PULLING" ]]; then
    printf "  │ Pending → ContainerCreating   %-10s│\n" "$(diff_sec "$T_CREATE" "$T_PULLING")"
    printf "  │ 镜像拉取 + 容器创建            %-10s│\n" "$(diff_sec "$T_PULLING" "$T_RUNNING")"
else
    printf "  │ Pending → Running              %-10s│\n" "$(diff_sec "$T_CREATE" "$T_RUNNING")"
fi

printf "  │ Running → 应用就绪             %-10s│\n" "$(diff_sec "$T_RUNNING" "$T_READY")"
echo "  ├──────────────────────────────────────────┤"
printf "  │ 总耗时                          %-10s│\n" "$TOTAL"
echo "  └──────────────────────────────────────────┘"
echo ""

echo "[5/5] 测试完成，正在清理..."
