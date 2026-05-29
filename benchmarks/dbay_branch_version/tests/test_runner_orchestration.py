import csv
import json
import threading
import time
from dataclasses import replace

from dbay_branch_version.dbay_client import DbayApiError
from dbay_branch_version.metrics import OperationSample
from dbay_branch_version.runner import run_benchmark_with_clients


class FakeDbay:
    def __init__(self):
        self.created_database = False
        self.branches = [
            {
                "id": "br_main",
                "name": "main",
                "is_default": True,
                "connection_uri": "postgresql://user:pass@host/main",
            }
        ]
        self.versions = {}
        self.get_version_calls = []
        self.active_gets = 0
        self.max_active_gets = 0
        self._get_lock = threading.Lock()

    def list_databases(self):
        return [], None

    def create_database(self, name, compute_size):
        self.created_database = True
        return {
            "id": "db_1",
            "name": name,
            "connection_uri": "postgresql://user:pass@host/main",
            "status": "running",
        }, None

    def list_branches(self, db_id):
        return list(self.branches), None

    def create_branch(self, db_id, name, start_compute=False, parent_branch_id=None):
        branch = {
            "id": f"br_{name}",
            "name": name,
            "is_default": False,
            "parent_branch_id": parent_branch_id,
        }
        if start_compute:
            branch["connection_uri"] = f"postgresql://user:pass@host/{name}"
        self.branches.append(branch)
        return branch, None

    def create_version(self, db_id, branch_id, name, description=""):
        version = {
            "id": f"ver_{name}",
            "branch_id": branch_id,
            "name": name,
            "lsn": "0/1",
            "snapshot_timeline_id": "tl_1",
            "created_at": "2026-05-29T00:00:00Z",
        }
        self.versions.setdefault(branch_id, []).append(version)
        return version, None

    def list_versions(self, db_id, branch_id):
        return list(self.versions.get(branch_id, [])), None

    def get_version(self, db_id, branch_id, version_id):
        with self._get_lock:
            self.get_version_calls.append(version_id)
            self.active_gets += 1
            self.max_active_gets = max(self.max_active_gets, self.active_gets)
        time.sleep(0.001)
        try:
            for version in self.versions.get(branch_id, []):
                if version["id"] == version_id:
                    return version, None
        finally:
            with self._get_lock:
                self.active_gets -= 1
        raise KeyError(version_id)

    def squash_versions(
        self,
        db_id,
        database_name,
        branch_id,
        from_version_id,
        to_version_id,
    ):
        versions = self.versions.get(branch_id, [])
        start = next(i for i, version in enumerate(versions) if version["id"] == from_version_id)
        end = next(i for i, version in enumerate(versions) if version["id"] == to_version_id)
        remove_ids = {version["id"] for version in versions[start + 1 : end]}
        self.versions[branch_id] = [version for version in versions if version["id"] not in remove_ids]
        return list(self.versions[branch_id]), None

    def delete_version(self, db_id, database_name, branch_id, version_id):
        return {}, None

    def delete_branch(self, db_id, database_name, branch_id, branch_name, is_default=False):
        return {}, None

    def delete_database(self, db_id, name):
        return {}, None


class FakeWorkload:
    def __init__(self):
        self.markers = {}
        self.current_dataset = ""
        self.loaded_datasets = []

    def load_dataset(self, connstr, dataset):
        self.current_dataset = dataset
        self.loaded_datasets.append(dataset)
        return None

    def fetch_checksums(self, connstr):
        suffix = self.current_dataset or "none"
        return {
            "bench_oltp": f"oltp-{suffix}",
            "bench_jsonb": f"jsonb-{suffix}",
            "bench_events": f"events-{suffix}",
        }

    def fetch_row_counts(self, connstr):
        base = {"S": 10_000, "M": 100_000, "L": 1_000_000}.get(
            self.current_dataset,
            10_000,
        )
        return {"bench_oltp": base, "bench_jsonb": base, "bench_events": base}

    def execute_isolation_insert(self, connstr, marker):
        self.markers[(connstr, marker)] = self.markers.get((connstr, marker), 0) + 1

    def marker_count(self, connstr, marker):
        return self.markers.get((connstr, marker), 0)


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


class MissingCreatedAtDbay(FakeDbay):
    def create_version(self, db_id, branch_id, name, description=""):
        version, sample = super().create_version(db_id, branch_id, name, description)
        version.pop("created_at", None)
        return version, sample


class EndpointDroppingSquashDbay(FakeDbay):
    def squash_versions(
        self,
        db_id,
        database_name,
        branch_id,
        from_version_id,
        to_version_id,
    ):
        versions = self.versions.get(branch_id, [])
        start = next(i for i, version in enumerate(versions) if version["id"] == from_version_id)
        end = next(i for i, version in enumerate(versions) if version["id"] == to_version_id)
        remove_ids = {version["id"] for version in versions[start : end + 1]}
        self.versions[branch_id] = [
            version for version in versions if version["id"] not in remove_ids
        ]
        return list(self.versions[branch_id]), None


class PreflightFailDbay(FakeDbay):
    def list_databases(self):
        raise RuntimeError("token rejected")


def full_matrix_config(sample_config):
    return replace(
        sample_config,
        datasets=("S",),
        scenarios={
            "branch_create_without_compute": {"samples_per_dataset": 1},
            "branch_create_with_compute": {"samples_per_dataset": 1},
            "branch_create_concurrent": {"total_samples": 2, "concurrency": [2]},
            "branch_depth": {"depths": [2]},
            "version_create": {"samples_per_dataset": 1},
            "version_read": {"samples": 2},
            "version_squash": {"groups": 1, "versions_per_group": 3},
        },
    )


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


