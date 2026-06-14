# LakebaseFS Data Agent Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build LakebaseFS as DBay's general mount/sync filesystem substrate, then use it as the data and home-directory layer for a console-hosted DataAgent powered by the existing opencode prototype.

**Architecture:** LakebaseFS is the control plane and ingestion plane for external directories: agent homes, arbitrary file folders, tabular datasets, Iceberg tables, and Lance tables. DBay DataAgent is an application layer that consumes DBay databases plus LakebaseFS data sources, runs opencode-based analysis jobs, and publishes reports, dashboards, tables, SQL, validation evidence, and lineage artifacts back to DBay.

**Tech Stack:** Spring Boot 3.3.5 + Java 17 for LakebaseFS/DataAgent APIs and workers, PostgreSQL tenant databases for file metadata and event queues, Rust `dbay-fuse` for mount/sync clients, Vue 3 + TypeScript + Vite for console, Python memory service for small-file derive, Bun/TypeScript opencode fork for DataAgent runtime.

---

## Repository Strategy

Do not create a long-lived copied directory of opencode. The current `/Users/jacky/code/opencode` checkout already has:

- `origin` -> `https://github.com/anomalyco/opencode.git`
- `branchable` -> `https://github.com/jackylk/opencode-branchable.git`
- active branch `feat/branchable-runtime`
- uncommitted DataAgent/workbench/runtime prototype files

Use this model:

1. Keep DBay/LakebaseFS control-plane code in `/Users/jacky/code/lakeon`.
2. Keep opencode runtime changes in the `jackylk/opencode-branchable` fork.
3. Use topic branches in the fork for DataAgent runtime work, for example `feat/data-agent-lbfs-runtime`.
4. When execution needs isolation from the dirty opencode checkout, create a git worktree from the fork branch instead of copying the repo:

```bash
git -C /Users/jacky/code/opencode worktree add \
  /Users/jacky/code/opencode-data-agent \
  -b feat/data-agent-lbfs-runtime
```

This keeps upstream mergeability, preserves git history, and avoids two unsynchronized opencode codebases. A copied repo is only acceptable for disposable experiments that will not be maintained.

## Product Boundary

LakebaseFS has two independent product roles:

- **General external service:** external clients mount or sync arbitrary directories into LakebaseFS, including Codex, Claude Code, OpenClaw, opencode home directories, local CSV/Excel folders, Iceberg tables, Lance tables, and ordinary file trees.
- **DBay DataAgent substrate:** DBay runs DataAgent pods without EVS by restoring opencode home state from LakebaseFS, then syncs analysis artifacts and source metadata back into LakebaseFS.

DataAgent has one user-facing role:

- Users connect DBay databases or LakebaseFS data folders, ask natural-language questions, and receive query results, reports, dashboards, validation evidence, and lineage.

## Current State Summary

LakebaseFS already has:

- Frontend route and API client under `lakeon-console/src/views/lbfs/` and `lakeon-console/src/api/lbfs.ts`.
- Backend public routes under `/api/v1/lbfs`.
- Rust client commands for `mount`, `sync`, `import`, `pull`, and `outbox-drain`.
- Directory profile concepts in `dbay-fuse/src/profile.rs`: `codex-home`, `claude-home`, `openclaw-home`, `iceberg-table`, `lance-table`, `data-dir`, `files`.
- A Java processing router abstraction in `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSProcessingRouter.java`.
- Memory derive forwarding for small files through `memory/service/main.py` `/lbfs/derive`.

LakebaseFS still needs:

- `opencode-home` as a first-class directory kind.
- Continuous sync daemon behavior.
- Folder registration from the client into the server registry.
- Real asynchronous workers for `dataset`, `iceberg`, `lance`, and `agent-home`.
- Job state APIs so console users can see indexing/profiling status.
- Object/table storage policy implementation beyond metadata.

opencode DataAgent already has:

- Shared Workbench model in `/Users/jacky/code/opencode/packages/opencode/src/workbench/model.ts`.
- DataAgent sample run builder in `/Users/jacky/code/opencode/packages/opencode/src/data-agent/web/model.ts`.
- Local web server in `/Users/jacky/code/opencode/packages/opencode/src/data-agent/web/server.ts`.
- CLI command `opencode data-agent web`.

opencode DataAgent still needs:

- Real runtime execution instead of sample-only planned runs.
- LakebaseFS source type and API client.
- DBay run/job API integration.
- Deterministic data tools for schema inspection, profiling, SQL execution, validation, chart generation, report generation, and dashboard generation.
- Container/pod startup contract for restoring opencode home from LakebaseFS.

## Target Data Flow

```text
External directory
  -> dbay-fuse mount or sync
  -> /api/v1/lbfs files and folder registry
  -> tenant lbfs_events
  -> LakebaseFS processing router
  -> profile-specific async worker
  -> LakebaseFS metadata, DBay data source registry, memory derive, or table metadata
  -> DataAgent source picker
  -> opencode DataAgent runtime
  -> SQL/table/report/dashboard/validation/lineage artifacts
  -> DBay console workbench and LakebaseFS artifact folder
```

## File Map

LakebaseFS client:

- Modify `dbay-fuse/src/profile.rs`: add `opencode-home`; refine profile detection for `.opencode`, `opencode.json`, and opencode session dirs.
- Modify `dbay-fuse/src/dbay_api.rs`: add folder registration and sync status calls.
- Modify `dbay-fuse/src/main.rs`: add `sync --watch`, `mount --register`, and `pull --folder-profile` behavior.
- Modify `dbay-fuse/src/sync.rs`: make one-shot sync reusable by a watch loop.
- Create `dbay-fuse/src/watch_sync.rs`: continuous filesystem watcher and debounce loop.
- Test `dbay-fuse/tests/test_profile.rs`, `dbay-fuse/tests/test_sync.rs`, `dbay-fuse/tests/test_cli_e2e.rs`.

LakebaseFS API and workers:

