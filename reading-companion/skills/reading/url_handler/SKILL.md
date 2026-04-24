---
name: reading_url_handler
description: When a URL is fed (CLI), fetch it, distill key points, link to past readings, write a reading session.
version: v0.1
---

# reading/url_handler

**Trigger:** CLI `python -m skills.reading.url_handler.cli <url>`
(future: feishu inbound when hermes exposes message hooks).

**Flow:**
1. Open `type=reading` session with trigger `{source, url, received_at, user_open_id}`.
2. `fetch.fetch_url(url)` → cleaned body. Attach raw_html as evidence.
3. `extract.extract(url, body, llm)` → title/key_points/keywords/quotes JSON.
   Attach as evidence; append `llm_completion` turn.
4. `related.find_related(log, keywords, since=30d)` → past reading sessions.
5. Write `conclusion.md` (title, URL, 要点, 相关阅读, 摘抄).
6. Push optional confirmation DM to Jacky via `feishu_send_dm`.
7. `session.close()`.

This skill consumes `agent_session_log` only — does not import any `skills/sre/*`.
