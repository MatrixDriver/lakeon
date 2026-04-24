# reading-companion

Independent Railway service for the dbay reading-companion agent. Cron-triggered
daily reflection (22:00 Asia/Shanghai = 14:00 UTC). URL ingestion + recall query
via CLI: `python -m skills.reading.url_handler.cli --url <url>` and
`python -m skills.reading.query_handler.cli "<question>"`.

Uses `agent-session-log` (workspace package) as the data layer and
`hermes-agent-utils` (workspace package) for LLM/feishu/cron primitives.

Does NOT run a hermes gateway — push-only feishu, no inbound listening.

Env vars (see .env.example): DEEPSEEK_API_KEY, DEEPSEEK_BASE_URL,
FEISHU_APP_ID, FEISHU_APP_SECRET, FEISHU_ALLOWED_USERS,
OBS_ACCESS_KEY, OBS_SECRET_KEY, OBS_BUCKET, OBS_ENDPOINT, OBS_PREFIX,
HERMES_HOME.
