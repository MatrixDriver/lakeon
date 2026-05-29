from dbay_branch_version.cleanup import CleanupRegistry, cleanup_benchmark_resources


class FakeClient:
    def __init__(self):
        self.calls = []

    def delete_version(self, db_id, database_name, branch_id, version_id):
        self.calls.append(("version", db_id, database_name, branch_id, version_id))
        return {}, None

    def delete_branch(
        self, db_id, database_name, branch_id, branch_name, is_default=False
    ):
        self.calls.append(("branch", db_id, database_name, branch_id, branch_name))
        return {}, None

    def delete_database(self, db_id, name):
        self.calls.append(("database", db_id, name))
        return {}, None


class FailingVersionDeleteClient(FakeClient):
    def delete_version(self, db_id, database_name, branch_id, version_id):
        self.calls.append(("version", db_id, database_name, branch_id, version_id))
        raise RuntimeError("version delete failed")


def test_cleanup_order_versions_branches_database():
    client = FakeClient()
    registry = CleanupRegistry(
        bench_id="b1",
        database_id="db_1",
        database_name="bench-branch-version-x",
        branches=[{"id": "br_1", "name": "feature", "is_default": False}],
        versions=[{"id": "ver_1", "branch_id": "br_1"}],
    )

    status = cleanup_benchmark_resources(client, registry)

    assert client.calls == [
        ("version", "db_1", "bench-branch-version-x", "br_1", "ver_1"),
        ("branch", "db_1", "bench-branch-version-x", "br_1", "feature"),
        ("database", "db_1", "bench-branch-version-x"),
    ]
    assert status["cleanup_status"] == "clean"


def test_cleanup_skips_main_branch():
    client = FakeClient()
    registry = CleanupRegistry(
        bench_id="b1",
        database_id="db_1",
        database_name="bench-branch-version-x",
        branches=[{"id": "br_main", "name": "main", "is_default": True}],
        versions=[],
    )

    cleanup_benchmark_resources(client, registry)

    assert client.calls == [("database", "db_1", "bench-branch-version-x")]


def test_cleanup_continues_after_version_delete_failure():
    client = FailingVersionDeleteClient()
    registry = CleanupRegistry(
        bench_id="b1",
        database_id="db_1",
        database_name="bench-branch-version-x",
        branches=[{"id": "br_1", "name": "feature", "is_default": False}],
        versions=[{"id": "ver_1", "branch_id": "br_1"}],
    )

    status = cleanup_benchmark_resources(client, registry)

    assert client.calls == [
        ("version", "db_1", "bench-branch-version-x", "br_1", "ver_1"),
        ("branch", "db_1", "bench-branch-version-x", "br_1", "feature"),
        ("database", "db_1", "bench-branch-version-x"),
    ]
    assert status["cleanup_status"] == "failed"
    assert status["failures"] == [
        {"type": "version", "id": "ver_1", "error": "version delete failed"}
    ]
