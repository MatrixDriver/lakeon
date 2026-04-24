"""Tests for reading/url_handler — fetch, extract, related, full flow."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from agent_session_log import LogStore


# ────────── Fakes shared across tests ──────────

class FakeLLM:
    """Record calls; return whatever is enqueued next."""
    def __init__(self, responses: list[dict]):
        self.responses = list(responses)
        self.calls: list[dict] = []

    def complete(self, *, system, user, tools=None):
        self.calls.append({"system": system, "user": user})
        if not self.responses:
            raise AssertionError("FakeLLM ran out of canned responses")
        return self.responses.pop(0)


class StaticHttpClient:
    """Stand-in for httpx.Client.get(): return canned responses by URL."""
    def __init__(self, pages: dict[str, tuple[int, str]]):
        self.pages = pages
        self.calls: list[str] = []

    def get(self, url: str, *args, **kwargs):
        self.calls.append(url)
        if url not in self.pages:
            class R:
                status_code = 404
                text = ""
                def raise_for_status(self): raise RuntimeError("404")
            return R()
        status, html = self.pages[url]
        class R:
            def __init__(self, s, h):
                self.status_code = s
                self.text = h
            def raise_for_status(self):
                if self.status_code >= 400:
                    raise RuntimeError(f"HTTP {self.status_code}")
        return R(status, html)


# ────────── fetch.py tests ──────────

def test_fetch_url_strips_html_keeps_main_text():
    from skills.reading.url_handler.fetch import fetch_url

    html = """
    <html><head><title>My Post</title></head>
    <body>
      <nav>Home About</nav>
      <article>
        <h1>Headline</h1>
        <p>This is the first paragraph of real content.</p>
        <p>And here is a second paragraph with detail.</p>
      </article>
      <footer>(c) 2026</footer>
    </body></html>
    """
    http = StaticHttpClient({"https://x.com/post": (200, html)})

    doc = fetch_url("https://x.com/post", client=http)

    assert doc.url == "https://x.com/post"
    assert "first paragraph" in doc.body
    assert "second paragraph" in doc.body
    # Chrome / nav / footer should not leak into the body
    assert "Home About" not in doc.body
    assert "(c) 2026" not in doc.body
    assert doc.title  # extracted by trafilatura or fallback to <title>


def test_fetch_url_404_raises():
    from skills.reading.url_handler.fetch import fetch_url, FetchError

    http = StaticHttpClient({})
    with pytest.raises(FetchError, match="HTTP"):
        fetch_url("https://x.com/missing", client=http)


def test_fetch_url_empty_body_raises():
    from skills.reading.url_handler.fetch import fetch_url, FetchError

    # HTML with no extractable content (just a <script>)
    http = StaticHttpClient({"https://x": (200, "<html><body><script>x=1</script></body></html>")})
    with pytest.raises(FetchError, match="extract"):
        fetch_url("https://x", client=http)
