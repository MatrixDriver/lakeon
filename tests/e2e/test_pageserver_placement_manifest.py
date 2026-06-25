import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "deploy" / "helm" / "lakeon"
HWSTAFF_VALUES = ROOT / "deploy" / "cce" / "sites" / "hwstaff" / "values.yaml"
HWSTAFF_CONTROL_VALUES = ROOT / "deploy" / "cce" / "sites" / "hwstaff" / "values-control-plane.yaml"
HWSTAFF_DATA_VALUES = ROOT / "deploy" / "cce" / "sites" / "hwstaff" / "values-data-plane.yaml"


def helm_template(*values_files: Path) -> str:
    cmd = ["helm", "template", "lakeon", str(CHART)]
    for values_file in values_files:
        cmd.extend(["-f", str(values_file)])
    cmd.extend([
        "--set-string", "api.logDbDsn=jdbc:postgresql://metadata-db:5432/lakeon",
        "--set-string", "computeJwt.privateKey=test-private-key",
        "--set-string", "computeJwt.publicJwk={\"kty\":\"RSA\",\"kid\":\"test\"}",
    ])
    result = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr
    return result.stdout


def test_control_plane_enables_pageserver_discovery_with_static_dns_fallback():
    manifest = helm_template(HWSTAFF_VALUES, HWSTAFF_CONTROL_VALUES)

    match = re.search(r"LAKEON_NEON_PAGESERVER_NODES_RAW: \"([^\"]+)\"", manifest)
    assert match, manifest
    nodes = match.group(1).split(",")
    assert len(nodes) == 3
    assert nodes == [
        "ps-0=http://pageserver-0.pageserver-headless.lakeon.svc.cluster.local:9898|pageserver-0.pageserver-headless.lakeon.svc.cluster.local|6400",
        "ps-1=http://pageserver-1.pageserver-headless.lakeon.svc.cluster.local:9898|pageserver-1.pageserver-headless.lakeon.svc.cluster.local|6400",
        "ps-2=http://pageserver-2.pageserver-headless.lakeon.svc.cluster.local:9898|pageserver-2.pageserver-headless.lakeon.svc.cluster.local|6400",
    ]
    assert 'LAKEON_NEON_PAGESERVER_DISCOVERY_ENABLED: "true"' in manifest
    assert 'LAKEON_NEON_PAGESERVER_DISCOVERY_NAMESPACE: "lakeon"' in manifest
    assert 'LAKEON_NEON_PAGESERVER_DISCOVERY_LABEL_SELECTOR: "app=pageserver"' in manifest
    assert 'LAKEON_NEON_PAGESERVER_DISCOVERY_HEADLESS_SERVICE: "pageserver-headless"' in manifest
    assert "http://192.168.0." not in match.group(1)


def test_data_plane_renders_pageserver_statefulset_and_headless_service():
    manifest = helm_template(HWSTAFF_VALUES, HWSTAFF_DATA_VALUES)

    assert "kind: StatefulSet\nmetadata:\n  name: pageserver" in manifest
    assert "kind: Deployment\nmetadata:\n  name: pageserver" not in manifest
    assert "serviceName: pageserver-headless" in manifest
    assert "kind: Service\nmetadata:\n  name: pageserver-headless" in manifest
    assert "replicas: 3" in manifest
    assert "ordinal=\"${HOSTNAME##*-}\"" in manifest
    assert "printf 'id = %s\\n' \"$id\" > /data/identity.toml" in manifest


def test_control_plane_renders_dicer_assigner_and_api_endpoint():
    manifest = helm_template(HWSTAFF_VALUES, HWSTAFF_CONTROL_VALUES)

    assert "kind: Deployment\nmetadata:\n  name: dicer-assigner" in manifest
    assert "kind: Service\nmetadata:\n  name: dicer-assigner" in manifest
    assert "image: \"swr.cn-north-4.myhuaweicloud.com/flex/dicer-assigner:5cce7985\"" in manifest
    assert "containerPort: 24500" in manifest
    assert "containerPort: 7777" in manifest
    assert "name: LOCATION" in manifest
    assert "kubernetes-cluster:prod/huawei/public/cn-north-4/control/01" in manifest
    assert 'LAKEON_DICER_ENABLED: "true"' in manifest
    assert 'LAKEON_DICER_ENDPOINT: "http://dicer-assigner.lakeon.svc.cluster.local:24500"' in manifest
    assert 'LAKEON_DICER_LIVE_LOAD_ENABLED: "true"' in manifest
    assert 'LAKEON_DICER_LIVE_LOAD_POLL_INTERVAL_MS: "10000"' in manifest
    assert 'LAKEON_DICER_METRICS_TIMEOUT_MS: "1500"' in manifest
    assert 'LAKEON_DICER_SNAPSHOT_TTL_MS: "30000"' in manifest


def test_control_plane_split_uses_gateway_and_does_not_render_legacy_lakeon_api():
    manifest = helm_template(HWSTAFF_VALUES, HWSTAFF_CONTROL_VALUES)

    assert "kind: Deployment\nmetadata:\n  name: admin-api" in manifest
    assert "kind: Deployment\nmetadata:\n  name: serving-api" in manifest
    assert "kind: Deployment\nmetadata:\n  name: api-gateway" in manifest
    assert "kind: Deployment\nmetadata:\n  name: lakeon-api" not in manifest
    assert "kind: Service\nmetadata:\n  name: lakeon-api\n" not in manifest
    assert "name: LAKEON_API_ROLE\n              value: \"admin\"" in manifest
    assert "name: LAKEON_API_ROLE\n              value: \"serving\"" in manifest
    assert "proxy_pass http://admin-api:8088" in manifest
    assert "proxy_pass http://serving-api:8088" in manifest
    assert "add_header X-Lakeon-Api-Gateway split always;" in manifest

    public_service = re.search(
        r"kind: Service\nmetadata:\n  name: lakeon-api-public.*?(?=\n---|\Z)",
        manifest,
        re.S,
    )
    assert public_service, manifest
    assert "app: api-gateway" in public_service.group(0)
    assert "targetPort: https" in public_service.group(0)
    assert "name: http-internal" not in public_service.group(0)
