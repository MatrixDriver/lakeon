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


EXPECTED_TABLES = {
    "derivative_event",
    "derivative_summary",
    "derivative_entity",
    "derivative_triple",
    "derivative_triple_pending",
    "derivative_skill",
    "skill_vec",
    "derivative_task",
    "dead_letter",
}


def test_m002_creates_all_derivative_tables():
    con = _open()
    rows = {
        r[0]
        for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table','virtualtable')"
        ).fetchall()
    }
    assert EXPECTED_TABLES.issubset(rows)


def test_derivative_summary_has_level_and_parent():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(derivative_summary)").fetchall()}
    assert {"id", "source_kind", "source_ref", "level", "parent_id", "text", "token_estimate"}.issubset(cols)


def test_derivative_triple_has_confidence_and_source():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(derivative_triple)").fetchall()}
    assert {"id", "subject_id", "predicate", "object_id", "source_memory_id", "confidence"}.issubset(cols)


def test_derivative_skill_has_trigger_pattern_and_source():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(derivative_skill)").fetchall()}
    assert {"id", "name", "trigger_pattern", "trigger_emb", "steps", "source", "observed_count", "success_count"}.issubset(cols)


def test_derivative_task_table_for_queue():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(derivative_task)").fetchall()}
    assert {"id", "kind", "memory_id", "status", "attempts", "last_error", "created_at", "updated_at"}.issubset(cols)
