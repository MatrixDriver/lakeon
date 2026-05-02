import sqlite3


def up(con: sqlite3.Connection) -> None:
    # NOTE: 与 m001 同样使用 IF NOT EXISTS；不需要 transaction 因为
    # 每张表创建是独立的 DDL，且失败时上层 apply_all 会留下 schema_version 不入库。
    con.executescript(
        """
        CREATE TABLE IF NOT EXISTS derivative_event (
          id                TEXT PRIMARY KEY,
          window_start      INTEGER NOT NULL,
          window_end        INTEGER NOT NULL,
          agent_id          TEXT NOT NULL,
          title             TEXT NOT NULL,
          summary           TEXT,
          member_memory_ids TEXT,
          created_at        INTEGER NOT NULL,
          rationale         TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_event_window ON derivative_event(window_start);
        CREATE INDEX IF NOT EXISTS idx_event_agent  ON derivative_event(agent_id);

        CREATE TABLE IF NOT EXISTS derivative_summary (
          id              TEXT PRIMARY KEY,
          source_kind     TEXT NOT NULL,
          source_ref      TEXT NOT NULL,
          level           INTEGER NOT NULL,
          parent_id       TEXT,
          text            TEXT NOT NULL,
          token_estimate  INTEGER,
          created_at      INTEGER NOT NULL,
          rationale       TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_summary_source ON derivative_summary(source_kind, source_ref);
        CREATE INDEX IF NOT EXISTS idx_summary_parent ON derivative_summary(parent_id);

        CREATE TABLE IF NOT EXISTS derivative_entity (
          id            TEXT PRIMARY KEY,
          name          TEXT NOT NULL,
          kind          TEXT,
          meta          TEXT,
          first_seen_at INTEGER NOT NULL,
          last_seen_at  INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_entity_name ON derivative_entity(name);

        CREATE TABLE IF NOT EXISTS derivative_triple (
          id                TEXT PRIMARY KEY,
          subject_id        TEXT NOT NULL,
          predicate         TEXT NOT NULL,
          object_id         TEXT NOT NULL,
          source_memory_id  TEXT NOT NULL,
          confidence        REAL NOT NULL,
          created_at        INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_triple_s ON derivative_triple(subject_id);
        CREATE INDEX IF NOT EXISTS idx_triple_o ON derivative_triple(object_id);
        CREATE INDEX IF NOT EXISTS idx_triple_src ON derivative_triple(source_memory_id);

        CREATE TABLE IF NOT EXISTS derivative_triple_pending (
          id                TEXT PRIMARY KEY,
          subject_text      TEXT NOT NULL,
          predicate         TEXT NOT NULL,
          object_text       TEXT NOT NULL,
          source_memory_id  TEXT NOT NULL,
          confidence        REAL NOT NULL,
          created_at        INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_triple_pending_src ON derivative_triple_pending(source_memory_id);

        CREATE TABLE IF NOT EXISTS derivative_skill (
          id              TEXT PRIMARY KEY,
          name            TEXT NOT NULL,
          trigger_pattern TEXT NOT NULL,
          trigger_emb     BLOB,
          steps           TEXT NOT NULL,
          agent_scope     TEXT,
          source          TEXT NOT NULL,
          observed_count  INTEGER NOT NULL DEFAULT 0,
          success_count   INTEGER NOT NULL DEFAULT 0,
          last_used_at    INTEGER,
          created_at      INTEGER NOT NULL,
          rationale       TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_skill_name   ON derivative_skill(name);
        CREATE INDEX IF NOT EXISTS idx_skill_source ON derivative_skill(source);

        CREATE TABLE IF NOT EXISTS derivative_task (
          id          TEXT PRIMARY KEY,
          kind        TEXT NOT NULL,
          memory_id   TEXT,
          status      TEXT NOT NULL,
          attempts    INTEGER NOT NULL DEFAULT 0,
          last_error  TEXT,
          created_at  INTEGER NOT NULL,
          updated_at  INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_task_status ON derivative_task(status, kind);
        CREATE INDEX IF NOT EXISTS idx_task_memory ON derivative_task(memory_id);

        CREATE TABLE IF NOT EXISTS dead_letter (
          id          TEXT PRIMARY KEY,
          task_id     TEXT NOT NULL,
          kind        TEXT NOT NULL,
          memory_id   TEXT,
          payload     TEXT,
          error       TEXT NOT NULL,
          created_at  INTEGER NOT NULL
        );
        """
    )
    # skill_vec virtual table — 1024 维与 memory_vec 一致
    con.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS skill_vec USING vec0(skill_id TEXT PRIMARY KEY, embedding float[1024])"
    )
