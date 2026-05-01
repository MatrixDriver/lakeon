from __future__ import annotations

import sqlite3
from typing import Callable

from echomem.drivers.migrations import m001_initial

MIGRATIONS: dict[int, Callable[[sqlite3.Connection], None]] = {
    1: m001_initial.up,
}


def apply_all(con: sqlite3.Connection) -> None:
    con.execute(
        "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)"
    )
    applied = {
        r[0] for r in con.execute("SELECT version FROM schema_version").fetchall()
    }
    for version in sorted(MIGRATIONS):
        if version in applied:
            continue
        MIGRATIONS[version](con)
        con.execute(
            "INSERT INTO schema_version(version, applied_at) VALUES (?, strftime('%s','now') * 1000)",
            (version,),
        )
        con.commit()
