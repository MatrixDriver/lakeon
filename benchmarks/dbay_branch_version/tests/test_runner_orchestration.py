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
