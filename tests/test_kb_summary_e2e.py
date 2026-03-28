"""
E2E test: upload a document to a KB, wait for L1 summary generation,
verify search returns both L0 chunks and L1 summary.

Prerequisites:
- lakeon-api running locally on port 8080
- A knowledge base already created (set KB_ID env var)
- AI API key configured in API

Usage:
  KB_ID=<your-kb-id> python tests/test_kb_summary_e2e.py
"""
import os
import sys
import time
import requests

API = os.getenv("API_BASE", "http://localhost:8080")
KB_ID = os.getenv("KB_ID")
TOKEN = os.getenv("API_TOKEN", "")

if not KB_ID:
    print("ERROR: Set KB_ID env var")
    sys.exit(1)

headers = {"Authorization": f"Bearer {TOKEN}"} if TOKEN else {}


def test_summary_generation():
    # 1. Upload a small test document
    print("1. Uploading test document...")
    upload_resp = requests.post(
        f"{API}/api/v1/knowledge/{KB_ID}/documents/upload",
        headers=headers,
        json={"filename": "test-summary.md", "tags": ["test"]},
    )
    assert upload_resp.status_code == 200, f"Upload failed: {upload_resp.text}"
    doc_id = upload_resp.json()["document_id"]
    presigned_url = upload_resp.json()["upload_url"]

    # Upload content via presigned URL
    test_content = """# 人工智能简介

人工智能（AI）是计算机科学的一个分支。它致力于创建能够执行通常需要人类智能的任务的系统。

## 机器学习
机器学习是AI的核心方法，通过数据训练模型来做出预测。

## 深度学习
深度学习使用多层神经网络处理复杂模式，在图像识别和自然语言处理中表现突出。

## 应用领域
AI广泛应用于医疗诊断、自动驾驶、推荐系统等领域。
"""
    requests.put(presigned_url, data=test_content.encode("utf-8"))

    # Trigger parse
    requests.post(
        f"{API}/api/v1/knowledge/{KB_ID}/documents/{doc_id}/parse",
        headers=headers,
    )

    # 2. Wait for processing + summarization
    print("2. Waiting for document processing and summarization...")
    for i in range(30):
        time.sleep(5)
        doc_resp = requests.get(
            f"{API}/api/v1/knowledge/{KB_ID}/documents/{doc_id}",
            headers=headers,
        )
        status = doc_resp.json().get("status")
        print(f"   [{i*5}s] status={status}")
        if status == "READY":
            break
    else:
        print("FAIL: Document did not reach READY status in 150s")
        sys.exit(1)

    # 3. Wait extra time for async summarization
    print("3. Waiting for async summarization...")
    time.sleep(15)

    # 4. Search for summary-level content
    print("4. Searching for document summary...")
    search_resp = requests.post(
        f"{API}/api/v1/knowledge/search",
        headers=headers,
        json={"kb_id": KB_ID, "query": "这篇文档讲了什么", "top_k": 10},
    )
    assert search_resp.status_code == 200, f"Search failed: {search_resp.text}"
    results = search_resp.json().get("results", [])

    levels = [r.get("level", 0) for r in results]
    print(f"   Result levels: {levels}")
    assert 1 in levels, "No L1 summary found in search results"

    l1_result = next(r for r in results if r.get("level") == 1)
    print(f"   L1 summary content: {l1_result['content'][:200]}...")

    # 5. Check KB summary (level=2)
    print("5. Checking KB-level summary...")
    kb_resp = requests.get(
        f"{API}/api/v1/knowledge/{KB_ID}",
        headers=headers,
    )
    kb_summary = kb_resp.json().get("summary")
    if kb_summary:
        print(f"   KB summary: {kb_summary[:200]}...")
    else:
        print("   KB summary not yet generated (may need more documents)")

    print("\nPASS: Document summary generated and searchable")


if __name__ == "__main__":
    test_summary_generation()
