# DBay FUSE — New User Onboarding

Two paths to get started. Names (not IDs) are used everywhere the user types.

---

## Path A · Console (recommended for first-time users)

1. **Sign up** → https://console.dbay.cloud
2. **First-run wizard** asks you to create a memory base by name:
   ```
   Name your memory base: [  personal  ]
   ```
   A name like `personal`, `work`, `side-project` is easy to remember.
3. **Copy the API key** shown after signup.
4. Jump to CLI step 2 below to wire up the daemon.

## Path B · CLI (for power users)

```bash
# 1. Log in. Stores api_key in ~/.dbay/config.json
dbay login                      # or:  dbay login --api-key lk_...

# 2. Create a memory base by NAME
dbay memory create personal

# 3. Bind agents to bases (optional if you only have one base)
dbay-fuse agent-bind --agent claude --base personal
dbay-fuse agent-bind --agent openclaw --base work

# 4. Check resolution
dbay-fuse whoami --agent claude
#   agent:     claude
#   api_key:   lk_8b...66eb
#   base_url:  https://api.dbay.cloud:8443/api/v1
#   base_ref:  personal  (name)
#   base_id:   mem_f3296d01b53c
#   base_name: personal

# 5. Mount + take over the agent's directories
dbay-fuse mount --agent claude &          # background daemon (or run in tmux)
dbay-fuse takeover --agent claude          # swaps ~/.claude/{projects,memory,CLAUDE.md}
                                          # for symlinks to FUSE mount
```

That's it. Just run `claude` normally — all writes go through FUSE → SQLite → DBay.

---

## How name resolution works

`~/.dbay/config.json`:

```json
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key": "lk_...",
  "memory_base": "personal",
  "agent_bases": {
    "claude":   "personal",
    "openclaw": "work"
  }
}
```

Resolution precedence (per agent):
1. `DBAY_BASE` env var
2. `agent_bases.<agent>` from config
3. `memory_base` from config (single-base legacy)
4. Auto-detect if the account has exactly one READY base

Names are resolved to `mem_xxx` IDs at daemon startup via `GET /memory/bases`.

## Rollback

If anything goes wrong, everything is reversible:

```bash
dbay-fuse release --agent claude   # restores ~/.claude/{projects,memory,CLAUDE.md} from backup
```

Backups live at `~/.dbay/backups/<agent>-<timestamp>/`.
