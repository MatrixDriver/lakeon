#!/usr/bin/env bash
# takeover-cc.sh — migrate selected ~/.claude paths into ~/.dbay/mnt/claude and
# symlink them back. Idempotent. Safe on dry-run. Creates a pre-takeover tarball.
#
# Usage:
#   ./takeover-cc.sh --dry-run [paths...]
#   ./takeover-cc.sh tasks plans
#   ./takeover-cc.sh --all
#   ./takeover-cc.sh --list
set -euo pipefail

: "${CC_DIR:=$HOME/.claude}"
: "${MNT_DIR:=$HOME/.dbay/mnt/claude}"
: "${STATE_DIR:=$HOME/.dbay/state/claude}"
: "${BACKUP_DIR:=$HOME/.dbay/takeover-backups}"
: "${SKIP_PREFLIGHT:=0}"

# Candidate paths (dir or file). Already-taken-over items skipped automatically.
ALL_PATHS=(
  tasks plans sessions skills commands hooks
  history.jsonl settings.json statusline-command.sh
  plugins/config.json
  projects
)

DRY=0
SELECTED=()

die() { echo "❌ $*" >&2; exit 1; }
info() { echo "→ $*"; }
ok() { echo "✓ $*"; }

usage() {
  cat <<EOF
takeover-cc.sh — 把 CC 目录接管到 DBay FUSE

Usage:
  $0 --list                 列出候选路径 + 当前状态
  $0 --dry-run [paths...]   不动手，只打印计划
  $0 tasks                  接管一个（推荐先试水）
  $0 --all                  接管全部候选
  $0 [paths...]             接管指定路径
EOF
}

[[ $# -eq 0 ]] && { usage; exit 1; }

case "$1" in
  -h|--help) usage; exit 0;;
  --list)
    for p in "${ALL_PATHS[@]}"; do
      full="$CC_DIR/$p"
      if [[ -L "$full" ]]; then echo "  [✓ taken] $p → $(readlink "$full")"
      elif [[ -e "$full" ]]; then echo "  [  local] $p"
      else echo "  [    n/a] $p"; fi
    done
    exit 0;;
  --dry-run) DRY=1; shift;;
esac

if [[ "${1:-}" == "--all" ]]; then
  SELECTED=("${ALL_PATHS[@]}")
else
  SELECTED=("$@")
fi
[[ ${#SELECTED[@]} -eq 0 ]] && die "no paths specified (use --all or list paths)"

# ── preflight ────────────────────────────────────────────────────────────
info "preflight checks"
if [[ $SKIP_PREFLIGHT -eq 0 ]]; then
  if pgrep -if "claude" | grep -vi "$(basename "$0")" | grep -v "chrome" | grep -q .; then
    ps aux | grep -iE "[c]laude" | head -5
    die "Claude Code / claude CLI seems to be running — quit it first"
  fi
  mount | grep -q "on $MNT_DIR " || die "FUSE not mounted at $MNT_DIR (run dbay-fuse first)"
fi
[[ -d "$STATE_DIR" ]] || die "state dir missing: $STATE_DIR"

mkdir -p "$BACKUP_DIR"
TS=$(date +%Y%m%d-%H%M%S)
TARBALL="$BACKUP_DIR/claude-pre-takeover-$TS.tgz"

# ── tarball snapshot ─────────────────────────────────────────────────────
if [[ $DRY -eq 1 ]]; then
  info "[dry-run] would tar -czf $TARBALL ~/.claude  (excluding caches)"
else
  info "snapshot → $TARBALL"
  CC_PARENT=$(dirname "$CC_DIR"); CC_BASE=$(basename "$CC_DIR")
  tar -czf "$TARBALL" \
    --exclude="$CC_BASE/plugins/cache" \
    --exclude="$CC_BASE/chrome" \
    --exclude="$CC_BASE/image-cache" \
    --exclude="$CC_BASE/paste-cache" \
    --exclude="$CC_BASE/.search_cache" \
    --exclude="$CC_BASE/statsig" \
    --exclude="$CC_BASE/telemetry" \
    --exclude="$CC_BASE/shell-snapshots" \
    -C "$CC_PARENT" "$CC_BASE"
  ok "snapshot size: $(du -h "$TARBALL" | cut -f1)"
fi

# ── per-path migration ────────────────────────────────────────────────────
migrate_one() {
  local rel="$1"
  local src="$CC_DIR/$rel"
  local dst="$STATE_DIR/$rel"
  local mnt="$MNT_DIR/$rel"
  local bak="${src}.pre-takeover-$TS"

  if [[ -L "$src" ]]; then
    ok "$rel — already a symlink ($(readlink "$src")), skip"
    return
  fi
  if [[ ! -e "$src" ]]; then
    info "$rel — not present in CC, skip"
    return
  fi

  info "migrate: $rel"
  if [[ $DRY -eq 1 ]]; then
    echo "  [dry-run] mkdir -p $(dirname "$dst")"
    echo "  [dry-run] cp -a  $src  $dst"
    echo "  [dry-run] diff -r $src $dst"
    echo "  [dry-run] mv    $src  $bak"
    echo "  [dry-run] ln -s $mnt  $src"
    return
  fi

  mkdir -p "$(dirname "$dst")"
  if [[ -e "$dst" ]]; then
    info "  state already has $rel — merging (cp -an)"
    cp -an "$src"/. "$dst"/ 2>/dev/null || cp -an "$src" "$dst"
  else
    cp -a "$src" "$dst"
  fi

  # verify
  if [[ -d "$src" ]]; then
    if ! diff -rq "$src" "$dst" >/dev/null 2>&1; then
      echo "  ⚠ diff detected between $src and $dst — NOT swapping"
      die "abort $rel (source preserved, state has copy)"
    fi
  else
    cmp -s "$src" "$dst" || die "file mismatch $rel — abort"
  fi

  mv "$src" "$bak"
  ln -s "$mnt" "$src"
  ok "$rel → symlink to $mnt  (backup: $bak)"
}

for p in "${SELECTED[@]}"; do
  migrate_one "$p"
done

# ── summary ──────────────────────────────────────────────────────────────
echo
ok "done"
if [[ $DRY -eq 0 ]]; then
  echo "Tarball: $TARBALL"
  echo "Rollback:  $(dirname "$0")/rollback-cc.sh $TS  [paths...]"
fi
