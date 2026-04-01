"""text_dedup: MinHash 近似去重，基于 datasketch 库."""

import re

try:
    from datasketch import MinHash, MinHashLSH
except ImportError:
    MinHash = None  # type: ignore[assignment, misc]
    MinHashLSH = None  # type: ignore[assignment, misc]

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component


def tokenize_for_minhash(text: str, ngram: int = 3) -> list[str]:
    """Tokenize text into character n-grams for MinHash computation.

    Uses character-level n-grams for language-agnostic deduplication
    (works for both Chinese and English).
    """
    # Normalize: lowercase, collapse whitespace
    text = re.sub(r"\s+", " ", text.lower().strip())
    if len(text) < ngram:
        return [text]
    return [text[i:i + ngram] for i in range(len(text) - ngram + 1)]


def compute_minhash(tokens: list[str], num_perm: int = 128) -> "MinHash":
    """Compute MinHash signature from token list."""
    if MinHash is None:
        raise ImportError("datasketch is required for MinHash deduplication: pip install datasketch")
    m = MinHash(num_perm=num_perm)
    for token in tokens:
        m.update(token.encode("utf-8"))
    return m


def deduplicate_texts(texts: list[dict], similarity_threshold: float = 0.85,
                      num_perm: int = 128, ngram: int = 3,
                      text_key: str = "content") -> tuple[list[dict], list[dict]]:
    """Deduplicate texts using MinHash LSH.

    Args:
        texts: List of text records (dicts with at least a text_key field).
        similarity_threshold: Jaccard similarity threshold for duplicate detection.
        num_perm: Number of permutations for MinHash.
        ngram: Character n-gram size.
        text_key: Key in each record containing the text content.

    Returns:
        (unique_texts, duplicate_texts) tuple.
    """
    if MinHashLSH is None:
        raise ImportError("datasketch is required for MinHash deduplication: pip install datasketch")

    lsh = MinHashLSH(threshold=similarity_threshold, num_perm=num_perm)
    minhashes = []

    for i, record in enumerate(texts):
        content = record.get(text_key, "")
        tokens = tokenize_for_minhash(content, ngram)
        mh = compute_minhash(tokens, num_perm)
        minhashes.append((i, mh))

    unique_indices = set()
    duplicate_indices = set()

    for i, mh in minhashes:
        key = f"doc_{i}"
        # Check if this document is similar to any already-inserted document
        result = lsh.query(mh)
        if result:
            duplicate_indices.add(i)
        else:
            try:
                lsh.insert(key, mh)
                unique_indices.add(i)
            except ValueError:
                # Key already exists (shouldn't happen, but be safe)
                duplicate_indices.add(i)

    unique = [texts[i] for i in sorted(unique_indices)]
    duplicates = [texts[i] for i in sorted(duplicate_indices)]
    return unique, duplicates


@Component(
    name="text_dedup",
    display_name="文本去重",
    category="CLEAN",
    data_type="TEXT",
    params_schema={
        "method": {
            "type": "string",
            "default": "minhash",
            "enum": ["minhash"],
            "description": "去重算法",
        },
        "similarity_threshold": {
            "type": "number",
            "default": 0.85,
            "description": "相似度阈值(Jaccard), 超过此值视为重复",
        },
        "num_perm": {
            "type": "integer",
            "default": 128,
            "description": "MinHash 排列数(精度 vs 速度)",
        },
        "ngram": {
            "type": "integer",
            "default": 3,
            "description": "字符 n-gram 大小",
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
def text_dedup(ctx: ComponentContext) -> dict:
    """Deduplicate text records using MinHash LSH."""
    texts = ctx.input["text"]
    threshold = ctx.params.get("similarity_threshold", 0.85)
    num_perm = ctx.params.get("num_perm", 128)
    ngram = ctx.params.get("ngram", 3)
    text_key = ctx.params.get("text_key", "content")

    ctx.log(f"Deduplicating {len(texts)} texts with threshold={threshold}")

    unique, duplicates = deduplicate_texts(
        texts, threshold, num_perm, ngram, text_key
    )

    ctx.checkpoint({"unique_count": len(unique), "duplicate_count": len(duplicates)})
    ctx.log(f"Result: {len(unique)} unique, {len(duplicates)} duplicates removed")
    ctx.report({
        "input_count": len(texts),
        "output_count": len(unique),
        "duplicate_count": len(duplicates),
        "retention": f"{len(unique)/max(len(texts),1)*100:.1f}%",
    })

    return {"text": unique}
