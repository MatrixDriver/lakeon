import json

from dbay_branch_version.runner import main


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
    assert plan["dry_run"] is True
    assert plan["datasets"] == ["S", "M"]
