"""text_quality_score: 基于规则的文本质量评分，条件分支输出 passed/low_quality."""

import math
import re

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component


def score_length(text: str) -> float:
    """Score based on text length. Sweet spot: 200-10000 chars."""
    length = len(text)
    if length < 50:
        return 0.0
    if length < 200:
        return 0.3
    if length <= 10000:
        return 1.0
    if length <= 50000:
        return 0.7
    return 0.4


def score_sentence_structure(text: str) -> float:
    """Score based on sentence structure quality.

    Checks for proper sentence endings, average sentence length, etc.
    """
    # Split by Chinese/English sentence endings
    sentences = re.split(r"[。！？.!?]+", text)
    sentences = [s.strip() for s in sentences if s.strip()]

    if not sentences:
        return 0.0

    avg_len = sum(len(s) for s in sentences) / len(sentences)

    # Score based on average sentence length (chars)
    if avg_len < 5:
        return 0.2  # Too fragmented
    if avg_len > 500:
        return 0.3  # Sentences too long (likely no punctuation)
    if 20 <= avg_len <= 200:
        return 1.0
    return 0.6


def score_repetition(text: str) -> float:
    """Score based on text repetition. Lower repetition = higher score."""
    if len(text) < 100:
        return 0.5

    # Check character-level repetition
    chars = list(text)
    unique_chars = set(chars)
    char_ratio = len(unique_chars) / max(len(chars), 1)

    # Check word/phrase-level repetition (2-gram)
    words = text.split()
    if len(words) < 4:
        return 0.5

    bigrams = [f"{words[i]} {words[i+1]}" for i in range(len(words) - 1)]
    unique_bigrams = set(bigrams)
    bigram_ratio = len(unique_bigrams) / max(len(bigrams), 1)

    # Combine: lower repetition = higher score
    return round(min(char_ratio * 2, 1.0) * 0.4 + min(bigram_ratio * 1.5, 1.0) * 0.6, 3)


def score_special_chars(text: str) -> float:
    """Score based on special character ratio. Too many = low quality."""
    if not text:
        return 0.0

    special_count = sum(1 for c in text if not c.isalnum() and not c.isspace()
                        and c not in "。，！？、；：\u201c\u201d\u2018\u2019《》（）.,:;!?\"'()-")
    ratio = special_count / max(len(text), 1)

    if ratio > 0.3:
        return 0.1
    if ratio > 0.15:
        return 0.4
    if ratio > 0.05:
        return 0.7
    return 1.0


def score_information_density(text: str) -> float:
    """Score based on information density heuristic.

    Approximates entropy using unique character ratio and vocabulary richness.
    """
    if len(text) < 20:
        return 0.3

    # Character-level entropy approximation
    chars = list(text.lower())
    char_freq: dict[str, int] = {}
    for c in chars:
        char_freq[c] = char_freq.get(c, 0) + 1

    total = len(chars)
    entropy = -sum(
        (count / total) * math.log2(count / total)
        for count in char_freq.values()
    )

    # Normalize: typical text entropy is 3-5 bits/char
    if entropy < 2.0:
        return 0.2
    if entropy > 6.0:
        return 0.5  # Could be gibberish
    return min(entropy / 5.0, 1.0)


def compute_quality_score(text: str) -> dict:
    """Compute overall quality score from multiple sub-scores.

    Returns dict with sub-scores and weighted overall score.
    """
    scores = {
        "length": score_length(text),
        "sentence_structure": score_sentence_structure(text),
        "repetition": score_repetition(text),
        "special_chars": score_special_chars(text),
        "information_density": score_information_density(text),
    }

    # Weighted average
    weights = {
        "length": 0.15,
        "sentence_structure": 0.25,
        "repetition": 0.25,
        "special_chars": 0.15,
        "information_density": 0.20,
    }

    overall = sum(scores[k] * weights[k] for k in scores)
    scores["overall"] = round(overall, 3)

    return scores


@Component(
    name="text_quality_score",
    display_name="文本质量评分",
    category="QC",
    data_type="TEXT",
    params_schema={
        "scorer": {
            "type": "string",
            "default": "rule",
            "enum": ["rule"],
            "description": "评分方法: rule=基于规则",
        },
        "min_score": {
            "type": "number",
            "default": 0.6,
            "description": "最低质量分(低于此值标记为 low_quality)",
        },
        "text_key": {
            "type": "string",
            "default": "content",
            "description": "文本字段名",
        },
    },
    output_branches=["passed", "low_quality"],
    input_schema={"type": "text_records", "format": "jsonl"},
    output_schema={"type": "text_records", "format": "jsonl"},
)
def text_quality_score(ctx: ComponentContext) -> dict:
    """Score text quality and route to passed/low_quality branches."""
    texts = ctx.input["text"]
    min_score = ctx.params.get("min_score", 0.6)
    text_key = ctx.params.get("text_key", "content")

    passed = []
    low_quality = []

    for record in texts:
        content = record.get(text_key, "")
        scores = compute_quality_score(content)

        enriched = {**record, "quality_scores": scores}

        if scores["overall"] >= min_score:
            passed.append(enriched)
        else:
            low_quality.append(enriched)

    ctx.log(
        f"Quality scoring: {len(texts)} input, "
        f"{len(passed)} passed (>={min_score}), {len(low_quality)} low quality"
    )
    ctx.report({
        "input_count": len(texts),
        "passed_count": len(passed),
        "low_quality_count": len(low_quality),
        "min_score": min_score,
        "avg_score": round(
            sum(r["quality_scores"]["overall"] for r in passed + low_quality) / max(len(texts), 1),
            3,
        ),
        "retention": f"{len(passed)/max(len(texts),1)*100:.1f}%",
    })

    # Return both branches; Orchestrator routes based on output_branches
    return {
        "passed": passed,
        "low_quality": low_quality,
    }
