use anyhow::{Context, Result};
use std::path::{Path, PathBuf};

use crate::profile::FolderProfile;
use crate::outbox::{self, Op, Outbox};
use crate::state_scan::{self, Kind};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncPaths {
    pub root: PathBuf,
    pub outbox: PathBuf,
    pub ledger: PathBuf,
    pub tmp: PathBuf,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncEntry {
    pub local_path: PathBuf,
    pub remote_path: String,
    pub kind: Kind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncPlan {
    pub state_root: PathBuf,
    pub remote_prefix: String,
    pub profile: FolderProfile,
    pub entries: Vec<SyncEntry>,
}

pub fn default_sync_paths(home: &Path, folder: &str) -> SyncPaths {
    let root = home.join(".dbay").join("sync").join(folder);
    SyncPaths {
        outbox: root.join("outbox"),
        ledger: root.join("etags.db"),
        tmp: root.join("tmp"),
        root,
    }
}

pub fn build_sync_plan(
    source_dir: &Path,
    remote_prefix: &str,
    profile: FolderProfile,
) -> Result<SyncPlan> {
    let source_dir = source_dir
        .canonicalize()
        .with_context(|| format!("canonicalize {}", source_dir.display()))?;
    let remote_prefix = normalize_prefix(remote_prefix);
    let entries = state_scan::walk(&source_dir)
        .into_iter()
        .map(|entry| {
            let remote_path = join_remote(&remote_prefix, &entry.rel);
            SyncEntry {
                local_path: source_dir.join(&entry.rel),
                remote_path,
                kind: entry.kind,
            }
        })
        .collect();

    Ok(SyncPlan {
        state_root: source_dir,
        remote_prefix,
        profile,
        entries,
    })
}

pub fn enqueue_sync_plan(plan: &SyncPlan, outbox: &Outbox) -> Result<usize> {
    let blobs = outbox.blobs_dir();
    let properties = plan.profile.properties();
    let mut count = 0;
    for entry in &plan.entries {
        match entry.kind {
            Kind::Dir => {
                outbox.enqueue(Op::Mkdir {
                    path: entry.remote_path.clone(),
                    properties: Some(properties.clone()),
                })?;
                count += 1;
            }
            Kind::File => {
                let data = std::fs::read(&entry.local_path)
                    .with_context(|| format!("read {}", entry.local_path.display()))?;
                let sha = outbox::write_blob(&blobs, &data)?;
                outbox.enqueue(Op::Put {
                    path: entry.remote_path.clone(),
                    blob: sha,
                    properties: Some(properties.clone()),
                })?;
                count += 1;
            }
        }
    }
    Ok(count)
}

fn normalize_prefix(prefix: &str) -> String {
    let trimmed = prefix.trim();
    if trimmed.is_empty() || trimmed == "/" {
        return "/".to_string();
    }
    let with_root = if trimmed.starts_with('/') {
        trimmed.to_string()
    } else {
        format!("/{trimmed}")
    };
    with_root.trim_end_matches('/').to_string()
}

fn join_remote(prefix: &str, rel: &Path) -> String {
    let rel = rel.to_string_lossy();
    if prefix == "/" {
        format!("/{rel}")
    } else {
        format!("{prefix}/{rel}")
    }
}