- Modify `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSFolderProfile.java`: add `opencode-home` and profile defaults.
- Modify `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSEventForwarder.java`: dispatch through `LakebaseFSProcessingRouter`.
- Modify `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSProcessingWorker.java`: return structured processing results.
- Create `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSAgentHomeWorker.java`.
- Create `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSDatasetWorker.java`.
- Create `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSTableWorker.java`.
- Create `lakeon-api/src/main/java/com/lakeon/lbfs/LBFSAutoJobEntity.java`.
- Create `lakeon-api/src/main/java/com/lakeon/lbfs/LBFSAutoJobRepository.java`.
- Create `lakeon-api/src/main/java/com/lakeon/lbfs/LBFSAutoJobController.java`.
- Test `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSFolderProfileTest.java`.
- Test `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSEventForwarderTest.java`.
- Test `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSProcessingWorkerTest.java`.

DBay DataAgent API:

- Create `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentRunEntity.java`.
- Create `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentArtifactEntity.java`.
- Create `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentSourceEntity.java`.
- Create `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentController.java`.
- Create `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentService.java`.
- Create `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentRuntimeClient.java`.
- Test `lakeon-api/src/test/java/com/lakeon/dataagent/DataAgentControllerTest.java`.

Console:

- Modify `lakeon-console/src/layouts/ConsoleLayout.vue`: add DataAgent menu entry using the same typography and visual pattern as Database and LakebaseFS.
- Modify `lakeon-console/src/router/index.ts`: add `/data-agent` and `/data-agent/runs/:runId`.
- Create `lakeon-console/src/api/data-agent.ts`.
- Create `lakeon-console/src/views/data-agent/DataAgentHome.vue`.
- Create `lakeon-console/src/views/data-agent/DataAgentRunDetail.vue`.
- Create `lakeon-console/src/components/data-agent/SourcePicker.vue`.
- Create `lakeon-console/src/components/data-agent/WorkbenchArtifactPanel.vue`.
- Test `lakeon-console/src/__tests__/data-agent-api.test.ts`.
- Test `lakeon-console/src/__tests__/DataAgentHome.test.ts`.

opencode fork:

- Modify `/Users/jacky/code/opencode/packages/opencode/src/data-agent/web/model.ts`: add LakebaseFS source kind and runtime run statuses.
- Create `/Users/jacky/code/opencode/packages/opencode/src/data-agent/lbfs/client.ts`.
- Create `/Users/jacky/code/opencode/packages/opencode/src/data-agent/runtime/run.ts`.
- Create `/Users/jacky/code/opencode/packages/opencode/src/data-agent/runtime/tools.ts`.
- Create `/Users/jacky/code/opencode/packages/opencode/src/data-agent/runtime/artifacts.ts`.
- Modify `/Users/jacky/code/opencode/packages/opencode/src/data-agent/web/server.ts`: route run creation to the runtime.
- Test `/Users/jacky/code/opencode/packages/opencode/test/workflow/data-agent-workbench.test.ts`.
- Test `/Users/jacky/code/opencode/packages/opencode/test/workflow/data-agent-cli.test.ts`.
- Create `/Users/jacky/code/opencode/packages/opencode/test/workflow/data-agent-runtime.test.ts`.

Deployment:

- Create `deploy/cce/data-agent/README.md`.
- Create `deploy/cce/data-agent/data-agent-pod.yaml`.
- Modify `deploy/helm/lakeon/values.yaml`: add DataAgent runtime endpoint and LakebaseFS worker flags.

---

### Task 1: Lock Repository Strategy and Preserve Current Work

**Files:**
- No source files changed in this task.

- [ ] **Step 1: Record current lakeon dirty state**

Run:

```bash
git -C /Users/jacky/code/lakeon status --short
```

Expected: output includes the ongoing LakebaseFS rename files and no command modifies them.

- [ ] **Step 2: Record current opencode fork state**

Run:

```bash
git -C /Users/jacky/code/opencode branch --show-current
git -C /Users/jacky/code/opencode remote -v
git -C /Users/jacky/code/opencode status --short
```

Expected: branch is `feat/branchable-runtime`, remotes include `origin` and `branchable`, and DataAgent prototype files are visible in status.

- [ ] **Step 3: Create an opencode worktree only when implementation starts**

Run this at execution time if isolated opencode edits are needed:

```bash
git -C /Users/jacky/code/opencode worktree add \
  /Users/jacky/code/opencode-data-agent \
  -b feat/data-agent-lbfs-runtime
```

Expected: `/Users/jacky/code/opencode-data-agent` exists and `git -C /Users/jacky/code/opencode-data-agent branch --show-current` prints `feat/data-agent-lbfs-runtime`.

- [ ] **Step 4: Commit**

No commit for this task unless documentation is updated during execution.

### Task 2: Add `opencode-home` to LakebaseFS Profiles

