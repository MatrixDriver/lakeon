use anyhow::{Context, Result};
use notify::{Event, EventKind, RecursiveMode, Watcher};
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::time::Duration;

use crate::outbox::Outbox;
use crate::profile::FolderProfile;
use crate::sync::{build_sync_plan, enqueue_sync_plan};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WatchEventKind {
    Changed,
    Removed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchPathEvent {
    pub path: String,
    pub kind: WatchEventKind,
}

impl WatchPathEvent {
    pub fn changed(path: impl Into<String>) -> Self {
        Self {
            path: path.into(),
            kind: WatchEventKind::Changed,
        }
    }

    pub fn removed(path: impl Into<String>) -> Self {
        Self {
            path: path.into(),
            kind: WatchEventKind::Removed,
        }
    }
}

pub fn coalesce_watch_events(events: Vec<WatchPathEvent>) -> Vec<WatchPathEvent> {
    let mut by_path = std::collections::BTreeMap::new();
    for event in events {
        by_path.insert(event.path.clone(), event);
    }
    by_path.into_values().collect()
}

pub fn run_watch_loop(
    source_dir: PathBuf,
    remote_prefix: String,
    profile: FolderProfile,
    outbox_dir: PathBuf,
) -> Result<()> {
    let (tx, rx) = mpsc::channel();
    let mut watcher = notify::recommended_watcher(move |event| {
        let _ = tx.send(event);
    })
    .context("create file watcher")?;
    watcher
        .watch(&source_dir, RecursiveMode::Recursive)
        .with_context(|| format!("watch {}", source_dir.display()))?;

    let outbox = Outbox::open(&outbox_dir)?;
    loop {
        let first = rx.recv().context("watch event receive")?;
        let mut events = event_to_path_events(&source_dir, first?);
        while let Ok(next) = rx.recv_timeout(Duration::from_millis(500)) {
            events.extend(event_to_path_events(&source_dir, next?));
        }
        let batch = coalesce_watch_events(events);
        if batch.is_empty() {
            continue;
        }
        let plan = build_sync_plan(&source_dir, &remote_prefix, profile.clone())?;
        let enqueued = enqueue_sync_plan(&plan, &outbox)?;
        tracing::info!(
            changed_paths = batch.len(),
            enqueued,
            "watch sync enqueued refreshed plan"
        );
    }
}

fn event_to_path_events(source_dir: &Path, event: Event) -> Vec<WatchPathEvent> {
    let kind = match event.kind {
        EventKind::Remove(_) => WatchEventKind::Removed,
        EventKind::Create(_) | EventKind::Modify(_) => WatchEventKind::Changed,
        _ => return Vec::new(),
    };
    event
        .paths
        .into_iter()
        .filter_map(|path| {
            let rel = path.strip_prefix(source_dir).ok()?;
            Some(WatchPathEvent {
                path: rel.to_string_lossy().to_string(),
                kind: kind.clone(),
            })
        })
        .collect()
}
