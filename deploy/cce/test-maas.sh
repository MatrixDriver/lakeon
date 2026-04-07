#!/bin/bash
# Test Huawei MaaS DeepSeek API from CCE cluster
# Usage: MAAS_API_KEY=xxx bash deploy/cce/test-maas.sh
#
# This runs a one-off pod that curls the MaaS API and exits.
# Does NOT affect any existing deployments.

set -euo pipefail

KUBECONFIG="${KUBECONFIG:-$HOME/.kube/cce-lakeon-config}"
NAMESPACE="lakeon"
MAAS_API_KEY="${MAAS_API_KEY:?Please set MAAS_API_KEY}"

# Models to test
MODELS=("deepseek-v3" "deepseek-v3.2")
ENDPOINT="https://api.modelarts-maas.com/v1/chat/completions"

echo "=== Huawei MaaS DeepSeek API Test (from CCE) ==="
echo "Endpoint: $ENDPOINT"
echo ""

for MODEL in "${MODELS[@]}"; do
  POD_NAME="maas-test-$(echo $MODEL | tr '.' '-')-$(date +%s)"
  echo "--- Testing model: $MODEL (pod: $POD_NAME) ---"

  kubectl --kubeconfig="$KUBECONFIG" -n "$NAMESPACE" run "$POD_NAME" \
    --image="swr.cn-north-4.myhuaweicloud.com/flex/busybox:1.36" \
    --restart=Never \
    --rm -i \
    --overrides='{
      "spec": {
        "hostNetwork": true,
        "containers": [{
          "name": "test",
          "image": "swr.cn-north-4.myhuaweicloud.com/flex/busybox:1.36",
          "command": ["wget", "-qO-", "--header=Content-Type: application/json", "--header=Authorization: Bearer '"$MAAS_API_KEY"'", "--post-data={\"model\":\"'"$MODEL"'\",\"messages\":[{\"role\":\"user\",\"content\":\"say hello in 10 words\"}],\"max_tokens\":50}", "'"$ENDPOINT"'"],
          "resources": {"requests": {"cpu": "50m", "memory": "32Mi"}, "limits": {"cpu": "100m", "memory": "64Mi"}}
        }],
        "tolerations": [{"operator": "Exists"}]
      }
    }' \
    --timeout=30s 2>&1 || echo "(pod may have timed out or failed)"

  echo ""
done

echo "=== Test complete ==="