**Files:**
- Modify: `dbay-fuse/src/profile.rs`
- Modify: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSFolderProfile.java`
- Modify: `lakeon-console/src/api/lbfs.ts`
- Modify: `lakeon-console/src/views/lbfs/LakebaseFSBrowse.vue`
- Test: `dbay-fuse/tests/test_profile.rs`
- Test: `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSFolderProfileTest.java`
- Test: `lakeon-console/src/__tests__/lbfs-api.test.ts`

- [ ] **Step 1: Write Rust profile tests**

Add assertions to `dbay-fuse/tests/test_profile.rs`:

```rust
#[test]
fn opencode_home_defaults_to_agent_home_processing() {
    let profile = FolderProfile::new(
        "/agents/opencode",
        DirectoryKind::OpencodeHome,
        None,
        None,
    );

    assert_eq!(profile.directory_kind, DirectoryKind::OpencodeHome);
    assert_eq!(profile.storage_policy, StoragePolicy::Auto);
    assert_eq!(profile.processing_profile, ProcessingProfile::AgentHome);
    assert_eq!(profile.directory_kind.to_string(), "opencode-home");
}
```

- [ ] **Step 2: Run Rust test to verify it fails**

Run:

```bash
cd /Users/jacky/code/lakeon/dbay-fuse
cargo test opencode_home_defaults_to_agent_home_processing
```

Expected: FAIL because `DirectoryKind::OpencodeHome` does not exist.

- [ ] **Step 3: Implement Rust profile kind**

In `dbay-fuse/src/profile.rs`, add the enum variant:

```rust
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
```

Add display mapping:

```rust
Self::OpencodeHome => "opencode-home",
```

Add default routing:

```rust
DirectoryKind::CodexHome
| DirectoryKind::ClaudeHome
| DirectoryKind::OpenclawHome
| DirectoryKind::OpencodeHome => (StoragePolicy::Auto, ProcessingProfile::AgentHome),
```

Add detection before generic data detection:

```rust
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
```

- [ ] **Step 4: Add Java profile test**

Add to `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSFolderProfileTest.java`:

```java
@Test
void opencodeHomeDefaultsToAgentHomeProcessing() {
    LakebaseFSFolderProfile profile = LakebaseFSFolderProfile.fromRequest(
            "/agents/opencode",
            "opencode-home",
            null,
            null);

    assertEquals("opencode-home", profile.directoryKind());
    assertEquals("auto", profile.storagePolicy());
    assertEquals("agent-home", profile.processingProfile());
}
```

- [ ] **Step 5: Implement Java profile kind**

In `LakebaseFSFolderProfile.java`, add `opencode-home` wherever `codex-home`, `claude-home`, and `openclaw-home` are accepted and mapped to `agent-home`.

- [ ] **Step 6: Add console type option**

In `lakeon-console/src/api/lbfs.ts`, extend the directory kind union:

```ts
export type LBFSDirectoryKind =
  | 'codex-home'
  | 'claude-home'
  | 'openclaw-home'
  | 'opencode-home'
  | 'iceberg-table'
  | 'lance-table'
  | 'data-dir'
  | 'files'
```

In `LakebaseFSBrowse.vue`, add the option label `opencode home`.

- [ ] **Step 7: Verify**

Run:

```bash
cd /Users/jacky/code/lakeon/dbay-fuse && cargo test test_profile
cd /Users/jacky/code/lakeon/lakeon-api && mvn test -Dtest=LakebaseFSFolderProfileTest
cd /Users/jacky/code/lakeon/lakeon-console && npm run test -- lbfs-api.test.ts
```

Expected: all commands pass.

- [ ] **Step 8: Commit**

```bash
git add dbay-fuse/src/profile.rs dbay-fuse/tests/test_profile.rs \
  lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSFolderProfile.java \
  lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSFolderProfileTest.java \
  lakeon-console/src/api/lbfs.ts \
  lakeon-console/src/views/lbfs/LakebaseFSBrowse.vue \
  lakeon-console/src/__tests__/lbfs-api.test.ts
git commit -m "feat(lbfs): add opencode home folder profile"
```

### Task 3: Register Mount and Sync Folders from `dbay-fuse`

**Files:**
- Modify: `dbay-fuse/src/dbay_api.rs`
- Modify: `dbay-fuse/src/main.rs`
- Modify: `dbay-fuse/src/sync.rs`
- Test: `dbay-fuse/tests/test_cli_e2e.rs`
- Test: `dbay-fuse/tests/test_sync.rs`

- [ ] **Step 1: Add API test for folder registration payload**

Add a test that creates a `FolderProfile` and verifies `DbayApi` posts:

```rust
#[test]
fn register_folder_posts_lbfs_folder_profile() {
    let profile = FolderProfile::new(
        "/agents/opencode",
        DirectoryKind::OpencodeHome,
        None,
        None,
    );

    let body = folder_registration_body(&profile);

    assert_eq!(body["folder"], "/agents/opencode");
    assert_eq!(body["directory_kind"], "opencode-home");
    assert_eq!(body["storage_policy"], "auto");
    assert_eq!(body["processing_profile"], "agent-home");
}
```

- [ ] **Step 2: Implement registration body helper**

In `dbay-fuse/src/dbay_api.rs`, add:

```rust
pub fn folder_registration_body(profile: &FolderProfile) -> serde_json::Value {
    serde_json::json!({
        "folder": profile.folder,
        "directory_kind": profile.directory_kind.to_string(),
        "storage_policy": profile.storage_policy.to_string(),
        "processing_profile": profile.processing_profile.to_string(),
    })
}
```

Add a method that posts to `/lbfs/folders`:

```rust
pub async fn register_folder(&self, profile: &FolderProfile) -> anyhow::Result<()> {
    self.post_json("/lbfs/folders", &folder_registration_body(profile)).await?;
    Ok(())
}
```

Use the existing request helper style in `dbay_api.rs` rather than introducing a second HTTP client.

- [ ] **Step 3: Call registration from mount and sync commands**

In `dbay-fuse/src/main.rs`, after resolving a folder profile and before starting mount/sync, call:

```rust
api.register_folder(&profile).await?;
```

If the command runs in offline test mode, use the existing test bypass pattern in `dbay-fuse` instead of silently skipping registration.

- [ ] **Step 4: Verify**

Run:

```bash
cd /Users/jacky/code/lakeon/dbay-fuse && cargo test test_cli_e2e test_sync
```

Expected: all relevant Rust tests pass.

- [ ] **Step 5: Commit**

```bash
git add dbay-fuse/src/dbay_api.rs dbay-fuse/src/main.rs dbay-fuse/src/sync.rs \
  dbay-fuse/tests/test_cli_e2e.rs dbay-fuse/tests/test_sync.rs
