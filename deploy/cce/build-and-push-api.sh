#!/usr/bin/env bash
#
# 构建 lakeon-api 镜像并推送到华为云 SWR
#
# 用法:
#   ./deploy/cce/build-and-push-api.sh                   # 默认站点 (hwstaff)，自动算 tag
#   SITE=jackylk ./deploy/cce/build-and-push-api.sh      # jackylk 站点
#   IMAGE_TAG=0.9.245 ./deploy/cce/build-and-push-api.sh # 显式覆盖 tag
#
# Tag 自动计算规则（IMAGE_TAG 未设置时）:
#   <semver-base>-<git-short-sha>[-dirty]
# 其中：
#   semver-base  从 sites/$SITE/values.yaml 当前 lakeon-api tag 提取（去掉历史 -sha 后缀）
#   short-sha    git rev-parse --short=8 HEAD
#   -dirty       若工作区有未提交改动则附加，提示构建产物不可追溯
# 这样每次 build 出来的 tag 都唯一不可变，绕开 K8s 节点 imagePullPolicy=IfNotPresent 缓存碰撞。
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
# 加载站点配置获取 SWR_ORG / SITE_VALUES
if [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-flex}"

# 自动计算 IMAGE_TAG（如果未显式指定）
if [ -z "${IMAGE_TAG:-}" ]; then
  if [ -z "${SITE_VALUES:-}" ] || [ ! -f "$SITE_VALUES" ]; then
    echo "ERROR: SITE_VALUES 未解析或不存在 — 检查 site.sh，或显式 IMAGE_TAG=x.y.z" >&2
    exit 1
  fi
  # 从 SITE_VALUES 取 lakeon-api 的当前 tag（仅 api 段，避开其他镜像）
  CURRENT_TAG=$(awk '
    /^api:/        { in_api=1; next }
    in_api && /^[a-z]/ && !/^  / { in_api=0 }
    in_api && /repository:.*lakeon-api/ { found_api=1; next }
    found_api && /tag:/ {
      gsub(/[" ]/, "", $2); print $2; exit
    }
  ' "$SITE_VALUES")
  if [ -z "$CURRENT_TAG" ]; then
    echo "ERROR: 无法从 $SITE_VALUES 提取 lakeon-api tag" >&2
    exit 1
  fi
  # 剥掉历史 -<8hex>[-dirty] 后缀只留 semver base
  BASE=$(echo "$CURRENT_TAG" | sed -E 's/-[0-9a-f]{8,}(-dirty)?$//')
  SHORT_SHA=$(cd "$PROJECT_DIR" && git rev-parse --short=8 HEAD)
  DIRTY=""
  if ! (cd "$PROJECT_DIR" && git diff-index --quiet HEAD --); then
    DIRTY="-dirty"
  fi
  IMAGE_TAG="${BASE}-${SHORT_SHA}${DIRTY}"
  echo "📐 自动计算 IMAGE_TAG: ${IMAGE_TAG}  (base=${BASE} sha=${SHORT_SHA}${DIRTY:+ dirty})"
fi

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

# 4. 写回 SITE_VALUES 让 deploy.sh 自动用新 tag（避免人工同步漏掉）
if [ -n "${SITE_VALUES:-}" ] && [ -f "$SITE_VALUES" ]; then
  # 仅替换 lakeon-api 的 tag 行：先匹配上一行的 lakeon-api repository，再改紧随其后的 tag
  python3 - "$SITE_VALUES" "$IMAGE_TAG" <<'PY'
import sys, re, pathlib
path = pathlib.Path(sys.argv[1])
new_tag = sys.argv[2]
text = path.read_text()
# 匹配 "repository: ...lakeon-api\n    tag: \"x\""，仅改后者
new_text, n = re.subn(
    r'(repository:\s*\S*lakeon-api\s*\n\s*tag:\s*)"[^"]*"',
    rf'\1"{new_tag}"', text, count=1)
if n != 1:
    print(f"WARN: 未在 {path} 找到 lakeon-api tag 行，未写回", file=sys.stderr)
    sys.exit(0)
path.write_text(new_text)
print(f"📝 已写回 {path} -> tag={new_tag}")
PY
fi

echo ""
echo "=== 完成: $IMAGE ==="
