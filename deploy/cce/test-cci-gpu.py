#!/usr/bin/env python3
"""
CCI GPU Pod 直接 API 测试

通过 CCI REST API（非 virtual-kubelet）创建 GPU Pod，测试 GPU 推理能力。
CCI API 兼容 Kubernetes API，endpoint: cci.{region}.myhuaweicloud.com

用法:
  cd deploy/cce
  LAKEON_SITE_DIR=sites/hwstaff python3 test-cci-gpu.py
"""

import json, os, sys, time

# 复用 hwcloud.py 的签名和凭证
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hwcloud import load_credentials, api, get_project_id

REGION = "cn-north-4"
CCI_ENDPOINT = f"https://cci.{REGION}.myhuaweicloud.com"
NS = "gpu-test-direct"
POD_NAME = "gpu-inference-test"
TIMEOUT = 180

ak, sk, creds = load_credentials()
project_id = get_project_id(ak, sk)
print(f"Project ID: {project_id}")


def cci_api(method, path, body=None):
    """调用 CCI Kubernetes API"""
    url = f"{CCI_ENDPOINT}/api/v1/{path}"
    return api(method, url, ak, sk, body=json.dumps(body) if body else "")


def cci_api_with_project(method, path, body=None):
    """带 project_id 的 CCI API（用于创建 namespace 等）"""
    url = f"{CCI_ENDPOINT}/api/v1/{path}"
    # CCI 需要在 header 加 X-Auth-Token 或用 AK/SK 签名 + project scope
    return api(method, url, ak, sk, body=json.dumps(body) if body else "")


# ── Step 1: 创建 GPU namespace ────────────────────────────────────
print(f"\n[1/5] 创建 GPU namespace: {NS}")

ns_body = {
    "apiVersion": "v1",
    "kind": "Namespace",
    "metadata": {
        "name": NS,
        "annotations": {
            # CCI namespace 类型: general-computing / gpu-accelerated
            "namespace.kubernetes.io/flavor": "gpu-accelerated",
        },
        "labels": {
            "sys_enterprise_project_id": "0",
        }
    }
}

status, resp = cci_api("POST", "namespaces", ns_body)
if status in (200, 201):
    print(f"  Namespace 创建成功")
elif status == 409:
    print(f"  Namespace 已存在，继续")
else:
    print(f"  创建失败: {status} {json.dumps(resp, ensure_ascii=False, indent=2)}")
    # 尝试其他 flavor 名称
    for flavor in ["gpu", "GPU"]:
        ns_body["metadata"]["annotations"]["namespace.kubernetes.io/flavor"] = flavor
        status, resp = cci_api("POST", "namespaces", ns_body)
        if status in (200, 201, 409):
            print(f"  使用 flavor={flavor} 成功")
            break
        print(f"  flavor={flavor}: {status}")
    else:
        print(f"  所有 flavor 尝试失败，最后一次响应: {json.dumps(resp, ensure_ascii=False, indent=2)}")
        sys.exit(1)

time.sleep(2)

# ── Step 2: 创建 GPU Pod ──────────────────────────────────────────
print(f"\n[2/5] 创建 GPU Pod: {POD_NAME}")

# GPU 推理测试脚本
test_script = """
import subprocess, sys
print("=== GPU Environment Check ===")

# nvidia-smi
try:
    out = subprocess.check_output(["nvidia-smi"], text=True, timeout=10)
    print(out)
except Exception as e:
    print(f"nvidia-smi: {e}")

# PyTorch GPU test
try:
    import torch
    print(f"PyTorch version: {torch.__version__}")
    print(f"CUDA available: {torch.cuda.is_available()}")
    if torch.cuda.is_available():
        print(f"GPU: {torch.cuda.get_device_name(0)}")
        print(f"GPU count: {torch.cuda.device_count()}")
        # matrix multiply
        a = torch.randn(2000, 2000, device='cuda')
        b = torch.randn(2000, 2000, device='cuda')
        import time
        t0 = time.time()
        for _ in range(10):
            c = torch.mm(a, b)
        torch.cuda.synchronize()
        elapsed = time.time() - t0
        print(f"10x matmul (2000x2000): {elapsed:.3f}s")
        print(f"GPU memory used: {torch.cuda.memory_allocated()/1024/1024:.1f} MB")
        print("GPU_TEST_PASS")
    else:
        print("GPU_TEST_FAIL: CUDA not available")
except ImportError:
    print("PyTorch not installed")
    # fallback: just check nvidia-smi
    print("GPU_DETECT_ONLY")
except Exception as e:
    print(f"GPU test error: {e}")
    print("GPU_TEST_FAIL")
"""

