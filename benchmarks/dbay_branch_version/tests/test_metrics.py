import csv
import json

from dbay_branch_version.metrics import OperationSample, redact_secret, summarize_samples, write_raw_csv


def test_redact_secret_hides_tokens_and_passwords():
    text = "Authorization: Bearer lk_secret postgresql://user:pass@host/db"

    redacted = redact_secret(text)

    assert "lk_secret" not in redacted
    assert "pass@host" not in redacted
    assert "Bearer [REDACTED]" in redacted
    assert "postgresql://[REDACTED]" in redacted


def test_redact_secret_hides_full_bearer_token_with_symbols():
    text = "Authorization: Bearer abc+secret/part==~"

    redacted = redact_secret(text)

    assert redacted == "Authorization: Bearer [REDACTED]"
    assert "abc" not in redacted
    assert "+secret/part==~" not in redacted


def test_summarize_samples_groups_by_scenario_operation():
    samples = [
        OperationSample(bench_id="b1", dataset="S", scenario="branch", operation="create", api_latency_ms=10, success=True),
        OperationSample(bench_id="b1", dataset="S", scenario="branch", operation="create", api_latency_ms=30, success=True),
        OperationSample(bench_id="b1", dataset="S", scenario="branch", operation="create", api_latency_ms=50, success=False),
    ]

    summary = summarize_samples(samples)
    key = "branch/create/S"

    assert summary[key]["sample_count"] == 3
    assert summary[key]["success_count"] == 2
    assert summary[key]["error_rate"] == 1 / 3
    assert summary[key]["api_latency_ms"]["p50"] == 30


def test_write_raw_csv_redacts_error_message(tmp_path):
    sample = OperationSample(
        bench_id="b1",
        dataset="S",
        scenario="version",
        operation="create",
        api_latency_ms=12.5,
        success=False,
        error_message="Bearer lk_secret",
    )

    path = tmp_path / "raw_samples.csv"
    write_raw_csv(path, [sample])

    rows = list(csv.DictReader(path.open(newline="", encoding="utf-8")))
    assert rows[0]["error_message"] == "Bearer [REDACTED]"
