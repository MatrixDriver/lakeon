"""OpenAI-compatible LLM client for server-side memory extraction and digest."""
import json
import logging
import os

import httpx

logger = logging.getLogger(__name__)

CHAT_API_URL = os.getenv("CHAT_API_URL", "https://api.siliconflow.cn/v1")
CHAT_API_KEY = os.getenv("CHAT_API_KEY", os.getenv("EMBEDDING_API_KEY", ""))
CHAT_MODEL = os.getenv("CHAT_MODEL", "Qwen/Qwen2.5-7B-Instruct")


async def chat_extract(prompt: str) -> dict:
    """Call LLM with extraction/digest prompt. Returns parsed JSON dict."""
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{CHAT_API_URL}/chat/completions",
            headers={"Authorization": f"Bearer {CHAT_API_KEY}"},
            json={
                "model": CHAT_MODEL,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.1,
                "response_format": {"type": "json_object"},
            },
        )
        resp.raise_for_status()
        text = resp.json()["choices"][0]["message"]["content"]
        return json.loads(text)
