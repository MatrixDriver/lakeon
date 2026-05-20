use dbay_fuse::conflict::{
    append_conflict_log, conflict_sidecar_path, local_filename_timestamp,
};
use std::path::Path;
use tempfile::TempDir;

#[test]
fn timestamp_matches_iso_filename_shape() {
    let ts = local_filename_timestamp();
    // Expect YYYY-MM-DDTHH-MM-SS (5+2+2+T+2+2+2 with 4 dashes + 1 T = 19 chars)
    assert_eq!(ts.len(), 19, "got {ts:?}");
    assert!(ts.chars().nth(4) == Some('-'), "got {ts:?}");
    assert!(ts.chars().nth(7) == Some('-'), "got {ts:?}");
    assert!(ts.chars().nth(10) == Some('T'), "got {ts:?}");
    assert!(ts.chars().nth(13) == Some('-'), "got {ts:?}");
    assert!(ts.chars().nth(16) == Some('-'), "got {ts:?}");
    // All other positions are digits
    for (i, c) in ts.chars().enumerate() {
        if [4, 7, 10, 13, 16].contains(&i) { continue; }
        assert!(c.is_ascii_digit(), "char at {i} isn't a digit: {c:?} in {ts:?}");
    }
}

#[test]
fn sidecar_path_strips_leading_slash_and_appends_suffix() {
    let root = Path::new("/tmp/conflicts-root");
    let p = conflict_sidecar_path(root, "/projects/x/y.md", "host1", "2026-05-20T10-00-00");
    assert_eq!(p, Path::new("/tmp/conflicts-root/projects/x/y.md.conflict-from-host1-2026-05-20T10-00-00"));
}

#[test]
fn sidecar_path_handles_root_level_files() {
    let root = Path::new("/tmp/conflicts-root");
    let p = conflict_sidecar_path(root, "/CLAUDE.md", "h", "ts");
    assert_eq!(p, Path::new("/tmp/conflicts-root/CLAUDE.md.conflict-from-h-ts"));
}

#[test]
fn sidecar_path_handles_unicode_paths() {
    let root = Path::new("/tmp/conflicts-root");
    let p = conflict_sidecar_path(root, "/é日本/file.txt", "h", "ts");
    assert_eq!(p, Path::new("/tmp/conflicts-root/é日本/file.txt.conflict-from-h-ts"));
}

#[test]
fn append_log_creates_directory_and_file() {
    let tmp = TempDir::new().unwrap();
    let root = tmp.path().join("conflicts");
    let saved_to = root.join("x.md.conflict-from-h-ts");
    append_conflict_log(&root, "/x.md", "etag1", "host1", "2026-05-20T10-00-00", &saved_to);
    let log_file = root.join("conflicts.log");
    assert!(log_file.exists(), "log file not created at {}", log_file.display());
    let content = std::fs::read_to_string(&log_file).unwrap();
    let parsed: serde_json::Value = serde_json::from_str(content.trim()).unwrap();
    assert_eq!(parsed["path"], "/x.md");
    assert_eq!(parsed["remote_etag"], "etag1");
    assert_eq!(parsed["hostname"], "host1");
    assert_eq!(parsed["ts"], "2026-05-20T10-00-00");
    assert!(parsed["saved_to"].as_str().unwrap().ends_with("x.md.conflict-from-h-ts"));
}

#[test]
fn append_log_appends_multiple_lines() {
    let tmp = TempDir::new().unwrap();
    let root = tmp.path().join("conflicts");
    let saved_to = root.join("dummy");
    append_conflict_log(&root, "/a", "e1", "h", "ts1", &saved_to);
    append_conflict_log(&root, "/b", "e2", "h", "ts2", &saved_to);
    let log_file = root.join("conflicts.log");
    let content = std::fs::read_to_string(&log_file).unwrap();
    let lines: Vec<&str> = content.trim().split('\n').collect();
    assert_eq!(lines.len(), 2, "got: {content:?}");
    let l0: serde_json::Value = serde_json::from_str(lines[0]).unwrap();
    let l1: serde_json::Value = serde_json::from_str(lines[1]).unwrap();
    assert_eq!(l0["path"], "/a");
    assert_eq!(l1["path"], "/b");
}