git commit -m "feat(lbfs): register client folders with control plane"
```

### Task 4: Add Continuous Sync Mode

**Files:**
- Modify: `dbay-fuse/Cargo.toml`
- Modify: `dbay-fuse/src/main.rs`
- Modify: `dbay-fuse/src/sync.rs`
- Create: `dbay-fuse/src/watch_sync.rs`
- Test: `dbay-fuse/tests/test_sync.rs`

- [ ] **Step 1: Add tests for watch-mode debounce behavior**

Add a unit test around a pure planner/debouncer helper:

```rust
#[test]
fn watch_sync_coalesces_duplicate_path_events() {
    let events = vec![
        WatchPathEvent::changed("data/orders.csv"),
        WatchPathEvent::changed("data/orders.csv"),
        WatchPathEvent::removed("data/old.csv"),
    ];

    let batch = coalesce_watch_events(events);

    assert_eq!(batch.len(), 2);
    assert!(batch.iter().any(|event| event.path == "data/orders.csv"));
    assert!(batch.iter().any(|event| event.path == "data/old.csv"));
}
```

- [ ] **Step 2: Add watcher dependency**

In `dbay-fuse/Cargo.toml`, add the watcher crate if it is not already present:

```toml
notify = "6"
```

- [ ] **Step 3: Create `watch_sync.rs`**

Implement a small module with these public shapes:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WatchEventKind {
    Changed,
    Removed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchPathEvent {
    pub path: String,
    pub kind: WatchEventKind,
}

impl WatchPathEvent {
    pub fn changed(path: impl Into<String>) -> Self {
        Self { path: path.into(), kind: WatchEventKind::Changed }
    }

    pub fn removed(path: impl Into<String>) -> Self {
        Self { path: path.into(), kind: WatchEventKind::Removed }
    }
}

pub fn coalesce_watch_events(events: Vec<WatchPathEvent>) -> Vec<WatchPathEvent> {
    let mut by_path = std::collections::BTreeMap::new();
    for event in events {
        by_path.insert(event.path.clone(), event);
    }
    by_path.into_values().collect()
}
```

Use this helper inside the real watcher loop so tests cover the event semantics without depending on filesystem timing.

- [ ] **Step 4: Add CLI flag**

In `dbay-fuse/src/main.rs`, add `--watch` to `sync`:

```rust
#[arg(long, default_value_t = false)]
watch: bool,
```

When `watch` is false, keep current one-shot sync behavior. When true, run initial sync and then start `watch_sync::run_watch_loop(...)`.

- [ ] **Step 5: Verify**

Run:

```bash
cd /Users/jacky/code/lakeon/dbay-fuse && cargo test test_sync
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add dbay-fuse/Cargo.toml dbay-fuse/src/main.rs dbay-fuse/src/sync.rs \
  dbay-fuse/src/watch_sync.rs dbay-fuse/tests/test_sync.rs
git commit -m "feat(lbfs): add continuous sync mode"
```

### Task 5: Wire LakebaseFS Event Forwarding into Profile Workers

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSProcessingWorker.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSProcessingRouter.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSEventForwarder.java`
- Create: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSAgentHomeWorker.java`
- Test: `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSEventForwarderTest.java`
- Test: `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSProcessingRouterTest.java`

- [ ] **Step 1: Add forwarding test**

Create a test that verifies a dataset-profile file no longer gets marked done without worker dispatch:

```java
@Test
void dispatchesNonMemoryProfilesThroughProcessingRouter() {
    LakebaseFSFolderEntity folder = new LakebaseFSFolderEntity();
    folder.setFolder("/datasets");
    folder.setProcessingProfile("dataset");
    LakebaseFSProcessingEvent event = new LakebaseFSProcessingEvent("tn_1", "/datasets/orders.csv", "e1", "put");
    RecordingWorker worker = new RecordingWorker("dataset");
    LakebaseFSProcessingRouter router = new LakebaseFSProcessingRouter(List.of(worker));

    router.dispatch(folder, event);

    assertEquals(List.of("/datasets/orders.csv"), worker.paths);
}
```

- [ ] **Step 2: Change worker result contract**

Update `LakebaseFSProcessingWorker` to return a result:

```java
public interface LakebaseFSProcessingWorker {
    String processingProfile();
    LakebaseFSProcessingResult process(LakebaseFSFolderEntity folder, LakebaseFSProcessingEvent event);
}
```

Create `LakebaseFSProcessingResult`:

```java
public record LakebaseFSProcessingResult(boolean accepted, boolean retryable, String message) {
    public static LakebaseFSProcessingResult done(String message) {
        return new LakebaseFSProcessingResult(true, false, message);
    }

    public static LakebaseFSProcessingResult retry(String message) {
        return new LakebaseFSProcessingResult(false, true, message);
    }
}
```

- [ ] **Step 3: Update router**

Make router return `LakebaseFSProcessingResult.done("no worker")` when profile is `none`, and `LakebaseFSProcessingResult.retry("missing worker: " + profile)` when a folder references a worker that is not registered.

- [ ] **Step 4: Extract agent-home memory derive into `LakebaseFSAgentHomeWorker`**

Move the existing `forwardOne(...)` memory derive behavior from `LakebaseFSEventForwarder` into a worker with:

```java
@Component
public class LakebaseFSAgentHomeWorker implements LakebaseFSProcessingWorker {
    @Override
    public String processingProfile() {
        return LakebaseFSFolderProfile.PROCESSING_AGENT_HOME;
    }

    @Override
    public LakebaseFSProcessingResult process(LakebaseFSFolderEntity folder, LakebaseFSProcessingEvent event) {
        // Build the same /lbfs/derive payload currently built in LakebaseFSEventForwarder.
    }
}
```

Keep tenant connection and file-content reads explicit in method parameters or a small context object; do not hide them behind static globals.

- [ ] **Step 5: Update `LakebaseFSEventForwarder`**

Replace the current branch:

```java
if (!routesToMemoryWorker(c, e.path)) {
    markDone(c, e.id);
    maxId = Math.max(maxId, e.id);
    continue;
}
```

with folder lookup plus router dispatch:

```java
LakebaseFSFolderEntity folder = resolveFolderForPath(tenant.getId(), e.path);
LakebaseFSProcessingResult result = processingRouter.dispatch(folder, toProcessingEvent(tenant, e));
if (result.accepted()) {
    markDone(c, e.id);
    maxId = Math.max(maxId, e.id);
    continue;
}
if (result.retryable()) {
    bumpRetry(c, e, result.message());
    maxId = Math.max(maxId, e.id);
    continue;
}
```

- [ ] **Step 6: Verify**

Run:

