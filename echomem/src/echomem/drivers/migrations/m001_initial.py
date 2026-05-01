import sqlite3


def up(con: sqlite3.Connection) -> None:
    con.executescript(
        """
        CREATE TABLE IF NOT EXISTS memory (
          id          TEXT PRIMARY KEY,
          agent_id    TEXT NOT NULL,
          source_kind TEXT NOT NULL DEFAULT 'explicit',
          source_ref  TEXT,
          text        TEXT NOT NULL,
          meta        TEXT,
          created_at  INTEGER NOT NULL,
          updated_at  INTEGER NOT NULL,
          deleted_at  INTEGER
        );

        CREATE INDEX IF NOT EXISTS idx_memory_created_at ON memory(created_at DESC);
        CREATE INDEX IF NOT EXISTS idx_memory_agent_id   ON memory(agent_id);
        """
    )
    # sqlite-vec virtual table — embedding 维度跟随 EchomemConfig.embedding_dim (默认 1024)
    con.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS memory_vec USING vec0(memory_id TEXT PRIMARY KEY, embedding float[1024])"
    )
    # FTS5 contentless table (不强绑 memory rowid 避免 trigger 复杂度)
    con.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(memory_id UNINDEXED, text, tokenize='porter')"
    )
