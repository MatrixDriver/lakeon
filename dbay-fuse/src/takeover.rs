//! Takeover helper.
//!
//! Phase-1 scope per `specs/universal-memory.md`:
//! - claude:   projects/ + memory/ + CLAUDE.md
//! - openclaw: workspace/memory/ + workspace/MEMORY.md + workspace/AGENTS.md
//!
//! Flow (takeover):
//!   1. Resolve real agent home (~/.claude or ~/.openclaw)
//!   2. Verify daemon is up (mount point appears non-empty / exists)
//!   3. For each node: skip if already symlink; else backup + rsync to state + verify
//!   4. Swap: rm original + symlink to mount
//!   5. Write a takeover-<ts>.json record into backups/
//!
//! Flow (release):
//!   1. Load latest takeover record
//!   2. For each node: remove symlink + rsync from backup back to original
//!   3. Mark record as released

use anyhow::{anyhow, bail, Context, Result};
use std::fs;
use std::os::unix::fs::symlink;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::state_scan;
use crate::{default_mount, default_outbox, default_state, home};

pub struct Node {
    /// Relative path under agent home, e.g. "projects" or "CLAUDE.md"
    pub rel: PathBuf,
    /// Real directory/file on disk
    pub real: PathBuf,
    /// Corresponding path inside state_dir
    pub state: PathBuf,
    /// Corresponding path inside mount_dir
    pub mnt: PathBuf,
}

pub struct Plan {
    pub agent: String,
    pub agent_home: PathBuf,
    pub state_dir: PathBuf,
    pub mount_dir: PathBuf,
    pub nodes: Vec<Node>,
}

fn agent_home(agent: &str) -> Result<PathBuf> {
    let h = home()?;
    match agent {
        "claude" => Ok(h.join(".claude")),
        "openclaw" => Ok(h.join(".openclaw")),
        other => bail!("unsupported agent: {other}"),
    }
}

fn default_nodes(agent: &str) -> Vec<&'static str> {
    match agent {
        "claude" => vec!["projects", "memory", "CLAUDE.md"],
        "openclaw" => vec!["workspace/memory", "workspace/MEMORY.md", "workspace/AGENTS.md"],
        _ => vec![],
    }
}

pub fn plan_for(agent: &str, nodes_arg: Option<&str>) -> Result<Plan> {
    let agent_home = agent_home(agent)?;
    let state_dir = default_state(agent)?;
    let mount_dir = default_mount(agent)?;
    let rel_list: Vec<String> = match nodes_arg {
        Some(s) => s.split(',').map(|s| s.trim().to_string()).collect(),
        None => default_nodes(agent).into_iter().map(String::from).collect(),
    };
    if rel_list.is_empty() {
        bail!("no nodes to take over");
    }
    let nodes = rel_list
        .into_iter()
        .map(|rel| {
            let rel = PathBuf::from(rel);
            Node {
                real: agent_home.join(&rel),
                state: state_dir.join(&rel),
                mnt: mount_dir.join(&rel),
                rel,
            }
        })
        .collect();
    Ok(Plan {
        agent: agent.to_string(),
        agent_home,
        state_dir,
        mount_dir,
        nodes,
    })
}

fn ts() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn is_symlink(p: &Path) -> bool {
    fs::symlink_metadata(p)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
}

fn exists(p: &Path) -> bool {
    fs::symlink_metadata(p).is_ok()
}

fn ensure_parent(p: &Path) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent)?;
    }
    Ok(())
}

fn rsync_tree(src: &Path, dst: &Path) -> Result<()> {
    // rsync -a <src>/ <dst>/  — preserves attrs, copies tree; works for files too if no trailing slash on src.
    ensure_parent(dst)?;
    let status = std::process::Command::new("rsync")
        .arg("-a")
        .arg("--delete")
        .arg(src)
        .arg(dst)
        .status()
        .context("rsync failed to spawn")?;
    if !status.success() {
        bail!("rsync {} → {} failed", src.display(), dst.display());
    }
    Ok(())
}

fn copy_tree(src: &Path, dst: &Path) -> Result<()> {
    ensure_parent(dst)?;
    let meta = fs::symlink_metadata(src)?;
    if meta.is_dir() {
        // rsync src/ dst  (trailing slash → contents into dst dir)
        let mut src_arg = src.as_os_str().to_owned();
        src_arg.push("/");
        fs::create_dir_all(dst)?;
        let status = std::process::Command::new("rsync")
            .arg("-a")
            .arg(src_arg)
            .arg(dst)
            .status()
            .context("rsync failed to spawn")?;
        if !status.success() {
            bail!("rsync {} → {} failed", src.display(), dst.display());
        }
    } else if meta.is_file() {
        fs::copy(src, dst).with_context(|| format!("copy {} → {}", src.display(), dst.display()))?;
    } else {
        bail!("unsupported file type at {}", src.display());
    }
    Ok(())
}

