use dbay_fuse::profile::{DirectoryKind, ProcessingProfile, StoragePolicy, FolderProfile};
use dbay_fuse::outbox::Outbox;
use dbay_fuse::sync::{build_sync_plan, default_sync_paths, enqueue_sync_plan};

#[test]
fn sync_plan_uses_source_directory_as_state_root() {
    let source = tempfile::tempdir().unwrap();
    std::fs::write(source.path().join("notes.md"), b"hello").unwrap();
    let profile = FolderProfile::new("notes", DirectoryKind::Files, None, None);

    let plan = build_sync_plan(source.path(), "/notes", profile).unwrap();

    assert_eq!(plan.state_root, source.path().canonicalize().unwrap());
    assert_eq!(plan.remote_prefix, "/notes");
    assert_eq!(plan.entries.len(), 1);
    assert_eq!(plan.entries[0].remote_path, "/notes/notes.md");
}

#[test]
fn sync_paths_store_only_metadata_under_dbay_sync() {
    let home = tempfile::tempdir().unwrap();

    let paths = default_sync_paths(home.path(), "reports");

    assert_eq!(paths.root, home.path().join(".dbay").join("sync").join("reports"));
    assert_eq!(paths.outbox, paths.root.join("outbox"));
    assert_eq!(paths.ledger, paths.root.join("etags.db"));
    assert_eq!(paths.tmp, paths.root.join("tmp"));
}

#[test]
fn sync_plan_keeps_profile_axes_separate() {
    let source = tempfile::tempdir().unwrap();
    std::fs::write(source.path().join("a.md"), b"a").unwrap();
    let profile = FolderProfile::new(
        "notes",
        DirectoryKind::Files,
        Some(StoragePolicy::InlineOnly),
        Some(ProcessingProfile::SmallFileMemory),
    );

    let plan = build_sync_plan(source.path(), "/", profile).unwrap();

    assert_eq!(plan.profile.directory_kind, DirectoryKind::Files);
    assert_eq!(plan.profile.storage_policy, StoragePolicy::InlineOnly);
    assert_eq!(plan.profile.processing_profile, ProcessingProfile::SmallFileMemory);
}

#[test]
fn sync_enqueue_attaches_profile_properties_to_dirs_and_files() {
    let source = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(source.path().join("nested")).unwrap();
    std::fs::write(source.path().join("nested").join("events.csv"), b"id,name\n1,alpha\n")
        .unwrap();
    let outbox_dir = tempfile::tempdir().unwrap();
    let outbox = Outbox::open(outbox_dir.path()).unwrap();
    let profile = FolderProfile::new("reports", DirectoryKind::DataDir, None, None);
    let plan = build_sync_plan(source.path(), "/datasets/reports", profile).unwrap();

    let count = enqueue_sync_plan(&plan, &outbox).unwrap();

    assert_eq!(count, 2);
    let pending = outbox.pending();
    assert_eq!(pending.len(), 2);
    for entry in pending {
        let properties = match entry.op {
            dbay_fuse::outbox::Op::Mkdir { properties, .. } => properties,
            dbay_fuse::outbox::Op::Put { properties, .. } => properties,
            other => panic!("unexpected op: {:?}", other),
        }
        .expect("profile properties");
        let profile = &properties["agentfs_profile"];
        assert_eq!(profile["folder"], "reports");
        assert_eq!(profile["directory_kind"], "data-dir");
        assert_eq!(profile["storage_policy"], "object-first");
        assert_eq!(profile["processing_profile"], "dataset");
    }
}
