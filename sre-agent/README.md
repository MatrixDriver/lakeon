# dbay.cloud SRE agent

Phase 0a: hermes-based SRE agent + agent_session_log.

See spec: ../docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md

## Layout
- `agent_session_log/` — commit log Python library (runtime-agnostic, zero lakeon dependency)
- `skills/sre/` — hermes skills
- `hermes_config/` — hermes config files
- `scripts/` — operational helpers
- `tests/` — pytest suite

## Local dev

    cd sre-agent
    uv sync --all-extras
    uv run pytest

## Deploy

See [docs/DEPLOY_RUNBOOK.md](docs/DEPLOY_RUNBOOK.md) for the Phase 0a Railway deploy + validation runbook.