pub fn execute(agent: &str, plan: &Plan, dry_run: bool) -> Result<()> {
    // Sanity checks
    if !exists(&plan.mount_dir) {
        bail!("mount dir {} does not exist — start daemon first with `dbay-fuse mount --agent {}`", plan.mount_dir.display(), agent);
    }

    // Actions collected for dry-run preview
    let mut actions: Vec<String> = Vec::new();

    let backup_root = home()?
        .join(".dbay")
        .join("backups")
        .join(format!("{}-{}", agent, ts()));

    for node in &plan.nodes {
        tracing::info!(?node.real, "inspecting");
        if is_symlink(&node.real) {
            actions.push(format!("SKIP  {} (already a symlink)", node.real.display()));
            continue;
        }
        if !exists(&node.real) {
            // Nothing to take over; create empty in state + symlink
            actions.push(format!(
                "CREATE empty at {} and symlink → {}",
                node.state.display(),
                node.mnt.display()
            ));
            continue;
        }

        let backup_dest = backup_root.join(&node.rel);
        actions.push(format!(
            "BACKUP {} → {}",
            node.real.display(),
            backup_dest.display()
        ));
        actions.push(format!(
            "IMPORT {} → {} (state)",
            node.real.display(),
            node.state.display()
        ));
        actions.push(format!(
            "SWAP  rm {} && ln -s {} {}",
            node.real.display(),
            node.mnt.display(),
            node.real.display()
        ));
    }

    if dry_run {
        println!("DRY RUN for agent={} — planned actions:", agent);
        for a in &actions {
            println!("  {a}");
        }
        return Ok(());
    }

    // Pre-takeover: pull remote files into state_dir so the subsequent
    // per-node rsync from real → state overlays local-new on top of
    // remote state. Best-effort — any failure logs a warning and we
    // continue with takeover.
    //
    // Note: state_dir here MUST match plan.state_dir (the same path the
    // per-node copy_tree below writes into via node.state = state_dir/rel).
    fs::create_dir_all(&plan.state_dir).ok();
    match crate::dbay_api::DbayClient::for_agent(agent) {
        Ok(Some(cli)) => {
            let ledger_path = home()
                .map(|h| h.join(".dbay").join("sync-ledger").join(agent).join("etags.db"))
                .unwrap_or_else(|_| PathBuf::from("./etags.db"));
            if let Some(parent) = ledger_path.parent() {
                fs::create_dir_all(parent).ok();
            }
            match crate::etag_ledger::Ledger::open(&ledger_path) {
                Ok(ledger) => {
                    match crate::pull::pull(&cli, &ledger, &plan.state_dir, "/", false, false) {
                        Ok(s) => tracing::info!(?s, "pre-takeover pull complete"),
                        Err(e) => tracing::warn!(?e, "pre-takeover pull failed; continuing"),
                    }
                }
                Err(e) => tracing::warn!(?e, "pre-takeover ledger open failed; continuing"),
            }
        }
        Ok(None) => tracing::warn!("DBay not configured — skipping pre-takeover pull"),
        Err(e) => tracing::warn!(?e, "pre-takeover DbayClient init failed; continuing"),
    }

    // Execute for real
    fs::create_dir_all(&backup_root)?;

    for node in &plan.nodes {
        if is_symlink(&node.real) {
            tracing::info!(?node.real, "already symlink, skipping");
            continue;
        }
        if !exists(&node.real) {
            // empty import — just ensure state path exists with proper type
            tracing::info!(?node.real, "not present; creating empty placeholder");
            if node.rel.extension().is_some() {
                ensure_parent(&node.state)?;
                fs::write(&node.state, b"")?;
            } else {
                fs::create_dir_all(&node.state)?;
            }
            ensure_parent(&node.real)?;
            symlink(&node.mnt, &node.real).with_context(|| {
                format!("symlink {} → {}", node.real.display(), node.mnt.display())
            })?;
            continue;
        }

        // 1) backup
        let backup_dest = backup_root.join(&node.rel);
        copy_tree(&node.real, &backup_dest).context("backup copy failed")?;
        // 2) import into state (overwrites whatever's there)
        if fs::symlink_metadata(&node.real)?.is_dir() {
            // Clear state first to mirror exactly
            if exists(&node.state) {
                fs::remove_dir_all(&node.state).ok();
            }
            copy_tree(&node.real, &node.state).context("import to state failed")?;
        } else {
            copy_tree(&node.real, &node.state).context("import to state failed")?;
        }
        // 3) swap: remove real, symlink to mount point
        if fs::symlink_metadata(&node.real)?.is_dir() {
            fs::remove_dir_all(&node.real)?;
        } else {
            fs::remove_file(&node.real)?;
        }
        ensure_parent(&node.real)?;
        symlink(&node.mnt, &node.real).with_context(|| {
            format!("symlink {} → {}", node.real.display(), node.mnt.display())
        })?;
        tracing::info!(?node.real, "taken over");
    }

    // Write record
    let rec = serde_record(agent, plan, &backup_root);
    fs::write(backup_root.join("takeover.json"), rec)?;

    // Signal the running daemon to re-scan state/ so freshly-imported
    // content propagates to AgentFS. Without this the server never
    // sees the takeover's rsynced files (existing files are only
    // flushed via FUSE write ops, which takeover doesn't generate).
    let outbox_dir = default_outbox(agent)?;
    let trigger = state_scan::write_rescan_trigger(&outbox_dir)
        .context("signal rescan to daemon")?;
    tracing::info!(?trigger, "rescan trigger written; daemon will sync state/ on next poll");

    println!("✓ takeover complete. Backup at: {}", backup_root.display());
    println!("  To reverse:  dbay-fuse release --agent {}", agent);
    Ok(())
}

