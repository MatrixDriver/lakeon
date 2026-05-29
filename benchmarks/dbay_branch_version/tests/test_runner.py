import json

from dbay_branch_version.cleanup import CleanupRegistry
from dbay_branch_version.runner import build_arg_parser, build_run_plan, create_result_dir, main


def test_arg_parser_supports_dry_run_and_cleanup_only():
    parser = build_arg_parser()

    args = parser.parse_args(["--config", "config.yaml", "--dry-run"])
    assert args.config == "config.yaml"
    assert args.dry_run is True

    args = parser.parse_args(["--config", "config.yaml", "--cleanup-only", "bench_123"])
    assert args.cleanup_only == "bench_123"


def test_build_run_plan_honors_dataset_override(sample_config):
    plan = build_run_plan(sample_config, datasets="S", allow_large_dataset=False)

    assert plan["datasets"] == ["S"]
    assert plan["profile"] == "public-comparison"
    assert plan["will_create_database"] is True


def test_create_result_dir_writes_run_config(tmp_path, sample_config):
    result_dir = create_result_dir(tmp_path, "bench_123", {"datasets": ["S"]})

    assert result_dir.name.endswith("bench_123")
    assert json.loads((result_dir / "run_config.json").read_text(encoding="utf-8")) == {
        "datasets": ["S"]
    }


def test_dry_run_prints_json_plan_without_token(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("DBAY_API_TOKEN", raising=False)
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
datasets: [S, M]
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios: {}
""",
        encoding="utf-8",
    )

    exit_code = main(["--config", str(config_path), "--dry-run"])

    assert exit_code == 0
    plan = json.loads(capsys.readouterr().out)
    assert plan["bench_id"]
    assert plan["plan"]["datasets"] == ["S", "M"]


def test_dry_run_allows_large_dataset_with_cli_override(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("DBAY_API_TOKEN", raising=False)
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
datasets: [L]
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios: {}
""",
        encoding="utf-8",
    )

    exit_code = main(
        ["--config", str(config_path), "--allow-large-dataset", "--dry-run"]
    )

    assert exit_code == 0
    plan = json.loads(capsys.readouterr().out)
    assert plan["plan"]["datasets"] == ["L"]


def test_cleanup_only_uses_result_registry(tmp_path, monkeypatch, sample_config, capsys):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
result_root: results
datasets: [S, M]
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios: {}
""",
        encoding="utf-8",
    )
    registry_dir = tmp_path / "results" / "20260101T000000Z_bench_123"
    CleanupRegistry(
        bench_id="bench_123",
        database_id="db_123",
        database_name="bench-branch-version-20260101-db",
        branches=[],
        versions=[],
    ).write(registry_dir / "cleanup_registry.json")

    calls = []

    class FakeClient:
        def __init__(self, api_base_url, api_token, timeout_seconds):
            calls.append((api_base_url, api_token, timeout_seconds))

        def delete_database(self, database_id, database_name):
            return {}, None

        def close(self):
            calls.append("closed")

    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("DBAY_API_TOKEN", "token-123")
    monkeypatch.setattr("dbay_branch_version.runner.DbayClient", FakeClient)

    exit_code = main(["--config", str(config_path), "--cleanup-only", "bench_123"])

    assert exit_code == 0
    status = json.loads(capsys.readouterr().out)
    assert status["cleanup_status"] == "clean"
    assert calls == [("https://api.dbay.cloud:8443/api/v1", "token-123", 60.0), "closed"]


def test_cleanup_only_missing_registry_returns_error(tmp_path, monkeypatch, capsys):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
result_root: results
datasets: [S, M]
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios: {}
""",
        encoding="utf-8",
    )

    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("DBAY_API_TOKEN", "token-123")

    exit_code = main(["--config", str(config_path), "--cleanup-only", "bench_missing"])

    captured = capsys.readouterr()
    assert exit_code == 2
    assert "bench_missing" in captured.err
    assert "cleanup registry" in captured.err


def test_non_dry_run_returns_2_for_failed_benchmark(tmp_path, monkeypatch, capsys):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
result_root: results
datasets: [S]
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios: {}
""",
        encoding="utf-8",
    )

    class FakeClient:
        def __init__(self, api_base_url, api_token, timeout_seconds):
            pass

        def close(self):
            pass

    def fake_run_benchmark_with_clients(config, plan, result_root, dbay, workload):
        return {
            "bench_id": "bench_failed",
            "result_dir": tmp_path / "results" / "bench_failed",
            "benchmark_status": "failed",
            "cleanup_status": {"cleanup_status": "clean"},
        }

    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("DBAY_API_TOKEN", "token-123")
    monkeypatch.setattr("dbay_branch_version.runner.DbayClient", FakeClient)
    monkeypatch.setattr(
        "dbay_branch_version.runner.run_benchmark_with_clients",
        fake_run_benchmark_with_clients,
    )

    exit_code = main(["--config", str(config_path)])

    assert exit_code == 2
    output = json.loads(capsys.readouterr().out)
    assert output["benchmark_status"] == "failed"
