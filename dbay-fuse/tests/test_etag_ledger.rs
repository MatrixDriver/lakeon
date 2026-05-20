use dbay_fuse::etag_ledger::Ledger;
use tempfile::TempDir;

#[test]
fn open_creates_empty_ledger() {
    let tmp = TempDir::new().unwrap();
    let l = Ledger::open(tmp.path().join("etags.db")).unwrap();
    assert!(l.get("/x").unwrap().is_none());
}

#[test]
fn upsert_then_get_roundtrips() {
    let tmp = TempDir::new().unwrap();
    let l = Ledger::open(tmp.path().join("etags.db")).unwrap();
    l.upsert("/a.md", "deadbeef", 12).unwrap();
    let e = l.get("/a.md").unwrap().unwrap();
    assert_eq!(e.etag, "deadbeef");
    assert_eq!(e.size, 12);
}

#[test]
fn upsert_overwrites_previous() {
    let tmp = TempDir::new().unwrap();
    let l = Ledger::open(tmp.path().join("etags.db")).unwrap();
    l.upsert("/a.md", "v1", 1).unwrap();
    l.upsert("/a.md", "v2", 9).unwrap();
    let e = l.get("/a.md").unwrap().unwrap();
    assert_eq!(e.etag, "v2");
    assert_eq!(e.size, 9);
}

#[test]
fn forget_removes_entry() {
    let tmp = TempDir::new().unwrap();
    let l = Ledger::open(tmp.path().join("etags.db")).unwrap();
    l.upsert("/a.md", "v1", 1).unwrap();
    l.forget("/a.md").unwrap();
    assert!(l.get("/a.md").unwrap().is_none());
}

#[test]
fn handles_special_chars_in_path() {
    let tmp = TempDir::new().unwrap();
    let l = Ledger::open(tmp.path().join("etags.db")).unwrap();
    l.upsert("/dir with spaces/é日本.md", "x", 1).unwrap();
    assert!(l.get("/dir with spaces/é日本.md").unwrap().is_some());
}

#[test]
fn opens_existing_db_preserves_entries() {
    let tmp = TempDir::new().unwrap();
    let path = tmp.path().join("etags.db");
    {
        let l = Ledger::open(&path).unwrap();
        l.upsert("/x", "e1", 1).unwrap();
    }
    let l = Ledger::open(&path).unwrap();
    assert_eq!(l.get("/x").unwrap().unwrap().etag, "e1");
}

#[test]
fn corrupt_db_renamed_and_recreated() {
    let tmp = TempDir::new().unwrap();
    let path = tmp.path().join("etags.db");
    std::fs::write(&path, b"this is not sqlite").unwrap();
    let l = Ledger::open(&path).unwrap();
    assert!(l.get("/x").unwrap().is_none());
    let entries: Vec<_> = std::fs::read_dir(tmp.path()).unwrap()
        .filter_map(|e| e.ok())
        .map(|e| e.file_name().into_string().unwrap())
        .collect();
    assert!(entries.iter().any(|n| n.starts_with("etags.db.broken-")),
            "expected a broken backup, got {entries:?}");
}