fn serde_record(agent: &str, plan: &Plan, backup_root: &Path) -> String {
    // Minimal hand-rolled JSON to avoid pulling serde into this crate for now.
    let nodes_json: Vec<String> = plan
        .nodes
        .iter()
        .map(|n| {
            format!(
                r#"{{"rel":{:?},"real":{:?},"state":{:?},"mnt":{:?}}}"#,
                n.rel.to_string_lossy(),
                n.real.to_string_lossy(),
                n.state.to_string_lossy(),
                n.mnt.to_string_lossy()
            )
        })
        .collect();
    format!(
        r#"{{"agent":{:?},"agent_home":{:?},"backup_root":{:?},"ts":{},"nodes":[{}]}}"#,
        agent,
        plan.agent_home.to_string_lossy(),
        backup_root.to_string_lossy(),
        ts(),
        nodes_json.join(",")
    )
}

pub fn release(agent: &str, dry_run: bool) -> Result<()> {
    // Find latest backup with takeover.json
    let backups = home()?.join(".dbay").join("backups");
    if !exists(&backups) {
        bail!("no backups dir at {}", backups.display());
    }
    let mut latest: Option<(u64, PathBuf)> = None;
    for entry in fs::read_dir(&backups)? {
        let e = entry?;
        let name = e.file_name().to_string_lossy().into_owned();
        if !name.starts_with(&format!("{agent}-")) {
            continue;
        }
        let rec = e.path().join("takeover.json");
        if !rec.exists() {
            continue;
        }
        let stamp: u64 = name
            .rsplit_once('-')
            .and_then(|(_, ts)| ts.parse().ok())
            .unwrap_or(0);
        if latest.as_ref().map(|(s, _)| *s < stamp).unwrap_or(true) {
            latest = Some((stamp, e.path()));
        }
    }
    let (_, backup_root) = latest.ok_or_else(|| anyhow!("no takeover record found for agent={agent}"))?;
    let rec_text = fs::read_to_string(backup_root.join("takeover.json"))?;
    tracing::info!(?backup_root, "releasing");

    // We only need the node rel paths from the record. Super-cheap parse:
    let rels: Vec<String> = rec_text
        .split(r#""rel":"#)
        .skip(1)
        .filter_map(|chunk| {
            let after = chunk.trim_start_matches(|c: char| c == ' ');
            if !after.starts_with('"') {
                return None;
            }
            after[1..].split_once('"').map(|(v, _)| v.to_string())
        })
        .collect();

    let agent_home = agent_home(agent)?;

    let mut actions: Vec<String> = Vec::new();
    for rel in &rels {
        let real = agent_home.join(rel);
        let backup_src = backup_root.join(rel);
        actions.push(format!("UNLINK {}", real.display()));
        actions.push(format!(
            "RESTORE {} → {}",
            backup_src.display(),
            real.display()
        ));
    }

    if dry_run {
        println!("DRY RUN release for agent={agent}:");
        for a in actions {
            println!("  {a}");
        }
        return Ok(());
    }

    for rel in &rels {
        let real = agent_home.join(rel);
        let backup_src = backup_root.join(rel);
        if is_symlink(&real) {
            fs::remove_file(&real).ok();
        } else if exists(&real) {
            if fs::symlink_metadata(&real)?.is_dir() {
                fs::remove_dir_all(&real)?;
            } else {
                fs::remove_file(&real)?;
            }
        }
        if exists(&backup_src) {
            copy_tree(&backup_src, &real).context("restore failed")?;
        }
        tracing::info!(?real, "restored");
    }

    // Rename record so next release doesn't pick it
    fs::rename(
        backup_root.join("takeover.json"),
        backup_root.join("takeover.released.json"),
    )
    .ok();

    println!("✓ release complete. Restored from: {}", backup_root.display());
    Ok(())
}

#[allow(dead_code)]
fn _unused() {
    let _ = rsync_tree;
}
