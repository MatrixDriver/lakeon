#!/usr/bin/env python3
"""
PoC: 在 CCI 上跑 dbay compute pod，验证可行性。

测试维度：
  1. CCI 能拉 SWR 上的 compute-node-v17 image
  2. compute_ctl 二进制能启动到 TCP 55433 listening
  3. Pod IP 跟 CCE pageserver 在同 VPC 能通（pageserver IP 192.168.x.x）
  4. 冷启动耗时（pod create → ready）vs CCE 当前节点池

不测：
  - 真实 PG 数据查询（要 hwstaff 真实 tenant_id / timeline_id 才行，二期）
  - SUSPEND / RESUME 流程
  - PG proxy 路由（需要改 lakeon-api 配置）

用法:
  cd deploy/cce
  LAKEON_SITE_DIR=sites/hwstaff python3 test-cci-compute.py
  KEEP=1 python3 test-cci-compute.py   # 不清理，方便事后 exec 进去看
"""

import json
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hwcloud import load_credentials, api, get_project_id

REGION = "cn-north-4"
CCI_ENDPOINT = f"https://cci.{REGION}.myhuaweicloud.com"
NS = "dbay-compute-poc"
POD_NAME = f"compute-poc-{uuid.uuid4().hex[:8]}"
IMAGE = "swr.cn-north-4.myhuaweicloud.com/flex/compute-node-v17:pgsearch-neon-v2"
TIMEOUT = 240
KEEP = os.environ.get("KEEP") == "1"

# 现网真实组件 IP / port（从 kubectl get pods -n lakeon -o wide）
PAGESERVER_IP = "192.168.0.76"
PAGESERVER_PORT = 6400
SAFEKEEPER_IPS = ["192.168.0.217", "192.168.0.190", "192.168.0.90"]
SAFEKEEPER_PORT = 6500

# Mock tenant/timeline — compute_ctl 启动时会试连 pageserver，错的 tenant 会日志报错
# 但 sandbox + image pull + binary startup 仍能测到
MOCK_TENANT = uuid.uuid4().hex
MOCK_TIMELINE = uuid.uuid4().hex

ak, sk, creds = load_credentials()
project_id = get_project_id(ak, sk)
print(f"Project ID: {project_id}")
print(f"Pod name: {POD_NAME}")
print(f"Mock tenant/timeline: {MOCK_TENANT[:8]}.../{MOCK_TIMELINE[:8]}...")


def cci_api(method, path, body=None):
    url = f"{CCI_ENDPOINT}/api/v1/{path}"
    return api(method, url, ak, sk, body=json.dumps(body) if body else "")


def cleanup():
    print("\n[cleanup] 删除 Pod + ConfigMap + Namespace ...")
    cci_api("DELETE", f"namespaces/{NS}/pods/{POD_NAME}")
    cci_api("DELETE", f"namespaces/{NS}/configmaps/{POD_NAME}-config")
    cci_api("DELETE", f"namespaces/{NS}")


# ── Step 1: Namespace (general-computing flavor) ─────────────────────────
print(f"\n[1/5] 创建 namespace: {NS}")
ns_body = {
    "apiVersion": "v1",
    "kind": "Namespace",
    "metadata": {
        "name": NS,
        "annotations": {
            "namespace.kubernetes.io/flavor": "general-computing",
        },
        "labels": {"sys_enterprise_project_id": "0"},
    },
}
status, resp = cci_api("POST", "namespaces", ns_body)
if status in (200, 201):
    print(f"  Namespace 创建成功")
elif status == 409:
    print(f"  Namespace 已存在")
else:
    print(f"  失败 {status}: {json.dumps(resp, ensure_ascii=False, indent=2)}")
    sys.exit(1)
time.sleep(2)

# ── Step 2: ConfigMap with compute spec ─────────────────────────────────
print(f"\n[2/5] 创建 ConfigMap (compute spec, pageserver={PAGESERVER_IP}:{PAGESERVER_PORT})")

config = {
    "format_version": 2,
    "operation_uuid": str(uuid.uuid4()),
    "tenant_id": MOCK_TENANT,
    "timeline_id": MOCK_TIMELINE,
    # 关键：用 IP 不用 svc DNS（CCI Pod 不在 CCE DNS scope）
    "pageserver_connstring": f"postgresql://{PAGESERVER_IP}:{PAGESERVER_PORT}",
    "safekeeper_connstrings": [f"{ip}:{SAFEKEEPER_PORT}" for ip in SAFEKEEPER_IPS],
    "mode": "Primary",
    "suspend_timeout_seconds": 0,
    "cluster": {
        "cluster_id": "poc_cci",
        "name": "pocdb",
        "state": "restarted",
        "roles": [
            {"name": "cloud_admin", "encrypted_password": "SCRAM-SHA-256$0:$:"},
        ],
        "databases": [{"name": "pocdb", "owner": "cloud_admin"}],
        "settings": [],
    },
}
cm_body = {
    "apiVersion": "v1",
    "kind": "ConfigMap",
    "metadata": {"name": f"{POD_NAME}-config", "namespace": NS},
    "data": {"config.json": json.dumps(config)},
}
status, resp = cci_api("POST", f"namespaces/{NS}/configmaps", cm_body)
print(f"  ConfigMap: {status}")
if status not in (200, 201):
    print(f"  {json.dumps(resp, ensure_ascii=False, indent=2)}")
    if not KEEP:
        cleanup()
    sys.exit(1)