```bash
cd /Users/jacky/code/lakeon/lakeon-api
mvn test -Dtest=LakebaseFSProcessingRouterTest,LakebaseFSEventForwarderTest
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/lbfs \
  lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSProcessingRouterTest.java \
  lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSEventForwarderTest.java
git commit -m "feat(lbfs): route file events through processing workers"
```

### Task 6: Add LakebaseFS Processing Job State

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/lbfs/LBFSAutoJobEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/lbfs/LBFSAutoJobRepository.java`
- Create: `lakeon-api/src/main/java/com/lakeon/lbfs/LBFSAutoJobController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSProcessingWorker.java`
- Modify: `lakeon-console/src/api/lbfs.ts`
- Modify: `lakeon-console/src/views/lbfs/LakebaseFSBrowse.vue`
- Test: `lakeon-api/src/test/java/com/lakeon/lbfs/LBFSAutoJobControllerTest.java`
- Test: `lakeon-console/src/__tests__/lbfs-api.test.ts`

- [ ] **Step 1: Add API test**

Test response shape:

```java
@Test
void listsProcessingJobsForFolder() throws Exception {
    mockMvc.perform(get("/api/v1/lbfs/folders/%s/auto-jobs".formatted(folderId))
            .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auto_jobs[0].folder_id").value(folderId))
        .andExpect(jsonPath("$.auto_jobs[0].profile").value("dataset"))
        .andExpect(jsonPath("$.auto_jobs[0].status").value("running"));
}
```

- [ ] **Step 2: Create job entity**

Use this status vocabulary:

```java
public enum LBFSAutoJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    RETRYING,
    FAILED
}
```

Entity fields:

```java
id, tenantId, folderId, sourcePath, sourceEtag, profile,
status, attempts, lastError, startedAt, finishedAt, createdAt, updatedAt
```

- [ ] **Step 3: Create controller**

Expose:

```text
GET /api/v1/lbfs/folders/{folderId}/auto-jobs
```

Return:

```json
{
  "jobs": [
    {
      "id": "job_1",
      "folder_id": "folder_1",
      "source_path": "/datasets/orders.csv",
      "profile": "dataset",
      "status": "running",
      "attempts": 1,
      "last_error": null
    }
  ]
}
```

- [ ] **Step 4: Add console API**

In `lakeon-console/src/api/lbfs.ts`:

```ts
export interface LBFSAutoJob {
  id: string
  folder_id: string
  source_path: string
  profile: string
  status: 'pending' | 'running' | 'succeeded' | 'retrying' | 'failed'
  attempts: number
  last_error?: string | null
}

export function listLBFSAutoJobs(folderId: string) {
  return api.get<{ auto_jobs: LBFSAutoJob[] }>(`/lbfs/folders/${folderId}/auto-jobs`)
}
```

- [ ] **Step 5: Show status in LakebaseFS page**

In `LakebaseFSBrowse.vue`, add a compact jobs section for the selected folder. Use the same font scale, row density, and restrained visual style used in Database pages.

- [ ] **Step 6: Verify**

Run:

```bash
cd /Users/jacky/code/lakeon/lakeon-api && mvn test -Dtest=LBFSAutoJobControllerTest
cd /Users/jacky/code/lakeon/lakeon-console && npm run test -- lbfs-api.test.ts
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/lbfs \
  lakeon-api/src/test/java/com/lakeon/lbfs/LBFSAutoJobControllerTest.java \
  lakeon-console/src/api/lbfs.ts lakeon-console/src/views/lbfs/LakebaseFSBrowse.vue \
  lakeon-console/src/__tests__/lbfs-api.test.ts
git commit -m "feat(lbfs): expose lbfs_auto_job status"
```

### Task 7: Implement Dataset and Table Workers

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSDatasetWorker.java`
- Create: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSTableWorker.java`
- Create: `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSDataProfile.java`
- Test: `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSDatasetWorkerTest.java`
- Test: `lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSTableWorkerTest.java`

- [ ] **Step 1: Add dataset worker tests**

Test CSV profiling:

```java
@Test
void profilesCsvHeaderAndSampleRows() {
    byte[] csv = "order_id,amount\\no1,12.5\\no2,9.0\\n".getBytes(StandardCharsets.UTF_8);

    LakebaseFSDataProfile profile = LakebaseFSDatasetWorker.profileCsv("/datasets/orders.csv", csv);

    assertEquals(List.of("order_id", "amount"), profile.columns());
    assertEquals(2, profile.sampleRowCount());
    assertEquals("csv", profile.format());
}
```

- [ ] **Step 2: Add table worker tests**

Test Iceberg detection:

```java
@Test
void detectsIcebergMetadataFiles() {
    LakebaseFSProcessingEvent event = new LakebaseFSProcessingEvent(
            "tn_1",
            "/tables/orders/metadata/v1.metadata.json",
            "etag-1",
            "put");

    LakebaseFSProcessingResult result = worker.process(folder("iceberg"), event);

    assertTrue(result.accepted());
    assertTrue(result.message().contains("iceberg"));
}
```

- [ ] **Step 3: Implement `LakebaseFSDataProfile`**

Use a compact record:

```java
public record LakebaseFSDataProfile(
        String path,
        String format,
        List<String> columns,
        int sampleRowCount,
        Map<String, Object> statistics) {
}
```

- [ ] **Step 4: Implement CSV profile first**

In `LakebaseFSDatasetWorker`, support `csv`, `tsv`, `jsonl`, and `ndjson` in the first pass. For `xlsx`, `parquet`, and `orc`, create accepted jobs with message `"metadata queued for external profiler"` so the worker contract is stable without claiming unsupported parsing.

- [ ] **Step 5: Implement table metadata worker**

In `LakebaseFSTableWorker`, route:

```java
if ("iceberg".equals(folder.getProcessingProfile())) {
    return LakebaseFSProcessingResult.done("iceberg metadata observed: " + event.path());
}
if ("lance".equals(folder.getProcessingProfile())) {
    return LakebaseFSProcessingResult.done("lance metadata observed: " + event.path());
}
return LakebaseFSProcessingResult.retry("unsupported table profile: " + folder.getProcessingProfile());
```

- [ ] **Step 6: Verify**

Run:

```bash
cd /Users/jacky/code/lakeon/lakeon-api
mvn test -Dtest=LakebaseFSDatasetWorkerTest,LakebaseFSTableWorkerTest
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSDatasetWorker.java \
  lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSTableWorker.java \
  lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSDataProfile.java \
  lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSDatasetWorkerTest.java \
  lakeon-api/src/test/java/com/lakeon/lbfs/LakebaseFSTableWorkerTest.java
