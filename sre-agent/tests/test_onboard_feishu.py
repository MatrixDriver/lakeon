"""Tests for onboard_feishu.py helpers.

The actual qr_register flow requires a real Feishu scan — not testable here.
We cover the pure-Python helpers and subprocess-mocked Railway interactions.
"""

import json
import subprocess
import sys
import types
import unittest.mock as mock

import pytest


# ---------------------------------------------------------------------------
# Import the module under test, injecting a fake gateway stub so we don't
# need hermes-agent to be installed in the test environment.
# ---------------------------------------------------------------------------

def _make_fake_hermes_module() -> None:
    """Inject a minimal gateway.platforms.feishu stub into sys.modules."""
    gateway_pkg = types.ModuleType("gateway")
    platforms_pkg = types.ModuleType("gateway.platforms")
    feishu_mod = types.ModuleType("gateway.platforms.feishu")
    feishu_mod.qr_register = mock.MagicMock(return_value=None)
    gateway_pkg.platforms = platforms_pkg
    platforms_pkg.feishu = feishu_mod
    sys.modules.setdefault("gateway", gateway_pkg)
    sys.modules.setdefault("gateway.platforms", platforms_pkg)
    sys.modules.setdefault("gateway.platforms.feishu", feishu_mod)


_make_fake_hermes_module()

# Now safe to import the script module
import importlib
import importlib.util
import pathlib

_script_path = pathlib.Path(__file__).parent.parent / "scripts" / "onboard_feishu.py"
_spec = importlib.util.spec_from_file_location("onboard_feishu", _script_path)
assert _spec is not None
onboard_feishu = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(onboard_feishu)  # type: ignore[union-attr]


# ---------------------------------------------------------------------------
# Tests: _mask_secret
# ---------------------------------------------------------------------------

class TestMaskSecret:
    def test_normal_secret(self):
        result = onboard_feishu._mask_secret("sk-abc123def456")
        # Should show first 4, then ..., then last 4
        assert result.startswith("sk-a")
        assert result.endswith("f456")
        assert "..." in result

    def test_very_short_secret(self):
        result = onboard_feishu._mask_secret("ab")
        assert "..." in result or result == "***"

    def test_exactly_8_chars(self):
        result = onboard_feishu._mask_secret("12345678")
        # <=8 chars: shows first 2 and last 2
        assert result.startswith("12")
        assert result.endswith("78")

    def test_empty_secret(self):
        result = onboard_feishu._mask_secret("")
        assert result == "(empty)"

    def test_long_secret(self):
        secret = "a" * 40 + "z" * 4
        result = onboard_feishu._mask_secret(secret)
        assert result.startswith("aaaa")
        assert result.endswith("zzzz")
        assert len(result) < len(secret)


# ---------------------------------------------------------------------------
# Tests: _check_prereqs
# ---------------------------------------------------------------------------

class TestCheckPrereqs:
    def test_missing_railway_returns_error(self):
        """When `which railway` fails, _check_prereqs returns an error mentioning railway."""
        with (
            mock.patch.object(
                onboard_feishu,
                "_run",
                side_effect=lambda cmd, **kw: (
                    subprocess.CompletedProcess(cmd, 0, "", "")
                    if cmd[0] == "which" and cmd[1] == "railway"
                    and False  # force the else branch
                    else subprocess.CompletedProcess(cmd, 1, "", "not found")
                ),
            ),
            mock.patch("pathlib.Path.exists", return_value=True),
        ):
            # Patch _run to return failure for 'which railway'
            def fake_run(cmd, **kw):
                if cmd[0] == "which":
                    return subprocess.CompletedProcess(cmd, 1, "", "not found")
                return subprocess.CompletedProcess(cmd, 0, "", "")

            with mock.patch.object(onboard_feishu, "_run", side_effect=fake_run):
                errors = onboard_feishu._check_prereqs()

            assert any("railway" in e.lower() for e in errors), f"Expected railway error, got: {errors}"

    def test_missing_hermes_returns_error(self):
        """When hermes feishu.py does not exist, _check_prereqs reports it."""
        def fake_run(cmd, **kw):
            return subprocess.CompletedProcess(cmd, 0, "", "")

        with (
            mock.patch.object(onboard_feishu, "_run", side_effect=fake_run),
            mock.patch("pathlib.Path.exists", return_value=False),
        ):
            errors = onboard_feishu._check_prereqs()

        assert any("hermes" in e.lower() for e in errors), f"Expected hermes error, got: {errors}"

    def test_railway_unlinked_returns_error(self):
        """When `railway status` fails, _check_prereqs reports link error."""
        def fake_run(cmd, **kw):
            if cmd[0] == "which":
                return subprocess.CompletedProcess(cmd, 0, "/usr/local/bin/railway", "")
            if cmd[:2] == ["railway", "status"]:
                return subprocess.CompletedProcess(cmd, 1, "", "Not linked")
            return subprocess.CompletedProcess(cmd, 0, "", "")

        with (
            mock.patch.object(onboard_feishu, "_run", side_effect=fake_run),
            mock.patch("pathlib.Path.exists", return_value=True),
        ):
            errors = onboard_feishu._check_prereqs()

        assert any("link" in e.lower() for e in errors), f"Expected link error, got: {errors}"


# ---------------------------------------------------------------------------
# Tests: _get_existing_railway_vars
# ---------------------------------------------------------------------------

class TestGetExistingRailwayVars:
    def test_parses_json_response(self):
        payload = {"FEISHU_APP_ID": "cli_abc", "FEISHU_APP_SECRET": "secret123"}
        fake_result = subprocess.CompletedProcess(
            ["railway", "variable", "list", "--json"], 0, json.dumps(payload), ""
        )
        with mock.patch.object(onboard_feishu, "_run", return_value=fake_result):
            result = onboard_feishu._get_existing_railway_vars()
        assert result == payload

    def test_returns_empty_on_failure(self):
        fake_result = subprocess.CompletedProcess(
            ["railway", "variable", "list", "--json"], 1, "", "error"
        )
        with mock.patch.object(onboard_feishu, "_run", return_value=fake_result):
            result = onboard_feishu._get_existing_railway_vars()
        assert result == {}

    def test_returns_empty_on_invalid_json(self):
        fake_result = subprocess.CompletedProcess(
            ["railway", "variable", "list", "--json"], 0, "not-json", ""
        )
        with mock.patch.object(onboard_feishu, "_run", return_value=fake_result):
            result = onboard_feishu._get_existing_railway_vars()
        assert result == {}
