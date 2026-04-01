"""text_tokenize: tiktoken/jieba 分词统计，计算 token 数和词频分布."""

from collections import Counter

try:
    import tiktoken
except ImportError:
    tiktoken = None  # type: ignore[assignment]

try:
    import jieba
except ImportError:
    jieba = None  # type: ignore[assignment]

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component


def tokenize_tiktoken(text: str, model: str = "cl100k_base") -> list[str]:
    """Tokenize text using tiktoken (OpenAI tokenizer).

    Returns list of token strings.
    """
    if tiktoken is None:
        raise ImportError("tiktoken is required for BPE tokenization: pip install tiktoken")
    enc = tiktoken.get_encoding(model)
    token_ids = enc.encode(text)
    return [enc.decode([tid]) for tid in token_ids]


def tokenize_jieba(text: str) -> list[str]:
    """Tokenize text using jieba Chinese segmentation.

    Returns list of word strings.
    """
    if jieba is None:
        raise ImportError("jieba is required for Chinese segmentation: pip install jieba")
    return list(jieba.cut(text))


def compute_text_stats(tokens: list[str]) -> dict:
    """Compute statistics from a token list.

    Returns dict with: token_count, unique_count, type_token_ratio,
    avg_token_length, top_tokens.
    """
    if not tokens:
        return {
            "token_count": 0,
            "unique_count": 0,
            "type_token_ratio": 0.0,
            "avg_token_length": 0.0,
            "top_tokens": [],
        }

    counter = Counter(tokens)
    unique = len(counter)
    total = len(tokens)
    avg_len = sum(len(t) for t in tokens) / max(total, 1)

    return {
        "token_count": total,
        "unique_count": unique,
        "type_token_ratio": round(unique / max(total, 1), 4),
        "avg_token_length": round(avg_len, 2),
        "top_tokens": counter.most_common(20),
    }


@Component(
    name="text_tokenize",
    display_name="分词统计",
    category="EXTRACT",
    data_type="TEXT",
    params_schema={
        "tokenizer": {
            "type": "string",
            "default": "tiktoken",
            "enum": ["tiktoken", "jieba"],
            "description": "分词器: tiktoken(BPE) 或 jieba(中文分词)",
        },
        "tiktoken_model": {
            "type": "string",
            "default": "cl100k_base",
            "description": "tiktoken 编码模型",
        },
        "compute_stats": {
            "type": "boolean",
            "default": True,
            "description": "是否计算统计信息",
        },
        "text_key": {
            "type": "string",
            "default": "content",
            "description": "文本字段名",
        },
    },
    input_schema={"type": "text_records", "format": "jsonl"},
    output_schema={"type": "text_records", "format": "jsonl"},
)
def text_tokenize(ctx: ComponentContext) -> dict:
    """Tokenize text records and compute statistics."""
    texts = ctx.input["text"]
    tokenizer = ctx.params.get("tokenizer", "tiktoken")
    tiktoken_model = ctx.params.get("tiktoken_model", "cl100k_base")
    compute_stats_flag = ctx.params.get("compute_stats", True)
    text_key = ctx.params.get("text_key", "content")

    total_tokens = 0
    global_counter: Counter = Counter()
    tokenized_texts = []

    for record in texts:
        content = record.get(text_key, "")

        if tokenizer == "tiktoken":
            tokens = tokenize_tiktoken(content, tiktoken_model)
        elif tokenizer == "jieba":
            tokens = tokenize_jieba(content)
        else:
            raise ValueError(f"Unknown tokenizer: {tokenizer}")

        enriched = {**record, "token_count": len(tokens)}

        if compute_stats_flag:
            stats = compute_text_stats(tokens)
            enriched["token_stats"] = stats
            global_counter.update(tokens)

        total_tokens += len(tokens)
        tokenized_texts.append(enriched)

    avg_tokens = total_tokens / max(len(texts), 1)

    ctx.log(
        f"Tokenized {len(texts)} texts with {tokenizer}: "
        f"total={total_tokens}, avg={avg_tokens:.0f} tokens/doc"
    )
    ctx.checkpoint({"total_tokens": total_tokens, "vocabulary_size": len(global_counter)})

    ctx.report({
        "input_count": len(texts),
        "output_count": len(tokenized_texts),
        "tokenizer": tokenizer,
        "total_tokens": total_tokens,
        "avg_tokens_per_doc": round(avg_tokens, 1),
        "vocabulary_size": len(global_counter),
    })

    return {"text": tokenized_texts}
