#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

export KUBECONFIG="$DATA_KUBECONFIG"
echo "Data plane: $KUBECONFIG"
kubectl get deploy,sts,svc,hpa -n lakeon
kubectl get pods -n lakeon-compute 2>/dev/null || true
