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

mod config;
mod dbay_api;
mod flush_watchdog;
mod outbox;
mod passthrough;
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
        Cmd::Mount { agent, mount, state, foreground: _ } => {
            let mount = mount.unwrap_or(default_mount(&agent)?);
            let state = state.unwrap_or(default_state(&agent)?);
            let outbox_dir = default_outbox(&agent)?;
            std::fs::create_dir_all(&mount)?;
            std::fs::create_dir_all(&state)?;
            std::fs::create_dir_all(&outbox_dir)?;
            tracing::info!(?agent, ?mount, ?state, ?outbox_dir, "mounting");
            passthrough::mount(&agent, &mount, &state, &outbox_dir)?;
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
