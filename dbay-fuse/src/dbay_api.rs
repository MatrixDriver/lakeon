//! Minimal DBay API client (ingest + recall).
//!
//! Uses the endpoints documented in `lakeon/docs/api-reference.md` and
//! the Python SDK at `lakeon/sdk/dbay/memory.py`.

use anyhow::{anyhow, bail, Context, Result};
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use std::time::Duration;

use crate::config::{self, is_base_id};

#[derive(Clone)]
pub struct DbayClient {
    http: Client,
    base_url: String,
    api_key: String,
    base_id: String,
    base_name: String,
}

#[derive(Deserialize, Debug)]
struct BaseInfo {
    id: String,
    name: String,
    #[serde(default)]
    status: Option<String>,
}

#[derive(Serialize)]
struct IngestReq<'a> {
    content: &'a str,
    role: &'a str,
    memory_type: &'a str,
    importance: f32,
    metadata: serde_json::Value,
}

#[derive(Deserialize, Debug)]
pub struct IngestResp {
    #[serde(default)]
    pub id: Option<i64>,
    #[serde(default)]
    pub memory_id: Option<i64>,
    #[serde(default)]
    pub status: Option<String>,
}

#[derive(Serialize)]
struct RecallReq<'a> {
    query: &'a str,
    top_k: u32,
    memory_types: Option<Vec<String>>,
}

#[derive(Deserialize, Debug)]
pub struct RecallResp {
    #[serde(default)]
    pub memories: Vec<RecalledMemory>,
}

/// AgentFS list/head entry. Used by the in-memory FUSE backend.
#[derive(Debug, Clone)]
pub struct AgentFSEntry {
    pub path: String,
    pub kind: String, // "file" | "dir"
    pub size: u64,
    pub mtime_ns: u64,
    pub etag: String,
}

impl AgentFSEntry {
    pub fn from_json(v: &serde_json::Value) -> Self {
        Self {
            path: v.get("path").and_then(|x| x.as_str()).unwrap_or("").to_string(),
            kind: v.get("kind").and_then(|x| x.as_str()).unwrap_or("file").to_string(),
            size: v.get("size").and_then(|x| x.as_u64()).unwrap_or(0),
            mtime_ns: v.get("mtime_ns").and_then(|x| x.as_u64()).unwrap_or(0),
            etag: v.get("etag").and_then(|x| x.as_str()).unwrap_or("").to_string(),
        }
    }
}

#[derive(Debug)]
pub enum AgentFSPutError {
    PreconditionFailed,
    Other(anyhow::Error),
}

impl std::fmt::Display for AgentFSPutError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::PreconditionFailed => write!(f, "precondition failed (etag mismatch)"),
            Self::Other(e) => write!(f, "{e}"),
        }
    }
}

impl std::error::Error for AgentFSPutError {}

#[derive(Deserialize, Debug)]
pub struct RecalledMemory {
    #[serde(default)]
    pub id: Option<i64>,
    pub content: String,
    #[serde(default)]
    pub memory_type: Option<String>,
    #[serde(default)]
    pub importance: Option<f32>,
    #[serde(default)]
    pub metadata: Option<serde_json::Value>,
}

impl DbayClient {
    /// Build a client for a specific agent using `config.json` + env overrides.
    /// Returns `Ok(None)` when the user isn't logged in yet (no api key anywhere).
    pub fn for_agent(agent: &str) -> Result<Option<Self>> {
        let Some(r) = config::resolve_for_agent(agent)? else { return Ok(None); };
        let http = Client::builder()
            .timeout(Duration::from_secs(30))
            .build()
            .context("build http client")?;
        let mut client = Self {
            http,
            base_url: r.base_url,
            api_key: r.api_key,
            base_id: String::new(),
            base_name: String::new(),
        };
        // Resolve base ref: if it's an id, use directly; if a name, lookup; if empty, auto-detect
        let (id, name) = client.resolve_base(&r.base_ref)?;
        client.base_id = id;
        client.base_name = name;
        Ok(Some(client))
    }

