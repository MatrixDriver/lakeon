# Developer Memory Types — Design Spec

**Date:** 2026-03-25
**Status:** Approved
**Scope:** Add `decision`, `rejection`, `convention` memory types to existing memory module

## Problem

Developers using AI agents (Claude Code, OpenClaw) suffer from:
- **Cross-session amnesia** — decisions made in one session are forgotten in the next
- **Repeated rejected suggestions** — agent keeps proposing approaches the user explicitly rejected
- **Convention re-explanation** — project conventions must be restated every session

## Solution

Add a CHECK constraint on the `memories` table `memory_type` column (currently VARCHAR(20) with no constraint) to enforce valid types, including three new developer types. No new tables, no new columns — developer-specific fields stored in the existing `metadata` JSONB column.

## New Memory Types

| Type | Purpose | Example |
|------|---------|---------|
| `decision` | Architecture/tech choice with reasoning | "Choose asyncpg over SQLAlchemy because project is fully async" |
| `rejection` | Explicitly excluded approach with reason | "No Redis — adds operational complexity at this stage" |
| `convention` | Project naming/style/architecture rule | "All API errors use HTTPException, no custom exception classes" |

All values fit within the existing VARCHAR(20) limit (longest: `convention` = 10 chars).

## Metadata Conventions

Each type stores structured data in the existing `metadata` JSONB column:

```json
// decision
{"rationale": "Project is fully async, ORM adds overhead", "project": "lakeon"}

// rejection
{"reason": "Adds operational complexity at this stage", "project": "lakeon"}

// convention
{"scope": "naming", "project": "lakeon"}
// scope values: naming | style | architecture | testing | other
```

`project` is optional in all types. `rationale`/`reason`/`scope` are recommended but not enforced at DB level.

## Changes

### 1. Python schema.py — Add CHECK constraint in `init_schema()`

Currently `memory_type` is `VARCHAR(20) NOT NULL` with no CHECK constraint. Add one:

```sql
ALTER TABLE memories DROP CONSTRAINT IF EXISTS memories_memory_type_check;
ALTER TABLE memories ADD CONSTRAINT memories_memory_type_check
  CHECK (memory_type IN ('fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'));
```

Applied in `init_schema()` (the existing schema initialization function) so both fresh and existing deployments get the constraint.

### 2. Python models.py — Add Literal validation on IngestRequest

The `IngestRequest` model at `models.py:46` currently has `memory_type: str = "fact"`. Update to use `Literal` for early validation with clear error messages before the DB rejects it:

```python
memory_type: Literal['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'] = "fact"
```

### 3. Python engine.py — No logic changes needed

- `ingest()` already accepts arbitrary `memory_type` string and `metadata` dict
- `recall()` already supports `memory_types` list filter
- Pydantic + CHECK constraint handle validation

### 4. Frontend memory.ts — Add type values

At `memory.ts:42`, the `MemoryItem` interface has inline union type:

```typescript
memory_type: 'fact' | 'episode' | 'procedural' | 'decision' | 'rejection' | 'convention'
```

### 5. Frontend MemoryBaseDetail.vue — Filter dropdown + tag colors + metadata display

- Add 3 options to the memory type filter dropdown
- Replace the existing ternary chain for tag colors (lines ~91, ~133) with a lookup map:

```typescript
const typeColors: Record<string, string> = {
  fact: 'blue', episode: 'purple', procedural: 'orange',
  decision: 'cyan', rejection: 'red', convention: 'green',
}
```

- In the memory detail dialog, render `metadata` fields when present (rationale, reason, scope) so users can see decision reasoning

### 6. No changes needed

- **Java MemoryController** — already transparently proxies `memory_type` to Python service
- **SDK** — already accepts arbitrary `memory_type` string parameter
- **MCP tools** — separate future work

## Testing

- Verify ingest with each new type succeeds
- Verify ingest with invalid type is rejected (Pydantic validation error)
- Verify recall with `memory_types=["decision"]` filter returns only decisions
- Verify recall without filter returns all types including new ones
- Verify frontend filter dropdown shows new types
- Verify metadata fields (rationale, reason, scope) are stored and rendered correctly

## Known Limitations

- `recallMemories` API call in frontend does not pass `memory_types` filter — type filter only applies to list view, not search. Pre-existing issue, not addressed here.

## Future Work (Not in scope)

- MCP tools: `zhixing_record_decision`, `zhixing_record_rejection`
- Developer Trait dimensions (stack_profile, architecture_style, etc.)
- Auto-capture: LLM-driven extraction of decisions from conversation
- L0/L1/L2 layered context loading
- PostCompact hook for Claude Code