# ── Step 3: Pod ─────────────────────────────────────────────────────────
print(f"\n[3/5] 创建 Pod (image={IMAGE})")
t_create_start = time.time()
pod_body = {
    "apiVersion": "v1",
    "kind": "Pod",
    "metadata": {
        "name": POD_NAME,
        "namespace": NS,
        "labels": {"app": "lakeon-compute-poc"},
    },
    "spec": {
        "restartPolicy": "Never",
        "terminationGracePeriodSeconds": 60,
        "imagePullSecrets": [{"name": "default-secret"}],  # CCI 默认 SWR pull secret
        "containers": [{
            "name": "compute",
            "image": IMAGE,
            "command": ["/usr/local/bin/compute_ctl"],
            "args": [
                "--pgdata", "/var/db/postgres/compute",
                "-C", "postgresql://cloud_admin@localhost:55433/postgres",
                "-b", "/usr/local/bin/postgres",
                "--compute-id", POD_NAME,
                "--config", "/config/config.json",
                "--dev",
            ],
            "ports": [
                {"containerPort": 55433, "name": "pg"},
                {"containerPort": 3080, "name": "http"},
            ],
            "resources": {
                "requests": {"cpu": "0.5", "memory": "1Gi"},
                "limits": {"cpu": "0.5", "memory": "1Gi"},
            },
            "volumeMounts": [{
                "name": "config-volume",
                "mountPath": "/config",
                "readOnly": True,
            }],
            "startupProbe": {
                "tcpSocket": {"port": 55433},
                "initialDelaySeconds": 1,
                "periodSeconds": 1,
                "failureThreshold": 180,
            },
        }],
        "volumes": [{
            "name": "config-volume",
            "configMap": {"name": f"{POD_NAME}-config"},
        }],
    },
}
status, resp = cci_api("POST", f"namespaces/{NS}/pods", pod_body)
if status not in (200, 201):
    print(f"  失败 {status}: {json.dumps(resp, ensure_ascii=False, indent=2)}")
    if not KEEP:
        cleanup()
    sys.exit(1)
print(f"  Pod created at t={time.time()-t_create_start:.2f}s")

# ── Step 4: 轮询状态 + 计时 ───────────────────────────────────────────
print(f"\n[4/5] 轮询 Pod 状态 (timeout={TIMEOUT}s)")
t_start = time.time()
last_phase = ""
pod_ip = None
ready = False
t_pulled = None
t_scheduled = None

while time.time() - t_start < TIMEOUT:
    s, pod = cci_api("GET", f"namespaces/{NS}/pods/{POD_NAME}")
    if s != 200:
        time.sleep(2)
        continue
    st = pod.get("status", {})
    phase = st.get("phase", "Unknown")
    pod_ip = st.get("podIP", pod_ip)
    cs = (st.get("containerStatuses") or [{}])[0]
    state = cs.get("state", {})
    reason = ""
    if "waiting" in state:
        reason = state["waiting"].get("reason", "")
    elif "running" in state:
        reason = "running"
    elif "terminated" in state:
        reason = f"terminated:{state['terminated'].get('reason', '')}"

    # 时间点：scheduled / pulled / ready
    for c in (st.get("conditions") or []):
        if c.get("type") == "PodScheduled" and c.get("status") == "True" and t_scheduled is None:
            t_scheduled = time.time() - t_start

    if cs.get("imageID") and t_pulled is None:
        t_pulled = time.time() - t_start

    line = f"  t={time.time()-t_start:5.1f}s  phase={phase:9s}  container={reason:30s}  podIP={pod_ip or '-'}"
    if line != last_phase:
        print(line)
        last_phase = line

    # Ready 判断：startup probe 通过（container Ready=True）
    if cs.get("ready") is True:
        ready = True
        break
    if phase == "Failed" or "terminated" in state:
        break
    time.sleep(2)

t_total = time.time() - t_start
print(f"\n[timing]")
print(f"  scheduled: {t_scheduled:.1f}s" if t_scheduled else "  scheduled: not reached")
print(f"  image pulled: {t_pulled:.1f}s" if t_pulled else "  image pulled: not reached")
print(f"  ready: {t_total:.1f}s  ({'YES' if ready else 'NO'})")
print(f"  pod IP: {pod_ip}")

# ── Step 5: 看 log（不管 ready 与否） ─────────────────────────────────────
print(f"\n[5/5] 抓取 compute_ctl 日志（最后 100 行）")
s, log = cci_api("GET", f"namespaces/{NS}/pods/{POD_NAME}/log?tailLines=100")
if isinstance(log, dict):
    print(f"  log fetch failed: {log}")
else:
    print(log if isinstance(log, str) else str(log)[:5000])

# ── 结论 ──────────────────────────────────────────────────────────────
print("\n=== PoC 结论 ===")
print(f"  Pod ready: {'PASS' if ready else 'FAIL'}")
print(f"  冷启动: {t_total:.1f}s  (CCE 节点池现状: ~5-15s)")
print(f"  Pod IP: {pod_ip}")
print(f"  VPC 互通待测: 从 CCE pod ping/curl {pod_ip}:55433")

if not KEEP:
    cleanup()
else:
    print(f"\n保留资源用于事后调试 (KEEP=1):")
    print(f"  kubectl --kubeconfig=<cci-kc> -n {NS} get pod {POD_NAME}")
    print(f"  kubectl --kubeconfig=<cci-kc> -n {NS} logs {POD_NAME}")
    print(f"  清理: cci_api DELETE namespaces/{NS}")
