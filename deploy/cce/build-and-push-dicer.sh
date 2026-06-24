#!/usr/bin/env bash
set -euo pipefail

SITE="${SITE:-hwstaff}"
REGISTRY="${REGISTRY:-swr.cn-north-4.myhuaweicloud.com/flex}"
DICER_REPO="${DICER_REPO:-https://github.com/databricks/dicer.git}"
DICER_COMMIT="${DICER_COMMIT:-5cce7985352c51c00890ca1bdb2c3667a3102569}"
IMAGE_TAG="${IMAGE_TAG:-${DICER_COMMIT:0:8}}"
WORKDIR="${WORKDIR:-/tmp/lakeon-dicer-build}"
IMAGE="${REGISTRY}/dicer-assigner:${IMAGE_TAG}"
DICER_BASE_IMAGE="${DICER_BASE_IMAGE:-${REGISTRY}/lakeon-api:d3794cc1}"
if command -v bazelisk >/dev/null 2>&1; then
  BAZEL=(bazelisk)
elif command -v npx >/dev/null 2>&1; then
  BAZEL=(npx --yes @bazel/bazelisk)
else
  echo "ERROR: bazelisk is required to build Dicer (Bazel $(cat .bazelversion 2>/dev/null || echo 8.x)). Install bazelisk or npm/npx." >&2
  exit 1
fi

echo "Building Dicer assigner for site=${SITE}, image=${IMAGE}"

rm -rf "${WORKDIR}"
git clone "${DICER_REPO}" "${WORKDIR}"
cd "${WORKDIR}"
git checkout "${DICER_COMMIT}"

mkdir -p dicer/external/config/dev dicer/external/config/staging dicer/external/config/prod
mkdir -p dicer/assigner/advanced_config/dev dicer/assigner/advanced_config/staging dicer/assigner/advanced_config/prod

for env in dev staging prod; do
  cat > "dicer/external/config/${env}/lakeon-pageserver.textproto" <<'CONFIG'
# proto-file: dicer/external/proto/target.proto
# proto-message: TargetConfigP

owner_team_name: "lakeon"

default_config {
  primary_rate_metric_config {
    max_load_hint: 10000
    imbalance_tolerance_hint: DEFAULT
  }
}
CONFIG

  cat > "dicer/assigner/advanced_config/${env}/lakeon-pageserver.textproto" <<'CONFIG'
# proto-file: dicer/assigner/config/proto/advanced_target_config.proto
# proto-message: AdvancedTargetConfigP

default_config {
}
CONFIG
done

"${BAZEL[@]}" build //dicer/demo:assigner_main_deploy.jar

mkdir -p dicer/demo
cp bazel-bin/dicer/demo/assigner_main_deploy.jar dicer/demo/assigner.jar

DICER_CHUNK_MB="${DICER_CHUNK_MB:-5}"
ASSIGNER_PARTS="dicer/demo/assigner-parts"
rm -rf "$ASSIGNER_PARTS"
mkdir -p "$ASSIGNER_PARTS"
split -b "${DICER_CHUNK_MB}m" -d -a 3 dicer/demo/assigner.jar "$ASSIGNER_PARTS/part-"

cat > Dockerfile.lakeon-dicer <<CONFIG
FROM ${DICER_BASE_IMAGE}

COPY dicer/external/config/prod/lakeon-pageserver.textproto /app/config/target_config/lakeon-pageserver.textproto
COPY dicer/assigner/advanced_config/prod/lakeon-pageserver.textproto /app/config/advanced_config/lakeon-pageserver.textproto
CONFIG
for part in "$ASSIGNER_PARTS"/part-*; do
  if [[ -f "$part" ]]; then
    echo "COPY dicer/demo/assigner-parts/$(basename "$part") /app/assigner-parts/$(basename "$part")" >> Dockerfile.lakeon-dicer
  fi
done
cat >> Dockerfile.lakeon-dicer <<CONFIG

EXPOSE 24500 7777
ENTRYPOINT ["sh", "-c", "cat /app/assigner-parts/part-* > /tmp/assigner.jar && exec java -jar /tmp/assigner.jar"]
CONFIG

docker build -f Dockerfile.lakeon-dicer -t "${IMAGE}" .
docker push "${IMAGE}"

echo "Pushed ${IMAGE}"
