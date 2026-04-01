"""Unit tests for text_clean component."""

from unittest.mock import MagicMock

import pytest

from components.text.text_clean import (
    strip_html,
    remove_urls,
    remove_emails,
    normalize_whitespace,
    detect_language,
    clean_text,
    text_clean,
)


class TestStripHtml:
    def test_removes_tags(self):
        assert strip_html("<p>Hello <b>world</b></p>") == "Hello world"

    def test_decodes_entities(self):
        assert strip_html("&amp; &lt; &gt;") == "& < >"

    def test_no_html(self):
        assert strip_html("plain text") == "plain text"


class TestRemoveUrls:
    def test_removes_http(self):
        assert remove_urls("Visit https://example.com today") == "Visit  today"

    def test_removes_www(self):
        assert remove_urls("See www.example.com") == "See "

    def test_no_urls(self):
        assert remove_urls("no urls here") == "no urls here"


class TestRemoveEmails:
    def test_removes_email(self):
        assert remove_emails("Contact user@example.com") == "Contact "

    def test_no_emails(self):
        assert remove_emails("no email here") == "no email here"


class TestNormalizeWhitespace:
    def test_collapses_spaces(self):
        assert normalize_whitespace("hello    world") == "hello world"

    def test_collapses_newlines(self):
        assert normalize_whitespace("a\n\n\n\n\nb") == "a\n\nb"

    def test_strips_edges(self):
        assert normalize_whitespace("  hello  ") == "hello"


class TestDetectLanguage:
    def test_chinese(self):
        assert detect_language("这是一段中文测试文本") == "zh"

    def test_english(self):
        assert detect_language("This is an English test text") == "en"

    def test_empty(self):
        assert detect_language("") is None

    def test_mixed_mostly_chinese(self):
        assert detect_language("这是一段中文内容，包含 some English") == "zh"


class TestCleanText:
    def test_full_pipeline(self):
        dirty = "<p>Hello   world</p> https://example.com user@test.com"
        result = clean_text(dirty)
        assert "<p>" not in result
        assert "https://" not in result
        assert "@" not in result
        assert "Hello" in result


class TestTextClean:
    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "remove_html": True,
            "normalize_whitespace": True,
            "remove_urls": True,
            "remove_emails": True,
            "min_length": 10,
            "max_length": 1000,
            "language_filter": ["zh", "en"],
            "text_key": "content",
        }
        ctx.input = {
            "text": [
                {"id": 1, "content": "This is a valid English text that should pass the length filter"},
                {"id": 2, "content": "short"},  # too short
                {"id": 3, "content": "<p>这是一段有效的中文文本，应该通过长度过滤器</p>"},
                {"id": 4, "content": "Visit https://example.com for more info about this topic"},
            ]
        }
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        return ctx

    def test_filters_short_text(self, mock_ctx):
        result = text_clean(mock_ctx)

        assert "text" in result
        ids = [r["id"] for r in result["text"]]
        assert 2 not in ids  # "short" dropped

    def test_cleans_html(self, mock_ctx):
        result = text_clean(mock_ctx)

        html_doc = next(r for r in result["text"] if r["id"] == 3)
        assert "<p>" not in html_doc["content"]

    def test_removes_urls_from_content(self, mock_ctx):
        result = text_clean(mock_ctx)

        url_doc = next(r for r in result["text"] if r["id"] == 4)
        assert "https://" not in url_doc["content"]

    def test_reports_metrics(self, mock_ctx):
        text_clean(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 4
        assert report["dropped_short"] == 1
