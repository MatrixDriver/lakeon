import pytest

from dbay_branch_version.config import BenchmarkConfig, ConfigError, load_config


def test_load_config_rejects_large_dataset_without_flag(tmp_path):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
datasets: [S, L]
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

    with pytest.raises(ConfigError, match="Dataset L requires"):
        load_config(config_path)


def test_load_config_rejects_string_large_dataset_flag(tmp_path):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
datasets: [L]
allow_large_dataset: "false"
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios: {}
""",
        encoding="utf-8",
    )

    with pytest.raises(ConfigError, match="allow_large_dataset must be a boolean"):
        load_config(config_path)


def test_config_builds_bench_name_with_safe_prefix():
    config = BenchmarkConfig(
        profile="public-comparison",
        resource_prefix="bench-branch-version",
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        compute_size="1cu",
        poll_interval_seconds=2.0,
        poll_timeout_seconds=60.0,
        request_timeout_seconds=30.0,
        result_root="results",
        datasets=("S", "M"),
        allow_large_dataset=False,
        limits={
            "max_branch_concurrency": 10,
            "max_total_branches": 20,
            "max_total_versions": 20,
            "max_runtime_seconds": 100,
        },
        scenarios={},
    )

    name = config.make_database_name("20260529t120000z", "abc123")

    assert name == "bench-branch-version-20260529t120000z-abc123"
    assert config.is_benchmark_database_name(name)
    assert not config.is_benchmark_database_name("customer-prod")


def test_concurrency_above_limit_is_rejected(tmp_path):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
datasets: [S]
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios:
  branch_create_concurrent:
    concurrency: [11]
""",
        encoding="utf-8",
    )

    with pytest.raises(ConfigError, match="max_branch_concurrency"):
        load_config(config_path)