git commit -m "feat(lbfs): process dataset and table folder events"
```

### Task 8: Add DBay DataAgent API

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentRunEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentArtifactEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentSourceEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentController.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentService.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataagent/DataAgentRuntimeClient.java`
- Test: `lakeon-api/src/test/java/com/lakeon/dataagent/DataAgentControllerTest.java`

- [ ] **Step 1: Add controller tests**

Create tests for run creation and retrieval:

```java
@Test
void createsDataAgentRunWithLbfsSource() throws Exception {
    mockMvc.perform(post("/api/v1/data-agent/runs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "goal": "分析 orders.csv 的收入异常",
                  "sources": [
                    {"kind": "lbfs-folder", "ref": "/datasets/orders", "label": "orders"}
                  ]
                }
                """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.run.goal").value("分析 orders.csv 的收入异常"))
        .andExpect(jsonPath("$.run.status").value("queued"))
        .andExpect(jsonPath("$.run.sources[0].kind").value("lbfs-folder"));
}
```

- [ ] **Step 2: Define API payloads**

Use these statuses:

```java
queued, running, waiting_for_input, succeeded, failed, canceled
```

Use these source kinds:

```java
dbay-database, lbfs-folder, external-database
```

Use these artifact kinds:

```java
sql, table, chart, report, dashboard, validation, lineage, video
```

- [ ] **Step 3: Implement controller**

Expose:

```text
POST /api/v1/data-agent/runs
GET /api/v1/data-agent/runs
GET /api/v1/data-agent/runs/{runId}
GET /api/v1/data-agent/runs/{runId}/artifacts
```

Run creation stores a queued run and calls `DataAgentRuntimeClient.startRun(...)`.

- [ ] **Step 4: Implement runtime client**

Use a small HTTP client with configured base URL:

```properties
lakeon.dataagent.runtime.base-url=http://data-agent-runtime:18912
```

Start-run request:

```json
{
  "run_id": "run_...",
  "tenant_id": "tn_...",
  "goal": "分析 orders.csv 的收入异常",
  "sources": [{"kind": "lbfs-folder", "ref": "/datasets/orders", "label": "orders"}]
}
```

- [ ] **Step 5: Verify**

Run:

```bash
cd /Users/jacky/code/lakeon/lakeon-api && mvn test -Dtest=DataAgentControllerTest
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/dataagent \
  lakeon-api/src/test/java/com/lakeon/dataagent/DataAgentControllerTest.java
git commit -m "feat(api): add data agent run api"
```

### Task 9: Build Console DataAgent Entry and Workbench

**Files:**
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`
- Modify: `lakeon-console/src/router/index.ts`
- Create: `lakeon-console/src/api/data-agent.ts`
- Create: `lakeon-console/src/views/data-agent/DataAgentHome.vue`
- Create: `lakeon-console/src/views/data-agent/DataAgentRunDetail.vue`
- Create: `lakeon-console/src/components/data-agent/SourcePicker.vue`
- Create: `lakeon-console/src/components/data-agent/WorkbenchArtifactPanel.vue`
- Test: `lakeon-console/src/__tests__/data-agent-api.test.ts`
- Test: `lakeon-console/src/__tests__/DataAgentHome.test.ts`

- [ ] **Step 1: Read design context**

Run:

```bash
cd /Users/jacky/code/lakeon
sed -n '1,220p' .impeccable.md
```

Expected: use the existing warm, restrained console style; do not introduce generic AI-template visuals.

- [ ] **Step 2: Add API client tests**

In `data-agent-api.test.ts`:

```ts
it('creates a run through the data agent api', async () => {
  const client = { post: vi.fn().mockResolvedValue({ run: { id: 'run_1', status: 'queued' } }) }
  const api = createDataAgentApi(client as never)

  await api.createRun({
    goal: '分析 orders.csv 的收入异常',
    sources: [{ kind: 'lbfs-folder', ref: '/datasets/orders', label: 'orders' }],
  })

  expect(client.post).toHaveBeenCalledWith('/data-agent/runs', {
    goal: '分析 orders.csv 的收入异常',
    sources: [{ kind: 'lbfs-folder', ref: '/datasets/orders', label: 'orders' }],
  })
})
```

- [ ] **Step 3: Implement API client**

In `lakeon-console/src/api/data-agent.ts`:

```ts
export type DataAgentSourceKind = 'dbay-database' | 'lbfs-folder' | 'external-database'

export interface DataAgentSourceInput {
  kind: DataAgentSourceKind
  ref: string
  label: string
}

export interface CreateDataAgentRunRequest {
  goal: string
  sources: DataAgentSourceInput[]
}

export function createDataAgentRun(payload: CreateDataAgentRunRequest) {
  return api.post<{ run: DataAgentRun }>('/data-agent/runs', payload)
}
```

- [ ] **Step 4: Add route and menu**

Add:

```ts
{
  path: '/data-agent',
  name: 'data-agent',
  component: () => import('../views/data-agent/DataAgentHome.vue'),
}
```

Use the same menu typography, spacing, hover state, and active state as Database and LakebaseFS.

- [ ] **Step 5: Implement home page**

Home page contains:

- source picker for DBay database and LakebaseFS folder
- natural-language goal input
- run button
- recent runs table

No explanatory marketing hero section.

- [ ] **Step 6: Implement run detail page**

Run detail contains:

- message/progress column
- artifact panel for SQL, table, report, dashboard, validation, and lineage
- source/lineage inspector
- status and retry/error state

- [ ] **Step 7: Verify**

Run:

```bash
cd /Users/jacky/code/lakeon/lakeon-console
npm run test -- data-agent-api.test.ts DataAgentHome.test.ts
npx vue-tsc -b --noEmit
npm run build
```

Expected: all commands pass.

- [ ] **Step 8: Commit**

```bash
git add lakeon-console/src/layouts/ConsoleLayout.vue lakeon-console/src/router/index.ts \
  lakeon-console/src/api/data-agent.ts lakeon-console/src/views/data-agent \
  lakeon-console/src/components/data-agent lakeon-console/src/__tests__/data-agent-api.test.ts \
  lakeon-console/src/__tests__/DataAgentHome.test.ts
