#!/usr/bin/env bash
# rollback-cc.sh — undo a takeover-cc.sh run.
#
# Usage:
#   ./rollback-cc.sh <TS>              restore ALL <path>.pre-takeover-<TS>
#   ./rollback-cc.sh <TS> tasks plans  restore only these
#   ./rollback-cc.sh --tarball FILE    nuke ~/.claude and extract tarball
set -euo pipefail

: "${CC_DIR:=$HOME/.claude}"
: "${SKIP_PREFLIGHT:=0}"

die() { echo "❌ $*" >&2; exit 1; }
info() { echo "→ $*"; }
ok() { echo "✓ $*"; }

[[ $# -eq 0 ]] && die "usage: $0 <TS> [paths...]   OR   $0 --tarball FILE"

if [[ $SKIP_PREFLIGHT -eq 0 ]]; then
  if pgrep -if "claude" | grep -vi "$(basename "$0")" | grep -v "chrome" | grep -q .; then
    die "Claude Code running — quit it first"
  fi
fi

if [[ "$1" == "--tarball" ]]; then
  tar="${2:?tarball path required}"
  [[ -f "$tar" ]] || die "tarball not found: $tar"
  info "moving current $CC_DIR aside → $CC_DIR.rolled-$$"
  mv "$CC_DIR" "$CC_DIR.rolled-$$"
  info "extracting $tar"
  tar -xzf "$tar" -C "$(dirname "$CC_DIR")"
  ok "restored from tarball. Old state preserved at $CC_DIR.rolled-$$"
  exit 0
fi

TS="$1"; shift
SEL=("$@")

# If no paths given, discover all *.pre-takeover-$TS entries (including nested).
if [[ ${#SEL[@]} -eq 0 ]]; then
  while IFS= read -r -d '' p; do
    rel="${p#$CC_DIR/}"
    rel="${rel%.pre-takeover-$TS}"
    SEL+=("$rel")
  done < <(find "$CC_DIR" -maxdepth 3 -name "*.pre-takeover-$TS" -print0)
fi

[[ ${#SEL[@]} -eq 0 ]] && die "no backups found for TS=$TS"

for rel in "${SEL[@]}"; do
  link="$CC_DIR/$rel"
  bak="${link}.pre-takeover-$TS"
  if [[ ! -e "$bak" ]]; then
    echo "  skip $rel — backup missing ($bak)"
    continue
  fi
  if [[ -L "$link" ]]; then
    info "restore $rel"
    rm "$link"
    mv "$bak" "$link"
    ok "$rel restored"
  else
    echo "  skip $rel — current path is not a symlink ($link)"
  fi
done

echo
ok "rollback done. ~/.dbay/state/claude/* preserved (cloud留底 unchanged)."
