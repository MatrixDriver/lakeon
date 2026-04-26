//! In-memory FUSE backend (experimental, opt-in via `--in-memory`).
//!
//! Replaces the disk-passthrough + outbox model with:
//!   - per-inode in-memory buffer (lazy-loaded from AgentFS on first read)
//!   - sync PUT on flush/release with `If-Match: <parent_etag>`
//!   - daemon crash before flush = nothing committed = effective rollback
//!
//! Implemented in a follow-up commit; this stub keeps the crate building
//! while the `--in-memory` flag is wired up.

use std::path::Path;

use anyhow::{bail, Result};

pub fn mount(_agent: &str, _mount_point: &Path) -> Result<()> {
    bail!(
        "--in-memory backend not yet implemented; mount without the flag to use \
         the default disk-passthrough backend"
    );
}
