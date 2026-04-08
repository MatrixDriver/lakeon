#!/usr/bin/env bash
#
# 构建 lakeon-api 镜像并推送到华为云 SWR
#
# 用法:
#   ./deploy/cce/build-and-push-api.sh                   # 默认站点 (hwstaff)
#   SITE=jackylk ./deploy/cce/build-and-push-api.sh      # jackylk 站点
#
# 前置条件:
#   - docker login swr.cn-north-4.myhuaweicloud.com 已完成
#   - Java 17 + Maven 已安装
#

set -euo pipefail

# Disable proxy for SWR access
export no_proxy="*"
export NO_PROXY="*"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# 加载站点配置获取 SWR_ORG（如果未手动指定）
if [ -z "${SWR_ORG:-}" ] && [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-flex}"
IMAGE_TAG="${IMAGE_TAG:-0.9.215}"
IMAGE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-api:${IMAGE_TAG}"

PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
API_DIR="$PROJECT_DIR/lakeon-api"

echo "=== 构建 lakeon-api 并推送到 SWR ==="
echo "镜像: $IMAGE"
echo ""

# 1. Maven 编译
echo "[1/3] Maven 编译..."
cd "$API_DIR"
mvn package -Dmaven.test.skip=true -q
JAR=$(ls target/lakeon-api-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
    echo "ERROR: 编译失败，未找到 jar 文件" >&2
    exit 1
fi
echo "  JAR: $JAR"

# 2. Docker 构建（使用预编译 jar，跳过容器内 Maven 下载）
echo "[2/3] Docker 构建..."

# 优先使用本地已有的 JRE 基础镜像，避免从 Docker Hub 拉取超时
BASE_IMAGE="eclipse-temurin:17-jre"
if ! docker image inspect "$BASE_IMAGE" &>/dev/null; then
    echo "  本地无 ${BASE_IMAGE}，尝试从 SWR 拉取..."
    SWR_BASE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/eclipse-temurin:17-jre"
    if docker pull "$SWR_BASE" 2>/dev/null; then
        docker tag "$SWR_BASE" "$BASE_IMAGE"
        echo "  已从 SWR 拉取基础镜像"
    else
        echo "  SWR 上无基础镜像，尝试从 Docker Hub 拉取（可能较慢）..."
        docker pull "$BASE_IMAGE"
    fi
fi

TMPFILE=$(mktemp "$API_DIR/Dockerfile.lakeon-api.XXXXXX")
trap "rm -f $TMPFILE" EXIT
cat > "$TMPFILE" <<DOCKERFILE
FROM ${BASE_IMAGE}
WORKDIR /app
COPY *.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
DOCKERFILE

docker build -t "$IMAGE" -f "$TMPFILE" "$(dirname "$JAR")/"
echo "  构建完成"

# 3. 推送
echo "[3/3] 推送到 SWR..."
docker push "$IMAGE"
echo ""
echo "=== 完成: $IMAGE ==="
