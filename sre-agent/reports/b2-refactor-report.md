# B2 Refactor Report — Reading Companion as Independent Service

## Outcome (fill in after deploy + 1 week)

- [ ] sre-agent on Railway redeployed cleanly with new workspace-aware Dockerfile
- [ ] reading-companion service stood up on Railway
- [ ] OBS bucket `dbay-agent-log` shows both `agent-log/sre/` and `agent-log/reading/` prefixes
- [ ] Daily reflection at 22:00 Asia/Shanghai working
- [ ] CLI `python -m skills.reading.url_handler.cli` working in production
- [ ] No data lost in migration (existing SRE sessions on Volume still readable)

## Phase 2 progress (the big point)

- [ ] `agent_session_log` lives at `lakeon/packages/agent-session-log/` with its own pyproject.toml
- [ ] Both consumers (sre-agent + reading-companion) install it via uv workspace path source
- [ ] Zero `agent_session_log.*` API changes during refactor
- [ ] Phase 2 next steps: extract `lakeon/packages/agent-session-log/` to its own git repo,
      add CI, publish to PyPI as `agent-session-log==0.1.0`. Code is already shaped for it.

## Architecture facts post-B2

| | sre-agent | reading-companion |
|---|---|---|
| Railway service | `sre-agent` (existing) | `reading-companion` (new) |
| Volume mount | `/data/hermes` (own) | `/data/hermes` (own) |
| OBS bucket | `dbay-agent-log` (shared) | `dbay-agent-log` (shared) |
| OBS prefix | `agent-log/sre/` | `agent-log/reading/` |
| Feishu bot | original SRE bot | NEW reading bot |
| Image | hermes-agent + dbay-sre-mcp + agent-session-log + hermes-agent-utils | trafilatura + agent-session-log + hermes-agent-utils |
| Crons | `*/2 *` (cold-start), `0 9` (outcome) | `0 14` (daily reflection) |
| Inbound feishu | YES (hermes gateway) | NO (push-only) |

## Code shape

```
lakeon/
├── pyproject.toml                # workspace root
├── packages/
│   ├── agent-session-log/        # the data layer (Phase 2 candidate)
│   └── hermes-agent-utils/       # shared helpers
├── sre-agent/                    # SRE service
└── reading-companion/            # reading service
```

## Surprises / friction during refactor

- ...

## Phase 2 decision point

After 1 week of reading-companion in production, if no API friction surfaced
(check `agent-session-log` git log for hot-fixes), proceed to Phase 2:
1. Initialize `~/code/agent-session-log/` git repo
2. Copy contents of `lakeon/packages/agent-session-log/` over
3. Add GitHub Actions: pytest + ruff + build wheel
4. Publish to PyPI as `agent-session-log==0.1.0`
5. Update `lakeon/packages/agent-session-log/` to be a re-export stub OR delete it,
   change sre-agent + reading-companion deps from `workspace = true` to `>=0.1.0`
