# dbay-fuse

General DBay LakebaseFS client for mounting or syncing user-declared folders.

LakebaseFS no longer takes over `~/.claude` or other agent homes. Users choose a
local directory, declare what kind of directory it is, and DBay uses that profile
to pick storage and background processing behavior.

## Architecture

```
App / agent / shell
        │ POSIX
┌───────▼────────────────────────────────┐
│ dbay-fuse (Rust)                       │
│  · mount: passthrough FS → state dir    │
│  · sync: source dir is the state root   │
│  · outbox (append-only log + blobs)     │
│  · uplink worker → DBay LakebaseFS         │
└───────┬────────────────────────────────┘
        │ HTTPS (async, out of band)
┌───────▼────────────────────────────────┐
│ DBay LakebaseFS API (/v1/lbfs/*)       │
└───────┬────────────────────────────────┘
        │
┌───────▼────────────────────────────────┐
│ Neon/Postgres metadata + file bytes     │
└───────┬────────────────────────────────┘
        │ async derivation
┌───────▼────────────────────────────────┐
│ Profile-specific workers               │
│ agent home / dataset / table metadata   │
└────────────────────────────────────────┘
```

## Profile Axes

`--kind` says what the directory is:

- `codex-home`
- `claude-home`
- `openclaw-home`
- `iceberg-table`
- `lance-table`
- `data-dir`
- `files`

`--storage` says where bytes should live:

- `auto`
- `inline-only`
- `object-first`
- `object-only`
- `table-native`

`--processing` says what cloud workers should derive:

- `none`
- `agent-home`
- `dataset`
- `iceberg`
- `lance`

Defaults are derived from `--kind`. For example, `iceberg-table` defaults to
`table-native + iceberg`, while `codex-home` defaults to `auto + agent-home`.

## Usage

```bash
# Mount a new general folder. Folder name defaults to "dbay".
dbay-fuse mount ~/DBay --kind files

# Sync an existing directory without copying it into ~/.dbay/state.
dbay-fuse sync ~/.codex --kind codex-home
dbay-fuse sync ~/datasets/events --kind iceberg-table
dbay-fuse sync ~/reports --kind data-dir

# One-shot import uses the same planner, then exits.
dbay-fuse import ~/archive --kind data-dir

# Ask the local advisor for a recommended directory kind.
dbay-fuse inspect ~/datasets/events

# Existing scripts can still use --agent as a compatibility alias.
dbay-fuse pull --agent claude
```

## Directory Layout

```
~/.dbay/
  ├── config.json
  ├── mnt/<folder>/          # mount mode view
  ├── state/<folder>/        # mount mode backing store/cache
  ├── outbox/<folder>/       # mount mode upload queue
  ├── sync/<folder>/         # sync/import metadata only
  │   ├── outbox/
  │   ├── etags.db
  │   └── tmp/
  └── sync-ledger/<folder>/  # mount/pull etag ledger
```

In sync mode, the user's original directory is the only full local copy.
`~/.dbay/sync/<folder>/` stores metadata, queues, and temporary files.

## Build

```bash
cd ~/code/lakeon/dbay-fuse
cargo build --release
```

## Verification

```bash
cargo test
cargo check
```
