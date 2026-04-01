"""text_clean: HTML 清理、空白标准化、URL 移除、长度过滤."""

import html
import re
import unicodedata

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component

# Regex patterns compiled once
URL_PATTERN = re.compile(
    r"https?://[^\s<>\"']+|www\.[^\s<>\"']+",
    re.IGNORECASE,
)
EMAIL_PATTERN = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
HTML_TAG_PATTERN = re.compile(r"<[^>]+>")
MULTI_NEWLINE_PATTERN = re.compile(r"\n{3,}")
MULTI_SPACE_PATTERN = re.compile(r"[ \t]{2,}")


def strip_html(text: str) -> str:
    """Remove HTML tags and decode HTML entities."""
    text = HTML_TAG_PATTERN.sub("", text)
    text = html.unescape(text)
    return text


def remove_urls(text: str) -> str:
    """Remove URLs from text."""
    return URL_PATTERN.sub("", text)


def remove_emails(text: str) -> str:
    """Remove email addresses from text."""
    return EMAIL_PATTERN.sub("", text)


def normalize_whitespace(text: str) -> str:
    """Normalize whitespace: collapse multiple spaces/newlines, strip edges."""
    text = MULTI_SPACE_PATTERN.sub(" ", text)
    text = MULTI_NEWLINE_PATTERN.sub("\n\n", text)
    return text.strip()


def normalize_unicode(text: str) -> str:
    """Normalize Unicode to NFC form, remove control characters."""
    text = unicodedata.normalize("NFC", text)
    # Remove control characters except newline and tab
    text = "".join(
        c for c in text
        if not unicodedata.category(c).startswith("C") or c in ("\n", "\t")
    )
    return text


def detect_language(text: str) -> str | None:
    """Simple heuristic language detection: 'zh', 'en', or None.

    Checks character distribution. Not a full language detector,
    but sufficient for basic CJK vs Latin filtering.
    """
    if not text:
        return None

    cjk_count = sum(1 for c in text if "\u4e00" <= c <= "\u9fff")
    latin_count = sum(1 for c in text if c.isascii() and c.isalpha())
    total = max(cjk_count + latin_count, 1)

    if cjk_count / total > 0.3:
        return "zh"
    if latin_count / total > 0.3:
        return "en"
    return None


def clean_text(text: str, remove_html_flag: bool = True,
               normalize_ws: bool = True, remove_urls_flag: bool = True,
               remove_emails_flag: bool = True) -> str:
    """Apply all cleaning steps to a text string."""
    if remove_html_flag:
        text = strip_html(text)
    if remove_urls_flag:
        text = remove_urls(text)
    if remove_emails_flag:
        text = remove_emails(text)
    text = normalize_unicode(text)
    if normalize_ws:
        text = normalize_whitespace(text)
    return text


@Component(
    name="text_clean",
    display_name="文本清洗",
    category="CLEAN",
    data_type="TEXT",
    params_schema={
        "remove_html": {"type": "boolean", "default": True, "description": "去除 HTML 标签"},
        "normalize_whitespace": {"type": "boolean", "default": True, "description": "标准化空白字符"},
        "remove_urls": {"type": "boolean", "default": True, "description": "移除 URL"},
        "remove_emails": {"type": "boolean", "default": True, "description": "移除邮箱地址"},
        "min_length": {"type": "integer", "default": 50, "description": "最小文本长度(字符)"},
        "max_length": {"type": "integer", "default": 100000, "description": "最大文本长度(字符)"},
        "language_filter": {
            "type": "array",
            "default": ["zh", "en"],
            "description": "保留的语言列表, 空=不过滤",
        },
        "text_key": {"type": "string", "default": "content", "description": "文本字段名"},
    },
    input_schema={"type": "text_records", "format": "jsonl"},
    output_schema={"type": "text_records", "format": "jsonl"},
)
def text_clean(ctx: ComponentContext) -> dict:
    """Clean and filter text records: HTML strip, URL removal, length/language filter."""
    texts = ctx.input["text"]
    params = ctx.params
    text_key = params.get("text_key", "content")
    min_len = params.get("min_length", 50)
    max_len = params.get("max_length", 100000)
    lang_filter = params.get("language_filter", ["zh", "en"])

    cleaned = []
    dropped_short = 0
    dropped_long = 0
    dropped_lang = 0

    for record in texts:
        content = record.get(text_key, "")

        # Apply cleaning
        content = clean_text(
            content,
            remove_html_flag=params.get("remove_html", True),
            normalize_ws=params.get("normalize_whitespace", True),
            remove_urls_flag=params.get("remove_urls", True),
            remove_emails_flag=params.get("remove_emails", True),
        )

        # Length filter
        if len(content) < min_len:
            dropped_short += 1
            continue
        if len(content) > max_len:
            dropped_long += 1
            continue

        # Language filter
        if lang_filter:
            lang = detect_language(content)
            if lang is not None and lang not in lang_filter:
                dropped_lang += 1
                continue

        cleaned_record = {**record, text_key: content}
        cleaned.append(cleaned_record)

    ctx.log(
        f"Cleaned {len(texts)} texts: {len(cleaned)} passed, "
        f"{dropped_short} too short, {dropped_long} too long, {dropped_lang} wrong language"
    )
    ctx.report({
        "input_count": len(texts),
        "output_count": len(cleaned),
        "dropped_short": dropped_short,
        "dropped_long": dropped_long,
        "dropped_language": dropped_lang,
        "retention": f"{len(cleaned)/max(len(texts),1)*100:.1f}%",
    })

    return {"text": cleaned}
