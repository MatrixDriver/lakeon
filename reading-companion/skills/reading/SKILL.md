---
name: reading
description: Reading companion — distill articles you read into commit log, link to past readings, reflect nightly.
category: true
---

# Reading companion

This category houses skills that help Jacky digest what he reads:

- **url_handler**: When a URL is fed (via CLI or future feishu inbound), fetch
  the page body, distill it via LLM, link it to past reading sessions, and
  write a `type=reading` session.
- **query_handler**: Answer free-form questions like "我最近读了什么关于
  agent commit log 的" by searching the reading sessions in commit log.
- **daily_reflection**: Cron at 22:00 — review today's reading sessions, write
  a `type=reflection` session, push the reflection text to Jacky on feishu.

All three skills consume `agent_session_log` only — they share zero code with
the SRE category.
