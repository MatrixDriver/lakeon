//! DBay FUSE daemon
//!
//! Mounts a virtual view of a DBay folder at any user-chosen local
//! directory. Real content lives on local disk at `~/.dbay/state/<folder>/`
//! for mount mode, or in the user's source directory for sync mode.
//!
//! Architecture (post red-blue review):
//!   FUSE op → passthrough (local state/) → outbox (append-only log + blobs)
//!                                                      ↓
//!                                             async uplink worker
//!                                                      ↓
//!                                             DBay AgentFS HTTP API
//!
//! No SQLite. No in-memory DB. Durability = POSIX file + outbox.

use anyhow::{anyhow, bail, Context, Result};
use clap::{Parser, Subcommand};
use std::path::{Path, PathBuf};

mod append_state;
mod config;
mod conflict;
mod dbay_api;
mod etag_ledger;
mod flush_watchdog;
mod hostname;
mod inmem;
mod outbox;
mod passthrough;
mod profile;
mod pull;
mod state_scan;
mod sync;
mod uplink_worker;

use profile::{DirectoryKind, ProcessingProfile, StoragePolicy, FolderProfile};

#[derive(Parser)]
#[command(name = "dbay-fuse", version)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Mount the virtual filesystem for a folder (blocks)
    Mount {
        /// Local mount directory. If omitted, defaults to ~/.dbay/mnt/<folder>.
        mount_dir: Option<PathBuf>,
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
        #[arg(long)]
        mount: Option<PathBuf>,
        #[arg(long)]
        state: Option<PathBuf>,
        #[arg(long, value_enum, default_value_t = DirectoryKind::Files)]
        kind: DirectoryKind,
        #[arg(long, value_enum)]
        storage: Option<StoragePolicy>,
        #[arg(long, value_enum)]
        processing: Option<ProcessingProfile>,
        #[arg(long)]
        foreground: bool,
        /// Experimental: in-memory backend, no local disk passthrough.
        /// Writes buffer in RAM until flush/release, then PUT to AgentFS
        /// in a single tx. Daemon crash before flush = no commit (rollback).
        /// Default false: keep proven disk-passthrough + outbox model.
        #[arg(long)]
        in_memory: bool,
        /// Skip the automatic remote→local pull on startup.
        #[arg(long)]
        skip_pull: bool,
    },
    /// Unmount
    Umount {
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
        #[arg(long)]
        mount: Option<PathBuf>,
    },
    /// Bind a folder to a memory base BY NAME.
    AgentBind {
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
        #[arg(long)]
        base: String,
    },
    /// Print resolved config
    Whoami {
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
        #[arg(long, value_enum, default_value_t = DirectoryKind::Files)]
        kind: DirectoryKind,
        #[arg(long, value_enum)]
        storage: Option<StoragePolicy>,
        #[arg(long, value_enum)]
        processing: Option<ProcessingProfile>,
    },
    /// Show outbox status (pending / failed / done entries)
    OutboxStatus {
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
    },
    /// Drain one sync/import outbox batch to DBay AgentFS.
    OutboxDrain {
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
    },
    /// Sync remote AgentFS down to local state directory (one-shot).
    Pull {
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
        #[arg(long, default_value = "/")]
        prefix: String,
        #[arg(long)]
        include_large: bool,
        #[arg(long)]
        dry_run: bool,
        #[arg(long)]
        state: Option<PathBuf>,
    },
    /// Add an existing local directory to DBay sync without copying it.
    Sync {
        source_dir: PathBuf,
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
        #[arg(long, value_enum, default_value_t = DirectoryKind::Files)]
        kind: DirectoryKind,
        #[arg(long, value_enum)]
        storage: Option<StoragePolicy>,
        #[arg(long, value_enum)]
        processing: Option<ProcessingProfile>,
        #[arg(long, default_value = "/")]
        remote: String,
        #[arg(long)]
        dry_run: bool,
    },
    /// One-shot import of an existing directory using the sync planner.
    Import {
        source_dir: PathBuf,
        #[arg(long)]
        folder: Option<String>,
        /// Deprecated compatibility alias for --folder.
        #[arg(long)]
        agent: Option<String>,
        #[arg(long, value_enum, default_value_t = DirectoryKind::Files)]
        kind: DirectoryKind,
        #[arg(long, value_enum)]
        storage: Option<StoragePolicy>,
        #[arg(long, value_enum)]
        processing: Option<ProcessingProfile>,
        #[arg(long, default_value = "/")]
        remote: String,
        #[arg(long)]
        dry_run: bool,
    },
    /// Inspect a local directory and recommend a DBay directory kind.
    Inspect {
        source_dir: PathBuf,
    },
}

fn home() -> Result<PathBuf> {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .ok_or_else(|| anyhow!("HOME not set"))
}

fn default_mount(folder: &str) -> Result<PathBuf> {
    Ok(home()?.join(".dbay").join("mnt").join(folder))
}

fn default_state(folder: &str) -> Result<PathBuf> {
    Ok(home()?.join(".dbay").join("state").join(folder))
}

