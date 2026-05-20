//! Per-agent SQLite ledger storing the last-known server etag for each path.
//!
//! Used by uplink to populate `if_match` on PUT/APPEND/batch ops and by
//! pull to skip already-synced files.
//!
//! Single-writer model: one `Ledger` handle per process. `rusqlite::Connection`
//! is `!Sync`, so the type system already enforces single-threaded access — we
//! don't add a separate lock.

use anyhow::{Context, Result};
use rusqlite::{params, Connection, OptionalExtension};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EtagEntry {
    pub etag: String,
    pub size: i64,
    pub updated_at_ms: i64,
}

pub struct Ledger {
    conn: Connection,
}

impl Ledger {
    /// Open or create a ledger at `path`. If the file exists but is corrupt,
    /// rename it to `<path>.broken-<unix_ms>` and create a fresh DB.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        if path.exists() {
            match Self::try_open(&path) {
                Ok(l) => return Ok(l),
                Err(e) => {
                    let ts = SystemTime::now().duration_since(UNIX_EPOCH)
                        .map(|d| d.as_millis() as i64).unwrap_or(0);
                    let broken = path.with_extension(format!("db.broken-{ts}"));
                    tracing::warn!(?path, ?broken, ?e,
                                   "etag ledger corrupt, rotating");
                    std::fs::rename(&path, &broken).context("rotate broken ledger")?;
                }
            }
        }
        Self::try_open(&path)
    }

    fn try_open(path: &Path) -> Result<Self> {
        let conn = Connection::open(path).context("open sqlite")?;
        let ok: String = conn.query_row("PRAGMA integrity_check", [], |r| r.get(0))
            .unwrap_or_else(|_| "fail".into());
        if ok != "ok" {
            anyhow::bail!("integrity_check failed: {ok}");
        }
        conn.execute_batch(r#"
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;
            CREATE TABLE IF NOT EXISTS etag_ledger (
                path        TEXT PRIMARY KEY,
                etag        TEXT NOT NULL,
                size        INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            );
        "#).context("init schema")?;
        Ok(Self { conn })
    }

    pub fn get(&self, path: &str) -> Result<Option<EtagEntry>> {
        let mut stmt = self.conn.prepare(
            "SELECT etag, size, updated_at_ms FROM etag_ledger WHERE path = ?1"
        )?;
        let row = stmt.query_row(params![path], |r| {
            Ok(EtagEntry {
                etag: r.get(0)?,
                size: r.get(1)?,
                updated_at_ms: r.get(2)?,
            })
        }).optional().context("ledger get")?;
        Ok(row)
    }

    pub fn upsert(&self, path: &str, etag: &str, size: i64) -> Result<()> {
        let now = SystemTime::now().duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64).unwrap_or(0);
        self.conn.execute(
            "INSERT INTO etag_ledger(path, etag, size, updated_at_ms) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(path) DO UPDATE SET etag = excluded.etag,
                                              size = excluded.size,
                                              updated_at_ms = excluded.updated_at_ms",
            params![path, etag, size, now],
        ).context("ledger upsert")?;
        Ok(())
    }

    pub fn forget(&self, path: &str) -> Result<()> {
        self.conn.execute("DELETE FROM etag_ledger WHERE path = ?1", params![path])
            .context("ledger forget")?;
        Ok(())
    }
}
