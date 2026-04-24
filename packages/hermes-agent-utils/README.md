# hermes-agent-utils

Shared runtime helpers used by dbay.cloud agents:

- `llm.DeepseekLLMClient` — OpenAI-compat LLM client (also works for HWC MaaS).
- `feishu.feishu_send_dm` / `feishu.jacky_open_id` — outbound Feishu DM via REST.
- `factory.make_log_store` / `make_skill_ledger` — wire `agent_session_log` from env.
- `runner.cron_loop(tasks)` / `start_subprocess` / `shutdown_children` — main-loop primitives.
- `cli` — `python -m hermes_agent_utils.cli sync` runs the OBS sync loop.

Not intended for distribution outside this workspace; lives here so SRE and
reading-companion services can share without duplication.
