#!/bin/bash
# Deploy DBay Neon data-plane resources to the data-plane CCE.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

for var in HWCLOUD_AK HWCLOUD_SK RDS_PRIVATE_IP RDS_PASSWORD LOG_DB_DSN; do
  if [ -z "${!var:-}" ]; then
    echo "ERROR: environment variable $var is not set; check $SITE_DIR/.env"
    exit 1
  fi
done

if [ -z "${CONTROL_PLANE_PROXY_AUTH_ENDPOINT:-}" ]; then
  echo "ERROR: CONTROL_PLANE_PROXY_AUTH_ENDPOINT is required, for example:"
  echo "  CONTROL_PLANE_PROXY_AUTH_ENDPOINT=http://<control-plane-private-elb>:8088/proxy"
  exit 1
fi

export KUBECONFIG="$DATA_KUBECONFIG"

echo "Deploying data plane to $KUBECONFIG"
helm upgrade --install lakeon-data "$SCRIPT_DIR/../helm/lakeon" \
  -f "$SITE_VALUES" \
  -f "$SITE_DATA_VALUES" \
  --set obs.accessKey="$HWCLOUD_AK" \
  --set obs.secretKey="$HWCLOUD_SK" \
  --set metadataDb.host="$RDS_PRIVATE_IP" \
  --set metadataDb.password="$RDS_PASSWORD" \
  --set-string api.logDbDsn="$LOG_DB_DSN" \
  --set-string proxy.authEndpoint="$CONTROL_PLANE_PROXY_AUTH_ENDPOINT" \
  ${AI_API_KEY:+--set} ${AI_API_KEY:+api.aiApiKey=$AI_API_KEY} \
  ${DATA_PLANE_PAGESERVER_ELB_ID:+--set} ${DATA_PLANE_PAGESERVER_ELB_ID:+pageserver.elb.id=$DATA_PLANE_PAGESERVER_ELB_ID} \
  ${DATA_PLANE_PAGESERVER_ELB_CLASS:+--set} ${DATA_PLANE_PAGESERVER_ELB_CLASS:+pageserver.elb.class=$DATA_PLANE_PAGESERVER_ELB_CLASS} \
  ${DATA_PLANE_PAGESERVER_SERVICE_TYPE:+--set} ${DATA_PLANE_PAGESERVER_SERVICE_TYPE:+pageserver.serviceType=$DATA_PLANE_PAGESERVER_SERVICE_TYPE} \
  --take-ownership \
  --server-side=false \
  -n lakeon --create-namespace --timeout 5m --no-hooks

kubectl rollout status deployment/pageserver -n lakeon --timeout=180s
kubectl rollout status statefulset/safekeeper -n lakeon --timeout=180s
kubectl rollout status deployment/storage-broker -n lakeon --timeout=180s
kubectl rollout status deployment/proxy -n lakeon --timeout=180s

echo "Data plane deployed."
