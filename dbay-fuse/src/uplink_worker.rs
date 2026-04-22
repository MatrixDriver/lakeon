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
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result};

use crate::dbay_api::DbayClient;
use crate::outbox::{self, Entry, Op, Outbox};
use crate::state_scan;

const POLL_INTERVAL: Duration = Duration::from_millis(100);
const MAX_BATCH: usize = 50;

pub fn spawn(agent: &str, outbox: Arc<Outbox>, state_dir: &Path, outbox_dir: &Path) -> Result<()> {
    let agent = agent.to_string();
    let state_dir = state_dir.to_path_buf();
    let outbox_dir = outbox_dir.to_path_buf();
    let client = DbayClient::for_agent(&agent)?;
    if client.is_none() {
        tracing::warn!("DBay not configured — uplink runs in log-only mode");
    }
    thread::spawn(move || {
        if let Err(e) = run(agent, outbox, client, state_dir, outbox_dir) {
            tracing::error!(?e, "uplink worker crashed");
        }
    });
    Ok(())
}

fn run(
    _agent: String,
    outbox: Arc<Outbox>,
    client: Option<DbayClient>,
    state_dir: PathBuf,
    outbox_dir: PathBuf,
) -> Result<()> {
    tracing::info!(has_client = client.is_some(), "uplink worker started");
    let trigger_path = state_scan::rescan_trigger_path(&outbox_dir);
    loop {
        // takeover (and future CLI tools) can signal a rescan by
        // creating the trigger file. Consume before the pending
        // check so the new ops join this iteration's batch.
        match state_scan::consume_rescan_trigger(&state_dir, &outbox, &trigger_path) {
            Ok(0) => {}
            Ok(n) => tracing::info!(enqueued = n, "rescan trigger consumed"),
            Err(e) => tracing::warn!(?e, "rescan trigger consumption failed, will retry"),
        }

        let pending = outbox.pending();
        if pending.is_empty() {
            let _ = outbox.maybe_compact();
            thread::sleep(POLL_INTERVAL);
            continue;
        }
        let batch = coalesce(&pending);
        if let Some(cli) = client.as_ref() {
            // Process in chunks of up to MAX_BATCH using /agentfs/batch
            let mut idx = 0;
            while idx < batch.len() {
                let end = (idx + MAX_BATCH).min(batch.len());
                let chunk = &batch[idx..end];
                match send_batch(cli, &outbox, chunk) {
                    Ok(_) => {
                        for entry in chunk {
                            let _ = outbox.ack(entry.seq);
                        }
                        idx = end;
                    }
                    Err(e) => {
                        tracing::warn!(seq_start = chunk[0].seq, n = chunk.len(),
                                       ?e, "batch uplink failed, will retry");
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

/// Build the batch payload and POST it. Single HTTP round-trip for the whole
/// chunk. Server runs all ops in one Postgres tx (atomic).
fn send_batch(cli: &DbayClient, outbox: &Outbox, entries: &[Entry]) -> Result<()> {
    use base64::Engine as _;
    let blobs_dir = outbox.blobs_dir();
    let mut ops: Vec<serde_json::Value> = Vec::with_capacity(entries.len());
    for entry in entries {
        let op_json = match &entry.op {
            Op::Put { path, blob, properties } => {
                let bytes = outbox::read_blob(&blobs_dir, blob)
                    .context("read blob for put")?;
                let mut obj = serde_json::json!({
                    "op": "put",
                    "path": path,
                    "data_base64": base64::engine::general_purpose::STANDARD.encode(&bytes),
                });
                if let Some(p) = properties {
                    obj["properties"] = p.clone();
                }
                obj
            }
            Op::Append { path, blob } => {
                let bytes = outbox::read_blob(&blobs_dir, blob)
                    .context("read blob for append")?;
                serde_json::json!({
                    "op": "append",
                    "path": path,
                    "data_base64": base64::engine::general_purpose::STANDARD.encode(&bytes),
                })
            }
            Op::Delete { path } => serde_json::json!({"op":"delete","path":path}),
            Op::Rename { path, new_path } => serde_json::json!({
                "op":"rename","from":path,"to":new_path,"overwrite":true
            }),
            Op::Mkdir { path } => serde_json::json!({"op":"mkdir","path":path}),
            Op::Ack { .. } => continue,
        };
        ops.push(op_json);
    }
    if ops.is_empty() {
        return Ok(());
    }
    cli.agentfs_batch(ops)?;
    Ok(())
}

#[allow(dead_code)]
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
