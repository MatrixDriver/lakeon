import sqlite3
import sqlite_vec
from echomem.drivers.migrations import apply_all


def _open():
    con = sqlite3.connect(":memory:")
    con.enable_load_extension(True)
    sqlite_vec.load(con)
    con.enable_load_extension(False)
    apply_all(con)
    return con


def test_m003_creates_blob_ref_and_path_alias():
    con = _open()
    rows = {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
    assert "blob_ref" in rows
    assert "path_alias" in rows


def test_blob_ref_columns():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(blob_ref)").fetchall()}
    assert {"sha256", "mime", "byte_size", "origin_url", "meta", "created_at"}.issubset(cols)


def test_path_alias_columns_and_uniqueness():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(path_alias)").fetchall()}
    assert {"path", "sha256", "created_at"}.issubset(cols)
    # path is PK so duplicates raise
    con.execute("INSERT INTO blob_ref(sha256, mime, byte_size, created_at) VALUES('abc', 'text/plain', 3, 1)")
    con.execute("INSERT INTO path_alias(path, sha256, created_at) VALUES('a/b.md', 'abc', 1)")
    import pytest
    with pytest.raises(sqlite3.IntegrityError):
        con.execute("INSERT INTO path_alias(path, sha256, created_at) VALUES('a/b.md', 'abc', 2)")
