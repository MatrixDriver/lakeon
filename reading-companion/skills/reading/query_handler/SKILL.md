---
name: reading_query_handler
description: Answer free-form questions about reading history by searching commit log.
version: v0.1
---

# reading/query_handler

**Trigger:** CLI `python -m skills.reading.query_handler.cli "<question>"`
(future: feishu inbound).

**Flow:**
1. Cheap keyword extraction from the question.
2. `log.search_text(term, type="reading")` per term; merge + dedupe.
3. Fall back to "5 most recent readings" if no hits.
4. Pass hits (title + snippet + date) to LLM with `query_prompt.md`.
5. Return answer text. Does **NOT** open a session — recall is one-shot.
