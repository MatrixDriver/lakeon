//! Read `~/.dbay/config.json` (same file `dbay-cli` / `dbay-mcp` use) and
//! resolve base name/id for the current folder.
//!
//! Config schema (backwards-compat with existing dbay-cli):
//! {
//!   "endpoint": "https://api.dbay.cloud:8443",
//!   "api_key":  "lk_...",
//!   "memory_base": "personal"          // name OR mem_xxx id; default base
//!   "agent_bases": {                   // legacy name; per-folder override by NAME
//!     "personal": "personal",
//!     "codex":    "work"
//!   }
//! }
//!
//! Env vars still override (for CI/one-shot):
//!   DBAY_API_KEY, DBAY_BASE, DBAY_BASE_URL

use anyhow::{anyhow, bail, Context, Result};
use serde::Deserialize;
use std::collections::HashMap;
use std::path::PathBuf;

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
pub struct FileConfig {
    pub endpoint: Option<String>,
    pub api_key: Option<String>,
    pub memory_base: Option<String>,
    pub agent_bases: HashMap<String, String>,
}

pub fn config_path() -> Result<PathBuf> {
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .ok_or_else(|| anyhow!("HOME not set"))?;
    Ok(home.join(".dbay").join("config.json"))
}

pub fn load() -> Result<FileConfig> {
    let path = config_path()?;
    if !path.exists() { return Ok(FileConfig::default()); }
    let text = std::fs::read_to_string(&path)
        .with_context(|| format!("read {}", path.display()))?;
    Ok(serde_json::from_str(&text)
        .with_context(|| format!("parse {}", path.display()))?)
}

/// Resolved connection info for a folder.
pub struct Resolved {
    pub api_key: String,
    pub base_url: String,
    /// The user-facing name or id that should be sent to the lookup step.
    pub base_ref: String,
}

pub fn resolve_for_agent(agent: &str) -> Result<Option<Resolved>> {
    let file = load().unwrap_or_default();
    // Precedence: DBAY_API_KEY env → config.json
    let api_key = std::env::var("DBAY_API_KEY").ok().or(file.api_key.clone());
    let Some(api_key) = api_key else { return Ok(None); };

    // Endpoint
    let raw_endpoint = std::env::var("DBAY_BASE_URL")
        .ok()
        .or(file.endpoint.clone())
        .unwrap_or_else(|| "https://api.dbay.cloud:8443".into());
    // If endpoint is a bare host (dbay-cli convention), append /api/v1
    let base_url = if raw_endpoint.contains("/api/") {
        raw_endpoint
    } else {
        format!("{}/api/v1", raw_endpoint.trim_end_matches('/'))
    };

    // Base selection priority:
    //   1. env DBAY_BASE
    //   2. config.json agent_bases.<folder> (legacy field name)
    //   3. config.json memory_base (legacy single base)
    //   4. None → caller will auto-detect (if exactly 1 READY base)
    let base_ref = std::env::var("DBAY_BASE")
        .ok()
        .or_else(|| file.agent_bases.get(agent).cloned())
        .or(file.memory_base.clone());
    let base_ref = match base_ref {
        Some(b) => b,
        None => return Ok(Some(Resolved { api_key, base_url, base_ref: String::new() })),
    };

    Ok(Some(Resolved { api_key, base_url, base_ref }))
}

/// True when the string looks like a DBay memory base id.
pub fn is_base_id(s: &str) -> bool {
    s.starts_with("mem_")
}

pub fn write_agent_binding(agent: &str, base_name: &str) -> Result<()> {
    let path = config_path()?;
    if let Some(parent) = path.parent() { std::fs::create_dir_all(parent)?; }
    let mut file: serde_json::Value = if path.exists() {
        serde_json::from_str(&std::fs::read_to_string(&path)?)?
    } else {
        serde_json::json!({})
    };
    let obj = file
        .as_object_mut()
        .ok_or_else(|| anyhow!("config.json root is not an object"))?;
    let bindings = obj
        .entry("agent_bases".to_string())
        .or_insert_with(|| serde_json::json!({}));
    let bindings = bindings
        .as_object_mut()
        .ok_or_else(|| anyhow!("agent_bases is not an object"))?;
    bindings.insert(agent.to_string(), serde_json::Value::String(base_name.to_string()));
    let text = serde_json::to_string_pretty(&file)?;
    std::fs::write(&path, text)?;
    Ok(())
}

/// Sanity: for a fresh user with zero api_key anywhere, return a friendly error.
pub fn ensure_logged_in() -> Result<Resolved> {
    let resolved = resolve_for_agent("claude")?;
    match resolved {
        Some(r) if !r.api_key.is_empty() => Ok(r),
        _ => bail!(
            "No DBay credentials. Run `dbay login` first, or set DBAY_API_KEY. \n\
             See https://console.dbay.cloud to create an account."
        ),
    }
}