    pub fn from_env() -> Result<Option<Self>> {
        // Back-compat shim: prefer for_agent("claude") but still support raw env.
        Self::for_agent("claude")
    }

    /// Look up base by name (or verify id).
    fn resolve_base(&self, base_ref: &str) -> Result<(String, String)> {
        if is_base_id(base_ref) {
            return Ok((base_ref.to_string(), String::new()));
        }
        let bases = self.list_bases()?;
        if !base_ref.is_empty() {
            let hit = bases
                .iter()
                .find(|b| b.name == base_ref)
                .ok_or_else(|| anyhow!(
                    "memory base named {base_ref:?} not found. \
                     Create it in console.dbay.cloud or run `dbay memory create {base_ref}`."
                ))?;
            return Ok((hit.id.clone(), hit.name.clone()));
        }
        // Auto-detect
        let ready: Vec<&BaseInfo> = bases
            .iter()
            .filter(|b| b.status.as_deref() == Some("READY"))
            .collect();
        match ready.len() {
            0 => bail!(
                "No memory bases on this account. \
                 Create one at https://console.dbay.cloud or run `dbay memory create personal`."
            ),
            1 => Ok((ready[0].id.clone(), ready[0].name.clone())),
            _ => {
                let names: Vec<_> = ready.iter().map(|b| b.name.as_str()).collect();
                bail!(
                    "Multiple memory bases found: {}. \
                     Pick one with `dbay agent bind <agent> --base <name>` \
                     or set DBAY_BASE.",
                    names.join(", ")
                );
            }
        }
    }

    fn list_bases(&self) -> Result<Vec<BaseInfo>> {
        let resp = self
            .http
            .get(format!("{}/memory/bases", self.base_url))
            .bearer_auth(&self.api_key)
            .send()
            .context("list bases http")?;
        if !resp.status().is_success() {
            let s = resp.status();
            let t = resp.text().unwrap_or_default();
            bail!("list bases failed: {s} {t}");
        }
        // API may return {bases: [...]} or plain array; try both.
        let v: serde_json::Value = resp.json()?;
        let arr = if v.is_array() { v } else { v.get("bases").cloned().unwrap_or(serde_json::json!([])) };
        Ok(serde_json::from_value(arr).unwrap_or_default())
    }

    pub fn base_name(&self) -> &str { &self.base_name }
    pub fn base_id(&self) -> &str { &self.base_id }

    fn url(&self, path: &str) -> String {
        format!("{}/memory/bases/{}{}", self.base_url, self.base_id, path)
    }

    fn agentfs_url(&self, tail: &str) -> String {
        format!("{}/agentfs{}", self.base_url, tail)
    }

