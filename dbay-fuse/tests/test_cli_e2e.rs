use std::process::Command;

fn bin() -> &'static str {
    env!("CARGO_BIN_EXE_dbay-fuse")
}

#[test]
fn help_exposes_folder_commands_without_takeover_or_workspace() {
    let output = Command::new(bin()).arg("--help").output().unwrap();

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("sync"));
    assert!(stdout.contains("inspect"));
    assert!(stdout.contains("outbox-drain"));
    assert!(!stdout.contains("takeover"));
    assert!(!stdout.contains("workspace"));
}

#[test]
fn sync_help_exposes_watch_mode() {
    let output = Command::new(bin()).arg("sync").arg("--help").output().unwrap();

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("--watch"));
}

#[test]
fn inspect_cli_recommends_iceberg_table_for_iceberg_layout() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(dir.path().join("metadata")).unwrap();
    std::fs::write(
        dir.path().join("metadata").join("v1.metadata.json"),
        br#"{"format-version":2}"#,
    )
    .unwrap();

    let output = Command::new(bin())
        .arg("inspect")
        .arg(dir.path())
        .output()
        .unwrap();

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("recommended_kind: iceberg-table"));
    assert!(stdout.contains("reason: found Iceberg metadata layout"));
}

#[test]
fn inspect_cli_recommends_data_dir_for_tabular_files() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("events.csv"), b"id,name\n1,alpha\n").unwrap();
    std::fs::write(dir.path().join("report.xlsx"), b"placeholder").unwrap();

    let output = Command::new(bin())
        .arg("inspect")
        .arg(dir.path())
        .output()
        .unwrap();

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("recommended_kind: data-dir"));
    assert!(stdout.contains("reason: found tabular data files"));
}

#[test]
fn inspect_cli_recommends_lance_table_for_lance_layout() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(dir.path().join("_versions")).unwrap();
    std::fs::create_dir_all(dir.path().join("_fragments")).unwrap();

    let output = Command::new(bin())
        .arg("inspect")
        .arg(dir.path())
        .output()
        .unwrap();

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("recommended_kind: lance-table"));
    assert!(stdout.contains("reason: found Lance table layout"));
}

#[test]
fn inspect_cli_recommends_agent_home_kinds_for_known_agent_layouts() {
    let codex_dir = tempfile::tempdir().unwrap();
    std::fs::write(codex_dir.path().join("AGENTS.md"), b"# rules\n").unwrap();
    let codex = Command::new(bin())
        .arg("inspect")
        .arg(codex_dir.path())
        .output()
        .unwrap();
    assert!(codex.status.success());
    let codex_stdout = String::from_utf8(codex.stdout).unwrap();
    assert!(codex_stdout.contains("recommended_kind: codex-home"));

    let claude_dir = tempfile::tempdir().unwrap();
    std::fs::write(claude_dir.path().join("CLAUDE.md"), b"# rules\n").unwrap();
    let claude = Command::new(bin())
        .arg("inspect")
        .arg(claude_dir.path())
        .output()
        .unwrap();
    assert!(claude.status.success());
    let claude_stdout = String::from_utf8(claude.stdout).unwrap();
    assert!(claude_stdout.contains("recommended_kind: claude-home"));
}

#[test]
fn sync_dry_run_infers_folder_name_and_does_not_copy_source_to_state() {
    let source = tempfile::tempdir_in("/tmp").unwrap();
    let source_path = source.path().join("Events.Data");
    std::fs::create_dir_all(&source_path).unwrap();
    std::fs::write(source_path.join("a.md"), b"hello").unwrap();

    let home = tempfile::tempdir().unwrap();
    let output = Command::new(bin())
        .env("HOME", home.path())
        .arg("sync")
        .arg(&source_path)
        .arg("--kind")
        .arg("files")
        .arg("--storage")
        .arg("inline-only")
        .arg("--processing")
        .arg("small-file-memory")
        .arg("--remote")
        .arg("/notes")
        .arg("--dry-run")
        .output()
        .unwrap();

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("folder: events-data"));
    assert!(stdout.contains("storage:   inline-only"));
    assert!(stdout.contains("processing: small-file-memory"));
    assert!(stdout.contains("entries:   1"));
    assert!(!home.path().join(".dbay").join("state").join("events-data").exists());
}

#[test]
fn import_dry_run_uses_sync_planner_without_writing_metadata() {
    let source = tempfile::tempdir_in("/tmp").unwrap();
    let source_path = source.path().join("Raw Reports");
    std::fs::create_dir_all(&source_path).unwrap();
    std::fs::write(source_path.join("events.csv"), b"id,name\n1,alpha\n").unwrap();

    let home = tempfile::tempdir().unwrap();
    let output = Command::new(bin())
        .env("HOME", home.path())
        .arg("import")
        .arg(&source_path)
        .arg("--kind")
        .arg("data-dir")
        .arg("--remote")
        .arg("/datasets/raw")
        .arg("--dry-run")
        .output()
        .unwrap();

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("import plan:"));
    assert!(stdout.contains("folder: raw-reports"));
    assert!(stdout.contains("remote:    /datasets/raw"));
    assert!(stdout.contains("kind:      data-dir"));
    assert!(stdout.contains("storage:   object-first"));
    assert!(stdout.contains("processing: dataset"));
    assert!(!home.path().join(".dbay").join("sync").join("raw-reports").exists());
    assert!(!home.path().join(".dbay").join("state").join("raw-reports").exists());
}

#[test]
fn sync_non_dry_run_enqueues_directory_and_file_ops_without_copying_source() {
    let source = tempfile::tempdir_in("/tmp").unwrap();
    let source_path = source.path().join("Project Notes");
    std::fs::create_dir_all(source_path.join("nested")).unwrap();
    std::fs::write(source_path.join("nested").join("a.md"), b"hello").unwrap();

    let home = tempfile::tempdir().unwrap();
    let output = Command::new(bin())
        .env("HOME", home.path())
        .arg("sync")
        .arg(&source_path)
        .arg("--kind")
        .arg("files")
        .arg("--remote")
        .arg("/notes")
        .output()
        .unwrap();

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("folder: project-notes"));
    assert!(stdout.contains("registration: skipped"));
    assert!(stdout.contains("queued:    2"));

    let sync_root = home.path().join(".dbay").join("sync").join("project-notes");
    let log = std::fs::read_to_string(sync_root.join("outbox").join("pending.log")).unwrap();
    assert!(log.contains(r#""op":"mkdir""#));
    assert!(log.contains(r#""path":"/notes/nested""#));
    assert!(log.contains(r#""op":"put""#));
    assert!(log.contains(r#""path":"/notes/nested/a.md""#));
    assert!(log.contains(r#""lbfs_profile""#));
    assert!(log.contains(r#""directory_kind":"files""#));
    assert!(log.contains(r#""storage_policy":"auto""#));
    assert!(log.contains(r#""processing_profile":"none""#));
    assert!(sync_root.join("outbox").join("blobs").exists());
    assert!(!home.path().join(".dbay").join("state").join("project-notes").exists());
}