git commit -m "feat(console): add data agent workspace"
```

### Task 10: Connect opencode DataAgent to LakebaseFS Sources

**Files:**
- Modify: `/Users/jacky/code/opencode/packages/opencode/src/data-agent/web/model.ts`
- Modify: `/Users/jacky/code/opencode/packages/opencode/src/data-agent/web/server.ts`
- Create: `/Users/jacky/code/opencode/packages/opencode/src/data-agent/lbfs/client.ts`
- Create: `/Users/jacky/code/opencode/packages/opencode/src/data-agent/runtime/run.ts`
- Create: `/Users/jacky/code/opencode/packages/opencode/src/data-agent/runtime/tools.ts`
- Create: `/Users/jacky/code/opencode/packages/opencode/src/data-agent/runtime/artifacts.ts`
- Test: `/Users/jacky/code/opencode/packages/opencode/test/workflow/data-agent-runtime.test.ts`
- Test: `/Users/jacky/code/opencode/packages/opencode/test/workflow/data-agent-workbench.test.ts`

- [ ] **Step 1: Add source type tests**

In `data-agent-workbench.test.ts`, assert LakebaseFS source rendering:

```ts
test('data agent run accepts lakebasefs folder sources', () => {
  const run = createDataAgentWorkbenchRun({
    id: 'run_lbfs',
    goal: '分析 orders 数据',
    sources: [
      {
        id: 'src_lbfs_orders',
        label: 'orders',
        kind: 'lakebasefs',
        status: 'connected',
        detail: '/datasets/orders',
      },
    ],
  })

  expect(run.inspector.find((section) => section.id === 'data_sources')?.items[0]?.detail)
    .toContain('/datasets/orders')
})
```

- [ ] **Step 2: Extend DataAgent source kind**

In `model.ts`:

```ts
export interface DataAgentSource {
  id: string
  label: string
  kind: "warehouse" | "database" | "lakebasefs" | "file" | "document"
  status: "connected" | "pending" | "blocked"
  detail: string
}
```

Update `isDataAgentSource` in `server.ts` to accept `lakebasefs`.

- [ ] **Step 3: Create LakebaseFS client**

In `data-agent/lbfs/client.ts`:

```ts
export interface LakebaseFSClientOptions {
  baseUrl: string
  token?: string
}

export interface LakebaseFSSourceSummary {
  path: string
  files: Array<{ path: string; size: number; etag: string }>
}

export class LakebaseFSClient {
  constructor(private readonly options: LakebaseFSClientOptions) {}

  async summarizeFolder(path: string): Promise<LakebaseFSSourceSummary> {
    const response = await fetch(`${this.options.baseUrl}/lbfs/list?prefix=${encodeURIComponent(path)}`, {
      headers: this.options.token ? { authorization: `Bearer ${this.options.token}` } : {},
    })
    if (!response.ok) throw new Error(`LakebaseFS list failed: ${response.status}`)
    const body = await response.json() as { entries?: Array<{ path: string; size?: number; etag?: string }> }
    return {
      path,
      files: (body.entries || []).map((entry) => ({
        path: entry.path,
        size: entry.size || 0,
        etag: entry.etag || "",
      })),
    }
  }
}
```

- [ ] **Step 4: Create deterministic runtime skeleton**

In `runtime/run.ts`, implement:

```ts
export interface DataAgentRuntimeInput {
  runId: string
  goal: string
  sources: DataAgentSource[]
}

export async function runDataAgent(input: DataAgentRuntimeInput): Promise<WorkbenchRun> {
  return createDataAgentWorkbenchRun({
    id: input.runId,
    goal: input.goal,
    sources: input.sources,
  })
}
```

This keeps current behavior stable while moving server code through a runtime boundary used by Task 12 E2E and by the next runtime-tool implementation plan.

- [ ] **Step 5: Route server run creation through runtime**

In `server.ts`, replace direct `createDataAgentWorkbenchRun(...)` with:

```ts
const run = await runDataAgent({
  runId: `planned_${crypto.randomUUID().slice(0, 8)}`,
  goal: body.goal,
  sources: parseSources(body.sources) || [],
})
return json(run, 202)
```

- [ ] **Step 6: Verify**

Run:

```bash
cd /Users/jacky/code/opencode/packages/opencode
bun test test/workflow/data-agent-workbench.test.ts test/workflow/data-agent-cli.test.ts test/workflow/data-agent-runtime.test.ts
bun run typecheck
```

Expected: all commands pass.

- [ ] **Step 7: Commit in opencode fork**

```bash
git -C /Users/jacky/code/opencode add packages/opencode/src/data-agent packages/opencode/test/workflow/data-agent-*.test.ts
git -C /Users/jacky/code/opencode commit -m "feat(data-agent): connect runtime model to lakebasefs sources"
```

### Task 11: Define DataAgent Pod Home Restore Contract

**Files:**
- Create: `deploy/cce/data-agent/README.md`
- Create: `deploy/cce/data-agent/data-agent-pod.yaml`
- Modify: `deploy/helm/lakeon/values.yaml`

- [ ] **Step 1: Document non-EVS runtime contract**

In `deploy/cce/data-agent/README.md`, define:

```text
DataAgent pod home contract:

