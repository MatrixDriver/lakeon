//! Outbox: append-only durable queue of pending uploads to DBay AgentFS.
//!
//! Layout (under `~/.dbay/outbox/<folder>/`):
//!   pending.log         — append-only JSON-lines; each line one op.
//!   blobs/<sha>/<sha>   — content-addressed payloads referenced by put/append ops.
//!
//! Each log line is one JSON object:
//!   {"seq":N,"op":"put","path":"/CLAUDE.md","blob":"ab12..","ts":...}
//!   {"seq":N,"op":"append","path":"/projects/X/session.jsonl","blob":"..","ts":...}
//!   {"seq":N,"op":"delete","path":"/memory/tmp.md","ts":...}
//!   {"seq":N,"op":"rename","path":"/a","new_path":"/b","ts":...}
//!   {"seq":N,"op":"ack","ref":M,"ts":...}         ← marks entry M as uploaded
//!
//! Durability model:
//!   - append is O_APPEND + fsync on the log file (POSIX guarantees atomic
//!     append for writes up to PIPE_BUF; our lines stay under that).
//!   - blobs are written to a temp file then atomically renamed into blobs/<sha>/.
//!
//! Recovery: on daemon start, replay pending entries not yet ack'd.
//!
//! Compaction: when every entry in the current segment is ack'd, the log is
//! truncated and blob dir swept. Compaction is opportunistic, run by the uplink
//! worker when it has nothing to do.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// Lightweight fsync via libc — bypasses Rust std's `sync_all`/`sync_data`
/// which on macOS issue `F_FULLFSYNC` (~11ms p50, ~19ms p95). We accept OS
/// buffer durability (data lives in kernel page cache, typically <5s lag
/// before disk) in exchange for ~200x latency improvement. State files in
/// the passthrough dir are also subject to OS buffer; a daemon crash without
/// OS panic loses nothing because writes are already queued to disk.
fn fsync_lite(f: &File) -> std::io::Result<()> {
    let r = unsafe { libc::fsync(f.as_raw_fd()) };
    if r == 0 { Ok(()) } else { Err(std::io::Error::last_os_error()) }
}

const LOG_FILE: &str = "pending.log";
const BLOBS_DIR: &str = "blobs";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "lowercase")]
pub enum Op {
    Put { path: String, blob: String, properties: Option<serde_json::Value> },
    Append { path: String, blob: String },
    Delete { path: String },
    Rename { path: String, new_path: String },
    Mkdir { path: String, properties: Option<serde_json::Value> },
    Ack { #[serde(rename = "ref")] ref_seq: u64 },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entry {
    pub seq: u64,
    #[serde(flatten)]
    pub op: Op,
    pub ts: i64,
}

pub struct Outbox {
    dir: PathBuf,
    inner: Mutex<Inner>,
}

struct Inner {
    log: File,
    next_seq: u64,
    /// Entries in the current log segment, indexed by seq.
    /// We keep them in memory for fast lookup; for 10k pending this is <10MB.
    live: std::collections::BTreeMap<u64, Entry>,
}

impl Outbox {
    pub fn open(dir: &Path) -> Result<Self> {
        std::fs::create_dir_all(dir)?;
        std::fs::create_dir_all(dir.join(BLOBS_DIR))?;

        let log_path = dir.join(LOG_FILE);
        let mut log = OpenOptions::new()
            .create(true)
            .read(true)
            .append(true)
            .open(&log_path)
            .with_context(|| format!("open {}", log_path.display()))?;

        // Replay: load everything, build live map (entries not yet ack'd)
        log.seek(SeekFrom::Start(0))?;
        let reader = BufReader::new(&log);
        let mut live: std::collections::BTreeMap<u64, Entry> =
            std::collections::BTreeMap::new();
        let mut max_seq: u64 = 0;
        for line in reader.lines() {
            let line = match line {
                Ok(l) if l.is_empty() => continue,
                Ok(l) => l,
                Err(_) => continue,
            };
            let entry: Entry = match serde_json::from_str(&line) {
                Ok(e) => e,
                Err(e) => {
                    tracing::warn!(?e, %line, "skip malformed outbox line");
                    continue;
                }
            };
            max_seq = max_seq.max(entry.seq);
            match &entry.op {
                Op::Ack { ref_seq } => {
                    live.remove(ref_seq);
                }
                _ => {
                    live.insert(entry.seq, entry);
                }
            }
        }
        // Re-open for append after rewinding
        drop(log);
        let log = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path)?;

        tracing::info!(
            pending = live.len(),
            next_seq = max_seq + 1,
            "outbox opened"
        );
        Ok(Self {
            dir: dir.to_path_buf(),
            inner: Mutex::new(Inner {
                log,
                next_seq: max_seq + 1,
                live,
            }),
        })
    }

    /// Append a non-ack entry. Returns the assigned seq.
    /// For Put/Append: caller must have already written the blob into `blobs/`.
    pub fn enqueue(&self, op: Op) -> Result<u64> {
        let mut inner = self.inner.lock().unwrap();
        let seq = inner.next_seq;
        inner.next_seq += 1;
        let entry = Entry { seq, op, ts: now_ns() };
        let mut line = serde_json::to_string(&entry)?;
        line.push('\n');
        inner.log.write_all(line.as_bytes())?;
        fsync_lite(&inner.log)?;
        inner.live.insert(seq, entry);
        Ok(seq)
    }

