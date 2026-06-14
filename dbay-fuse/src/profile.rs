use anyhow::Result;
use clap::ValueEnum;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum DirectoryKind {
    CodexHome,
    ClaudeHome,
    OpenclawHome,
    OpencodeHome,
    IcebergTable,
    LanceTable,
    DataDir,
    Files,
}

impl std::fmt::Display for DirectoryKind {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::CodexHome => "codex-home",
            Self::ClaudeHome => "claude-home",
            Self::OpenclawHome => "openclaw-home",
            Self::OpencodeHome => "opencode-home",
            Self::IcebergTable => "iceberg-table",
            Self::LanceTable => "lance-table",
            Self::DataDir => "data-dir",
            Self::Files => "files",
        };
        f.write_str(s)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum StoragePolicy {
    Auto,
    InlineOnly,
    ObjectFirst,
    ObjectOnly,
    TableNative,
}

impl std::fmt::Display for StoragePolicy {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::Auto => "auto",
            Self::InlineOnly => "inline-only",
            Self::ObjectFirst => "object-first",
            Self::ObjectOnly => "object-only",
            Self::TableNative => "table-native",
        };
        f.write_str(s)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum ProcessingProfile {
    None,
    AgentHome,
    Dataset,
    Iceberg,
    Lance,
    SmallFileMemory,
}

impl std::fmt::Display for ProcessingProfile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::None => "none",
            Self::AgentHome => "agent-home",
            Self::Dataset => "dataset",
            Self::Iceberg => "iceberg",
            Self::Lance => "lance",
            Self::SmallFileMemory => "small-file-memory",
        };
        f.write_str(s)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FolderProfile {
    pub folder: String,
    pub directory_kind: DirectoryKind,
    pub storage_policy: StoragePolicy,
    pub processing_profile: ProcessingProfile,
}

impl FolderProfile {
    pub fn new(
        folder: impl Into<String>,
        directory_kind: DirectoryKind,
        storage_override: Option<StoragePolicy>,
        processing_override: Option<ProcessingProfile>,
    ) -> Self {
        let (default_storage, default_processing) = defaults_for(directory_kind);
        Self {
            folder: folder.into(),
            directory_kind,
            storage_policy: storage_override.unwrap_or(default_storage),
            processing_profile: processing_override.unwrap_or(default_processing),
        }
    }

    pub fn properties(&self) -> serde_json::Value {
        serde_json::json!({
            "lbfs_profile": {
                "folder": self.folder,
                "directory_kind": self.directory_kind.to_string(),
                "storage_policy": self.storage_policy.to_string(),
                "processing_profile": self.processing_profile.to_string(),
            }
        })
    }
}

pub fn defaults_for(kind: DirectoryKind) -> (StoragePolicy, ProcessingProfile) {
    match kind {
        DirectoryKind::CodexHome
        | DirectoryKind::ClaudeHome
        | DirectoryKind::OpenclawHome
        | DirectoryKind::OpencodeHome => (StoragePolicy::Auto, ProcessingProfile::AgentHome),
        DirectoryKind::IcebergTable => (StoragePolicy::TableNative, ProcessingProfile::Iceberg),
        DirectoryKind::LanceTable => (StoragePolicy::TableNative, ProcessingProfile::Lance),
        DirectoryKind::DataDir => (StoragePolicy::ObjectFirst, ProcessingProfile::Dataset),
        DirectoryKind::Files => (StoragePolicy::Auto, ProcessingProfile::None),
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ProfileRecommendation {
    pub kind: DirectoryKind,
    pub confidence: f32,
    pub reasons: Vec<String>,
}

pub fn inspect_path(path: &Path) -> Result<ProfileRecommendation> {
    let mut reasons = Vec::new();

    if looks_like_iceberg(path) {
        reasons.push("found Iceberg metadata layout".to_string());
        return Ok(ProfileRecommendation {
            kind: DirectoryKind::IcebergTable,
            confidence: 0.90,
            reasons,
        });
    }
    if looks_like_lance(path) {
        reasons.push("found Lance table layout".to_string());
        return Ok(ProfileRecommendation {
            kind: DirectoryKind::LanceTable,
            confidence: 0.90,
            reasons,
        });
    }
    if path.join(".opencode").exists()
        || path.join("opencode.json").exists()
        || path.join("packages").join("opencode").exists()
    {
        reasons.push("found opencode-style home or workspace files".to_string());
        return Ok(ProfileRecommendation {
            kind: DirectoryKind::OpencodeHome,
            confidence: 0.80,
            reasons,
        });
    }
    if path.join("AGENTS.md").exists() || path.join("skills").exists() {
        reasons.push("found Codex-style agent instructions or skills".to_string());
        return Ok(ProfileRecommendation {
            kind: DirectoryKind::CodexHome,
            confidence: 0.75,
            reasons,
        });
    }
    if path.join("CLAUDE.md").exists() || path.join("projects").exists() {
        reasons.push("found Claude Code-style files".to_string());
        return Ok(ProfileRecommendation {
            kind: DirectoryKind::ClaudeHome,
            confidence: 0.75,
            reasons,
        });
    }
    if looks_like_data_dir(path) {
        reasons.push("found tabular data files".to_string());
        return Ok(ProfileRecommendation {
            kind: DirectoryKind::DataDir,
            confidence: 0.70,
            reasons,
        });
    }

    reasons.push("no known specialized layout detected".to_string());
    Ok(ProfileRecommendation {
        kind: DirectoryKind::Files,
        confidence: 0.50,
        reasons,
    })
}

fn looks_like_iceberg(path: &Path) -> bool {
    let metadata = path.join("metadata");
    if !metadata.is_dir() {
        return false;
    }
    let Ok(entries) = std::fs::read_dir(metadata) else {
        return false;
    };
    entries.filter_map(|e| e.ok()).any(|entry| {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        name.ends_with(".metadata.json") || name.ends_with(".avro")
    })
}

fn looks_like_lance(path: &Path) -> bool {
    path.join("_versions").is_dir()
        || path.join("_fragments").is_dir()
        || path.join("_indices").is_dir()
        || path.extension().and_then(|s| s.to_str()) == Some("lance")
}

fn looks_like_data_dir(path: &Path) -> bool {
    let Ok(entries) = std::fs::read_dir(path) else {
        return false;
    };
    entries.filter_map(|e| e.ok()).any(|entry| {
        let entry_path = entry.path();
        let Some(ext) = entry_path.extension().and_then(|s| s.to_str()) else {
            return false;
        };
        matches!(
            ext.to_ascii_lowercase().as_str(),
            "csv" | "tsv" | "xlsx" | "xls" | "parquet" | "orc" | "arrow" | "jsonl" | "ndjson"
        )
    })
}
