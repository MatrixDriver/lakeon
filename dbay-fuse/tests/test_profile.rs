use dbay_fuse::profile::{
    inspect_path, DirectoryKind, ProcessingProfile, StoragePolicy, FolderProfile,
};

#[test]
fn agent_home_defaults_to_auto_storage_and_agent_processing() {
    let profile = FolderProfile::new(
        "codex",
        DirectoryKind::CodexHome,
        None,
        None,
    );

    assert_eq!(profile.folder, "codex");
    assert_eq!(profile.storage_policy, StoragePolicy::Auto);
    assert_eq!(profile.processing_profile, ProcessingProfile::AgentHome);
}

#[test]
fn opencode_home_defaults_to_agent_home_processing() {
    let profile = FolderProfile::new(
        "/agents/opencode",
        DirectoryKind::OpencodeHome,
        None,
        None,
    );

    assert_eq!(profile.folder, "/agents/opencode");
    assert_eq!(profile.directory_kind, DirectoryKind::OpencodeHome);
    assert_eq!(profile.storage_policy, StoragePolicy::Auto);
    assert_eq!(profile.processing_profile, ProcessingProfile::AgentHome);
    assert_eq!(profile.directory_kind.to_string(), "opencode-home");
}

#[test]
fn table_kinds_default_to_table_native_storage() {
    let iceberg = FolderProfile::new(
        "warehouse",
        DirectoryKind::IcebergTable,
        None,
        None,
    );
    let lance = FolderProfile::new(
        "vectors",
        DirectoryKind::LanceTable,
        None,
        None,
    );

    assert_eq!(iceberg.storage_policy, StoragePolicy::TableNative);
    assert_eq!(iceberg.processing_profile, ProcessingProfile::Iceberg);
    assert_eq!(lance.storage_policy, StoragePolicy::TableNative);
    assert_eq!(lance.processing_profile, ProcessingProfile::Lance);
}

#[test]
fn files_inline_is_storage_only_and_keeps_no_processing() {
    let profile = FolderProfile::new(
        "notes",
        DirectoryKind::Files,
        Some(StoragePolicy::InlineOnly),
        None,
    );

    assert_eq!(profile.directory_kind, DirectoryKind::Files);
    assert_eq!(profile.storage_policy, StoragePolicy::InlineOnly);
    assert_eq!(profile.processing_profile, ProcessingProfile::None);
}

#[test]
fn data_dir_defaults_to_object_first_dataset_processing() {
    let profile = FolderProfile::new("reports", DirectoryKind::DataDir, None, None);

    assert_eq!(profile.storage_policy, StoragePolicy::ObjectFirst);
    assert_eq!(profile.processing_profile, ProcessingProfile::Dataset);
}

#[test]
fn profile_serializes_to_lbfs_properties() {
    let profile = FolderProfile::new("warehouse", DirectoryKind::IcebergTable, None, None);

    let props = profile.properties();
    let lbfs = &props["lbfs_profile"];

    assert_eq!(lbfs["folder"], "warehouse");
    assert_eq!(lbfs["directory_kind"], "iceberg-table");
    assert_eq!(lbfs["storage_policy"], "table-native");
    assert_eq!(lbfs["processing_profile"], "iceberg");
}

#[test]
fn inspect_detects_iceberg_layout() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(dir.path().join("metadata")).unwrap();
    std::fs::write(
        dir.path().join("metadata").join("v1.metadata.json"),
        br#"{"format-version":2}"#,
    )
    .unwrap();

    let recommendation = inspect_path(dir.path()).unwrap();

    assert_eq!(recommendation.kind, DirectoryKind::IcebergTable);
    assert!(recommendation.confidence >= 0.80);
    assert!(recommendation.reasons.iter().any(|r| r.contains("Iceberg")));
}

#[test]
fn inspect_detects_lance_layout() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(dir.path().join("_versions")).unwrap();
    std::fs::create_dir_all(dir.path().join("_fragments")).unwrap();

    let recommendation = inspect_path(dir.path()).unwrap();

    assert_eq!(recommendation.kind, DirectoryKind::LanceTable);
    assert!(recommendation.confidence >= 0.80);
    assert!(recommendation.reasons.iter().any(|r| r.contains("Lance")));
}

#[test]
fn inspect_detects_opencode_layout() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(dir.path().join(".opencode")).unwrap();

    let recommendation = inspect_path(dir.path()).unwrap();

    assert_eq!(recommendation.kind, DirectoryKind::OpencodeHome);
    assert!(recommendation.confidence >= 0.75);
    assert!(recommendation.reasons.iter().any(|r| r.contains("opencode")));
}
