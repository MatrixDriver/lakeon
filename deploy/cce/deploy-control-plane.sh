#!/bin/bash
# Deploy DBay control-plane resources to the control-plane CCE.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

for var in HWCLOUD_AK HWCLOUD_SK RDS_PRIVATE_IP RDS_PASSWORD LOG_DB_DSN CONNECTOR_SECRET_KEY COMPUTE_JWT_PRIVATE_KEY COMPUTE_JWT_PUBLIC_JWK DATA_PLANE_PAGESERVER_URL; do
  if [ -z "${!var:-}" ]; then
    echo "ERROR: environment variable $var is not set; check $SITE_DIR/.env or export it before running"
    exit 1
  fi
done

DATA_PLANE_KUBE_API_SERVER="${DATA_PLANE_KUBE_API_SERVER:-}"
DATA_PLANE_KUBE_CA_B64="${DATA_PLANE_KUBE_CA_B64:-}"
DATA_PLANE_KUBE_TOKEN="${DATA_PLANE_KUBE_TOKEN:-}"

if [ -z "$DATA_PLANE_KUBE_API_SERVER" ]; then
  DATA_PLANE_KUBE_API_SERVER="$(KUBECONFIG="$DATA_KUBECONFIG" kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.server}')"
fi

if [ -z "$DATA_PLANE_KUBE_CA_B64" ]; then
  DATA_PLANE_KUBE_CA_B64="$(KUBECONFIG="$DATA_KUBECONFIG" kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')"
fi

if [ -z "$DATA_PLANE_KUBE_TOKEN" ]; then
  DATA_PLANE_KUBE_TOKEN="$(KUBECONFIG="$DATA_KUBECONFIG" kubectl -n lakeon create token lakeon-api --duration=8760h)"
fi

export KUBECONFIG="$CONTROL_KUBECONFIG"

echo "Deploying control plane to $KUBECONFIG"
helm upgrade --install lakeon-control "$SCRIPT_DIR/../helm/lakeon" \
  -f "$SITE_VALUES" \
  -f "$SITE_CONTROL_VALUES" \
  --set obs.accessKey="$HWCLOUD_AK" \
  --set obs.secretKey="$HWCLOUD_SK" \
  --set metadataDb.host="$RDS_PRIVATE_IP" \
  --set metadataDb.password="$RDS_PASSWORD" \
  --set-string api.logDbDsn="$LOG_DB_DSN" \
  --set-string api.connectorSecretKey="$CONNECTOR_SECRET_KEY" \
  --set-string dataPlane.pageserverUrl="$DATA_PLANE_PAGESERVER_URL" \
  --set-string dataPlane.kubeApiServer="$DATA_PLANE_KUBE_API_SERVER" \
  --set-string dataPlane.kubeToken="$DATA_PLANE_KUBE_TOKEN" \
  --set-string dataPlane.kubeCaB64="$DATA_PLANE_KUBE_CA_B64" \
  --set-file computeJwt.privateKey=<(printf '%s' "$COMPUTE_JWT_PRIVATE_KEY") \
  --set-file computeJwt.publicJwk=<(printf '%s' "$COMPUTE_JWT_PUBLIC_JWK") \
  ${COMPUTE_JWT_KID:+--set} ${COMPUTE_JWT_KID:+computeJwt.kid=$COMPUTE_JWT_KID} \
  ${AI_API_KEY:+--set} ${AI_API_KEY:+api.aiApiKey=$AI_API_KEY} \
  --take-ownership \
  --server-side=false \
  -n lakeon --create-namespace --timeout 5m --no-hooks

for deploy in lakeon-api serving-api admin-api; do
  kubectl rollout status "deployment/$deploy" -n lakeon --timeout=180s
done

echo "Control plane deployed."
