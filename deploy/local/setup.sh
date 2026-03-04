#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HELM_CHART="$PROJECT_ROOT/deploy/helm/lakeon"
VALUES_LOCAL="$SCRIPT_DIR/values-local.yaml"
NAMESPACE="lakeon"
COMPUTE_NAMESPACE="lakeon-compute"
RELEASE_NAME="lakeon"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# 1. Check prerequisites
info "Checking prerequisites..."

if ! command -v kubectl &>/dev/null; then
  error "kubectl not found. Please install kubectl."
fi

if ! command -v helm &>/dev/null; then
  error "helm not found. Please install helm."
fi

if ! command -v docker &>/dev/null; then
  error "docker not found. Please install Docker Desktop."
fi

if ! kubectl cluster-info &>/dev/null; then
  error "Cannot connect to Kubernetes cluster. Please enable Kubernetes in Docker Desktop Settings."
fi

CONTEXT=$(kubectl config current-context)
info "Using K8s context: $CONTEXT"

# 2. Build lakeon-api Docker image
info "Building lakeon-api Docker image..."
docker build -t lakeon/lakeon-api:local "$PROJECT_ROOT/lakeon-api"

# 3. Create namespaces
info "Creating namespaces..."
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace "$COMPUTE_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

# 4. Helm install or upgrade
info "Deploying Lakeon via Helm..."
helm upgrade --install "$RELEASE_NAME" "$HELM_CHART" \
  -f "$VALUES_LOCAL" \
  -n "$NAMESPACE" \
  --wait \
  --timeout 5m

# 5. Wait for all pods to be ready
info "Waiting for pods to be ready..."
kubectl wait --for=condition=ready pod -l app=metadata-db -n "$NAMESPACE" --timeout=120s 2>/dev/null || true
kubectl wait --for=condition=ready pod -l app=minio -n "$NAMESPACE" --timeout=120s 2>/dev/null || true
kubectl wait --for=condition=ready pod -l app=pageserver -n "$NAMESPACE" --timeout=120s 2>/dev/null || true
kubectl wait --for=condition=ready pod -l app=safekeeper -n "$NAMESPACE" --timeout=120s 2>/dev/null || true
kubectl wait --for=condition=ready pod -l app=storage-broker -n "$NAMESPACE" --timeout=120s 2>/dev/null || true
kubectl wait --for=condition=ready pod -l app=proxy -n "$NAMESPACE" --timeout=120s 2>/dev/null || true
kubectl wait --for=condition=ready pod -l app=lakeon-api -n "$NAMESPACE" --timeout=180s 2>/dev/null || true

# 6. Print status and access info
echo ""
info "=== Deployment Status ==="
kubectl get pods -n "$NAMESPACE"
echo ""

info "=== Access Info ==="
echo "  API:   kubectl port-forward svc/lakeon-api 8080:8080 -n $NAMESPACE"
echo "  Proxy: kubectl port-forward svc/proxy 4432:4432 -n $NAMESPACE"
echo "  MinIO: kubectl port-forward svc/minio 9000:9000 9001:9001 -n $NAMESPACE"
echo ""
info "Quick start:"
echo "  # Terminal 1: port-forward"
echo "  kubectl port-forward svc/lakeon-api 8080:8080 -n $NAMESPACE &"
echo "  kubectl port-forward svc/proxy 4432:4432 -n $NAMESPACE &"
echo ""
echo "  # Create tenant"
echo "  curl -s -X POST http://localhost:8080/api/v1/tenants \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"name\":\"test-tenant\"}' | jq ."
echo ""
info "Lakeon deployed successfully!"
