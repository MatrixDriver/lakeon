import pytest
from echomem.context.fetcher import fetch_url, ExtractedDoc


@pytest.mark.asyncio
async def test_fetch_html_extracts_main_text(httpx_mock):
    html = "<html><body><nav>nav</nav><article><h1>Title</h1><p>Body text.</p></article></body></html>"
    httpx_mock.add_response(
        method="GET", url="https://example.com/post",
        headers={"content-type": "text/html; charset=utf-8"},
        content=html.encode("utf-8"),
    )
    doc = await fetch_url("https://example.com/post")
    assert isinstance(doc, ExtractedDoc)
    assert doc.mime.startswith("text/html")
    assert "Body text" in doc.text or "Title" in doc.text  # trafilatura output
    assert doc.byte_size > 0


@pytest.mark.asyncio
async def test_fetch_plain_text_passthrough(httpx_mock):
    httpx_mock.add_response(
        method="GET", url="https://example.com/notes.txt",
        headers={"content-type": "text/plain"},
        content=b"raw text passthrough",
    )
    doc = await fetch_url("https://example.com/notes.txt")
    assert doc.text == "raw text passthrough"
    assert doc.mime == "text/plain"


@pytest.mark.asyncio
async def test_fetch_unknown_mime_returns_bytes_no_text(httpx_mock):
    httpx_mock.add_response(
        method="GET", url="https://example.com/img.png",
        headers={"content-type": "image/png"},
        content=b"\x89PNG\r\n",
    )
    doc = await fetch_url("https://example.com/img.png")
    assert doc.mime == "image/png"
    assert doc.text == ""
    assert doc.raw_bytes == b"\x89PNG\r\n"
