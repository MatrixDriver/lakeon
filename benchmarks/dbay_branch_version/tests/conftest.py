import pytest

from dbay_branch_version.config import BenchmarkConfig


@pytest.fixture
def sample_config():
    return BenchmarkConfig(
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