def test_run_benchmark_with_clients_exercises_configured_scenario_matrix(
    tmp_path, sample_config
):
    result = run_benchmark_with_clients(
        config=full_matrix_config(sample_config),
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=FakeDbay(),
        workload=FakeWorkload(),
    )

    assert result["benchmark_status"] == "completed"
    with (result["result_dir"] / "raw_samples.csv").open(encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))

    scenario_operations = {(row["scenario"], row["operation"]) for row in rows}
    assert ("branch_create_without_compute", "create") in scenario_operations
    assert ("branch_create_with_compute", "create") in scenario_operations
    assert ("branch_create_concurrent", "create") in scenario_operations
    assert ("branch_depth", "create") in scenario_operations
    assert ("version_create", "create") in scenario_operations
    assert ("version_read", "list") in scenario_operations
    assert ("version_read", "get") in scenario_operations
    assert ("version_squash", "squash") in scenario_operations
    assert any(row["scenario"] == "branch_create_concurrent" and row["concurrency"] == "2" for row in rows)
    assert any(row["scenario"] == "branch_depth" and row["depth"] == "2" for row in rows)

    correctness = json.loads(
        (result["result_dir"] / "correctness.json").read_text(encoding="utf-8")
    )
    assert correctness["benchmark_status"] == "completed"
    assert correctness["preflight"]["status"] == "passed"
    assert correctness["datasets"]["S"]["expected_row_counts"] == {
        "bench_events": 10_000,
        "bench_jsonb": 10_000,
        "bench_oltp": 10_000,
    }
    assert correctness["datasets"]["S"]["base_checksums"]
    assert correctness["checks"]
    assert all(check["passed"] is not False for check in correctness["checks"])


def test_multi_dataset_run_loads_each_dataset_before_its_scenarios(
    tmp_path, sample_config
):
    config = replace(
        sample_config,
        datasets=("S", "M"),
        scenarios={"branch_create_with_compute": {"samples_per_dataset": 1}},
    )
    workload = FakeWorkload()

    result = run_benchmark_with_clients(
        config=config,
        plan={"datasets": ["S", "M"]},
        result_root=tmp_path,
        dbay=FakeDbay(),
        workload=workload,
    )

    correctness = json.loads(
        (result["result_dir"] / "correctness.json").read_text(encoding="utf-8")
    )
    assert result["benchmark_status"] == "completed"
    assert workload.loaded_datasets == ["S", "M"]
    assert correctness["datasets"]["S"]["base_checksums"]["bench_oltp"] == "oltp-S"
    assert correctness["datasets"]["M"]["base_checksums"]["bench_oltp"] == "oltp-M"
    assert all(
        check["passed"] is not False
        for check in correctness["checks"]
        if check["name"].startswith("branch_fork_checksum:")
    )


def test_version_read_uses_configured_sample_count_and_concurrency(
    tmp_path, sample_config
):
    dbay = FakeDbay()
    config = replace(
        sample_config,
        datasets=("S",),
        limits={**sample_config.limits, "max_total_versions": 10},
        scenarios={
            "version_create": {"samples_per_dataset": 2},
            "version_read": {"samples": 5, "concurrency": 3},
        },
    )

    result = run_benchmark_with_clients(
        config=config,
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=dbay,
        workload=FakeWorkload(),
    )

    assert result["benchmark_status"] == "completed"
    with (result["result_dir"] / "raw_samples.csv").open(encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    get_rows = [
        row
        for row in rows
        if row["scenario"] == "version_read" and row["operation"] == "get"
    ]
    assert len(get_rows) == 5
    assert {row["concurrency"] for row in get_rows} == {"3"}
    assert len(dbay.get_version_calls) == 5
    assert dbay.max_active_gets > 1


def test_squash_correctness_fails_when_endpoint_versions_are_missing(
    tmp_path, sample_config
):
    config = replace(
        sample_config,
        datasets=("S",),
        scenarios={"version_squash": {"groups": 1, "versions_per_group": 3}},
    )

    result = run_benchmark_with_clients(
        config=config,
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=EndpointDroppingSquashDbay(),
        workload=FakeWorkload(),
    )

    correctness = json.loads(
        (result["result_dir"] / "correctness.json").read_text(encoding="utf-8")
    )
    assert result["benchmark_status"] == "failed"
    assert any(
        check["name"] == "version_squash:0" and check["passed"] is False
        for check in correctness["checks"]
    )


def test_missing_version_created_at_fails_correctness_gate(tmp_path, sample_config):
    result = run_benchmark_with_clients(
        config=full_matrix_config(sample_config),
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=MissingCreatedAtDbay(),
        workload=FakeWorkload(),
    )

    correctness = json.loads(
        (result["result_dir"] / "correctness.json").read_text(encoding="utf-8")
    )
    assert result["benchmark_status"] == "failed"
    assert correctness["benchmark_status"] == "failed"
    assert correctness["error"]["type"] == "CorrectnessError"
    assert any(
        check["name"] == "version_metadata" and check["passed"] is False
        for check in correctness["checks"]
    )


def test_preflight_failure_aborts_before_database_creation(tmp_path, sample_config):
    dbay = PreflightFailDbay()

    result = run_benchmark_with_clients(
        config=full_matrix_config(sample_config),
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=dbay,
        workload=FakeWorkload(),
    )

    correctness = json.loads(
        (result["result_dir"] / "correctness.json").read_text(encoding="utf-8")
    )
    assert dbay.created_database is False
    assert result["benchmark_status"] == "failed"
    assert correctness["preflight"]["status"] == "failed"
    assert correctness["error"]["type"] == "PreflightError"


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
