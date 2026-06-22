#!/bin/bash
# Deploy DBay control-plane resources to the control-plane CCE.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

for var in HWCLOUD_AK HWCLOUD_SK RDS_PRIVATE_IP RDS_PASSWORD LOG_DB_DSN COMPUTE_JWT_PRIVATE_KEY COMPUTE_JWT_PUBLIC_JWK DATA_PLANE_PAGESERVER_URL; do
  if [ -z "${!var:-}" ]; then
    echo "ERROR: environment variable $var is not set; check $SITE_DIR/.env or export it before running"
    exit 1
  fi
done

DATA_PLANE_KUBE_API_SERVER="${DATA_PLANE_KUBE_API_SERVER:-}"
DATA_PLANE_KUBE_CA_B64="${DATA_PLANE_KUBE_CA_B64:-}"
DATA_PLANE_KUBE_TOKEN="${DATA_PLANE_KUBE_TOKEN:-}"
DATA_PLANE_CCE_CLUSTER_NAME="${DATA_PLANE_CCE_CLUSTER_NAME:-dbay-cce}"

is_private_kube_api_server() {
  python3 - "$1" <<'PY'
import ipaddress
import socket
import sys
from urllib.parse import urlparse

host = urlparse(sys.argv[1]).hostname
if not host:
    sys.exit(1)
try:
    ip = ipaddress.ip_address(host)
except ValueError:
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError:
        sys.exit(1)
    for info in infos:
        try:
            if ipaddress.ip_address(info[4][0]).is_private:
                sys.exit(0)
        except ValueError:
            pass
    sys.exit(1)
sys.exit(0 if ip.is_private else 1)
PY
}

derive_data_plane_internal_endpoint() {
  LAKEON_SITE_DIR="$SITE_DIR" SCRIPT_DIR="$SCRIPT_DIR" DATA_PLANE_CCE_CLUSTER_NAME="$DATA_PLANE_CCE_CLUSTER_NAME" python3 - <<'PY'
import importlib.util
import os
import sys

script_dir = os.environ["SCRIPT_DIR"]
hwcloud_path = os.path.join(script_dir, "hwcloud.py")
spec = importlib.util.spec_from_file_location("hwcloud", hwcloud_path)
hwcloud = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hwcloud)

target_name = os.environ.get("DATA_PLANE_CCE_CLUSTER_NAME", "dbay-cce")
ak, sk, _ = hwcloud.load_credentials()
project_id = hwcloud.get_project_id(ak, sk)
status, clusters = hwcloud.api(
    "GET",
    f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters",
    ak,
    sk,
)
if status != 200:
    raise SystemExit(f"list CCE clusters failed: {status} {clusters}")

cluster_id = None
for cluster in clusters.get("items", []):
    metadata = cluster.get("metadata", {})
    if metadata.get("name") == target_name:
        cluster_id = metadata.get("uid")
        break
if not cluster_id:
    raise SystemExit(f"data-plane CCE cluster not found: {target_name}")

status, detail = hwcloud.api(
    "GET",
    f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters/{cluster_id}",
    ak,
    sk,
)
if status != 200:
    raise SystemExit(f"get data-plane CCE cluster failed: {status} {detail}")

for endpoint in detail.get("status", {}).get("endpoints", []):
    if endpoint.get("type") == "Internal" and endpoint.get("url"):
        print(endpoint["url"])
        break
else:
    raise SystemExit(f"data-plane CCE cluster {target_name} has no Internal endpoint")
PY
}

if [ -z "$DATA_PLANE_KUBE_API_SERVER" ]; then
  DATA_PLANE_KUBE_API_SERVER="$(derive_data_plane_internal_endpoint)"
fi

if ! is_private_kube_api_server "$DATA_PLANE_KUBE_API_SERVER"; then
  echo "ERROR: DATA_PLANE_KUBE_API_SERVER must be a private/VPCEP endpoint, got: $DATA_PLANE_KUBE_API_SERVER" >&2
  echo "       Refusing to deploy a control-plane that calls the data-plane kube-apiserver public management endpoint." >&2
  exit 1
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
