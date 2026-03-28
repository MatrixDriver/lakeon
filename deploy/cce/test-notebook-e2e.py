#!/usr/bin/env python3
"""
Notebook E2E 测试 — 模拟完整 notebook 会话

流程: 创建 session → WebSocket 连接 → 等 ready → 发 Python 代码 → 验证输出 → 清理

用法:
  LAKEON_API_TOKEN=lk_xxx python3 deploy/cce/test-notebook-e2e.py
  LAKEON_API_TOKEN=lk_xxx python3 deploy/cce/test-notebook-e2e.py python-data 0
"""

import asyncio
import json
import os
import ssl
import sys
import time

import requests
import websockets

API_URL = os.environ.get("LAKEON_API_URL", "https://api.dbay.cloud:8443")
API_TOKEN = os.environ.get("LAKEON_API_TOKEN", "")
IMAGE_KEY = sys.argv[1] if len(sys.argv) > 1 else "ray"
WORKER_COUNT = int(sys.argv[2]) if len(sys.argv) > 2 else 1
TIMEOUT = 60

if not API_TOKEN:
    print("ERROR: LAKEON_API_TOKEN 未设置")
    sys.exit(1)

headers = {"Authorization": f"Bearer {API_TOKEN}", "Content-Type": "application/json"}
ssl_ctx = ssl.create_default_context()
ssl_ctx.check_hostname = False
ssl_ctx.verify_mode = ssl.CERT_NONE
session = requests.Session()
session.verify = False

# Suppress SSL warnings
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

failures = 0
def ok(msg): print(f"  ✓ {msg}")
def fail(msg):
    global failures
    print(f"  ✗ {msg}")
    failures += 1

print(f"=== Notebook E2E Test ===")
print(f"Image: {IMAGE_KEY}, Workers: {WORKER_COUNT}")
print()

# ── Step 1: 清理已有 session ─────────────────────────────────
print("[Step 1] 清理已有 session...")
try:
    r = session.get(f"{API_URL}/api/v1/datalake/notebook/sessions/current", headers=headers)
    if r.status_code == 200:
        old_id = r.json().get("id")
        if old_id:
            session.delete(f"{API_URL}/api/v1/datalake/notebook/sessions/{old_id}", headers=headers)
            print(f"  已删除旧 session: {old_id}")
            time.sleep(2)
except Exception:
    pass

# ── Step 2: 创建 session ─────────────────────────────────────
print("[Step 2] 创建 notebook session...")
t_create = time.time()
r = session.post(f"{API_URL}/api/v1/datalake/notebook/sessions", headers=headers,
                 json={"image": IMAGE_KEY, "worker_count": WORKER_COUNT, "worker_size": "small"})
t_created = time.time()

if r.status_code not in (200, 201):
    fail(f"Session 创建失败: {r.status_code} {r.text}")
    sys.exit(1)

data = r.json()
status = data.get("status")
pod_name = data.get("pod_name", "")
session_id = data.get("id", "")
create_time = f"{t_created - t_create:.1f}s"

if status == "RUNNING":
    ok(f"Session 创建成功 ({create_time}): {session_id}")
else:
    fail(f"Session 状态异常: {status} — {data}")
    sys.exit(1)

if pod_name.startswith("warm-ray-head-"):
    ok(f"使用热池 pod: {pod_name}")
else:
    print(f"  ℹ 冷启动 pod: {pod_name}")

# ── Step 3: WebSocket 连接 + 执行代码 ────────────────────────
print("[Step 3] WebSocket 连接 + 执行 Python 代码...")

ws_url = API_URL.replace("https://", "wss://").replace("http://", "ws://")
ws_endpoint = f"{ws_url}/ws/notebook?token={API_TOKEN}"

async def test_websocket():
    global failures
    t_ws = time.time()

    try:
        async with websockets.connect(ws_endpoint, ssl=ssl_ctx, open_timeout=30,
                                       close_timeout=5) as ws:
            print("  WebSocket 已连接")

            got_ready = False
            t_ready = None

            # 等 ready 消息
            for _ in range(TIMEOUT):
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=1.0)
                    print(f"  ← {msg[:200]}")
                    try:
                        parsed = json.loads(msg)
                    except json.JSONDecodeError:
                        continue

                    if parsed.get("type") == "ready":
                        t_ready = time.time()
                        ok(f"Kernel ready ({t_ready - t_ws:.1f}s)")
                        got_ready = True
                        break
                    elif parsed.get("type") == "progress":
                        pass  # progress messages are fine
                    elif parsed.get("type") == "error":
                        fail(f"Kernel 错误: {parsed.get('traceback', msg)}")
                        return
                except asyncio.TimeoutError:
                    continue

            if not got_ready:
                fail(f"Kernel 未就绪 ({TIMEOUT}s 超时)")
                return

            # 发送 Python 代码
            code = json.dumps({"type": "execute", "code": "print(1+1)"})
            print(f"  → {code}")
            await ws.send(code)

            # 等待执行结果
            got_result = False
            for _ in range(30):
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
                    print(f"  ← {msg[:200]}")
                    try:
                        parsed = json.loads(msg)
                    except json.JSONDecodeError:
                        continue

                    if parsed.get("type") in ("output", "stdout", "done"):
                        output = parsed.get("text", parsed.get("output", ""))
                        if "2" in str(output):
                            t_result = time.time()
                            ok(f"代码执行成功 ({t_result - t_ready:.1f}s): print(1+1) → {output.strip()}")
                            got_result = True
                            break
                except asyncio.TimeoutError:
                    continue

            if not got_result:
                fail("代码执行无输出或结果错误")

    except Exception as e:
        fail(f"WebSocket 连接失败: {e}")

asyncio.run(test_websocket())

# ── Cleanup ──────────────────────────────────────────────────
print()
print("[Cleanup] 删除 session...")
try:
    session.delete(f"{API_URL}/api/v1/datalake/notebook/sessions/{session_id}", headers=headers)
    print("  已清理")
except Exception:
    pass

# ── Summary ──────────────────────────────────────────────────
print()
print("============================================")
print(f"  Session 创建: {create_time}")
total = time.time() - t_create
print(f"  总耗时: {total:.1f}s")
if failures == 0:
    print("  ✅ All tests passed")
else:
    print(f"  ❌ {failures} test(s) FAILED")
print("============================================")
sys.exit(failures)
