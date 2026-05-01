import sqlite3


def up(con: sqlite3.Connection) -> None:
    # NOTE: executescript() implicitly commits any open txn before running and
    # does not run inside a transaction itself. m001 is safe because all DDL
    # uses IF NOT EXISTS; future multi-statement migrations needing rollback
    # must use per-statement execute() inside an explicit BEGIN/COMMIT.
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
    # sqlite-vec virtual table — embedding 维度跟随 EchomemConfig.embedding_dim (默认 1024)。
    # vec0 不支持 ALTER；改维度需 DROP + recreate（销毁所有向量，需重新 embed）。
    con.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS memory_vec USING vec0(memory_id TEXT PRIMARY KEY, embedding float[1024])"
    )
    # 标准 FTS5 表（自带 text 副本，不需要 trigger 同步）。
    # T6+ 在 upsert/delete memory 时必须同步写入/删除 memory_fts 行。
    con.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(memory_id UNINDEXED, text, tokenize='porter')"
    )
