#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${SWR_ORG:-}" ] && [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

# Build and push lakeon-admin image to SWR
SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-flex}"
IMAGE_TAG="${IMAGE_TAG:-0.1.0}"
IMAGE_NAME="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-admin:${IMAGE_TAG}"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../../lakeon-admin" && pwd)"

echo "Building lakeon-admin image..."
docker build --no-cache -t "${IMAGE_NAME}" "${PROJECT_DIR}"

echo "Pushing to SWR..."
docker push "${IMAGE_NAME}"

echo "Done: ${IMAGE_NAME}"
