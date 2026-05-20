use dbay_fuse::pull::{plan_pull_action, LocalState, PullAction, RemoteEntry, LARGE_FILE_THRESHOLD};

fn entry(path: &str, etag: &str, size: u64) -> RemoteEntry {
    RemoteEntry { path: path.into(), etag: etag.into(), size, kind: "file".into() }
}

#[test]
fn remote_exists_local_missing_download() {
    let local = LocalState { exists: false, ledger_etag: None };
    assert_eq!(plan_pull_action(&entry("/a.md", "v1", 10), &local, false), PullAction::Download);
}

#[test]
fn remote_and_local_match_skip() {
    let local = LocalState { exists: true, ledger_etag: Some("v1".into()) };
    assert_eq!(plan_pull_action(&entry("/a.md", "v1", 10), &local, false), PullAction::Skip);
}

#[test]
fn remote_changed_local_unchanged_conflict() {
    let local = LocalState { exists: true, ledger_etag: Some("v1".into()) };
    assert_eq!(plan_pull_action(&entry("/a.md", "v2", 10), &local, false), PullAction::Conflict);
}

#[test]
fn local_exists_no_ledger_treated_as_new_local_skip_pull() {
    let local = LocalState { exists: true, ledger_etag: None };
    assert_eq!(plan_pull_action(&entry("/a.md", "v1", 10), &local, false), PullAction::Skip);
}

#[test]
fn large_file_default_skip() {
    let local = LocalState { exists: false, ledger_etag: None };
    let big = RemoteEntry {
        path: "/big.bin".into(), etag: "v1".into(),
        size: LARGE_FILE_THRESHOLD + 1, kind: "file".into(),
    };
    assert_eq!(plan_pull_action(&big, &local, false), PullAction::SkipLarge);
}

#[test]
fn large_file_with_include_large_downloads() {
    let local = LocalState { exists: false, ledger_etag: None };
    let big = RemoteEntry {
        path: "/big.bin".into(), etag: "v1".into(),
        size: LARGE_FILE_THRESHOLD + 1, kind: "file".into(),
    };
    assert_eq!(plan_pull_action(&big, &local, true), PullAction::Download);
}

#[test]
fn directory_entries_skipped() {
    let local = LocalState { exists: false, ledger_etag: None };
    let d = RemoteEntry {
        path: "/dir".into(), etag: "d".into(), size: 0, kind: "dir".into(),
    };
    assert_eq!(plan_pull_action(&d, &local, false), PullAction::Skip);
}