1. Pod starts with ephemeral writable volume mounted at /home/opencode.
2. Init container runs lbfs pull for folder kind opencode-home into /home/opencode.
3. Main container runs opencode DataAgent runtime.
4. Sidecar or background process runs lbfs sync --watch from /home/opencode to LakebaseFS.
5. On termination, preStop runs lbfs sync once and drains the outbox.
```

This avoids EVS while keeping pod migration possible. FUSE-in-pod can be added after the pull/sync model passes reliability tests because FUSE may require elevated container privileges.

- [ ] **Step 2: Add pod manifest**

Create `data-agent-pod.yaml` with:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: data-agent-runtime
spec:
  volumes:
    - name: opencode-home
      emptyDir: {}
  initContainers:
    - name: lbfs-pull-home
      image: dbay/lbfs-client:latest
      command: ["lbfs", "pull", "--folder", "/agents/opencode", "--dest", "/home/opencode"]
      volumeMounts:
        - name: opencode-home
          mountPath: /home/opencode
  containers:
    - name: data-agent
      image: dbay/data-agent-runtime:latest
      env:
        - name: DATA_AGENT_HOME
          value: /home/opencode
      volumeMounts:
        - name: opencode-home
          mountPath: /home/opencode
    - name: lbfs-sync-home
      image: dbay/lbfs-client:latest
      command: ["lbfs", "sync", "--watch", "--source", "/home/opencode", "--folder", "/agents/opencode", "--kind", "opencode-home"]
      volumeMounts:
        - name: opencode-home
          mountPath: /home/opencode
```

- [ ] **Step 3: Verify manifest shape**

Run:

```bash
kubectl --dry-run=client -f /Users/jacky/code/lakeon/deploy/cce/data-agent/data-agent-pod.yaml
```

Expected: Kubernetes client accepts the manifest.

- [ ] **Step 4: Commit**

```bash
git add deploy/cce/data-agent deploy/helm/lakeon/values.yaml
git commit -m "docs(data-agent): define lbfs home restore contract"
```

### Task 12: End-to-End Validation Ladder

**Files:**
- Create: `tests/e2e/test_lbfs_data_agent.py`
- Create: `lakeon-console/tests/e2e/data-agent.spec.ts`
- Modify: `tests/e2e/test_lbfs_sync_roundtrip.py` if endpoint names remain stale.

- [ ] **Step 1: Add API E2E for file sync to DataAgent source**

In `tests/e2e/test_lbfs_data_agent.py`:

```python
def test_csv_folder_becomes_data_agent_source(api_client, tenant):
    folder = api_client.post("/lbfs/folders", json={
        "folder": "/datasets/orders",
        "directory_kind": "data-dir",
        "storage_policy": "object-first",
        "processing_profile": "dataset",
    }).json()

    put = api_client.put("/lbfs/files", json={
        "path": "/datasets/orders/orders.csv",
        "content": "order_id,amount\\no1,12.5\\n",
    })
    assert put.status_code in (200, 201)

    run = api_client.post("/data-agent/runs", json={
        "goal": "汇总 orders 收入",
        "sources": [{"kind": "lbfs-folder", "ref": "/datasets/orders", "label": "orders"}],
    }).json()["run"]

    assert run["status"] in ("queued", "running", "succeeded")
```

- [ ] **Step 2: Add console E2E**

In `lakeon-console/tests/e2e/data-agent.spec.ts`:

```ts
test('starts a data agent run from a lakebasefs source', async ({ page }) => {
  await page.goto('/data-agent')
  await page.getByRole('button', { name: /LakebaseFS/ }).click()
  await page.getByPlaceholder(/自然语言/).fill('分析 orders 收入异常')
  await page.getByRole('button', { name: /运行/ }).click()
  await expect(page.getByText(/queued|running|succeeded/)).toBeVisible()
})
```

- [ ] **Step 3: Run full verification**

Run:

```bash
cd /Users/jacky/code/lakeon/lakeon-api && mvn test
cd /Users/jacky/code/lakeon/dbay-fuse && cargo test
cd /Users/jacky/code/lakeon/lakeon-console && npm run test && npx vue-tsc -b --noEmit && npm run build
python3 -m pytest /Users/jacky/code/lakeon/tests/e2e/test_lbfs_data_agent.py -v
cd /Users/jacky/code/opencode/packages/opencode && bun test test/workflow/data-agent-workbench.test.ts test/workflow/data-agent-cli.test.ts test/workflow/data-agent-runtime.test.ts
```

Expected: all commands pass. Any failed test is fixed before reporting completion.

- [ ] **Step 4: Commit**

```bash
git add tests/e2e/test_lbfs_data_agent.py lakeon-console/tests/e2e/data-agent.spec.ts
git commit -m "test(data-agent): cover lbfs source analysis flow"
```

## MVP Completion Criteria

MVP is complete when all of these are true:

- LakebaseFS supports `opencode-home`, `codex-home`, `claude-home`, `openclaw-home`, `data-dir`, `iceberg-table`, `lance-table`, and `files` profiles.
- `dbay-fuse sync --watch` can continuously send local directory changes to LakebaseFS.
- `dbay-fuse mount` and `sync` register folder profiles with `/api/v1/lbfs/folders`.
- LakebaseFS routes file events through profile-specific workers.
- Dataset folders produce schema/sample metadata.
- Iceberg and Lance folders are recognized and registered as table-like sources.
- Console shows LakebaseFS folders and processing status.
- Console exposes DataAgent as a user-facing workspace.
- DataAgent run creation accepts DBay database and LakebaseFS folder sources.
- opencode DataAgent runtime accepts LakebaseFS sources through a runtime boundary.
- DataAgent pods can restore opencode home from LakebaseFS without EVS and sync changes back.
- E2E covers: local CSV folder -> LakebaseFS -> dataset worker -> DataAgent run -> report/dashboard artifacts.

## Non-MVP Work Kept Out of This Plan

- Full privileged FUSE mount inside Kubernetes pods.
- Complete Iceberg/Lance query execution engine.
- Multi-tenant billing and quota for LakebaseFS storage.
- Public SDKs for every external client.
- Video rendering beyond storyboard/script artifact generation.
- Full object-store migration of all existing inline `files.data` bytes.

These items are productively separable after the MVP proves the LakebaseFS-to-DataAgent loop.
