#!/bin/bash
# Lakeon 多站点配置加载器
#
# 用法:
#   SITE=jackylk ./deploy/cce/deploy.sh   # 使用 jackylk 站点
#   ./deploy/cce/deploy.sh                # 使用默认站点 (hwstaff)
#
# 每个站点目录 (sites/<name>/) 包含:
#   site.conf    — 非敏感配置 (KUBECONFIG, SWR_ORG 等)
#   values.yaml  — Helm values
#   .env         — 凭据 (gitignored)

SITE="${SITE:-hwstaff}"
_SITE_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SITE_DIR="$_SITE_SH_DIR/sites/$SITE"

if [ ! -d "$SITE_DIR" ]; then
  echo "❌ 站点 '$SITE' 不存在"
  echo "   可用站点: $(ls "$_SITE_SH_DIR/sites/" 2>/dev/null | tr '\n' ' ')"
  exit 1
fi

# 加载站点配置
if [ ! -f "$SITE_DIR/site.conf" ]; then
  echo "❌ $SITE_DIR/site.conf 不存在"
  exit 1
fi
source "$SITE_DIR/site.conf"

# 设置 KUBECONFIG
export KUBECONFIG="${SITE_KUBECONFIG:-$HOME/.kube/cce-lakeon-config}"
export CONTROL_KUBECONFIG="${SITE_CONTROL_KUBECONFIG:-$HOME/.kube/cce-dbay-control-plane-config}"
export DATA_KUBECONFIG="${SITE_DATA_KUBECONFIG:-$SITE_KUBECONFIG}"

# 站点凭据文件
SITE_ENV_FILE="$SITE_DIR/.env"
if [ -f "$SITE_ENV_FILE" ]; then
  source "$SITE_ENV_FILE"
fi

# 站点 Helm values
SITE_VALUES="$SITE_DIR/values.yaml"
SITE_CONTROL_VALUES="$SITE_DIR/values-control-plane.yaml"
SITE_DATA_VALUES="$SITE_DIR/values-data-plane.yaml"

# 导出给 hwcloud.py 等 Python 脚本使用
export LAKEON_SITE_DIR="$SITE_DIR"

echo "📍 站点: $SITE (${SITE_DESCRIPTION:-未知})"
