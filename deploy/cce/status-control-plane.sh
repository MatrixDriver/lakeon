#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

export KUBECONFIG="$CONTROL_KUBECONFIG"
echo "Control plane: $KUBECONFIG"
kubectl get deploy,svc,hpa -n lakeon
