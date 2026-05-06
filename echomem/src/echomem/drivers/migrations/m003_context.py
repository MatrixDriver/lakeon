import sqlite3


def up(con: sqlite3.Connection) -> None:
    con.executescript(
        """
        CREATE TABLE IF NOT EXISTS blob_ref (
          sha256       TEXT PRIMARY KEY,
          mime         TEXT NOT NULL,
          byte_size    INTEGER,
          origin_url   TEXT,
          meta         TEXT,
          created_at   INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_blob_ref_origin ON blob_ref(origin_url);
        CREATE INDEX IF NOT EXISTS idx_blob_ref_created ON blob_ref(created_at DESC);

        CREATE TABLE IF NOT EXISTS path_alias (
          path        TEXT PRIMARY KEY,
          sha256      TEXT NOT NULL,
          created_at  INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_path_alias_sha ON path_alias(sha256);
        """
    )
