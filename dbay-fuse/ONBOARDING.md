# DBay FUSE New User Onboarding

Names, not IDs, are used wherever a user types.

## Console Path

1. Sign up at https://console.dbay.cloud.
2. Create or select a memory base.
3. Copy the API key.
4. Save `~/.dbay/config.json`:

```json
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key": "lk_...",
  "memory_base": "personal"
}
```

## CLI Path

```bash
dbay login
dbay memory create personal

dbay-fuse agent-bind --folder personal --base personal
dbay-fuse whoami --kind files
```

## Add A Folder

Mount a new DBay-backed directory:

```bash
dbay-fuse mount ~/DBay --kind files
```

Sync an existing directory without moving or duplicating it:

```bash
dbay-fuse sync ~/.codex --kind codex-home
dbay-fuse sync ~/datasets/events --kind iceberg-table
dbay-fuse sync ~/reports --kind data-dir
```

Ask for a recommendation before choosing:

```bash
dbay-fuse inspect ~/datasets/events
```

## Config Resolution

`~/.dbay/config.json`:

```json
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key": "lk_...",
  "memory_base": "personal",
  "agent_bases": {
    "personal": "personal",
    "codex": "personal"
  }
}
```

`agent_bases` is kept for backwards compatibility, but new commands should pass
`--folder`.

Resolution precedence:

1. `DBAY_BASE` env var
2. `agent_bases.<folder>` from config
3. `memory_base` from config
4. Auto-detect if the account has exactly one READY base
