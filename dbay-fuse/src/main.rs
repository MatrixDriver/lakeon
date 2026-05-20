//! DBay FUSE daemon
//!
//! Mounts a virtual view of a user's managed agent directories (projects/, memory/, CLAUDE.md, ...)
//! at `~/.dbay/mnt/<agent>/`. Real content lives on local disk at
//! `~/.dbay/state/<agent>/` (passthrough FS).
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
mod dbay_api;
mod etag_ledger;
mod flush_watchdog;
mod inmem;
mod outbox;
mod passthrough;
mod pull;
mod state_scan;
mod takeover;
mod uplink_worker;

#[derive(Parser)]
#[command(name = "dbay-fuse", version)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Mount the virtual filesystem for an agent (blocks)
    Mount {
        #[arg(long)]
        agent: String,
        #[arg(long)]
        mount: Option<PathBuf>,
        #[arg(long)]
        state: Option<PathBuf>,
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
        agent: String,
        #[arg(long)]
        mount: Option<PathBuf>,
    },
    /// Take over an agent's real directories: backup + rsync into state + symlink to mount
    Takeover {
        #[arg(long)]
        agent: String,
        #[arg(long)]
        nodes: Option<String>,
        #[arg(long)]
        dry_run: bool,
    },
    /// Reverse of takeover
    Release {
        #[arg(long)]
        agent: String,
        #[arg(long)]
        dry_run: bool,
    },
    /// Bind an agent to a memory base BY NAME
    AgentBind {
        #[arg(long)]
        agent: String,
        #[arg(long)]
        base: String,
    },
    /// Print resolved config
    Whoami {
        #[arg(long, default_value = "claude")]
        agent: String,
    },
    /// Show outbox status (pending / failed / done entries)
    OutboxStatus {
        #[arg(long)]
        agent: String,
    },
    /// Sync remote AgentFS down to local state directory (one-shot).
    Pull {
        #[arg(long)]
        agent: String,
        #[arg(long, default_value = "/")]
        prefix: String,
        #[arg(long)]
        include_large: bool,
        #[arg(long)]
        dry_run: bool,
        #[arg(long)]
        state: Option<PathBuf>,
    },
}

fn home() -> Result<PathBuf> {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .ok_or_else(|| anyhow!("HOME not set"))
}

fn default_mount(agent: &str) -> Result<PathBuf> {
    Ok(home()?.join(".dbay").join("mnt").join(agent))
}

fn default_state(agent: &str) -> Result<PathBuf> {
    Ok(home()?.join(".dbay").join("state").join(agent))
}

pub fn default_outbox(agent: &str) -> Result<PathBuf> {
    Ok(home()?.join(".dbay").join("outbox").join(agent))
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
        Cmd::Mount { agent, mount, state, foreground: _, in_memory, skip_pull } => {
            let mount = mount.unwrap_or(default_mount(&agent)?);
            std::fs::create_dir_all(&mount)?;
            if in_memory {
                tracing::info!(?agent, ?mount, "mounting (in-memory backend)");
                inmem::mount(&agent, &mount)?;
            } else {
                let state = state.unwrap_or(default_state(&agent)?);
                let outbox_dir = default_outbox(&agent)?;
                std::fs::create_dir_all(&state)?;
                std::fs::create_dir_all(&outbox_dir)?;

                if !skip_pull {
                    match dbay_api::DbayClient::for_agent(&agent)? {
                        Some(cli) => {
                            let ledger_path = home()?
                                .join(".dbay")
                                .join("sync-ledger")
                                .join(&agent)
                                .join("etags.db");
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

                tracing::info!(?agent, ?mount, ?state, ?outbox_dir, "mounting (disk passthrough)");
                passthrough::mount(&agent, &mount, &state, &outbox_dir)?;
            }
        }
        Cmd::Umount { agent, mount } => {
            let mount = mount.unwrap_or(default_mount(&agent)?);
            tracing::info!(?agent, ?mount, "unmounting");
            umount(&mount)?;
        }
        Cmd::Takeover { agent, nodes, dry_run } => {
            let plan = takeover::plan_for(&agent, nodes.as_deref())?;
            takeover::execute(&agent, &plan, dry_run)?;
        }
        Cmd::Release { agent, dry_run } => {
            takeover::release(&agent, dry_run)?;
        }
        Cmd::AgentBind { agent, base } => {
            config::write_agent_binding(&agent, &base)?;
            println!("✓ agent {agent} bound to base {base}");
            println!("  config: {}", config::config_path()?.display());
        }
        Cmd::Whoami { agent } => {
            match config::resolve_for_agent(&agent)? {
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
                    println!("agent:     {agent}");
                    println!("api_key:   {}", key_preview);
                    println!("base_url:  {}", r.base_url);
                    println!(
                        "base_ref:  {}  ({})",
                        if r.base_ref.is_empty() { "(auto-detect)" } else { &r.base_ref },
                        if config::is_base_id(&r.base_ref) { "id" } else { "name" }
                    );
                    match dbay_api::DbayClient::for_agent(&agent) {
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
        Cmd::OutboxStatus { agent } => {
            let outbox_dir = default_outbox(&agent)?;
            outbox::print_status(&outbox_dir)?;
        }
        Cmd::Pull { agent, prefix, include_large, dry_run, state } => {
            let cli = dbay_api::DbayClient::for_agent(&agent)?
                .ok_or_else(|| anyhow!("DBay not configured: see ~/.dbay/config.json"))?;
            let state_dir = state.unwrap_or(default_state(&agent)?);
            std::fs::create_dir_all(&state_dir).ok();
            let ledger_path = home()?
                .join(".dbay")
                .join("sync-ledger")
                .join(&agent)
                .join("etags.db");
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
    }
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
