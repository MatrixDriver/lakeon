//! Conflict-file naming + log writing helpers shared by the uplink worker
//! (when the server returns 412) and (in the future) any other component
//! that produces sidecar files for resolved conflicts.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

/// Filename-safe local-time stamp: e.g. "2026-05-20T13-42-07".
pub fn local_filename_timestamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    // SAFETY: libc::localtime returns a pointer into a static buffer; we copy out
    // the fields immediately. Sound for read-only access from a single thread.
    let tm = unsafe {
        let t = secs as libc::time_t;
        *libc::localtime(&t)
    };
    format!(
        "{:04}-{:02}-{:02}T{:02}-{:02}-{:02}",
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
        tm.tm_hour, tm.tm_min, tm.tm_sec
    )
}

/// Map virtual `/projects/x/y.md` → `<conflicts_root>/projects/x/y.md.conflict-from-<host>-<ts>`.
/// `conflicts_root` is typically `~/.dbay/conflicts/` but is a parameter for testability.
pub fn conflict_sidecar_path(conflicts_root: &Path, virt_path: &str, host: &str, ts: &str) -> PathBuf {
    let stripped = virt_path.trim_start_matches('/');
    conflicts_root.join(format!("{stripped}.conflict-from-{host}-{ts}"))
}

/// Append a JSON line to `<conflicts_root>/conflicts.log`.
pub fn append_conflict_log(
    conflicts_root: &Path,
    virt_path: &str,
    remote_etag: &str,
    host: &str,
    ts: &str,
    saved_to: &Path,
) {
    let _ = fs::create_dir_all(conflicts_root);
    let log_file = conflicts_root.join("conflicts.log");
    let line = serde_json::json!({
        "ts": ts,
        "path": virt_path,
        "remote_etag": remote_etag,
        "hostname": host,
        "saved_to": saved_to.display().to_string(),
    })
    .to_string();
    if let Ok(mut f) = fs::OpenOptions::new().create(true).append(true).open(log_file) {
        let _ = writeln!(f, "{line}");
    }
}
