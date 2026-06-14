//! Background uplink worker: drains the outbox to DBay LakebaseFS.
//!
//! One thread per daemon. Polls every 2s, or wakes when the outbox itself
//! appends. Coalesces adjacent Put ops on the same path into the latest.
//! Retries with exp backoff; on persistent failure logs and leaves entries
//! in-place for operator intervention.
//!
//! Auth: reads `~/.dbay/config.json` via `DbayClient::for_agent(agent)`.
//! If no api_key, runs in log-only mode (useful for dev/tests).

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result};

use crate::dbay_api::DbayClient;
use crate::etag_ledger::Ledger;
use crate::outbox::{self, Entry, Op, Outbox};
use crate::state_scan;

const POLL_INTERVAL: Duration = Duration::from_millis(100);
const MAX_BATCH: usize = 50;
const DRAIN_RETRIES: usize = 5;
const DRAIN_RETRY_SLEEP: Duration = Duration::from_secs(10);

pub fn spawn(
    agent: &str,
    outbox: Arc<Outbox>,
    state_dir: &Path,
    outbox_dir: &Path,
    rescan_properties: Option<serde_json::Value>,
) -> Result<()> {
    let agent = agent.to_string();
    let state_dir = state_dir.to_path_buf();
    let outbox_dir = outbox_dir.to_path_buf();
    let client = DbayClient::for_agent_no_base(&agent)?;
    if client.is_none() {
        tracing::warn!("DBay not configured — uplink runs in log-only mode");
    }
    let ledger_path = std::env::var_os("HOME")
        .map(std::path::PathBuf::from)
        .ok_or_else(|| anyhow::anyhow!("HOME not set"))?
        .join(".dbay").join("sync-ledger").join(&agent).join("etags.db");
    let ledger = Ledger::open(&ledger_path)
        .with_context(|| format!("open ledger {}", ledger_path.display()))?;
    thread::spawn(move || {
        if let Err(e) = run(agent, outbox, client, state_dir, outbox_dir, ledger, rescan_properties) {
            tracing::error!(?e, "uplink worker crashed");
        }
    });
    Ok(())
}

pub fn drain_once(agent: &str, outbox_dir: &Path, ledger_path: &Path) -> Result<usize> {
    let client = DbayClient::for_agent_no_base(agent)?
        .ok_or_else(|| anyhow::anyhow!("DBay not configured: see ~/.dbay/config.json"))?;
    let outbox = Outbox::open(outbox_dir)?;
    let ledger = Ledger::open(ledger_path)
        .with_context(|| format!("open ledger {}", ledger_path.display()))?;
    let pending = outbox.pending();
    if pending.is_empty() {
        return Ok(0);
    }
    let batch = coalesce(&pending);
    let mut drained = 0usize;
    let mut idx = 0;
    while idx < batch.len() {
        let end = (idx + MAX_BATCH).min(batch.len());
        let chunk = &batch[idx..end];
        let seqs_to_ack = send_batch_with_retries(&client, &outbox, chunk, &ledger)?;
        drained += seqs_to_ack.len();
        for seq in seqs_to_ack {
            outbox.ack(seq)?;
        }
        idx = end;
    }
    let _ = outbox.maybe_compact();
    Ok(drained)
}

fn send_batch_with_retries(
    client: &DbayClient,
    outbox: &Outbox,
    chunk: &[Entry],
    ledger: &Ledger,
) -> Result<Vec<u64>> {
    let mut attempt = 0usize;
    loop {
        match send_batch(client, outbox, chunk, ledger) {
            Ok(seqs) => return Ok(seqs),
            Err(e) if is_transient_lbfs_provisioning_error(&e) && attempt < DRAIN_RETRIES => {
                attempt += 1;
                tracing::warn!(attempt, ?e, "LakebaseFS provisioning not ready; retrying batch drain");
                thread::sleep(DRAIN_RETRY_SLEEP);
            }
            Err(e) => return Err(e),
        }
    }
}

fn is_transient_lbfs_provisioning_error(e: &anyhow::Error) -> bool {
    let msg = format!("{e:#}");
    msg.contains("LakebaseFS database not READY")
        || msg.contains("LakebaseFS database still provisioning")
        || msg.contains("retry shortly")
}

