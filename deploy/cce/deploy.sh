#!/bin/bash
# Lakeon CCE 部署脚本 — 自动加载凭据 + 冒烟测试
#
# 用法:
#   ./deploy/cce/deploy.sh              # helm upgrade + 冒烟测试 (默认 hwstaff 站点)
#   ./deploy/cce/deploy.sh --skip-test  # 仅部署，跳过测试
#   SITE=jackylk ./deploy/cce/deploy.sh # 部署 jackylk 站点

set -e

# Respect caller's proxy settings — some dev environments need a proxy
# to reach the CCE API endpoint over public internet. If you need to
# bypass proxy, set NO_PROXY="*" in your own shell before invoking.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

# 校验必填变量
# LOG_DB_DSN 不在 git，必须从 sites/<SITE>/.env 加载（避免把数据库密码提交到仓库）。
# 历史上 logDbDsn 的明文密码污染过 values.yaml 的 git 历史，所以这里强制要求。
for var in HWCLOUD_AK HWCLOUD_SK RDS_PRIVATE_IP RDS_PASSWORD LOG_DB_DSN; do
  if [ -z "${!var}" ]; then
    echo "❌ 环境变量 $var 未设置，请检查 $SITE_DIR/.env"
    exit 1
  fi
done

# ── 同步开发机 IP 到 CCE master SG 5443 白名单 ──
# 出门换网络后开发机公网 IP 经常变，导致 helm 连不上 CCE。
# 这一步幂等：IP 没变就 no-op，几百毫秒。失败则中止部署，避免之后陷入 helm timeout。
echo "── 同步 CCE master 白名单 ──"
NO_PROXY="*" no_proxy="*" python3 "$SCRIPT_DIR/update-cce-acl.py" || {
  echo "❌ ACL 同步失败 — 中止部署"
  exit 1
}
echo ""

# ── Helm 部署 ──
echo "── Helm 部署 ──"
helm upgrade --install lakeon "$SCRIPT_DIR/../helm/lakeon" \
  -f "$SITE_VALUES" \
  --set obs.accessKey=$HWCLOUD_AK --set obs.secretKey=$HWCLOUD_SK \
  --set metadataDb.host=$RDS_PRIVATE_IP --set metadataDb.password=$RDS_PASSWORD \
  --set api.logDbDsn="$LOG_DB_DSN" \
  ${AI_API_KEY:+--set api.aiApiKey=$AI_API_KEY} \
  --take-ownership \
  --server-side=false \
  -n lakeon --create-namespace --timeout 5m --no-hooks 2>&1

# ── 等待服务就绪 ──
echo ""
echo "── 等待服务就绪 ──"
for sts in safekeeper; do
  kubectl rollout status statefulset/$sts -n lakeon --timeout=180s 2>/dev/null || true
done
for deploy in pageserver storage-broker lakeon-api proxy; do
  kubectl rollout status deployment/$deploy -n lakeon --timeout=180s 2>/dev/null || true
done

# ── 冒烟测试 ──
if [ "$1" != "--skip-test" ]; then
  echo ""
  source "$SCRIPT_DIR/smoke-test.sh"
fi
