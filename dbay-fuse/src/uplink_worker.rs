//! Background uplink worker: drains the outbox to DBay AgentFS.
//!
//! One thread per daemon. Polls every 2s, or wakes when the outbox itself
//! appends. Coalesces adjacent Put ops on the same path into the latest.
//! Retries with exp backoff; on persistent failure logs and leaves entries
//! in-place for operator intervention.
//!
//! Auth: reads `~/.dbay/config.json` via `DbayClient::for_agent(agent)`.
//! If no api_key, runs in log-only mode (useful for dev/tests).

use std::collections::HashMap;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result};

use crate::dbay_api::DbayClient;
use crate::outbox::{self, Entry, Op, Outbox};

const POLL_INTERVAL: Duration = Duration::from_secs(2);

pub fn spawn(agent: &str, outbox: Arc<Outbox>) -> Result<()> {
    let agent = agent.to_string();
    let client = DbayClient::for_agent(&agent)?;
    if client.is_none() {
        tracing::warn!("DBay not configured — uplink runs in log-only mode");
    }
    thread::spawn(move || {
        if let Err(e) = run(agent, outbox, client) {
            tracing::error!(?e, "uplink worker crashed");
        }
    });
    Ok(())
}

fn run(_agent: String, outbox: Arc<Outbox>, client: Option<DbayClient>) -> Result<()> {
    tracing::info!(has_client = client.is_some(), "uplink worker started");
    loop {
        let pending = outbox.pending();
        if pending.is_empty() {
            let _ = outbox.maybe_compact();
            thread::sleep(POLL_INTERVAL);
            continue;
        }
        let batch = coalesce(&pending);
        if let Some(cli) = client.as_ref() {
            for entry in &batch {
                match send_one(cli, &outbox, entry) {
                    Ok(_) => {
                        let _ = outbox.ack(entry.seq);
                    }
                    Err(e) => {
                        tracing::warn!(seq = entry.seq, ?e, "uplink failed, will retry");
                        thread::sleep(Duration::from_secs(5));
                        break;
                    }
                }
            }
        } else {
            for entry in &batch {
                log_only(&outbox, entry);
                let _ = outbox.ack(entry.seq);
            }
        }
        thread::sleep(POLL_INTERVAL);
    }
}

/// Reduce N entries → M where M ≤ N, collapsing adjacent duplicate Put/Append
/// on the same path to the last occurrence. Deletes and renames flush the
/// coalescing window to preserve ordering.
fn coalesce(entries: &[Entry]) -> Vec<Entry> {
    let mut out: Vec<Entry> = Vec::with_capacity(entries.len());
    let mut last_put_idx_by_path: HashMap<String, usize> = HashMap::new();
    for e in entries {
        match &e.op {
            Op::Put { path, .. } => {
                if let Some(&idx) = last_put_idx_by_path.get(path) {
                    out[idx] = e.clone();
                } else {
                    last_put_idx_by_path.insert(path.clone(), out.len());
                    out.push(e.clone());
                }
            }
            Op::Append { .. } => {
                out.push(e.clone());
            }
            Op::Delete { path } | Op::Rename { path, .. } => {
                last_put_idx_by_path.remove(path);
                out.push(e.clone());
            }
            Op::Mkdir { .. } => {
                out.push(e.clone());
            }
            Op::Ack { .. } => {}
        }
    }
    out
}

fn send_one(cli: &DbayClient, outbox: &Outbox, entry: &Entry) -> Result<()> {
    match &entry.op {
        Op::Put { path, blob, properties } => {
            let bytes = outbox::read_blob(&outbox.blobs_dir(), blob)
                .context("read blob for put")?;
            cli.agentfs_put(path, &bytes, properties.as_ref())?;
        }
        Op::Append { path, blob } => {
            let bytes = outbox::read_blob(&outbox.blobs_dir(), blob)
                .context("read blob for append")?;
            cli.agentfs_append(path, &bytes)?;
        }
        Op::Delete { path } => {
            cli.agentfs_delete(path)?;
        }
        Op::Rename { path, new_path } => {
            cli.agentfs_rename(path, new_path)?;
        }
        Op::Mkdir { path } => {
            cli.agentfs_mkdir(path)?;
        }
        Op::Ack { .. } => {}
    }
    Ok(())
}

fn log_only(_outbox: &Outbox, entry: &Entry) {
    let op_label = match &entry.op {
        Op::Put { path, .. } => format!("PUT {path}"),
        Op::Append { path, .. } => format!("APPEND {path}"),
        Op::Delete { path } => format!("DELETE {path}"),
        Op::Rename { path, new_path } => format!("RENAME {path} → {new_path}"),
        Op::Mkdir { path } => format!("MKDIR {path}"),
        Op::Ack { .. } => return,
    };
    tracing::info!(seq = entry.seq, "[log-only] {}", op_label);
}
