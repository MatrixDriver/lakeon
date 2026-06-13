# AgentFS General Folders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move AgentFS from hard-coded agent directory takeover toward general user-declared folder profiles for mount, sync, import, and inspect.

**Architecture:** The client separates directory semantics (`directory_kind`), byte placement (`storage_policy`), and cloud processing (`processing_profile`). `mount` remains a FUSE-backed folder view with a local state/cache, while `sync` treats the user's existing directory as the only full local copy and records only ledger/outbox metadata under `~/.dbay`.

**Tech Stack:** Rust 2021, `clap`, existing `dbay-fuse` outbox/pull/state scan modules, Spring AgentFS API for folder registry and file operations.

---

## Target Server Model

The product concept is `folder`, not `workspace`. A folder is a user-added local directory plus a profile. It should not map 1:1 to a Neon database.

Default mapping:

```text
tenant/account
  -> default AgentFS storage pool
     -> one shared Neon/Postgres database and compute path by default
        -> agentfs_folders
        -> agentfs_devices
        -> agentfs_files
        -> agentfs_objects
        -> agentfs_datasets
        -> agentfs_dataset_versions
```

Logical records:

```text
agentfs_folders:
  folder_id, tenant_id, display_name, directory_kind,
  storage_policy, processing_profile, status, created_at

agentfs_devices:
  device_id, folder_id, local_path, hostname, last_seen_at,
  sync_cursor, status

agentfs_files:
  folder_id, path, kind, size, mtime_ns, etag,
  storage_backend, inline_data, object_id, properties

agentfs_objects:
  object_id, folder_id, object_key, content_sha256,
  size, storage_class, created_at

agentfs_datasets:
  dataset_id, folder_id, format, current_ref,
  metadata_location, storage_root, processing_status

agentfs_dataset_versions:
  dataset_id, dbay_version, iceberg_snapshot_id,
  lance_version, metadata_location, created_at
```

Cost rule: all folders for a tenant use the default storage pool unless the user explicitly buys or requests isolation. Only enterprise isolation, high-QPS analytics, regional compliance, or heavy Data Agent workloads should create another storage pool / Neon DB.

Multi-device rule: devices join the same `folder_id`; conflicts are detected by `tenant_id + folder_id + path + etag`, not by local path.

Current backend status:

- [x] `agentfs_folders` exists in the control-plane metadata DB.
- [x] `/api/v1/agentfs/folders` exposes create/list/get/update profile APIs.
- [x] The legacy AgentFS memory forwarder is gated to `agent-home` and `small-file-memory` profiles.
- [ ] `agentfs_devices`, `agentfs_objects`, `agentfs_datasets`, and `agentfs_dataset_versions` are still pending.
- [ ] OBS object tier, Iceberg/Lance catalog mapping, and worker status APIs are still pending.

### Task 1: Profile Model

**Files:**
- Create: `dbay-fuse/src/profile.rs`
- Modify: `dbay-fuse/src/lib.rs`
- Test: `dbay-fuse/tests/test_profile.rs`

- [ ] Add `DirectoryKind`, `StoragePolicy`, and `ProcessingProfile` enums.
- [ ] Add `FolderProfile::new(folder, kind, storage_override, processing_override)`.
- [ ] Default `codex-home`, `claude-home`, and `openclaw-home` to `storage=auto`, `processing=agent-home`.
- [ ] Default `iceberg-table` and `lance-table` to `storage=table-native`, `processing=iceberg|lance`.
- [ ] Default `data-dir` to `storage=object-first`, `processing=dataset`.
- [ ] Default `files` to `storage=auto`, `processing=none`.
- [ ] Add tests that prove `small-files-inline` is represented as `kind=files, storage=inline-only`.

### Task 2: Remove Takeover

**Files:**
- Modify: `dbay-fuse/src/main.rs`
- Delete: `dbay-fuse/src/takeover.rs`
- Delete: `dbay-fuse/scripts/takeover-cc.sh`
- Delete: `dbay-fuse/scripts/rollback-cc.sh`
- Modify: `dbay-fuse/README.md`
- Modify: `docs/agentfs-user-guide.md`

- [ ] Remove `takeover` and `release` CLI variants.
- [ ] Remove `mod takeover`.
- [ ] Remove docs that instruct users to replace real agent directories with symlinks.
- [ ] Keep existing `pull`, `mount`, `outbox-status`, and memory-base binding behavior working.

### Task 3: Folder-Oriented CLI

**Files:**
- Modify: `dbay-fuse/src/main.rs`
- Modify: `dbay-fuse/src/config.rs`
- Test: `dbay-fuse/tests/test_profile.rs`

- [ ] Add `--folder` as the primary identity argument.
- [ ] Keep `--agent` as a deprecated alias for existing scripts and E2E tests.
- [ ] Derive default paths from folder: `~/.dbay/mnt/<folder>`, `~/.dbay/state/<folder>`, `~/.dbay/outbox/<folder>`, `~/.dbay/sync-ledger/<folder>/etags.db`.
- [ ] Print profile details in `whoami`.

### Task 4: Sync and Import Skeleton

**Files:**
- Create: `dbay-fuse/src/sync.rs`
- Modify: `dbay-fuse/src/lib.rs`
- Modify: `dbay-fuse/src/main.rs`
- Test: `dbay-fuse/tests/test_sync.rs`

- [ ] Implement `sync plan` behavior as a pure function over a local directory scan.
- [ ] Add `sync <local_dir> --folder <name> --kind <kind> [--dry-run]`.
- [ ] In `sync`, use the local directory as the state root; do not copy it into `~/.dbay/state`.
- [ ] Enqueue scan results into `~/.dbay/sync/<folder>/outbox`.
- [ ] Add `import <local_dir>` as a one-shot alias over the same scanner with a separate output message.

### Task 5: Inspect Advisor

**Files:**
- Modify: `dbay-fuse/src/profile.rs`
- Modify: `dbay-fuse/src/main.rs`
- Test: `dbay-fuse/tests/test_profile.rs`

- [ ] Add `inspect <local_dir>` that returns recommended `directory_kind`, confidence, and reasons.
- [ ] Detect Iceberg by `metadata/*.metadata.json` plus parquet/avro hints.
- [ ] Detect Lance by `_versions`, `_fragments`, `_indices`, or `.lance` path hints.
- [ ] Detect agent homes by known files/directories without making that a storage decision.

### Task 6: Verification

**Commands:**
- `cd dbay-fuse && cargo test`
- `cd dbay-fuse && cargo check`

- [ ] Run tests.
- [ ] Fix failures without touching unrelated dirty files.
- [ ] Report exact verification output and remaining gaps.
