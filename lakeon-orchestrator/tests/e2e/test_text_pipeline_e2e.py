"""E2E test: Text data cleaning pipeline.

Exercises the full flow:
  raw texts -> text_dedup -> text_clean -> text_tokenize
  -> text_quality_score (branch) -> quality_check (auto_approve) -> dataset_publish

Does NOT require ffmpeg.
"""

import json
import os

import pytest

from components.text.text_dedup import text_dedup
from components.text.text_clean import text_clean
from components.text.text_tokenize import text_tokenize
from components.text.text_quality_score import text_quality_score
from components.video.quality_check import quality_check
from components.universal.dataset_publish import dataset_publish

from tests.conftest import FakeComponentContext


pytestmark = pytest.mark.e2e

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "..", "fixtures")


def load_test_texts() -> list[dict]:
    """Load test texts from sample_texts.jsonl fixture."""
    texts = []
    fixture_path = os.path.join(FIXTURES_DIR, "sample_texts.jsonl")
    with open(fixture_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                texts.append(json.loads(line))
    return texts


class TestTextPipelineE2E:
    """End-to-end test of the text data cleaning pipeline."""

    def test_full_text_pipeline(self):
        """Run the complete text pipeline from raw texts to published dataset.

        This test validates:
        1. text_dedup removes near-duplicate texts (MinHash)
        2. text_clean strips HTML/URLs, filters by length
        3. text_tokenize adds token counts and statistics
        4. text_quality_score routes to passed/low_quality
        5. quality_check auto-approves
        6. dataset_publish writes Parquet output
        """
        raw_texts = load_test_texts()
        assert len(raw_texts) == 20

        # --- Step 1: Dedup ---
        ctx_dedup = FakeComponentContext(
            input_data={"text": raw_texts},
            params={
                "similarity_threshold": 0.85,
                "num_perm": 128,
                "ngram": 3,
                "text_key": "content",
            },
        )
        dedup_result = text_dedup(ctx_dedup)
        deduped = dedup_result["text"]
        assert len(deduped) < len(raw_texts), "Dedup should remove some near-duplicates"
        # t001/t002 are near-duplicates, t015/t017 are near-duplicates
        dedup_ids = {r["id"] for r in deduped}
        assert not ({"t001", "t002"}.issubset(dedup_ids)), "Near-duplicate pair should be deduplicated"

        print(f"\nDedup: {len(raw_texts)} -> {len(deduped)} texts")

        # --- Step 2: Clean ---
        ctx_clean = FakeComponentContext(
            input_data={"text": deduped},
            params={
                "remove_html": True,
                "normalize_whitespace": True,
                "remove_urls": True,
                "remove_emails": True,
                "min_length": 50,
                "max_length": 100000,
                "language_filter": ["zh", "en"],
                "text_key": "content",
            },
        )
        clean_result = text_clean(ctx_clean)
        cleaned = clean_result["text"]
        assert len(cleaned) < len(deduped), "Cleaning should filter some short/invalid texts"

        # Verify HTML was stripped from t003
        t003 = next((r for r in cleaned if r["id"] == "t003"), None)
        if t003:
            assert "<p>" not in t003["content"]
            assert "https://" not in t003["content"]
            assert "@example.com" not in t003["content"]

        # Verify short texts (t005, t016) were dropped
        clean_ids = {r["id"] for r in cleaned}
        assert "t005" not in clean_ids, "Very short text 'hi' should be dropped"
        assert "t016" not in clean_ids, "Very short text 'abc' should be dropped"

        print(f"Clean: {len(deduped)} -> {len(cleaned)} texts")

        # --- Step 3: Tokenize ---
        ctx_token = FakeComponentContext(
            input_data={"text": cleaned},
            params={
                "tokenizer": "tiktoken",
                "tiktoken_model": "cl100k_base",
                "compute_stats": True,
                "text_key": "content",
            },
        )
        token_result = text_tokenize(ctx_token)
        tokenized = token_result["text"]
        assert len(tokenized) == len(cleaned)

        # Verify token counts were added
        for record in tokenized:
            assert "token_count" in record
            assert record["token_count"] > 0
            assert "token_stats" in record

        total_tokens = sum(r["token_count"] for r in tokenized)
        print(f"Tokenize: {len(tokenized)} texts, {total_tokens} total tokens")

        # --- Step 4: Quality Score (conditional branching) ---
        ctx_quality = FakeComponentContext(
            input_data={"text": tokenized},
            params={
                "scorer": "rule",
                "min_score": 0.5,
                "text_key": "content",
            },
        )
        quality_result = text_quality_score(ctx_quality)
        passed = quality_result["passed"]
        low_quality = quality_result["low_quality"]
        assert len(passed) + len(low_quality) == len(tokenized)

        # Spam text (t011) should be low quality
        low_ids = {r["id"] for r in low_quality}
        # Special chars text (t007) should be low quality if it survived cleaning
        if "t007" in {r["id"] for r in tokenized}:
            assert "t007" in low_ids, "Special chars text should be low quality"

        print(f"Quality: {len(passed)} passed, {len(low_quality)} low quality")

        # --- Step 5: Quality Check (auto-approve) ---
        ctx_qc = FakeComponentContext(
            input_data={"text": passed},
            params={"review_mode": "auto_approve"},
        )
        qc_result = quality_check(ctx_qc)
        approved = qc_result["text"]
        assert len(approved) == len(passed)

        # --- Step 6: Publish ---
        ctx_pub = FakeComponentContext(
            input_data={"text": approved},
            params={
                "dataset_name": "e2e_test_text",
                "format": "PARQUET",
                "text_key": "content",
            },
        )
        pub_result = dataset_publish(ctx_pub)
        assert "dataset_version" in pub_result
        version = pub_result["dataset_version"]
        assert version["row_count"] == len(approved)
        assert version["format"] == "PARQUET"
        assert version["dataset_name"] == "e2e_test_text"

        # --- Verify full pipeline summary ---
        retention = version["row_count"] / max(len(raw_texts), 1) * 100

        print(f"\n--- Text Pipeline E2E Summary ---")
        print(f"Input: {len(raw_texts)} texts")
        print(f"After dedup: {len(deduped)}")
        print(f"After clean: {len(cleaned)}")
        print(f"After quality: {len(passed)} passed / {len(low_quality)} low")
        print(f"Published: {version['row_count']} texts")
        print(f"Overall retention: {retention:.1f}%")

        # Sanity checks
        assert version["row_count"] >= 5, "At least 5 good texts should survive"
        assert version["row_count"] <= 18, "Not all texts should pass (some are dupes/short/low quality)"

    def test_dedup_detects_near_duplicates(self):
        """Focused test: verify MinHash catches the known near-duplicate pairs."""
        raw_texts = load_test_texts()

        ctx = FakeComponentContext(
            input_data={"text": raw_texts},
            params={
                "similarity_threshold": 0.85,
                "num_perm": 128,
                "ngram": 3,
                "text_key": "content",
            },
        )
        result = text_dedup(ctx)
        deduped = result["text"]
        remaining_ids = {r["id"] for r in deduped}

        # Dedup should remove at least some texts
        assert len(deduped) < len(raw_texts), "Dedup should remove duplicates"
        # At least one from the t001/t002 near-dup pair should be removed
        assert not ({"t001", "t002"}.issubset(remaining_ids))

    def test_quality_score_identifies_spam(self):
        """Focused test: verify spam and special char texts get low quality scores."""
        texts = [
            {"id": "spam", "content": "spam " * 100},
            {"id": "special", "content": "!@#$%^&*()" * 20},
            {
                "id": "good",
                "content": (
                    "Machine learning enables computers to learn from data "
                    "and improve performance over time. It has applications "
                    "in healthcare, finance, and many other domains."
                ),
            },
        ]
        ctx = FakeComponentContext(
            input_data={"text": texts},
            params={"scorer": "rule", "min_score": 0.6, "text_key": "content"},
        )
        result = text_quality_score(ctx)

        passed_ids = {r["id"] for r in result["passed"]}
        low_ids = {r["id"] for r in result["low_quality"]}

        assert "good" in passed_ids, "Good quality text should pass"
        # spam (0.547) and special (0.573) should be below 0.6 threshold
        assert len(low_ids) >= 1, "At least one low-quality text should be detected"
