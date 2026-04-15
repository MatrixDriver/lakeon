# dbay-fuse

FUSE daemon that takes over AI agent working directories (Claude Code, OpenClaw, ...)
and backs them with DBay AgentFS.

## Architecture

```
Claude Code / OpenClaw
        │ POSIX
┌───────▼────────────────────────────────┐
│ dbay-fuse (Rust)                       │
│  · Passthrough FS → ~/.dbay/state/     │
│  · Outbox (append-only log + blobs)    │
│  · Flush watchdog (idle + size)        │
│  · Uplink worker → DBay AgentFS        │
└───────┬────────────────────────────────┘
        │ HTTPS (async, out of band)
┌───────▼────────────────────────────────┐
│ DBay AgentFS API (/v1/agentfs/*)       │
└───────┬────────────────────────────────┘
        │
┌───────▼────────────────────────────────┐
│ Postgres (agent_files table)           │
└───────┬────────────────────────────────┘
        │ async CDC
┌───────▼────────────────────────────────┐
│ Memory extraction workers              │
│ (existing memory_ingest path)          │
└────────────────────────────────────────┘
```

**No SQLite.** Durability:
- Reads/writes hit local state directory immediately (POSIX)
- Uploads queued in `outbox/pending.log` (append-only + fsync)
- Upload worker drains async; daemon crash = replay outbox on restart

## Install

Prereqs:
- [macFUSE](https://osxfuse.github.io/) (macOS) or fuse3 (Linux)
- Rust toolchain

Build:

```bash
cd ~/code/lakeon/dbay-fuse
cargo build --release
```

## Usage

```bash
# Mount (blocks, run in tmux / &)
dbay-fuse mount --agent claude

# Takeover selected subdirs (first time)
dbay-fuse takeover --agent claude --nodes CLAUDE.md
dbay-fuse takeover --agent claude --nodes memory
dbay-fuse takeover --agent claude --nodes projects       # kill CC first!

# Rollback
dbay-fuse release --agent claude

# Config
dbay-fuse whoami --agent claude
dbay-fuse agent-bind --agent claude --base personal

# Status
dbay-fuse outbox-status --agent claude
```

## Flush triggers

A file's write is pushed to DBay AgentFS when **any** of the following happens:

| Trigger      | How                         |
|--------------|-----------------------------|
| close        | FUSE release op             |
| fsync        | FUSE fsync op               |
| idle 500ms   | watchdog thread             |
| dirty >1MB   | watchdog thread             |
| mkdir / rm   | immediate (small ops)       |
| rename       | immediate                   |

## Directory layout

```
~/.dbay/
  ├── config.json                # api key, base binding
  ├── mnt/<agent>/               # FUSE mountpoint
  ├── state/<agent>/             # local passthrough backing store
  ├── outbox/<agent>/
  │    ├── pending.log           # append-only op log
  │    └── blobs/<sha>/<sha>     # content-addressed payloads
  └── backups/<agent>-<ts>/      # takeover rollback targets
```

## Roadmap

- [x] Passthrough FS
- [x] Outbox + flush watchdog + uplink worker
- [x] AgentFS HTTP client (path-in-body, append endpoint)
- [ ] DBay server-side AgentFS API implementation (`/v1/agentfs/*`)
- [ ] AgentFS → Memory derivation worker (async indexing)
- [ ] Virtual CLAUDE.md composition from memory_items
- [ ] OpenClaw adapter (`~/.openclaw/workspace/*`)

See `../specs/agentfs-openapi.yaml` and `../specs/universal-memory.md`.
