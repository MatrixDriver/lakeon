from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def test_cce_integration_preflight_checks_split_api_components():
    script = (ROOT / "deploy" / "cce" / "integration-test-cce.sh").read_text()

    assert "api-gateway admin-api serving-api" in script
    assert "lakeon-api-public" in script
    assert ".spec.selector.app" in script
    assert ".spec.ports[?(@.name==\"https\")].targetPort" in script
    assert '"api-gateway"' in script
    assert '"https"' in script
    assert "-l app=lakeon-api" not in script
    assert "lakeon-api pod not ready" not in script


def test_smoke_test_checks_split_gateway_in_control_plane():
    script = (ROOT / "deploy" / "cce" / "smoke-test.sh").read_text()

    assert "CONTROL_KUBECONFIG" in script
    assert "api-gateway admin-api serving-api" in script
    assert "lakeon-api-public" in script
    assert ".spec.selector.app" in script
    assert ".spec.ports[?(@.name==\"https\")].targetPort" in script
    assert '"api-gateway"' in script
    assert '"https"' in script
    assert "-l app=lakeon-api" not in script
