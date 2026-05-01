import sqlite3
import sqlite_vec
from echomem.drivers.migrations import apply_all, MIGRATIONS


def _open():
    con = sqlite3.connect(":memory:")
    con.enable_load_extension(True)
    sqlite_vec.load(con)
    con.enable_load_extension(False)
    return con


def test_apply_all_creates_expected_tables():
    con = _open()
    apply_all(con)
    rows = {
        r[0]
        for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table','virtualtable','view')"
        ).fetchall()
    }
    assert "memory" in rows
    assert "memory_vec" in rows
    assert "memory_fts" in rows
    assert "schema_version" in rows


def test_apply_all_is_idempotent():
    con = _open()
    apply_all(con)
    apply_all(con)
    v = con.execute("SELECT MAX(version) FROM schema_version").fetchone()[0]
    assert v == max(MIGRATIONS.keys())


def test_memory_table_columns():
    con = _open()
    apply_all(con)
    cols = {r[1] for r in con.execute("PRAGMA table_info(memory)").fetchall()}
    expected = {
        "id",
        "agent_id",
        "source_kind",
        "source_ref",
        "text",
        "meta",
        "created_at",
        "updated_at",
        "deleted_at",
    }
    assert expected.issubset(cols)
