# KB Schema

This document tells the wiki agent how to maintain this knowledge base. The agent reads it at the start of every ingest / curate / lint run. You can edit it, and the agent may also co-evolve it over time.

## Scope

Describe what this KB is about and what is out of scope. The agent will use this to decide whether a new source belongs here, whether a concept deserves a page, and which existing pages to link into.

## Conventions

- **Language**: Write every page in Simplified Chinese (简体中文) regardless of source language.
- **Page size**: Aim for 800-2500 Chinese characters per page. Split a page if it grows past 3000.
- **Title style**: Clear noun phrases in Chinese. Avoid generic titles like "概述" or "介绍".
- **Wikilinks**: Use `[[Page Title]]` to cross-reference related pages; prefer exact title matches.
- **Citations**: Footnote-style `[^1]: source-filename, section` when making specific factual claims.

## Create vs Update Budget

- **Strongly prefer updating existing pages** over creating new ones. When in doubt, search first, then update.
- **Target per ingest**: touch 5-15 pages (including updates); create at most 2-3 new pages.
- **Only create a new page when** the concept is central to this KB's domain AND no existing page covers it AND it would answer a distinct user question.
- **Do not create pages for** widely-known generic concepts (e.g. 通用 AI 概念), off-topic tangents, or one-off facts that fit into an existing page.

## Log

Write a one-line entry to `log.md` for every ingest / curate / lint run via the `log_note` tool. Format: `[YYYY-MM-DD HH:MM] <op> | <short description>`.

## Self-maintenance

When you notice this schema is wrong, outdated, or missing rules that would have improved your work, call `update_page` on `schema.md` to fix it.
