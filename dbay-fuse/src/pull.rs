//! `pull` subcommand: bring local state directory in sync with remote AgentFS.
//!
//! Decision matrix (per remote entry):
//!   remote-only            → Download
//!   match (ledger == remote) → Skip
//!   ledger != remote, local exists → Conflict (download remote to sidecar)
//!   local exists, ledger absent → Skip (assume local is new, uplink pushes)
//!   large file (>100MB) and !include_large → SkipLarge
//!   remote is a dir → Skip

use anyhow::{Context, Result};
use std::fs;
use std::path::{Path, PathBuf};

use crate::dbay_api::DbayClient;
use crate::etag_ledger::Ledger;

pub const LARGE_FILE_THRESHOLD: u64 = 100 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct RemoteEntry {
    pub path: String,
    pub etag: String,
    pub size: u64,
    pub kind: String,
}

#[derive(Debug, Clone)]
pub struct LocalState {
    pub exists: bool,
    pub ledger_etag: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PullAction {
    Download,
    Skip,
    Conflict,
    SkipLarge,
}

#[derive(Debug, Default)]
pub struct PullSummary {
    pub synced: usize,
    pub skipped: usize,
    pub conflicts: usize,
    pub skipped_large: usize,
    pub errors: usize,
}

/// Pure decision function: given the remote entry, the local file's status,
/// and whether large-file downloads are enabled, return the action to take.
pub fn plan_pull_action(
    remote: &RemoteEntry,
    local: &LocalState,
    include_large: bool,
) -> PullAction {
    if remote.kind == "dir" {
        return PullAction::Skip;
    }
    if remote.size > LARGE_FILE_THRESHOLD && !include_large {
        return PullAction::SkipLarge;
    }
    match (local.exists, &local.ledger_etag) {
        (false, _) => PullAction::Download,
        (true, Some(le)) if le == &remote.etag => PullAction::Skip,
        (true, Some(_)) => PullAction::Conflict,
        (true, None) => PullAction::Skip,
    }
}

/// Main pull loop: list the remote prefix, compute an action per entry, and
/// for `Download` / `Conflict` actions fetch the remote bytes and write to
/// either the canonical state path or a conflict sidecar.
pub fn pull(
    cli: &DbayClient,
    ledger: &Ledger,
    state_dir: &Path,
    prefix: &str,
    include_large: bool,
    dry_run: bool,
) -> Result<PullSummary> {
    let entries = cli.agentfs_list(prefix, true).context("list remote")?;
    let total = entries.len();
    let mut summary = PullSummary::default();
    for (i, e) in entries.iter().enumerate() {
        let remote = RemoteEntry {
            path: e.path.clone(),
            etag: e.etag.clone(),
            size: e.size,
            kind: e.kind.clone(),
        };
        let local_path = state_path_for(state_dir, &remote.path);
        let local = LocalState {
            exists: local_path.exists(),
            ledger_etag: ledger.get(&remote.path)?.map(|e| e.etag),
        };
        let action = plan_pull_action(&remote, &local, include_large);
        if (i + 1) % 50 == 0 {
            tracing::info!(progress = format!("{}/{}", i + 1, total), "pull progress");
        }
        match action {
            PullAction::Skip => {
                summary.skipped += 1;
                continue;
            }
            PullAction::SkipLarge => {
                tracing::warn!(
                    path = %remote.path,
                    size = remote.size,
                    "skipped large file; use --include-large to fetch"
                );
                summary.skipped_large += 1;
                continue;
            }
            PullAction::Download | PullAction::Conflict => {
                if dry_run {
                    tracing::info!(path = %remote.path, ?action, "dry-run");
                    if matches!(action, PullAction::Conflict) {
                        summary.conflicts += 1;
                    } else {
                        summary.synced += 1;
                    }
                    continue;
                }
                match download_and_write(cli, ledger, &remote, &local_path, &action) {
                    Ok(()) => {
                        if matches!(action, PullAction::Conflict) {
                            summary.conflicts += 1;
                        } else {
                            summary.synced += 1;
                        }
                    }
                    Err(e) => {
                        tracing::warn!(path = %remote.path, ?e, "download failed");
                        summary.errors += 1;
                    }
                }
            }
        }
    }
    Ok(summary)
}

fn state_path_for(state_dir: &Path, virt_path: &str) -> PathBuf {
    state_dir.join(virt_path.trim_start_matches('/'))
}

fn download_and_write(
    cli: &DbayClient,
    ledger: &Ledger,
    remote: &RemoteEntry,
    local_path: &Path,
    action: &PullAction,
) -> Result<()> {
    let (bytes, etag, _mtime) = cli
        .agentfs_get(&remote.path)
        .with_context(|| format!("get {}", remote.path))?;
    if let Some(parent) = local_path.parent() {
        fs::create_dir_all(parent).ok();
    }
    let target = match action {
        PullAction::Conflict => {
            let host = crate::hostname::hostname_or_unknown();
            let ts = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            let base_name = local_path
                .file_name()
                .and_then(|s| s.to_str())
                .unwrap_or("file");
            local_path.with_file_name(format!("{base_name}.conflict-pull-{host}-{ts}"))
        }
        _ => local_path.to_path_buf(),
    };
    fs::write(&target, &bytes).with_context(|| format!("write {}", target.display()))?;
    // Only the canonical Download path updates the ledger. A Conflict download
    // writes a sidecar and leaves the ledger pointing at the prior etag so the
    // user can resolve manually.
    if matches!(action, PullAction::Download) {
        ledger.upsert(&remote.path, &etag, bytes.len() as i64)?;
    }
    Ok(())
}
