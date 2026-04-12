"""OpenAI-format tool schemas passed to DeepSeek V3.2 via MaaS."""

TOOL_SCHEMAS: list[dict] = [
    # ── Read tools ─────────────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "list_pages",
            "description": (
                "列出当前知识库的全部 wiki 页面（含 title/summary/updated_at）。"
                "在创建新页面前先调用此工具确认是否已存在同主题页面。"
            ),
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_page",
            "description": (
                "读取一个已有 wiki 页面的完整 markdown 内容。"
                "用于决定该走 update_page 还是 create_page。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {
                        "type": "string",
                        "description": "wiki 页面标题（中文名词短语）",
                    }
                },
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_pages",
            "description": "按关键词搜索 wiki 页面，按 title*3 + summary*1 打分排序。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "关键词（支持多词，空格分隔）"},
                    "top_k": {
                        "type": "integer",
                        "description": "返回的最大匹配数，默认 5",
                    },
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_source",
            "description": (
                "读取本次 ingest 正在处理的原始源文档全文。"
                "通常只在 ingest 流程的第一步调用一次。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "document_id": {
                        "type": "string",
                        "description": "document_id（从 run 上下文中获得）",
                    }
                },
                "required": ["document_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_schema",
            "description": (
                "读取本 KB 的 schema 文档。里面定义了写作规范、页数预算、"
                "命名约定等。每次 run 开始时必须先读一次。"
            ),
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    # ── Write tools ────────────────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "create_page",
            "description": (
                "创建一个新 wiki 页面。仅当现有页面都不合适时才创建；"
                "优先使用 update_page 把新信息融入已有页面。"
                "创建前必须先 list_pages 或 search_pages 确认不重复。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {
                        "type": "string",
                        "description": "中文名词短语（避免通用标题如 '概述'）",
                    },
                    "content": {
                        "type": "string",
                        "description": "页面正文（markdown，含 [[wikilink]]）",
                    },
                    "tags": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "标签列表（可为空）",
                    },
                },
                "required": ["title", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_page",
            "description": (
                "用精确字符串替换更新已有页面的一段内容。"
                "要求 old_text 在页面中唯一匹配；如果匹配多处，"
                "先调 read_page 获取更多上下文再扩大 old_text 的范围。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "old_text": {"type": "string", "description": "要被替换的原文（必须唯一匹配）"},
                    "new_text": {"type": "string", "description": "替换后的新文本"},
                },
                "required": ["title", "old_text", "new_text"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "append_page",
            "description": "向已有页面末尾追加一段内容（如补充章节或新发现）。",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "content": {"type": "string", "description": "要追加的 markdown 片段"},
                },
                "required": ["title", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "delete_page",
            "description": (
                "删除一个 wiki 页面。仅在 curate/lint 模式下使用；"
                "ingest 模式禁止调用此工具。"
            ),
            "parameters": {
                "type": "object",
                "properties": {"title": {"type": "string"}},
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "log_note",
            "description": (
                "向 log.md 追加一行操作记录。"
                "每次 run 结束前调用一次，用一句话总结本次变更。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "message": {"type": "string", "description": "一行简短的操作摘要"}
                },
                "required": ["message"],
            },
        },
    },
    # ── Synthetic termination tool ─────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "done",
            "description": (
                "在所有必要的 wiki 变更完成后调用此工具结束本次 run。"
                "必须提供一句话的 summary 总结本次做了什么。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {
                        "type": "string",
                        "description": "本次 run 的一句话总结（会写入 run log）",
                    }
                },
                "required": ["summary"],
            },
        },
    },
]

TOOL_NAMES: set[str] = {t["function"]["name"] for t in TOOL_SCHEMAS}

# Tools the agent MUST NOT call during ingest runs.
# (curate / lint may use them.)
INGEST_FORBIDDEN: set[str] = {"delete_page"}

# Read-only tools for chat mode (no create/update/delete/log_note/done)
_CHAT_TOOL_NAMES = {"list_pages", "read_page", "search_pages", "read_source", "get_schema"}
CHAT_TOOL_SCHEMAS: list[dict] = [
    t for t in TOOL_SCHEMAS if t["function"]["name"] in _CHAT_TOOL_NAMES
]