    /// Mark a previously-enqueued seq as uploaded.
    pub fn ack(&self, seq: u64) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        let ack_seq = inner.next_seq;
        inner.next_seq += 1;
        let entry = Entry {
            seq: ack_seq,
            op: Op::Ack { ref_seq: seq },
            ts: now_ns(),
        };
        let mut line = serde_json::to_string(&entry)?;
        line.push('\n');
        inner.log.write_all(line.as_bytes())?;
        fsync_lite(&inner.log)?;
        inner.live.remove(&seq);
        Ok(())
    }

    pub fn pending(&self) -> Vec<Entry> {
        let inner = self.inner.lock().unwrap();
        inner.live.values().cloned().collect()
    }

    pub fn pending_count(&self) -> usize {
        self.inner.lock().unwrap().live.len()
    }

    pub fn blobs_dir(&self) -> PathBuf {
        self.dir.join(BLOBS_DIR)
    }

    /// Compact: if all entries are ack'd, truncate the log and sweep blob dir.
    /// No-op when log is already empty — without this guard the uplink worker's
    /// 100ms idle loop spams a truncate + readdir + INFO log every tick.
    pub fn maybe_compact(&self) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        if !inner.live.is_empty() {
            return Ok(());
        }
        let cur_len = inner.log.metadata().map(|m| m.len()).unwrap_or(0);
        if cur_len == 0 && inner.next_seq == 1 {
            return Ok(());
        }
        let log_path = self.dir.join(LOG_FILE);
        inner.log.set_len(0)?;
        fsync_lite(&inner.log)?;
        inner.next_seq = 1;
        tracing::info!(log = %log_path.display(), "outbox compacted");

        // Sweep unreferenced blobs (no live entries → all blobs are orphan).
        let blobs_root = self.blobs_dir();
        if let Ok(entries) = std::fs::read_dir(&blobs_root) {
            for e in entries.flatten() {
                let p = e.path();
                if p.is_dir() {
                    let _ = std::fs::remove_dir_all(&p);
                } else {
                    let _ = std::fs::remove_file(&p);
                }
            }
        }
        Ok(())
    }
}

/// Write bytes into `blobs_dir`, return content-addressed sha hex.
pub fn write_blob(blobs_dir: &Path, bytes: &[u8]) -> Result<String> {
    use std::io::Write;
    let sha = sha256_hex(bytes);
    let shard = &sha[..2];
    let shard_dir = blobs_dir.join(shard);
    std::fs::create_dir_all(&shard_dir)?;
    let final_path = shard_dir.join(&sha);
    if final_path.exists() {
        return Ok(sha);
    }
    let tmp = shard_dir.join(format!(".tmp.{}", sha));
    {
        let mut f = File::create(&tmp)?;
        f.write_all(bytes)?;
        fsync_lite(&f)?;
    }
    std::fs::rename(&tmp, &final_path)?;
    Ok(sha)
}

pub fn read_blob(blobs_dir: &Path, sha: &str) -> Result<Vec<u8>> {
    let shard = &sha[..2];
    let path = blobs_dir.join(shard).join(sha);
    let mut f = File::open(&path)
        .with_context(|| format!("missing blob {}", path.display()))?;
    let mut buf = Vec::new();
    f.read_to_end(&mut buf)?;
    Ok(buf)
}

fn sha256_hex(bytes: &[u8]) -> String {
    // Tiny SHA-256 without pulling a crate — use a fast, bundled
    // implementation. We go with a minimal dependency: `sha2` is
    // ubiquitous and small. Keep the call site trivial.
    use sha2::{Digest, Sha256};
    let mut h = Sha256::new();
    h.update(bytes);
    let out = h.finalize();
    out.iter().map(|b| format!("{:02x}", b)).collect()
}

fn now_ns() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as i64)
        .unwrap_or(0)
}

/// CLI `dbay-fuse outbox-status --agent X`
pub fn print_status(outbox_dir: &Path) -> Result<()> {
    let ob = Outbox::open(outbox_dir)?;
    let pending = ob.pending();
    println!("outbox dir:    {}", outbox_dir.display());
    println!("pending count: {}", pending.len());
    for entry in pending.iter().take(20) {
        let op_label = match &entry.op {
            Op::Put { path, .. } => format!("PUT {path}"),
            Op::Append { path, .. } => format!("APPEND {path}"),
            Op::Delete { path } => format!("DELETE {path}"),
            Op::Rename { path, new_path } => format!("RENAME {path} → {new_path}"),
            Op::Mkdir { path, .. } => format!("MKDIR {path}"),
            Op::Ack { ref_seq } => format!("ACK #{ref_seq}"),
        };
        println!("  #{:>6}  {}", entry.seq, op_label);
    }
    if pending.len() > 20 {
        println!("  ... and {} more", pending.len() - 20);
    }
    Ok(())
}
