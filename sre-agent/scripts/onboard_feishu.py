#!/usr/bin/env python3
"""One-command Feishu onboarding helper for the dbay.cloud SRE agent.

Usage:
    uv run python scripts/onboard_feishu.py

Prerequisites:
    - hermes-agent cloned to ~/code/hermes-agent (or set HERMES_AGENT_PATH)
    - railway CLI installed (https://railway.app/install.sh)
    - railway project linked (run `railway link` first)
"""

import json
import os
import subprocess
import sys
import pathlib
from typing import Optional

# ---------------------------------------------------------------------------
# Hermes path injection — configurable via HERMES_AGENT_PATH env var
# ---------------------------------------------------------------------------
_hermes_path = os.environ.get(
    "HERMES_AGENT_PATH",
    str(pathlib.Path.home() / "code/hermes-agent"),
)
sys.path.insert(0, _hermes_path)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _mask_secret(secret: str) -> str:
    """Return a masked version of a secret string.

    Shows first 4 chars and last 4 chars, with '...' in between.
    For very short strings (<=8 chars), masks everything except first 2.
    """
    if not secret:
        return "(empty)"
    if len(secret) <= 8:
        return secret[:2] + "..." + secret[-2:] if len(secret) > 4 else "***"
    return secret[:4] + "..." + secret[-4:]


def _run(cmd: list[str], capture: bool = True) -> subprocess.CompletedProcess:
    """Run a subprocess command."""
    return subprocess.run(
        cmd,
        capture_output=capture,
        text=True,
    )


def _check_prereqs() -> list[str]:
    """Check prerequisites. Returns a list of error messages (empty = all OK)."""
    errors = []

    # Check hermes-agent is importable
    hermes_init = pathlib.Path(_hermes_path) / "gateway" / "platforms" / "feishu.py"
    if not hermes_init.exists():
        errors.append(
            f"hermes-agent not found at {_hermes_path}\n"
            "  Clone it: git clone <hermes-repo> ~/code/hermes-agent\n"
            "  Or set: HERMES_AGENT_PATH=/your/path uv run python scripts/onboard_feishu.py"
        )

    # Check railway CLI is on PATH
    result = _run(["which", "railway"])
    if result.returncode != 0:
        errors.append(
            "railway CLI not found on PATH.\n"
            "  Install with: curl -fsSL https://railway.app/install.sh | sh\n"
            "  Then restart your shell and re-run this script."
        )
        return errors  # Can't check link without railway

    # Check railway project is linked
    result = _run(["railway", "status"])
    if result.returncode != 0:
        errors.append(
            "Railway project is not linked.\n"
            "  Run: railway link\n"
            "  Then select the target project and service."
        )

    return errors


def _get_existing_railway_vars() -> dict[str, str]:
    """Fetch current Railway variables. Returns empty dict on failure."""
    result = _run(["railway", "variable", "list", "--json"])
    if result.returncode != 0:
        return {}
    try:
        data = json.loads(result.stdout)
        # railway variable list --json returns {"KEY": "VALUE", ...}
        if isinstance(data, dict):
            return data
        return {}
    except (json.JSONDecodeError, TypeError):
        return {}


def _set_railway_var(key: str, value: str) -> bool:
    """Set a Railway environment variable via stdin to avoid leaking in process list."""
    proc = subprocess.run(
        ["railway", "variable", "set", "--stdin", key],
        input=value,
        capture_output=True,
        text=True,
    )
    return proc.returncode == 0


def _confirm_overwrite(key: str, current_masked: str) -> bool:
    """Ask user whether to overwrite an existing variable."""
    print(f"\n  {key} is already set (current: {current_masked})")
    answer = input(f"  Overwrite {key}? [y/N] ").strip().lower()
    return answer in ("y", "yes")


# ---------------------------------------------------------------------------
# Main flow
# ---------------------------------------------------------------------------