pub fn default_outbox(folder: &str) -> Result<PathBuf> {
    Ok(home()?.join(".dbay").join("outbox").join(folder))
}

fn folder_name(folder: Option<String>, agent: Option<String>, local_hint: Option<&Path>) -> String {
    folder
        .or(agent)
        .or_else(|| {
            local_hint
                .and_then(|p| p.file_name())
                .and_then(|s| s.to_str())
                .map(sanitize_folder_name)
        })
        .unwrap_or_else(|| "default".to_string())
}

fn sanitize_folder_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c.to_ascii_lowercase()
            } else {
                '-'
            }
        })
        .collect();
    let cleaned = cleaned.trim_matches('-');
    if cleaned.is_empty() {
        "folder".to_string()
    } else {
        cleaned.to_string()
    }
}

fn ledger_path(folder: &str) -> Result<PathBuf> {
    Ok(home()?
        .join(".dbay")
        .join("sync-ledger")
        .join(folder)
        .join("etags.db"))
}

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    let cli = Cli::parse();
    match cli.cmd {
        Cmd::Mount {
            mount_dir,
            folder,
            agent,
            mount,
            state,
            kind,
            storage,
            processing,
            foreground: _,
            in_memory,
            skip_pull,
        } => {
            let mount_hint = mount.as_deref().or(mount_dir.as_deref());
            let folder = folder_name(folder, agent, mount_hint);
            let profile = FolderProfile::new(&folder, kind, storage, processing);
            let mount = mount.or(mount_dir).unwrap_or(default_mount(&folder)?);
            std::fs::create_dir_all(&mount)?;
            if in_memory {
                tracing::info!(?profile, ?mount, "mounting (in-memory backend)");
                inmem::mount(&folder, &mount)?;
            } else {
                let state = state.unwrap_or(default_state(&folder)?);
                let outbox_dir = default_outbox(&folder)?;
                std::fs::create_dir_all(&state)?;
                std::fs::create_dir_all(&outbox_dir)?;

                if !skip_pull {
                    match dbay_api::DbayClient::for_agent_no_base(&folder)? {
                        Some(cli) => {
                            let ledger_path = ledger_path(&folder)?;
                            if let Some(parent) = ledger_path.parent() {
                                std::fs::create_dir_all(parent).ok();
                            }
                            match etag_ledger::Ledger::open(&ledger_path) {
                                Ok(ledger) => {
                                    tracing::info!("running startup pull (skip with --skip-pull)");
                                    match pull::pull(&cli, &ledger, &state, "/", false, false) {
                                        Ok(s) => tracing::info!(
                                            synced = s.synced,
                                            skipped = s.skipped,
                                            conflicts = s.conflicts,
                                            errors = s.errors,
                                            "startup pull complete"
                                        ),
                                        Err(e) => tracing::warn!(
                                            ?e,
                                            "startup pull failed — continuing with cached local state"
                                        ),
                                    }
                                }
                                Err(e) => tracing::warn!(
                                    ?e,
                                    "ledger open failed — skipping startup pull"
                                ),
                            }
                        }
                        None => tracing::warn!("DBay not configured — skipping startup pull"),
                    }
                }

                tracing::info!(?profile, ?mount, ?state, ?outbox_dir, "mounting (disk passthrough)");
                passthrough::mount(&folder, &mount, &state, &outbox_dir, profile)?;
            }
        }
        Cmd::Umount { folder, agent, mount } => {
            let folder = folder_name(folder, agent, None);
            let mount = mount.unwrap_or(default_mount(&folder)?);
            tracing::info!(?folder, ?mount, "unmounting");
            umount(&mount)?;
        }
        Cmd::AgentBind { folder, agent, base } => {
            let folder = folder_name(folder, agent, None);
            config::write_agent_binding(&folder, &base)?;
            println!("✓ folder {folder} bound to base {base}");
            println!("  config: {}", config::config_path()?.display());
        }
        Cmd::Whoami { folder, agent, kind, storage, processing } => {
            let folder = folder_name(folder, agent, None);
            let profile = FolderProfile::new(&folder, kind, storage, processing);
            match config::resolve_for_agent(&folder)? {
                None => {
                    println!("Not logged in. Run `dbay login` or set DBAY_API_KEY.");
                    println!("Sign up at https://console.dbay.cloud");
                }
                Some(r) => {
                    let key_preview = if r.api_key.len() > 10 {
                        format!("{}...{}", &r.api_key[..6], &r.api_key[r.api_key.len() - 4..])
                    } else {
                        "***".into()
                    };
                    println!("folder: {}", profile.folder);
                    println!("kind:      {}", profile.directory_kind);
                    println!("storage:   {}", profile.storage_policy);
                    println!("processing: {}", profile.processing_profile);
                    println!("api_key:   {}", key_preview);
                    println!("base_url:  {}", r.base_url);
                    println!(
                        "base_ref:  {}  ({})",
                        if r.base_ref.is_empty() { "(auto-detect)" } else { &r.base_ref },
                        if config::is_base_id(&r.base_ref) { "id" } else { "name" }
                    );
                    match dbay_api::DbayClient::for_agent(&folder) {
                        Ok(Some(c)) => {
                            println!("base_id:   {}", c.base_id());
                            println!(
                                "base_name: {}",
                                if c.base_name().is_empty() { "—" } else { c.base_name() }
                            );
                        }
                        Ok(None) => {}
                        Err(e) => println!("resolve error: {e}"),
                    }
                }
            }
        }
        Cmd::OutboxStatus { folder, agent } => {
            let folder = folder_name(folder, agent, None);
            let outbox_dir = default_outbox(&folder)?;
            outbox::print_status(&outbox_dir)?;
        }
        Cmd::OutboxDrain { folder, agent } => {
            let folder = folder_name(folder, agent, None);
            let paths = sync::default_sync_paths(&home()?, &folder);
            std::fs::create_dir_all(&paths.outbox)?;
            if let Some(parent) = paths.ledger.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let drained = uplink_worker::drain_once(&folder, &paths.outbox, &paths.ledger)?;
            println!("outbox drain complete: drained={drained}");
        }
        Cmd::Pull { folder, agent, prefix, include_large, dry_run, state } => {
            let folder = folder_name(folder, agent, None);
            let cli = dbay_api::DbayClient::for_agent_no_base(&folder)?
                .ok_or_else(|| anyhow!("DBay not configured: see ~/.dbay/config.json"))?;
            let state_dir = state.unwrap_or(default_state(&folder)?);
            std::fs::create_dir_all(&state_dir).ok();
            let ledger_path = ledger_path(&folder)?;
            if let Some(parent) = ledger_path.parent() {
                std::fs::create_dir_all(parent).ok();
            }
            let ledger = etag_ledger::Ledger::open(&ledger_path)
                .with_context(|| format!("open ledger {}", ledger_path.display()))?;
            let summary = pull::pull(&cli, &ledger, &state_dir, &prefix, include_large, dry_run)?;
            println!(
                "pull complete: synced={} skipped={} conflicts={} skipped_large={} errors={}",
                summary.synced,
                summary.skipped,
                summary.conflicts,
                summary.skipped_large,
                summary.errors
            );
            if summary.errors > 0 {
                std::process::exit(2);
            }
        }
        Cmd::Sync {
            source_dir,
            folder,
            agent,
            kind,
            storage,
            processing,
            remote,
            dry_run,
        } => {
            run_sync_like("sync", source_dir, folder, agent, kind, storage, processing, remote, dry_run)?;
        }
        Cmd::Import {
            source_dir,
            folder,
            agent,
            kind,
            storage,
            processing,
            remote,
            dry_run,
        } => {
            run_sync_like("import", source_dir, folder, agent, kind, storage, processing, remote, dry_run)?;
        }
        Cmd::Inspect { source_dir } => {
            let rec = profile::inspect_path(&source_dir)?;
            println!("recommended_kind: {}", rec.kind);
            println!("confidence: {:.2}", rec.confidence);
            for reason in rec.reasons {
                println!("reason: {reason}");
            }
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn run_sync_like(
    label: &str,
    source_dir: PathBuf,
    folder: Option<String>,
    agent: Option<String>,
    kind: DirectoryKind,
    storage: Option<StoragePolicy>,
    processing: Option<ProcessingProfile>,
    remote: String,
    dry_run: bool,
) -> Result<()> {
    let folder = folder_name(folder, agent, Some(&source_dir));
    let profile = FolderProfile::new(&folder, kind, storage, processing);
    let plan = sync::build_sync_plan(&source_dir, &remote, profile)?;
    let paths = sync::default_sync_paths(&home()?, &folder);

    println!("{label} plan:");
    println!("  folder: {}", plan.profile.folder);
    println!("  source:    {}", plan.state_root.display());
    println!("  remote:    {}", plan.remote_prefix);
    println!("  kind:      {}", plan.profile.directory_kind);
    println!("  storage:   {}", plan.profile.storage_policy);
    println!("  processing: {}", plan.profile.processing_profile);
    println!("  entries:   {}", plan.entries.len());
    println!("  metadata:  {}", paths.root.display());

    if dry_run {
        return Ok(());
    }

    std::fs::create_dir_all(&paths.outbox)?;
    std::fs::create_dir_all(&paths.tmp)?;
    let outbox = outbox::Outbox::open(&paths.outbox)?;
    let enqueued = sync::enqueue_sync_plan(&plan, &outbox)?;
    println!("  queued:    {enqueued}");
    println!("  note:      run a folder sync daemon/uplink worker to drain this queue");
    Ok(())
}

fn umount(mount: &Path) -> Result<()> {
    let status = std::process::Command::new("umount")
        .arg(mount)
        .status()
        .with_context(|| format!("failed to spawn umount {}", mount.display()))?;
    if !status.success() {
        bail!("umount {} failed", mount.display());
    }
    Ok(())
}