pod_body = {
    "apiVersion": "v1",
    "kind": "Pod",
    "metadata": {
        "name": POD_NAME,
        "namespace": NS,
        "annotations": {
            "cri.cci.io/gpu-driver": "460.106",
        }
    },
    "spec": {
        "containers": [{
            "name": "gpu-test",
            "image": "swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data",
            "command": ["python3", "-c", test_script],
            "resources": {
                "requests": {
                    "cpu": "4",
                    "memory": "32Gi",
                    "nvidia.com/gpu-tesla-v100-16GB": "1"
                },
                "limits": {
                    "cpu": "4",
                    "memory": "32Gi",
                    "nvidia.com/gpu-tesla-v100-16GB": "1"
                }
            }
        }],
        "restartPolicy": "Never"
    }
}

status, resp = cci_api("POST", f"namespaces/{NS}/pods", pod_body)
if status in (200, 201):
    print(f"  Pod 创建成功")
elif status == 409:
    print(f"  Pod 已存在")
else:
    print(f"  创建失败: {status}")
    print(f"  {json.dumps(resp, ensure_ascii=False, indent=2)}")
    # 尝试 T4
    print("\n  尝试 T4 GPU...")
    pod_body["metadata"]["name"] = POD_NAME
    for container in pod_body["spec"]["containers"]:
        container["resources"]["requests"].pop("nvidia.com/gpu-tesla-v100-16GB", None)
        container["resources"]["limits"].pop("nvidia.com/gpu-tesla-v100-16GB", None)
        container["resources"]["requests"]["nvidia.com/gpu-tesla-t4-16GB"] = "1"
        container["resources"]["limits"]["nvidia.com/gpu-tesla-t4-16GB"] = "1"
    status, resp = cci_api("POST", f"namespaces/{NS}/pods", pod_body)
    if status in (200, 201):
        print(f"  T4 Pod 创建成功")
    else:
        print(f"  T4 也失败: {status}")
        print(f"  {json.dumps(resp, ensure_ascii=False, indent=2)}")
        # 清理
        cci_api("DELETE", f"namespaces/{NS}")
        sys.exit(1)

# ── Step 3: 轮询 Pod 状态 ────────────────────────────────────────
print(f"\n[3/5] 轮询 Pod 状态 (最多 {TIMEOUT}s)...")

t0 = time.time()
prev_phase = ""
while time.time() - t0 < TIMEOUT:
    s, pod = cci_api("GET", f"namespaces/{NS}/pods/{POD_NAME}")
    if s != 200:
        print(f"  查询失败: {s}")
        time.sleep(3)
        continue

    phase = pod.get("status", {}).get("phase", "Unknown")
    container_statuses = pod.get("status", {}).get("containerStatuses", [])
    reason = ""
    if container_statuses:
        state = container_statuses[0].get("state", {})
        if "waiting" in state:
            reason = state["waiting"].get("reason", "")
        elif "terminated" in state:
            reason = f"terminated:{state['terminated'].get('reason', '')}"

    current = f"{phase}/{reason}"
    if current != prev_phase:
        elapsed = time.time() - t0
        print(f"  [{elapsed:6.1f}s] Phase={phase:12s} Reason={reason}")
        prev_phase = current

    if phase == "Succeeded":
        break
    if phase == "Failed":
        print("  Pod Failed!")
        break

    time.sleep(3)

# ── Step 4: 获取日志 ─────────────────────────────────────────────
print(f"\n[4/5] 获取 Pod 日志...")

s, logs = api("GET", f"{CCI_ENDPOINT}/api/v1/namespaces/{NS}/pods/{POD_NAME}/log", ak, sk)
if s == 200:
    if isinstance(logs, dict):
        print(json.dumps(logs, indent=2))
    else:
        print(logs)
else:
    # logs 可能是纯文本
    print(f"  获取日志: status={s}")
    print(f"  {logs}")

# ── Step 5: 清理 ────────────────────────────────────────────────
print(f"\n[5/5] 清理资源...")
cci_api("DELETE", f"namespaces/{NS}/pods/{POD_NAME}")
time.sleep(2)
cci_api("DELETE", f"namespaces/{NS}")
print("  清理完成")
