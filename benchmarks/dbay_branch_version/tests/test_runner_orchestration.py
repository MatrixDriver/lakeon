import csv
import json

from dbay_branch_version.dbay_client import DbayApiError
from dbay_branch_version.metrics import OperationSample
from dbay_branch_version.runner import run_benchmark_with_clients


class FakeDbay:
    def __init__(self):
        self.created_database = False

    def create_database(self, name, compute_size):
        self.created_database = True
        return {
            "id": "db_1",
            "name": name,
            "connection_uri": "postgresql://user:pass@host/db",
            "status": "running",
        }, None

    def list_branches(self, db_id):
        return [
            {
                "id": "br_main",
                "name": "main",
                "is_default": True,
                "connection_uri": "postgresql://user:pass@host/db",
            }
        ], None

    def create_branch(self, db_id, name, start_compute=False, parent_branch_id=None):
        return {
            "id": f"br_{name}",
            "name": name,
            "is_default": False,
            "connection_uri": "postgresql://user:pass@host/db",
        }, None

    def create_version(self, db_id, branch_id, name, description=""):
        return {
            "id": f"ver_{name}",
            "branch_id": branch_id,
            "name": name,
            "lsn": "0/1",
            "snapshot_timeline_id": "tl_1",
        }, None

    def delete_version(self, db_id, database_name, branch_id, version_id):
        return {}, None

    def delete_branch(self, db_id, database_name, branch_id, branch_name, is_default=False):
        return {}, None

    def delete_database(self, db_id, name):
        return {}, None


class FakeWorkload:
    def load_dataset(self, connstr, dataset):
        return None

    def fetch_checksums(self, connstr):
        return {"bench_oltp": "a", "bench_jsonb": "b", "bench_events": "c"}


class FailingVersionDbay(FakeDbay):
    def create_version(self, db_id, branch_id, name, description=""):
        raise DbayApiError(
            500,
            {"error": "postgresql://user:pass@host/db version failed"},
            sample=OperationSample(
                bench_id="",
                dataset="",
                scenario="version",
                operation="create",
                resource_type="version",
                success=False,
                http_status=500,
                error_message="postgresql://user:pass@host/db version failed",
            ),
        )


class FailingWorkload(FakeWorkload):
    def load_dataset(self, connstr, dataset):
        raise RuntimeError("load failed for postgresql://user:pass@host/db")


def test_run_benchmark_with_clients_creates_artifacts(tmp_path, sample_config):
    result = run_benchmark_with_clients(
        config=sample_config,
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=FakeDbay(),
        workload=FakeWorkload(),
    )

    assert result["cleanup_status"]["cleanup_status"] == "clean"
    assert (result["result_dir"] / "raw_samples.csv").exists()
    assert (result["result_dir"] / "summary.json").exists()
    assert (result["result_dir"] / "correctness.json").exists()
    assert (result["result_dir"] / "comparison.md").exists()
    assert (result["result_dir"] / "cleanup_registry.json").exists()
    assert "user:pass" not in (result["result_dir"] / "comparison.md").read_text(
        encoding="utf-8"
    )
    registry_text = (result["result_dir"] / "cleanup_registry.json").read_text(
        encoding="utf-8"
    )
    assert "user:pass" not in registry_text
    assert "connection_uri" not in registry_text
    assert "postgresql://" not in registry_text


def test_run_benchmark_with_clients_records_dbay_error_sample(tmp_path, sample_config):
    result = run_benchmark_with_clients(
        config=sample_config,
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=FailingVersionDbay(),
        workload=FakeWorkload(),
    )

    assert result["benchmark_status"] == "failed"
    with (result["result_dir"] / "raw_samples.csv").open(encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    failed = [row for row in rows if row["scenario"] == "version" and row["operation"] == "create"]
    assert failed
    assert failed[0]["success"] == "False"
    assert "postgresql://[REDACTED]" in failed[0]["error_message"]


def test_run_benchmark_with_clients_marks_workload_failure(tmp_path, sample_config):
    result = run_benchmark_with_clients(
        config=sample_config,
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=FakeDbay(),
        workload=FailingWorkload(),
    )

    correctness = json.loads(
        (result["result_dir"] / "correctness.json").read_text(encoding="utf-8")
    )
    assert result["benchmark_status"] == "failed"
    assert correctness["benchmark_status"] == "failed"
    assert correctness["error"]["type"] == "RuntimeError"
    assert "postgresql://[REDACTED]" in correctness["error"]["message"]
    assert result["cleanup_status"]["cleanup_status"] == "clean"
