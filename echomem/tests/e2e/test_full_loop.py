"""
模拟 Claude Code 的真实接入：
  - spawn `python -m echomem.mcp_shim` 子进程
  - 通过 stdin 发 initialize → tools/call(memory_ingest) → tools/call(memory_recall)
  - 验证 stdout 是合法 JSON-RPC

需要 ECHOMEM_E2E=1 才跑，避免本地无 daemon 时 CI 红。
"""
import asyncio
import json
import os
import socket
import subprocess
import sys
import time
import pytest
from contextlib import contextmanager


pytestmark = pytest.mark.skipif(
    os.environ.get("ECHOMEM_E2E") != "1",
    reason="set ECHOMEM_E2E=1 to run; requires running Ollama with qwen3-embedding:0.6b",
)


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@contextmanager
def _daemon(tmp_path, port):
    env = os.environ.copy()
    env["HOME"] = str(tmp_path)
    env["ECHOMEM_PORT"] = str(port)
    # init then start
    subprocess.check_call([sys.executable, "-m", "echomem", "init"], env=env)
    proc = subprocess.Popen(
        [sys.executable, "-m", "echomem", "start", "--foreground"],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    # 等待 /health 200
    deadline = time.time() + 15
    import httpx

    while time.time() < deadline:
        try:
            r = httpx.get(f"http://127.0.0.1:{port}/health", timeout=1.0)
            if r.status_code == 200:
                break
        except Exception:
            pass
        time.sleep(0.2)
    else:
        proc.terminate()
        raise RuntimeError("daemon not up")
    try:
        yield
    finally:
        proc.terminate()
        proc.wait(timeout=5)


def test_full_loop(tmp_path):
    port = _free_port()
    with _daemon(tmp_path, port):
        env = os.environ.copy()
        env["HOME"] = str(tmp_path)
        env["ECHOMEM_PORT"] = str(port)
        shim = subprocess.Popen(
            [sys.executable, "-m", "echomem.mcp_shim"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            text=True,
        )

        def send(msg):
            shim.stdin.write(json.dumps(msg) + "\n")
            shim.stdin.flush()

        def recv():
            line = shim.stdout.readline()
            return json.loads(line) if line else None

        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        init = recv()
        assert init["result"]["serverInfo"]["name"] == "echomem"

        send(
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {
                    "name": "memory_ingest",
                    "arguments": {"text": "alpha bravo", "agent_id": "cc"},
                },
            }
        )
        ing = recv()
        ing_payload = json.loads(ing["result"]["content"][0]["text"])
        assert ing_payload["agent_id"] == "cc"

        send(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "memory_recall",
                    "arguments": {"query": "alpha"},
                },
            }
        )
        rec = recv()
        rec_payload = json.loads(rec["result"]["content"][0]["text"])
        assert any("alpha" in h["text"] for h in rec_payload["hits"])

        shim.stdin.close()
        shim.terminate()
        shim.wait(timeout=5)
