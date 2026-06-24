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


def test_control_plane_injects_three_stable_pageserver_nodes():
    manifest = helm_template(HWSTAFF_VALUES, HWSTAFF_CONTROL_VALUES)

    match = re.search(r"LAKEON_NEON_PAGESERVER_NODES_RAW: \"([^\"]+)\"", manifest)
    assert match, manifest
    nodes = match.group(1).split(",")
    assert len(nodes) == 3
    assert nodes == [
        "ps-0=http://192.168.0.21:9898|pageserver-0.pageserver-headless.lakeon.svc.cluster.local|6400",
        "ps-1=http://192.168.0.202:9898|pageserver-1.pageserver-headless.lakeon.svc.cluster.local|6400",
        "ps-2=http://192.168.0.81:9898|pageserver-2.pageserver-headless.lakeon.svc.cluster.local|6400",
    ]


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