fn run(
    _agent: String,
    outbox: Arc<Outbox>,
    client: Option<DbayClient>,
    state_dir: PathBuf,
    outbox_dir: PathBuf,
    ledger: Ledger,
    rescan_properties: Option<serde_json::Value>,
) -> Result<()> {
    tracing::info!(has_client = client.is_some(), "uplink worker started");
    let trigger_path = state_scan::rescan_trigger_path(&outbox_dir);
    loop {
        // Folder tools can signal a rescan by creating the trigger file.
        // Consume before the pending
        // check so the new ops join this iteration's batch.
        match state_scan::consume_rescan_trigger_with_properties(
            &state_dir,
            &outbox,
            &trigger_path,
            rescan_properties.as_ref(),
        ) {
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
            // Process in chunks of up to MAX_BATCH using /lbfs/batch
            let mut idx = 0;
            while idx < batch.len() {
                let end = (idx + MAX_BATCH).min(batch.len());
                let chunk = &batch[idx..end];
                match send_batch(cli, &outbox, chunk, &ledger) {
                    Ok(seqs_to_ack) => {
                        for seq in seqs_to_ack {
                            let _ = outbox.ack(seq);
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
///
/// Entries whose blobs are missing on disk (past compaction or outbox corruption)
/// are skipped + ACK'd in place rather than failing the whole batch — without
/// the blob we cannot recover the payload anyway, and holding the queue on
/// them would starve every subsequent write.
fn send_batch(cli: &DbayClient, outbox: &Outbox, entries: &[Entry], ledger: &Ledger) -> Result<Vec<u64>> {
    use base64::Engine as _;
    let blobs_dir = outbox.blobs_dir();
    let mut ops: Vec<serde_json::Value> = Vec::with_capacity(entries.len());
    let mut sizes_in_order: Vec<i64> = Vec::with_capacity(entries.len());
    let mut seqs_in_order: Vec<u64> = Vec::with_capacity(entries.len());
    let mut skipped_seqs: Vec<u64> = Vec::new();
    for entry in entries {
        let (op_json, sz) = match &entry.op {
            Op::Put { path, blob, properties } => {
                match outbox::read_blob(&blobs_dir, blob) {
                    Ok(bytes) => {
                        let mut obj = serde_json::json!({
                            "op": "put",
                            "path": path,
                            "data_base64": base64::engine::general_purpose::STANDARD.encode(&bytes),
                        });
                        if let Some(p) = properties { obj["properties"] = p.clone(); }
                        if let Ok(Some(e)) = ledger.get(path) {
                            obj["if_match"] = serde_json::json!(e.etag);
                        }
                        (obj, bytes.len() as i64)
                    }
                    Err(e) => {
                        tracing::warn!(seq = entry.seq, %path, blob = %blob, ?e,
                            "blob missing for PUT; skipping (unrecoverable)");
                        skipped_seqs.push(entry.seq);
                        continue;
                    }
                }
            }
            Op::Append { path, blob } => {
                match outbox::read_blob(&blobs_dir, blob) {
                    Ok(bytes) => {
                        let mut obj = serde_json::json!({
                            "op": "append",
                            "path": path,
                            "data_base64": base64::engine::general_purpose::STANDARD.encode(&bytes),
                        });
                        if let Ok(Some(e)) = ledger.get(path) {
                            obj["if_match"] = serde_json::json!(e.etag);
                        }
                        // size=0 placeholder: payload here is the delta, not full file.
                        // Pull will refresh the real size later if needed.
                        (obj, 0i64)
                    }
                    Err(e) => {
                        tracing::warn!(seq = entry.seq, %path, blob = %blob, ?e,
                            "blob missing for APPEND; skipping (unrecoverable)");
                        skipped_seqs.push(entry.seq);
                        continue;
                    }
                }
            }
            Op::Delete { path } => (
                serde_json::json!({"op":"delete","path":path}),
                0,
            ),
            Op::Rename { path, new_path } => (
                serde_json::json!({
                    "op":"rename","from":path,"to":new_path,"overwrite":true
                }),
                0,
            ),
            Op::Mkdir { path, properties } => {
                let mut obj = serde_json::json!({"op":"mkdir","path":path});
                if let Some(p) = properties { obj["properties"] = p.clone(); }
                (obj, 0)
            }
            Op::Ack { .. } => continue,
        };
        ops.push(op_json);
        sizes_in_order.push(sz);
        seqs_in_order.push(entry.seq);
    }
    // ACK skipped entries before sending so they don't come back next poll.
    for seq in skipped_seqs { let _ = outbox.ack(seq); }
    if ops.is_empty() { return Ok(Vec::new()); }
    let results = cli.lbfs_batch(ops)?;
    // Update ledger from per-op results. Server echoes each op's path back in
    // BatchOpResult.path — read it directly instead of carrying a parallel Vec.
    let mut seqs_to_ack: Vec<u64> = Vec::with_capacity(results.len());
    for (i, r) in results.iter().enumerate() {
        let p_opt = r.path.as_deref();
        let seq = seqs_in_order.get(i).copied();
        match r.status.as_str() {
            "ok" => {
                if let (Some(path), Some(etag)) = (p_opt, r.etag.as_ref()) {
                    let size = sizes_in_order.get(i).copied().unwrap_or(0);
                    if let Err(e) = ledger.upsert(path, etag, size) {
                        tracing::warn!(?e, %path, "ledger upsert failed (non-fatal)");
                    }
                }
                if let Some(s) = seq { seqs_to_ack.push(s); }
            }
            "ok_absent" => {
                if let Some(path) = p_opt { let _ = ledger.forget(path); }
                if let Some(s) = seq { seqs_to_ack.push(s); }
            }
            "precondition_failed" => {
                // Invoke conflict handler (T9 will implement the sidecar
                // sync). Do NOT ACK this seq — it stays in the outbox for
                // T9 / the next loop iteration to retry.
                if let Some(path) = p_opt {
                    if let Err(e) = handle_conflict(cli, ledger, path) {
                        tracing::warn!(?e, %path, "handle_conflict failed (non-fatal)");
                    }
                }
            }
            _ => {
                // Unknown / future status: ACK to avoid wedging the queue.
                if let Some(s) = seq { seqs_to_ack.push(s); }
            }
        }
        // For a successful delete op, also forget the ledger entry.
        if r.op == "delete" && r.status == "ok" {
            if let Some(path) = p_opt { let _ = ledger.forget(path); }
        }
    }
    Ok(seqs_to_ack)
}

/// On 412 (`status:"precondition_failed"`): download remote, save as a sidecar
/// file under `~/.dbay/conflicts/`, append a line to the conflicts log, then
/// forget the ledger entry so the next retry sends WITHOUT `if_match` —
/// effectively making the local version the new baseline (local wins).
fn handle_conflict(cli: &DbayClient, ledger: &Ledger, path: &str) -> Result<()> {
    let (remote_bytes, remote_etag, _mtime) = cli
        .lbfs_get(path)
        .with_context(|| format!("download remote for conflict path {path}"))?;
    let host = crate::hostname::hostname_or_unknown();
    let ts = crate::conflict::local_filename_timestamp();
    let conflicts_root = std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_default()
        .join(".dbay").join("conflicts");
    let conflict_local = crate::conflict::conflict_sidecar_path(&conflicts_root, path, &host, &ts);
    if let Some(parent) = conflict_local.parent() {
        let _ = fs::create_dir_all(parent);
    }
    fs::write(&conflict_local, &remote_bytes).with_context(|| {
        format!("write conflict file {}", conflict_local.display())
    })?;
    crate::conflict::append_conflict_log(&conflicts_root, path, &remote_etag, &host, &ts, &conflict_local);
    let _ = ledger.forget(path);
    tracing::warn!(
        %path,
        %remote_etag,
        conflict_file = %conflict_local.display(),
        "etag conflict: saved remote sidecar, dropped ledger entry (local wins)"
    );
    Ok(())
}

#[allow(dead_code)]
fn send_one(cli: &DbayClient, outbox: &Outbox, entry: &Entry) -> Result<()> {
    match &entry.op {
        Op::Put { path, blob, properties } => {
            let bytes = outbox::read_blob(&outbox.blobs_dir(), blob)
                .context("read blob for put")?;
            cli.lbfs_put(path, &bytes, properties.as_ref())?;
        }
        Op::Append { path, blob } => {
            let bytes = outbox::read_blob(&outbox.blobs_dir(), blob)
                .context("read blob for append")?;
            cli.lbfs_append(path, &bytes)?;
        }
        Op::Delete { path } => {
            cli.lbfs_delete(path)?;
        }
        Op::Rename { path, new_path } => {
            cli.lbfs_rename(path, new_path)?;
        }
        Op::Mkdir { path, properties } => {
            cli.lbfs_mkdir(path, properties.as_ref())?;
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
        Op::Mkdir { path, .. } => format!("MKDIR {path}"),
        Op::Ack { .. } => return,
    };
    tracing::info!(seq = entry.seq, "[log-only] {}", op_label);
}

#[cfg(test)]
mod tests {
    use super::is_transient_lbfs_provisioning_error;

    #[test]
    fn recognizes_lbfs_provisioning_retry_errors() {
        let err = anyhow::anyhow!(
            "lbfs batch failed: 500 Internal Server Error LakebaseFS database not READY after 120s; retry shortly"
        );
        assert!(is_transient_lbfs_provisioning_error(&err));

        let fatal = anyhow::anyhow!("lbfs batch failed: 401 Unauthorized");
        assert!(!is_transient_lbfs_provisioning_error(&fatal));
    }
}
