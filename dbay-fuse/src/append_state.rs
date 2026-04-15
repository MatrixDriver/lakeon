//! Per-file append tracking shared between FS main thread and flush watchdog.
//!
//! Enables delta upload for pure-append workloads (e.g. session.jsonl).
//! On flush, if the file has only had appends since the last flush, we read
//! only the bytes `[uploaded_offset..current_size]` and send them via
//! `Op::Append` instead of reading + hashing + uploading the whole file.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Default)]
pub struct AppendState {
    /// Bytes already known to be in cloud (either initially 0 or updated after
    /// a successful local enqueue).
    pub uploaded_offset: u64,
    /// Logical current file size (tracked from writes, not reread from disk).
    pub current_size: u64,
    /// True if every write since last flush was to offset == previous size.
    pub is_pure_append: bool,
}

pub type AppendMap = Arc<Mutex<HashMap<PathBuf, AppendState>>>;

pub fn new_map() -> AppendMap {
    Arc::new(Mutex::new(HashMap::new()))
}

/// Initialize (or return existing) entry for a file, seeded with current size.
pub fn ensure_entry(map: &AppendMap, rel: &Path, current_size: u64) {
    let mut m = map.lock().unwrap();
    m.entry(rel.to_path_buf())
        .or_insert(AppendState {
            uploaded_offset: 0,   // conservative: first flush pushes full file
            current_size,
            is_pure_append: true,
        });
}

/// Record a write; updates pure-append flag and size.
pub fn note_write(map: &AppendMap, rel: &Path, offset: u64, len: u64) {
    let mut m = map.lock().unwrap();
    let st = m.entry(rel.to_path_buf()).or_default();
    if offset != st.current_size {
        st.is_pure_append = false;
    }
    let end = offset + len;
    if end > st.current_size {
        st.current_size = end;
    }
}

/// Record a write using the post-write authoritative file size.
/// Robust against macFUSE quirks (which can pass offset=0 for O_APPEND writes).
/// `is_append_mode` should be true for fds opened with O_APPEND.
pub fn note_write_to_size(map: &AppendMap, rel: &Path, new_size: u64, is_append_mode: bool) {
    let mut m = map.lock().unwrap();
    let st = m.entry(rel.to_path_buf()).or_default();
    if !is_append_mode && new_size < st.current_size {
        // shrink => rewrite (truncate or middle write that overwrote)
        st.is_pure_append = false;
    }
    st.current_size = new_size;
}

/// Notify of a size change from setattr (truncate/extend). Always invalidates
/// pure-append tracking — the next flush must do a full Put so the cloud
/// reflects the new size (smaller or larger).
pub fn note_truncate(map: &AppendMap, rel: &Path, new_size: u64) {
    let mut m = map.lock().unwrap();
    let st = m.entry(rel.to_path_buf()).or_default();
    st.is_pure_append = false;
    st.current_size = new_size;
}

/// On rename, move the state under the new key.
pub fn rename(map: &AppendMap, old: &Path, new: &Path) {
    let mut m = map.lock().unwrap();
    if let Some(st) = m.remove(old) {
        m.insert(new.to_path_buf(), st);
    }
}

/// On delete, drop the state entry.
pub fn forget(map: &AppendMap, rel: &Path) {
    let mut m = map.lock().unwrap();
    m.remove(rel);
}

/// What kind of flush should happen for this path.
#[derive(Debug)]
pub enum FlushMode {
    Nothing,
    Append { from: u64, to: u64 },
    Put,
}

/// Decide what to do, WITHOUT performing the flush. The caller reads bytes
/// from the state file and enqueues the appropriate outbox op, then calls
/// `mark_flushed`.
pub fn plan_flush(map: &AppendMap, rel: &Path) -> FlushMode {
    let m = map.lock().unwrap();
    let Some(st) = m.get(rel) else {
        tracing::debug!(?rel, "plan_flush: no state → Nothing");
        return FlushMode::Nothing;
    };
    let mode = if st.current_size == st.uploaded_offset {
        FlushMode::Nothing
    } else if st.is_pure_append && st.uploaded_offset < st.current_size {
        FlushMode::Append { from: st.uploaded_offset, to: st.current_size }
    } else {
        FlushMode::Put
    };
    tracing::debug!(?rel, uploaded=st.uploaded_offset, current=st.current_size,
                    pure_append=st.is_pure_append, mode=?mode, "plan_flush");
    mode
}

/// Mark the entry as fully uploaded after a successful enqueue.
pub fn mark_flushed(map: &AppendMap, rel: &Path) {
    let mut m = map.lock().unwrap();
    if let Some(st) = m.get_mut(rel) {
        st.uploaded_offset = st.current_size;
        st.is_pure_append = true; // reset after a full put; stays true after append
    }
}
