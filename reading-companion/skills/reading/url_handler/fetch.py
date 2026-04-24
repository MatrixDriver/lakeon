"""Fetch a URL and extract main content using trafilatura.

Uses an httpx client we accept by injection (so tests can use StaticHttpClient).
In production, callers pass a real `httpx.Client` instance.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Protocol

import trafilatura


class HttpClient(Protocol):
    def get(self, url: str, *args: Any, **kwargs: Any) -> Any: ...


class FetchError(RuntimeError):
    pass


@dataclass(frozen=True)
class FetchedDoc:
    url: str
    title: str
    body: str           # cleaned plain text / markdown
    raw_html: str       # full original response (for evidence blob)


_TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.IGNORECASE | re.DOTALL)


def _fallback_title(html: str, url: str) -> str:
    m = _TITLE_RE.search(html)
    if m:
        return re.sub(r"\s+", " ", m.group(1)).strip()[:80]
    return url


def fetch_url(url: str, *, client: HttpClient, timeout: float = 30.0) -> FetchedDoc:
    """Fetch URL and return cleaned body + original html. Raises FetchError on any failure."""
    try:
        resp = client.get(url, timeout=timeout, follow_redirects=True,
                          headers={"User-Agent": "dbay-reading-companion/0.1"})
        resp.raise_for_status()
    except FetchError:
        raise
    except Exception as exc:  # noqa: BLE001
        raise FetchError(f"HTTP fetch failed for {url}: {exc}") from exc

    html = getattr(resp, "text", "") or ""
    if not html.strip():
        raise FetchError(f"empty response from {url}")

    body = trafilatura.extract(
        html,
        include_comments=False,
        include_tables=True,
        favor_recall=True,
        url=url,
        prune_xpath=["//nav", "//footer", "//header", "//aside"],
    )
    if not body or not body.strip():
        raise FetchError(f"could not extract main text from {url}")

    title = _fallback_title(html, url)
    body = body.strip()
    return FetchedDoc(url=url, title=title, body=body, raw_html=html)