def main() -> int:
    print()
    print("=== Feishu Onboarding for dbay.cloud SRE Agent ===")
    print()

    # Step 1: Check prerequisites
    print("Checking prerequisites...")
    errors = _check_prereqs()
    if errors:
        print("\nPrerequisite check failed:")
        for err in errors:
            print(f"\n  ERROR: {err}")
        print()
        return 1
    print("  All prerequisites OK.")
    print()

    # Step 2: Import and run QR flow
    print("Starting Feishu QR registration flow...")
    print("(A QR code will appear below — scan it with Feishu mobile app)")
    print()

    try:
        from gateway.platforms.feishu import qr_register  # type: ignore[import]
    except ImportError as exc:
        print(f"Failed to import hermes qr_register: {exc}")
        print(f"  Hermes path: {_hermes_path}")
        print("  Make sure hermes-agent dependencies are installed.")
        return 1

    creds: Optional[dict] = qr_register(initial_domain="feishu", timeout_seconds=600)

    if creds is None:
        print()
        print("ERROR: Feishu onboarding failed — check network and try again.")
        print("  Common causes:")
        print("    - Network timeout (check connectivity to feishu.cn)")
        print("    - QR code expired (try again)")
        print("    - Auth denied in Feishu mobile app")
        return 1

    print()
    print("Feishu authorization successful.")

    app_id: str = creds.get("app_id", "")
    app_secret: str = creds.get("app_secret", "")
    open_id: Optional[str] = creds.get("open_id")
    bot_name: Optional[str] = creds.get("bot_name")

    if not app_id or not app_secret:
        print("ERROR: Feishu returned incomplete credentials (missing app_id or app_secret).")
        return 1

    # Step 3: Check existing Railway vars and push credentials
    print()
    print("Checking existing Railway variables...")
    existing = _get_existing_railway_vars()

    vars_to_set: dict[str, str] = {
        "FEISHU_APP_ID": app_id,
        "FEISHU_APP_SECRET": app_secret,
    }
    if open_id:
        vars_to_set["FEISHU_ALLOWED_USERS"] = open_id

    print()
    print("Pushing credentials to Railway...")
    failed_vars = []

    for key, value in vars_to_set.items():
        is_secret = key == "FEISHU_APP_SECRET"
        display_new = _mask_secret(value) if is_secret else value

        if key in existing:
            current_val = existing[key]
            current_display = _mask_secret(current_val) if is_secret else current_val
            if not _confirm_overwrite(key, current_display):
                print(f"  Skipped {key}.")
                continue

        print(f"  Setting {key} = {display_new} ...", end=" ", flush=True)
        ok = _set_railway_var(key, value)
        if ok:
            print("done.")
        else:
            print("FAILED.")
            failed_vars.append(key)

    if failed_vars:
        print(f"\nWARNING: Failed to set: {', '.join(failed_vars)}")
        print("  Check `railway status` and try setting them manually.")

    # Step 4: WebSocket mode note about verification_token / encrypt_key
    print()
    print("NOTE: Feishu WebSocket mode (FEISHU_CONNECTION_MODE=websocket) is the default.")
    print("  FEISHU_VERIFICATION_TOKEN and FEISHU_ENCRYPT_KEY are NOT required for WebSocket mode.")
    print("  If you switch to webhook mode, set those manually at:")
    print("  https://open.feishu.cn/ → your app → Event Subscriptions")

    # Step 5: Optional force redeploy
    print()
    answer = input("Force a Railway redeploy now? (Railway auto-redeploys on env change, this adds an explicit trigger) [y/N] ").strip().lower()
    if answer in ("y", "yes"):
        print("  Triggering Railway redeploy...", end=" ", flush=True)
        result = _run(["railway", "redeploy", "--yes"], capture=False)
        if result.returncode == 0:
            print("  Redeploy triggered.")
        else:
            print("  redeploy command returned non-zero; check `railway logs` manually.")
    else:
        print("  Skipping forced redeploy. Railway will auto-redeploy shortly.")

    # Step 6: Summary
    print()
    print("=" * 50)
    print("ONBOARDING COMPLETE")
    print("=" * 50)
    print(f"  App ID:          {app_id}")
    print(f"  App Secret:      {_mask_secret(app_secret)}")
    print(f"  Bot Name:        {bot_name or '(not available — bot may not be enabled yet)'}")
    print(f"  Scanned User ID: {open_id or '(not returned — scan again to get open_id)'}")
    print()
    print("Bot is ready. Try DMing it on Feishu after Railway finishes redeploying (~1-2 min).")
    print()

    return 0


if __name__ == "__main__":
    sys.exit(main())
