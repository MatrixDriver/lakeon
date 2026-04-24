---
name: reading_daily_reflection
description: Every night at 22:00, review today's reading sessions and produce a reflection.
version: v0.1
---

# reading/daily_reflection

**Trigger:** main.py `_CRON_TASKS` cron `0 22 * * *` (Asia/Shanghai = UTC+8 → set TZ env).

**Flow:**
1. `log.list_sessions(type="reading", since="24h")`. If empty → skip.
2. Pack (title + key_points) of each into LLM prompt.
3. LLM writes ≤ 150 字 reflection.
4. Open `type=reflection` session with `parent_sessions = [...]`; one llm_completion turn; conclude+close.
5. Return reflection text — main.py pushes to feishu via `feishu_send_dm`.
