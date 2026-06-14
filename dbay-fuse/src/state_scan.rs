//! State-dir scan: walk the passthrough state directory and produce
//! the ordered sequence of ops needed to replicate it to LakebaseFS.
//!
//! Used by folder import/sync flows and by mount-mode rescans. Server-side
//! idempotency absorbs re-uploading unchanged content.
//!
//! Server-side idempotency (LakebaseFSService.upsertFile WHERE etag IS
//! DISTINCT FROM ...) absorbs the cost of re-uploading unchanged
//! content, so this module has no local ledger — the server is the
//! source of truth for "already synced".

use anyhow::{Context, Result};
use std::path::{Path, PathBuf};

use crate::outbox::{self, Op, Outbox};

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Kind {
    File,
    Dir,
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct ScanEntry {
    pub rel: PathBuf,
    pub kind: Kind,
}

/// Walk `root` and return entries in pre-order DFS so that a parent
/// directory is always yielded before any of its children. This lets
/// callers enqueue `Mkdir` before `Put` safely.
///
/// Returns an empty vec when `root` does not exist. The root itself
/// is not yielded — only its contents.
pub fn walk(root: &Path) -> Vec<ScanEntry> {
    let mut out = Vec::new();
    walk_inner(root, Path::new(""), &mut out);
    out
}

fn walk_inner(real_root: &Path, rel: &Path, out: &mut Vec<ScanEntry>) {
    let abs = real_root.join(rel);
    let Ok(read) = std::fs::read_dir(&abs) else {
        return;
    };
    let mut entries: Vec<_> = read.filter_map(|e| e.ok()).collect();
    entries.sort_by_key(|e| e.file_name());
    for e in entries {
        let name = e.file_name();
        if should_skip(&name) {
            continue;
        }
        let child_rel = rel.join(&name);
        let Ok(ft) = e.file_type() else { continue };
        if ft.is_dir() {
            out.push(ScanEntry { rel: child_rel.clone(), kind: Kind::Dir });
            walk_inner(real_root, &child_rel, out);
        } else if ft.is_file() {
            out.push(ScanEntry { rel: child_rel, kind: Kind::File });
        }
        // symlinks / sockets / fifos are ignored — LakebaseFS schema has
        // no representation for them.
    }
}

fn should_skip(name: &std::ffi::OsStr) -> bool {
    let s = name.to_string_lossy();
    matches!(s.as_ref(), ".DS_Store" | "Thumbs.db" | ".git")
}

/// Convert a state-relative path to the virtual LakebaseFS path.
/// Mirrors passthrough::to_virt_path.
fn to_virt_path(rel: &Path) -> String {
    let s = rel.to_string_lossy();
    if s.starts_with('/') {
        s.into_owned()
    } else {
        format!("/{s}")
    }
}

/// Standard trigger path inside an outbox directory.
pub fn rescan_trigger_path(outbox_dir: &Path) -> PathBuf {
    outbox_dir.join("rescan.trigger")
}

/// Create the rescan trigger file inside `outbox_dir`. A folder tool can
/// call this after changing a state tree; the daemon's uplink worker detects
/// it on the next poll and runs `consume_rescan_trigger`.
///
/// Idempotent: if the file already exists, this returns the same path
/// without error. Parent directory is created if missing (fresh-install
/// scenario where a folder tool runs before the outbox has ever been
/// touched by the daemon).
pub fn write_rescan_trigger(outbox_dir: &Path) -> Result<PathBuf> {
    std::fs::create_dir_all(outbox_dir)
        .with_context(|| format!("create outbox dir {}", outbox_dir.display()))?;
    let path = rescan_trigger_path(outbox_dir);
    std::fs::write(&path, b"")
        .with_context(|| format!("write trigger {}", path.display()))?;
    Ok(path)
}

/// If `trigger_path` exists, walk `state_root` and enqueue Mkdir/Put
/// ops for every entry, then delete the trigger. Returns the number
/// of ops enqueued (0 when no trigger file present).
///
/// Single-writer guarantee: this is only ever called from the uplink
/// worker thread, which is the sole writer of the outbox. Other folder
/// tools signal intent by creating `rescan.trigger` but never touch the outbox
/// directly.
pub fn consume_rescan_trigger(
    state_root: &Path,
    outbox: &Outbox,
    trigger_path: &Path,
) -> Result<usize> {
    if !trigger_path.exists() {
        return Ok(0);
    }
    let entries = walk(state_root);
    let n = enqueue_scan(&entries, state_root, outbox)?;
    // Remove AFTER enqueue so a crash mid-scan leaves the trigger in
    // place and the next poll retries. Re-enqueueing identical Puts is
    // absorbed by the server-side etag idempotency.
    std::fs::remove_file(trigger_path)
        .with_context(|| format!("remove trigger {}", trigger_path.display()))?;
    Ok(n)
}

/// Drain `entries` into `outbox` as Mkdir/Put ops. File contents are
/// read from `state_root/<rel>` and stored as content-addressed blobs.
/// Returns the number of ops enqueued.
///
/// Safe to call multiple times: the server-side upsert is now
/// idempotent (skips UPDATE when etag unchanged), so re-uploading the
/// same content is a cheap no-op on the DB side.
pub fn enqueue_scan(entries: &[ScanEntry], state_root: &Path, outbox: &Outbox) -> Result<usize> {
    let blobs = outbox.blobs_dir();
    let mut count = 0;
    for e in entries {
        let virt = to_virt_path(&e.rel);
        match e.kind {
            Kind::Dir => {
                outbox.enqueue(Op::Mkdir {
                    path: virt,
                    properties: None,
                })?;
                count += 1;
            }
            Kind::File => {
                let real = state_root.join(&e.rel);
                let data = std::fs::read(&real)
                    .with_context(|| format!("read {}", real.display()))?;
                let sha = outbox::write_blob(&blobs, &data)?;
                outbox.enqueue(Op::Put { path: virt, blob: sha, properties: None })?;
                count += 1;
            }
        }
    }
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::outbox::{self, Op, Outbox};
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn write_trigger_creates_marker_file() {
        let tmp_outbox = tempdir().unwrap();
        let path = write_rescan_trigger(tmp_outbox.path()).unwrap();
        assert!(path.exists());
        assert_eq!(path.file_name().unwrap(), "rescan.trigger");
        assert_eq!(path.parent().unwrap(), tmp_outbox.path());
    }

    #[test]
    fn write_trigger_is_idempotent() {
        let tmp_outbox = tempdir().unwrap();
        let p1 = write_rescan_trigger(tmp_outbox.path()).unwrap();
        let p2 = write_rescan_trigger(tmp_outbox.path()).unwrap();
        assert_eq!(p1, p2);
        assert!(p1.exists());
    }

    #[test]
    fn consume_trigger_is_noop_when_trigger_missing() {
        let tmp_state = tempdir().unwrap();
        let tmp_outbox = tempdir().unwrap();
        let outbox = Outbox::open(tmp_outbox.path()).unwrap();
        let trigger = tmp_outbox.path().join("rescan.trigger");
        // Trigger doesn't exist.
        let n = consume_rescan_trigger(tmp_state.path(), &outbox, &trigger).unwrap();
        assert_eq!(n, 0);
        assert!(!trigger.exists(), "trigger should remain missing");
        assert_eq!(outbox.pending().len(), 0);
    }

    #[test]
    fn consume_trigger_scans_state_and_removes_trigger() {
        let tmp_state = tempdir().unwrap();
        let tmp_outbox = tempdir().unwrap();
        fs::create_dir_all(tmp_state.path().join("m")).unwrap();
        fs::write(tmp_state.path().join("m/k.md"), b"memo").unwrap();

        let outbox = Outbox::open(tmp_outbox.path()).unwrap();
        let trigger = tmp_outbox.path().join("rescan.trigger");
        fs::write(&trigger, b"").unwrap();

        let n = consume_rescan_trigger(tmp_state.path(), &outbox, &trigger).unwrap();
        assert_eq!(n, 2, "expected Mkdir + Put");
        assert!(!trigger.exists(), "trigger must be removed after consumption");
        assert_eq!(outbox.pending().len(), 2);
    }

    #[test]
    fn enqueue_scan_emits_mkdir_before_put_and_preserves_blob() {
        let tmp_state = tempdir().unwrap();
        let tmp_outbox = tempdir().unwrap();

        fs::create_dir_all(tmp_state.path().join("a")).unwrap();
        fs::write(tmp_state.path().join("a/x.md"), b"hello").unwrap();

        let outbox = Outbox::open(tmp_outbox.path()).unwrap();
        let entries = walk(tmp_state.path());
        let count = enqueue_scan(&entries, tmp_state.path(), &outbox).unwrap();
        assert_eq!(count, 2, "expected one Mkdir + one Put");

        let pending = outbox.pending();
        assert_eq!(pending.len(), 2);

        // Ordering: Mkdir for /a must come before Put for /a/x.md
        // so that the server has a parent when the file lands.
        match &pending[0].op {
            Op::Mkdir { path, properties } => {
                assert_eq!(path, "/a");
                assert!(properties.is_none());
            }
            other => panic!("expected Mkdir at index 0, got {:?}", other),
        }
        match &pending[1].op {
            Op::Put { path, blob, properties } => {
                assert_eq!(path, "/a/x.md");
                assert!(properties.is_none());
                let data = outbox::read_blob(&outbox.blobs_dir(), blob).unwrap();
                assert_eq!(data, b"hello");
            }
            other => panic!("expected Put at index 1, got {:?}", other),
        }
    }

    #[test]
    fn enqueue_scan_on_empty_entries_is_noop() {
        let tmp_state = tempdir().unwrap();
        let tmp_outbox = tempdir().unwrap();
        let outbox = Outbox::open(tmp_outbox.path()).unwrap();
        let count = enqueue_scan(&[], tmp_state.path(), &outbox).unwrap();
        assert_eq!(count, 0);
        assert_eq!(outbox.pending().len(), 0);
    }

    #[test]
    fn walk_empty_dir_yields_nothing() {
        let tmp = tempdir().unwrap();
        assert_eq!(walk(tmp.path()), vec![]);
    }

    #[test]
    fn walk_missing_dir_yields_nothing() {
        let tmp = tempdir().unwrap();
        let missing = tmp.path().join("does-not-exist");
        assert_eq!(walk(&missing), vec![]);
    }

    #[test]
    fn walk_emits_dirs_before_files_in_preorder() {
        let tmp = tempdir().unwrap();
        fs::create_dir_all(tmp.path().join("a/b")).unwrap();
        fs::write(tmp.path().join("a/x.txt"), b"x").unwrap();
        fs::write(tmp.path().join("a/b/y.txt"), b"y").unwrap();
        fs::write(tmp.path().join("top.md"), b"z").unwrap();

        let out = walk(tmp.path());
        let kinds: Vec<_> = out
            .iter()
            .map(|e| (e.rel.to_string_lossy().replace('\\', "/"), e.kind.clone()))
            .collect();

        // Alphabetical sort places "a" before "top.md". Within "a/",
        // "b" (dir) comes before "x.txt" (file) alphabetically, and
        // pre-order means "a/b" is emitted right after "a" and before
        // "a/x.txt".
        assert_eq!(
            kinds,
            vec![
                ("a".into(), Kind::Dir),
                ("a/b".into(), Kind::Dir),
                ("a/b/y.txt".into(), Kind::File),
                ("a/x.txt".into(), Kind::File),
                ("top.md".into(), Kind::File),
            ]
        );
    }

    #[test]
    fn walk_skips_ds_store_and_git() {
        let tmp = tempdir().unwrap();
        fs::create_dir_all(tmp.path().join(".git/objects")).unwrap();
        fs::write(tmp.path().join(".git/HEAD"), b"ref").unwrap();
        fs::write(tmp.path().join(".DS_Store"), b"junk").unwrap();
        fs::write(tmp.path().join("real.md"), b"ok").unwrap();

        let out = walk(tmp.path());
        let paths: Vec<_> = out.iter().map(|e| e.rel.to_string_lossy().into_owned()).collect();
        assert_eq!(paths, vec!["real.md".to_string()]);
    }
}
