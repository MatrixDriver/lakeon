from dbay_branch_version.pg_workload import (
    DATASETS,
    checksum_sql,
    dataset_row_counts,
    isolation_insert_sql,
)


def test_dataset_sizes_are_explicit():
    assert DATASETS["S"].scale == 10_000
    assert DATASETS["M"].scale == 100_000
    assert DATASETS["L"].scale == 1_000_000


def test_checksum_sql_mentions_all_tables():
    sql = checksum_sql()

    assert "bench_oltp" in sql
    assert "bench_jsonb" in sql
    assert "bench_events" in sql
    assert "md5" in sql


def test_isolation_insert_sql_uses_marker():
    sql = isolation_insert_sql("parent-only")

    assert "parent-only" in sql
    assert "bench_events" in sql


def test_isolation_insert_sql_quotes_marker_literals():
    sql = isolation_insert_sql("child's branch")

    assert "child''s branch" in sql
    assert "child's branch" not in sql


def test_dataset_row_counts_splits_rows_across_tables():
    counts = dataset_row_counts("S")

    assert counts["bench_oltp"] == 10_000
    assert counts["bench_jsonb"] == 10_000
    assert counts["bench_events"] == 10_000