    /// AgentFS PUT (path in body, base64-encoded data).
    pub fn agentfs_put(
        &self,
        path: &str,
        data: &[u8],
        properties: Option<&serde_json::Value>,
    ) -> Result<()> {
        use base64::Engine;
        let body = serde_json::json!({
            "path": path,
            "data_base64": base64::engine::general_purpose::STANDARD.encode(data),
            "properties": properties.cloned().unwrap_or(serde_json::Value::Null),
        });
        let resp = self
            .http
            .post(self.agentfs_url("/files/put"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("agentfs put http")?;
        if !resp.status().is_success() {
            let s = resp.status();
            let t = resp.text().unwrap_or_default();
            bail!("agentfs put failed: {s} {t}");
        }
        Ok(())
    }

    pub fn agentfs_append(&self, path: &str, data: &[u8]) -> Result<()> {
        use base64::Engine;
        let body = serde_json::json!({
            "path": path,
            "data_base64": base64::engine::general_purpose::STANDARD.encode(data),
        });
        let resp = self
            .http
            .post(self.agentfs_url("/files/append"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("agentfs append http")?;
        if !resp.status().is_success() {
            let s = resp.status();
            let t = resp.text().unwrap_or_default();
            bail!("agentfs append failed: {s} {t}");
        }
        Ok(())
    }

    pub fn agentfs_delete(&self, path: &str) -> Result<()> {
        let body = serde_json::json!({ "path": path });
        let resp = self
            .http
            .post(self.agentfs_url("/files/delete"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("agentfs delete http")?;
        if !resp.status().is_success() {
            let s = resp.status();
            let t = resp.text().unwrap_or_default();
            bail!("agentfs delete failed: {s} {t}");
        }
        Ok(())
    }

    pub fn agentfs_rename(&self, from: &str, to: &str) -> Result<()> {
        let body = serde_json::json!({ "from": from, "to": to });
        let resp = self
            .http
            .post(self.agentfs_url("/rename"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("agentfs rename http")?;
        if !resp.status().is_success() {
            let s = resp.status();
            let t = resp.text().unwrap_or_default();
            bail!("agentfs rename failed: {s} {t}");
        }
        Ok(())
    }

    pub fn agentfs_mkdir(&self, path: &str) -> Result<()> {
        let body = serde_json::json!({ "path": path });
        let resp = self
            .http
            .post(self.agentfs_url("/mkdir"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("agentfs mkdir http")?;
        if !resp.status().is_success() {
            let s = resp.status();
            let t = resp.text().unwrap_or_default();
            bail!("agentfs mkdir failed: {s} {t}");
        }
        Ok(())
    }

    /// POST /agentfs/batch — atomic multi-op transaction.
    pub fn agentfs_batch(&self, ops: Vec<serde_json::Value>) -> Result<()> {
        let body = serde_json::json!({ "ops": ops });
        let resp = self
            .http
            .post(self.agentfs_url("/batch"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("agentfs batch http")?;
        if !resp.status().is_success() {
            let s = resp.status();
            let t = resp.text().unwrap_or_default();
            bail!("agentfs batch failed: {s} {t}");
        }
        Ok(())
    }

    pub fn ingest(
        &self,
        content: &str,
        memory_type: &str,
        metadata: serde_json::Value,
    ) -> Result<IngestResp> {
        let body = IngestReq {
            content,
            role: "user",
            memory_type,
            importance: 0.5,
            metadata,
        };
        let resp = self
            .http
            .post(self.url("/ingest"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("ingest http send")?;
        let status = resp.status();
        if !status.is_success() {
            let text = resp.text().unwrap_or_default();
            bail!("ingest failed: {status} {text}");
        }
        Ok(resp.json().context("ingest decode")?)
    }

    // ───────── AgentFS strict / read-side methods (used by inmem backend) ─────────

    /// GET /agentfs/files — returns (bytes, etag, mtime_ns).
    pub fn agentfs_get(&self, path: &str) -> Result<(Vec<u8>, String, u64)> {
        use base64::Engine;
        let p_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(path.as_bytes());
        let resp = self.http
            .get(self.agentfs_url("/files"))
            .query(&[("path", &p_b64)])
            .bearer_auth(&self.api_key)
            .send()
            .context("agentfs get http")?;
        if !resp.status().is_success() {
            bail!("agentfs get failed: {} {}", resp.status(), resp.text().unwrap_or_default());
        }
        let etag = resp.headers().get("ETag")
            .and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
        let mtime_ns = resp.headers().get("X-AgentFS-Mtime")
            .and_then(|v| v.to_str().ok()).and_then(|s| s.parse::<u64>().ok()).unwrap_or(0);
        let bytes = resp.bytes().context("agentfs get body")?.to_vec();
        Ok((bytes, etag, mtime_ns))
    }

    /// GET /agentfs/files/head — metadata only. None on 404.
    pub fn agentfs_head(&self, path: &str) -> Result<Option<AgentFSEntry>> {
        use base64::Engine;
        let p_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(path.as_bytes());
        let resp = self.http
            .get(self.agentfs_url("/files/head"))
            .query(&[("path", &p_b64)])
            .bearer_auth(&self.api_key)
            .send()
            .context("agentfs head http")?;
        let status = resp.status();
        if status.as_u16() == 404 {
            return Ok(None);
        }
        let text = resp.text().unwrap_or_default();
        if !status.is_success() {
            // 404 may surface as a 400 with NotFoundException → string match.
            if text.contains("not_found") || text.contains("NotFound") {
                return Ok(None);
            }
            bail!("agentfs head failed: {status} {text}");
        }
        let v: serde_json::Value = serde_json::from_str(&text).context("head decode")?;
        Ok(Some(AgentFSEntry::from_json(&v)))
    }

    /// GET /agentfs/list — returns directory entries.
    pub fn agentfs_list(&self, prefix: &str, recursive: bool) -> Result<Vec<AgentFSEntry>> {
        use base64::Engine;
        let p_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(prefix.as_bytes());
        let resp = self.http
            .get(self.agentfs_url("/list"))
            .query(&[("prefix", p_b64.as_str()), ("recursive", if recursive {"true"} else {"false"})])
            .bearer_auth(&self.api_key)
            .send()
            .context("agentfs list http")?;
        let status = resp.status();
        let text = resp.text().unwrap_or_default();
        if !status.is_success() {
            bail!("agentfs list failed: {status} {text}");
        }
        let v: serde_json::Value = serde_json::from_str(&text).context("list decode")?;
        let arr = v.get("entries").cloned().unwrap_or(serde_json::json!([]));
        Ok(arr.as_array().map(|a| a.iter().map(AgentFSEntry::from_json).collect()).unwrap_or_default())
    }

    /// POST /agentfs/files/put with optional If-Match / If-None-Match. Returns
    /// the new etag on success. PreconditionFailed surfaces as a typed variant
    /// so the caller can distinguish concurrent modification from real errors.
    pub fn agentfs_put_strict(
        &self,
        path: &str,
        data: &[u8],
        if_match: Option<&str>,
        if_none_match: Option<&str>,
    ) -> std::result::Result<String, AgentFSPutError> {
        use base64::Engine;
        let mut body = serde_json::json!({
            "path": path,
            "data_base64": base64::engine::general_purpose::STANDARD.encode(data),
        });
        if let Some(m) = if_match { body["if_match"] = serde_json::json!(m); }
        if let Some(m) = if_none_match { body["if_none_match"] = serde_json::json!(m); }
        let resp = self.http
            .post(self.agentfs_url("/files/put"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .map_err(|e| AgentFSPutError::Other(anyhow::anyhow!("put http: {e}")))?;
        let status = resp.status();
        let text = resp.text().unwrap_or_default();
        if status.is_success() {
            let v: serde_json::Value = serde_json::from_str(&text)
                .map_err(|e| AgentFSPutError::Other(anyhow::anyhow!("put decode: {e}")))?;
            let etag = v.get("etag").and_then(|x| x.as_str()).unwrap_or_default().to_string();
            return Ok(etag);
        }
        if text.contains("precondition_failed") {
            return Err(AgentFSPutError::PreconditionFailed);
        }
        Err(AgentFSPutError::Other(anyhow::anyhow!("agentfs put failed: {status} {text}")))
    }

    /// POST /agentfs/files/delete with optional If-Match.
    pub fn agentfs_delete_strict(&self, path: &str, if_match: Option<&str>) -> Result<()> {
        let mut body = serde_json::json!({ "path": path });
        if let Some(m) = if_match { body["if_match"] = serde_json::json!(m); }
        let resp = self.http
            .post(self.agentfs_url("/files/delete"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("agentfs delete http")?;
        if !resp.status().is_success() {
            bail!("agentfs delete failed: {} {}", resp.status(), resp.text().unwrap_or_default());
        }
        Ok(())
    }

    pub fn recall(&self, query: &str, top_k: u32) -> Result<RecallResp> {
        let body = RecallReq { query, top_k, memory_types: None };
        let resp = self
            .http
            .post(self.url("/recall"))
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .context("recall http send")?;
        let status = resp.status();
        if !status.is_success() {
            let text = resp.text().unwrap_or_default();
            bail!("recall failed: {status} {text}");
        }
        Ok(resp.json().context("recall decode")?)
    }
}
