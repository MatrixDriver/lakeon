# Memory Module Endpoints Refactor — Design Spec

**Date:** 2026-03-25
**Status:** Approved
**Scope:** Align DBay memory service endpoints with neuromem-cloud; add developer memory types to extraction; add CLI + E2E tests

---

## Problem

Current DBay memory service has a single `/ingest` endpoint that stores pre-structured memories directly (no LLM extraction). This is equivalent to neuromem's `ingest_extracted` — it only supports Agent-Extract Mode and cannot serve clients without their own LLM. Additionally, `/digest` is unimplemented and there are no CLI commands or E2E tests for the memory module.

---

## Goals

1. Align endpoints with neuromem-cloud two-mode design
2. Extend extraction prompt with 3 developer memory types: `decision`, `rejection`, `convention`
3. Implement `/digest` with both modes
4. Add `dbay mem` CLI command group
5. Add E2E tests covering all memory operations

---

## Architecture Overview

Two modes, symmetrical for both ingest and digest:

```
Agent-Extract Mode (Agent 已有 LLM)        普通模式 (服务端 LLM)
─────────────────────────────────          ──────────────────────
POST /ingest {content}                     POST /ingest {content}
  → stores raw_message                       → stores raw_message
  → returns extraction_prompt                → calls LLM → extracts
  → 0 server LLM calls                       → auto-calls ingest_extracted
                                             → 1 server LLM call
POST /ingest_extracted {msg_id, data}
  → stores structured memories

POST /digest                               POST /digest
  → returns unreflected + prompt             → calls LLM → generates traits
  → 0 server LLM calls                       → 1-2 server LLM calls

POST /digest_extracted {traits}
  → stores traits, advances watermark
```

Mode selection: per memory base, stored in `MemoryBaseEntity.one_llm_mode` (Java side). Python service reads it from request header `X-One-Llm-Mode: true/false`.

**Canonical semantics:** `one_llm_mode = true` = **Agent-Extract Mode** (client has LLM, zero server LLM calls); `one_llm_mode = false` = **普通模式** (server calls LLM, default).

---

## 1. Schema Changes

### 1.1 Add `raw_messages` table

```sql
CREATE TABLE IF NOT EXISTS raw_messages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content     TEXT NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'user',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Added to `init_schema()` in `schema.py`.

### 1.2 Add `reflection_watermark` table

Tracks which memories have been reflected (digest watermark), mirroring neuromem design:

```sql
CREATE TABLE IF NOT EXISTS reflection_watermark (
    id              SERIAL PRIMARY KEY,
    last_reflected  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 1.3 `memories` CHECK constraint (already deployed)

```sql
CHECK (memory_type IN ('fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'))
```

---

## 2. Python Service Endpoint Changes

### 2.0 New Pydantic Request Models (`models.py`)

```python
class IngestRequest(BaseModel):
    content: str
    role: str = "user"
    auto_extract: Optional[bool] = None   # None = use X-One-Llm-Mode header default

class IngestExtractedItem(BaseModel):
    content: str
    importance: float = 0.5
    # type-specific optional fields:
    category: Optional[str] = None        # fact, procedural
    timestamp: Optional[str] = None       # episode
    rationale: Optional[str] = None       # decision
    project: Optional[str] = None         # decision, rejection, convention
    reason: Optional[str] = None          # rejection
    scope: Optional[str] = None           # convention

class IngestExtractedData(BaseModel):
    facts: List[IngestExtractedItem] = []
    episodes: List[IngestExtractedItem] = []
    procedural: List[IngestExtractedItem] = []
    decisions: List[IngestExtractedItem] = []
    rejections: List[IngestExtractedItem] = []
    conventions: List[IngestExtractedItem] = []

class IngestExtractedRequest(BaseModel):
    message_id: str
    data: IngestExtractedData

class DigestExtractedTrait(BaseModel):
    content: str
    category: Optional[str] = None
    importance: int = 5   # 1-10; only >= 7 stored

class DigestExtractedData(BaseModel):
    traits: List[DigestExtractedTrait] = []

class DigestExtractedRequest(BaseModel):
    data: DigestExtractedData
```

### 2.1 `POST /ingest` — changed behavior

**Before:** direct store of pre-structured memory
**After:** stores raw message, then diverges by mode

**Request:**
```json
{
  "content": "我们决定用 asyncpg，放弃 SQLAlchemy",
  "role": "user",
  "auto_extract": true
}
```

**Response (Agent-Extract, auto_extract=false):**
```json
{
  "message_id": "uuid",
  "extraction_required": true,
  "extraction_prompt": "..."
}
```

**Response (普通模式, auto_extract=true):**
```json
{
  "message_id": "uuid",
  "extraction_required": false,
  "status": "extracting"
}
```

Extraction runs asynchronously via `asyncio.create_task()` — the response returns immediately after storing the raw message (milliseconds). The background task calls LLM → parses → embeds → stores memories. Clients do not wait for extraction to complete. If they need to verify results, they can poll `/memories` or `/stats`.

`auto_extract` (ingest only, does not apply to digest) defaults to the space-level `one_llm_mode` setting passed via `X-One-Llm-Mode` header. When `X-One-Llm-Mode: false` (普通模式, default), `auto_extract` defaults to `true`. When `X-One-Llm-Mode: true` (Agent-Extract), `auto_extract` defaults to `false`.

### 2.2 `POST /ingest_extracted` — new endpoint

**Request:**
```json
{
  "message_id": "uuid",
  "data": {
    "facts":       [{"content": "...", "category": "work", "importance": 0.8}],
    "episodes":    [{"content": "...", "timestamp": "2026-03-25", "importance": 0.6}],
    "procedural":  [{"content": "...", "category": "workflow"}],
    "decisions":   [{"content": "选择 asyncpg", "rationale": "项目全异步", "project": "lakeon"}],
    "rejections":  [{"content": "不用 SQLAlchemy", "reason": "ORM 开销大", "project": "lakeon"}],
    "conventions": [{"content": "API 错误统一用 HTTPException", "scope": "architecture", "project": "lakeon"}]
  }
}
```

**Flow:**
1. Validate `message_id` exists in `raw_messages`
2. For each item in each type array: embed content → insert into `memories` with correct `memory_type` and `metadata`
3. Return counts

**Response:**
```json
{
  "facts_stored": 1,
  "episodes_stored": 0,
  "procedural_stored": 0,
  "decisions_stored": 1,
  "rejections_stored": 1,
  "conventions_stored": 0
}
```

### 2.3 `POST /recall` — unchanged

No changes. Already implements neuromem's hybrid search: vector cosine + BM25 text search + RRF merge (k=60). Already supports `memory_types` filter array and `access_count` tracking.

### 2.4 `POST /digest` — implement

**Agent-Extract mode response:**
```json
{
  "one_llm_mode": true,
  "unreflected_count": 12,
  "memories": [{"id": 1, "content": "...", "memory_type": "decision", ...}],
  "existing_traits": [{"id": 1, "content": "...", "trait_stage": "core"}],
  "reflection_prompt": "..."
}
```

**普通模式response:**
```json
{
  "one_llm_mode": false,
  "traits_generated": 3
}
```

Unreflected memories = memories with `created_at > last watermark`. Max 50 per digest call.

### 2.5 `POST /digest_extracted` — new endpoint

**Request:**
```json
{
  "data": {
    "traits": [
      {"content": "偏好轻量异步库，避免 ORM 开销", "category": "pattern", "importance": 8}
    ]
  }
}
```

**Flow:**
1. Filter traits with `importance >= 7`
2. Embed each trait content
3. Insert into `traits` table with `trait_stage = 'trend'`
4. Advance watermark: update `reflection_watermark` with current max `created_at` of memories

**Response:**
```json
{"traits_stored": 1}
```

---

## 3. Extraction & Digest Prompts (ported from neuromem-cloud)

New files: `memory/service/extraction_prompt.py`, `memory/service/digest_prompt.py`

Source: `~/code/neuromem-cloud/server/src/neuromem_cloud/extraction_prompt.py` and `digest_prompt.py`

### 3.1 Language detection (ported as-is)

```python
def detect_language(content: str) -> str:
    chinese_chars = sum(1 for c in content if "\u4e00" <= c <= "\u9fff")
    ratio = chinese_chars / len(content) if content else 0
    return "zh" if ratio > 0.1 else "en"
```

### 3.2 Extraction prompt — English (neuromem 4 types + DBay 3 new types)

The extraction prompt extends neuromem's 4 original types (facts, episodes, procedural, triples) with 3 new developer memory types (decisions, rejections, conventions). The full prompt includes `{content}` substitution:

```
Extract structured memory information from the user message that was just ingested.
Use the FULL conversation context available to you (not just the ingested content).
Return results strictly in JSON format.

The content that was ingested:
<user_content>
{content}
</user_content>

Extract the following memories:

1. **Facts**: Objective, persistent information about the user
   - Format: {"content": "...", "category": "...", "confidence": 0.0-1.0,
     "importance": 1-10, "entities": {"people": [], "locations": [], "topics": []},
     "emotion": {"valence": -1.0~1.0, "arousal": 0.0~1.0, "label": "..."} or null}
   - Categories: identity|work|skill|hobby|personal|education|location|health|
     relationship|finance|values
   - Captures ONLY persistent, reusable attributes. One-time events → Episodes.
   - Each fact must be atomic and self-contained (explicit subject, no pronouns).

2. **Episodes**: Events, experiences, temporal information
   - Format: {"content": "...", "timestamp": "ISO or null",
     "timestamp_original": "original expression or null", "people": [],
     "location": "...", "confidence": 0.0-1.0, "importance": 1-10,
     "entities": {...}, "emotion": {...} or null}

3. **Procedural**: Instructions, workflow steps, tool usage patterns
   - Format: {"content": "...", "category": "workflow|tool_usage|coding_pattern|
     configuration|process"}
   - HOW-TOs, not personal facts about the user.

4. **Triples**: Entity-relation triples
   - Format: {"subject": "...", "subject_type": "user|person|organization|location|
     skill|entity", "relation": "...", "object": "...", "object_type": "...",
     "content": "original description", "confidence": 0.0-1.0}
   - Skip triples with confidence < 0.6.

5. **Decisions**: Intentional choices involving comparison or trade-offs
   - Format: {"content": "选择 asyncpg 替代 SQLAlchemy", "rationale": "项目全异步，
     ORM 增加开销", "project": "lakeon" or null}
   - A decision is NOT a fact — it involves choosing between alternatives.
   - content must be self-contained, no pronouns.
   - project: infer from context if not explicit, or null.

6. **Rejections**: Explicitly excluded approaches or tools
   - Format: {"content": "不使用 Redis 缓存", "reason": "引入额外运维复杂度，
     当前阶段不合适", "project": "lakeon" or null}
   - Must have a reason. If no clear reason stated, do not extract as rejection.
   - rejection ≠ preference: rejection is an active, deliberate exclusion.

7. **Conventions**: Project rules, coding standards, architectural patterns
   - Format: {"content": "所有 API 错误统一用 HTTPException", "scope":
     "naming|style|architecture|testing|other", "project": "lakeon" or null}
   - Must be a prescriptive rule, not a one-time action.

Requirements:
- Only extract explicitly mentioned information
- Confidence represents extraction certainty (0.0-1.0)
- Return empty list if no information for a category
- Must return valid JSON only, no additional text

Return format (JSON only):
{"facts": [], "episodes": [], "procedural": [], "triples": [],
 "decisions": [], "rejections": [], "conventions": []}
```

### 3.3 Extraction prompt — Chinese

Same structure, Chinese text. Ported from neuromem's `_build_zh_extraction_prompt()` with 3 new types appended:

```
分析刚才存储的用户消息，提取结构化记忆信息。
利用你可用的完整对话上下文（不仅仅是存储的内容）。
请严格按照 JSON 格式返回结果。

[... neuromem 的 4 类原始 prompt (facts/episodes/procedural/triples) ...]

5. **Decisions（决策）**: 涉及比较或取舍的有意选择
   - 格式: {"content": "决策描述", "rationale": "理由", "project": "项目名或null"}
   - decision 不是 fact —— 必须涉及在多个选项之间的选择
   - content 必须自包含，禁止代词

6. **Rejections（排除）**: 明确拒绝的方案或工具
   - 格式: {"content": "被排除的方案", "reason": "排除理由", "project": "项目名或null"}
   - 必须有 reason，没有明确理由则不提取

7. **Conventions（约定）**: 项目规则、编码标准、架构模式
   - 格式: {"content": "约定内容", "scope": "naming|style|architecture|testing|other",
     "project": "项目名或null"}
   - 必须是规范性规则，不是一次性操作

返回格式（只返回 JSON）:
{"facts": [], "episodes": [], "procedural": [], "triples": [],
 "decisions": [], "rejections": [], "conventions": []}
```

### 3.4 Extraction rules

- **无代词**：content 必须是自包含的陈述，不得含"它"、"这个"、"他们"等代词
- **project 优先级**：① 调用方显式传入 → ② 对话中出现的项目名 → ③ null
- **decision vs fact**：decision 是有意做出的选择（涉及比较或取舍），fact 是客观信息
- **rejection vs preference**：rejection 是明确拒绝某方案，必须有 reason
- **convention vs procedural**：convention 是团队/项目层面的规则，procedural 是个人工作流

### 3.5 Digest prompt (ported from neuromem `digest_prompt.py`)

New file: `memory/service/digest_prompt.py`

The digest prompt generates behavioral pattern and summary traits from unreflected memories. Ported from neuromem's `_build_reflection_prompt()`:

```python
def build_digest_prompt(memories: list[dict], existing_traits: list[dict] | None = None) -> str:
    """Build reflection prompt for digest. Ported from neuromem."""
    existing_text = ""
    if existing_traits:
        recent = existing_traits[-20:]
        existing_lines = [f"- {t.get('content', '')}" for t in recent]
        existing_text = (
            f"\nExisting traits ({len(existing_traits)} total, showing last {len(recent)}):\n"
            + "\n".join(existing_lines) + "\n\n"
            + "STRICT deduplication rules:\n"
            + "- If a new trait expresses same/similar meaning as existing, SKIP it\n"
            + "- Only output NEW angles not yet captured\n"
            + '- If no new traits, return empty: {"traits": []}\n'
        )

    return f"""You are a memory analysis system. Based on the user's recent memories,
generate **incremental** behavioral pattern and summary traits.

{existing_text}Generation rules:
1. Each trait must synthesize MULTIPLE memories into deeper understanding
2. Categories:
   - pattern: Specific behavioral patterns with details
   - summary: Summary of recent experiences with temporal context
3. importance (1-10): 9-10 = core personality/values; 7-8 = useful patterns; below 7 = do NOT output
4. If no worthwhile new traits, return empty list

Return ONLY valid JSON:
{{"traits": [{{"content": "...", "category": "pattern|summary", "importance": 7}}]}}"""
```

The `build_digest_response()` function (for Agent-Extract mode REST response) formats memories as a numbered list with `[type]` prefix and wraps the prompt with instructions to call `digest_extracted`.

### 3.6 LLM client (new file: `memory/service/llm_client.py`)

Ported from neuromem's OpenAI-compatible LLM integration. Uses `httpx` for async calls:

```python
import httpx, os, json

CHAT_API_URL = os.getenv("CHAT_API_URL", "https://api.siliconflow.cn/v1")
CHAT_API_KEY = os.getenv("CHAT_API_KEY", os.getenv("EMBEDDING_API_KEY", ""))
CHAT_MODEL = os.getenv("CHAT_MODEL", "Qwen/Qwen2.5-7B-Instruct")

async def chat_extract(system_prompt: str, user_content: str) -> dict:
    """Call LLM to extract structured memories. Returns parsed JSON."""
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{CHAT_API_URL}/chat/completions",
            headers={"Authorization": f"Bearer {CHAT_API_KEY}"},
            json={"model": CHAT_MODEL, "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ], "temperature": 0.1, "response_format": {"type": "json_object"}},
        )
        resp.raise_for_status()
        text = resp.json()["choices"][0]["message"]["content"]
        return json.loads(text)
```

### 3.7 LLM configuration (env vars)

```
CHAT_API_URL   = https://api.siliconflow.cn/v1   (default, OpenAI-compatible)
CHAT_API_KEY   = ${EMBEDDING_API_KEY}             (reuse same key by default)
CHAT_MODEL     = Qwen/Qwen2.5-7B-Instruct         (fast, cheap)
```

Helm template adds these to `memory-service.yaml`, sourcing from `lakeon-secrets`.

### 3.8 Ingest flow — server-side extraction (普通模式, async)

Ported from neuromem `do_ingest()`. In 普通模式 (`auto_extract=true`):

1. Store raw message in `raw_messages` → get `message_id`
2. Fire `asyncio.create_task(background_extract(connstr, message_id, content))` — non-blocking
3. Return immediately: `{"message_id": "...", "status": "extracting"}`

**Background task (`background_extract`):**
1. Call `chat_extract()` with the extraction prompt + content
2. Parse JSON response into 7 type arrays (silently ignore `triples`)
3. For each extracted item: embed content → insert into `memories` with correct `memory_type` and `metadata`
4. Log success/failure; errors are logged, not raised to caller

This matches neuromem's async extraction pattern. The client gets a response in milliseconds; extraction completes in 5-15 seconds in the background.

### 3.9 Digest flow — server-side (普通模式)

Ported from neuromem `do_digest()`:

1. Read watermark from `reflection_watermark` table
2. Fetch unreflected memories (`created_at > watermark`, max 50)
3. Fetch existing traits for dedup context (last 20)
4. Call `chat_extract()` with the digest prompt + formatted memories
5. Filter traits with `importance >= 7`
6. Embed each trait → insert into `traits` table with `trait_stage = 'trend'`
7. Advance watermark
8. Return `traits_generated` count

### 3.10 Digest flow — Agent-Extract mode

Ported from neuromem `do_digest_one_llm()`:

1. Read watermark, fetch unreflected memories (max 50) + existing traits
2. Build digest prompt via `build_digest_prompt()`
3. Return `{memories, existing_traits, unreflected_count, reflection_prompt}` — no LLM call
4. Client LLM processes → calls `/digest_extracted`

### 3.11 Digest extracted flow

Ported from neuromem `do_digest_extracted()`:

1. Filter traits: `importance >= 7` (1-10 scale, distinct from memories' 0.0-1.0 importance) and non-empty content
2. Insert into `traits` table (not `memories` — DBay stores traits separately unlike neuromem). No embedding for traits — `traits` table has no `embedding` column and trait search is by stage/confidence ordering, not vector similarity.
3. Advance watermark: insert into `reflection_watermark` with max `created_at` of memories

### 3.12 Server-side extraction: handling `triples` in LLM response

The extraction prompt includes `triples` (type 4, ported from neuromem), but `IngestExtractedData` does not have a `triples` field (triple/graph extraction is out of scope per Section 9). When parsing the LLM JSON response in server-side extraction (Section 3.8), the `triples` key should be silently ignored, not raise an error. Pydantic's default `model_config` with `extra="ignore"` handles this.

---

## 4. Java MemoryController Changes

### 4.1 New proxy endpoints

```java
@PostMapping("/bases/{id}/ingest_extracted")
public Object ingestExtracted(HttpServletRequest req, @PathVariable String id,
                               @RequestBody Map<String, Object> body) { ... }

@PostMapping("/bases/{id}/digest_extracted")
public Object digestExtracted(HttpServletRequest req, @PathVariable String id,
                               @RequestBody Map<String, Object> body) { ... }
```

### 4.2 One-LLM-Mode header propagation

`MemoryService.proxyPost()` must look up the `MemoryBaseEntity` (via `memoryBaseRepository.findByIdAndTenantId()`) to read `oneLlmMode`, then add header `X-One-Llm-Mode: true/false` to all proxied requests. The current `proxyPost()` signature only receives `connstr` — it must be updated to accept the full entity (or the `oneLlmMode` boolean) as a parameter.

### 4.3 MemoryBaseEntity — add field

```java
@Column(name = "one_llm_mode")
private Boolean oneLlmMode = false;   // false = 普通模式 (default); true = Agent-Extract Mode
```

Migration: `V20__add_memory_one_llm_mode.sql`

```sql
ALTER TABLE memory_bases ADD COLUMN one_llm_mode BOOLEAN NOT NULL DEFAULT false;
```

### 4.4 `createBase()` — auto-provision backing database

**Current bug (pre-existing):** `MemoryService.createBase()` sets `status = "READY"` but never sets `databaseId`. Calls to `/ingest`, `/recall`, etc. immediately fail with 400 ("Memory base has no backing database").

**Fix:** `createBase()` must provision a Neon database, following the same async pattern as `DatabaseService.create()`:

1. Create a `DatabaseEntity` for the memory base (name derived from memory base name, tenant scoped)
2. Persist `MemoryBaseEntity` with `status = "PROVISIONING"` and `databaseId = db.getId()`
3. Call `provisioningService.provisionAsync(db.getId(), ...)` — this fires off async and returns immediately
4. Return the memory base with `status = "PROVISIONING"`; client polls until status is `"READY"`

```java
// In MemoryService.createBase():
DatabaseEntity db = databaseService.createForMemoryBase(base.getName(), tenantId);
entity.setDatabaseId(db.getId());
entity.setStatus("PROVISIONING");   // NOT "READY" — provisioning is async (~10-17s)
// provisionAsync fires inside databaseService.createForMemoryBase()
```

Clients must not call `/ingest`, `/recall`, etc. until status is `"READY"`. The Java controller should return 503 with a clear message if the base is still provisioning.

### 4.5 `toMemResponse()` — add `one_llm_mode` field

Add `one_llm_mode` boolean to the memory base response DTO so clients know which mode is active for a given base.

---

## 5. DbayClient — New Memory Methods

File: `dbay-cli/dbay_cli/client.py`

```python
# Memory Bases
def list_memory_bases(self) -> list
def create_memory_base(self, name: str, description: str = None, one_llm_mode: bool = False) -> dict
def get_memory_base(self, mem_id: str) -> dict
def delete_memory_base(self, mem_id: str) -> dict

# Memory Operations
def mem_ingest(self, mem_id: str, content: str, role: str = "user", auto_extract: bool = None) -> dict
def mem_ingest_extracted(self, mem_id: str, message_id: str, data: dict) -> dict
def mem_recall(self, mem_id: str, query: str, top_k: int = 10,
               memory_types: list = None) -> dict
def mem_list(self, mem_id: str, memory_type: str = None,
             offset: int = 0, limit: int = 20) -> dict
def mem_delete(self, mem_id: str, memory_id: int) -> dict
def mem_stats(self, mem_id: str) -> dict
def mem_digest(self, mem_id: str) -> dict
def mem_digest_extracted(self, mem_id: str, data: dict) -> dict
```

---

## 6. CLI `dbay mem` Command Group

New file: `dbay-cli/dbay_cli/commands/mem.py`
Registered in `main.py`: `app.add_typer(mem.app, name="mem", help="Memory base management")`

### Commands

```
dbay mem list                                     # List memory bases
dbay mem create <name> [--desc DESC] [--agent-extract]
dbay mem info <mem_id>
dbay mem delete <mem_id> [-y]

dbay mem ingest <mem_id> <content> [--role user|assistant] [--no-extract]
  # --no-extract forces Agent-Extract mode (returns extraction_prompt)

dbay mem ingest-extracted <mem_id> --message-id UUID --data '{"decisions":[...]}'

dbay mem recall <mem_id> <query> [--types fact,decision,...] [--limit 10]
dbay mem list-memories <mem_id> [--type TYPE] [--limit 20] [--offset 0]
dbay mem delete-memory <mem_id> <memory_id>

dbay mem stats <mem_id>
dbay mem digest <mem_id>
dbay mem digest-extracted <mem_id> --data '{"traits":[...]}'
```

---

## 7. E2E Tests

File: `tests/e2e/test_memory.py`

### 7.1 Fixtures

```python
@pytest.fixture(scope="module")
def mem_base(e2e_client):
    """Memory base in Agent-Extract mode (one_llm_mode=True). No server LLM calls."""
    base = e2e_client.create_memory_base(
        name=f"e2e-mem-{int(time.time())}", one_llm_mode=True
    )
    yield base
    try:
        e2e_client.delete_memory_base(base["id"])
    except Exception:
        pass

@pytest.fixture(scope="module")
def mem_base_server_mode(e2e_client):
    """Memory base in 普通模式 (one_llm_mode=False). Used for LLM-dependent tests."""
    base = e2e_client.create_memory_base(
        name=f"e2e-mem-server-{int(time.time())}", one_llm_mode=False
    )
    yield base
    try:
        e2e_client.delete_memory_base(base["id"])
    except Exception:
        pass

@pytest.fixture(scope="module")
def tenant_b_client():
    """A second tenant client for isolation tests. Uses _create_tenant_with_invite()."""
    from tests.e2e.conftest import _create_tenant_with_invite
    client = _create_tenant_with_invite(f"tenant-b-{int(time.time())}@e2e.test")
    yield client
    # teardown: delete tenant (best-effort)
```

**LLM-dependent tests** (`test_server_extract`, `test_server_extract_decision`, `test_digest_server`) are marked `@pytest.mark.llm`. These tests require a live LLM endpoint and are non-deterministic by nature — they assert structural presence (`decisions_stored >= 1`, `"asyncpg" in content`) rather than exact output. They are excluded from the default test run (`pytest -m "not llm"`) and run separately in CI with a real API key.

### 7.2 Test cases

| Test | Mode | Covers |
|------|------|--------|
| `test_base_crud` | — | create / get / list / delete |
| `test_agent_extract_fact` | Agent-Extract | ingest → ingest_extracted (fact) |
| `test_agent_extract_decision` | Agent-Extract | decision + metadata.rationale stored |
| `test_agent_extract_rejection` | Agent-Extract | rejection + metadata.reason stored |
| `test_agent_extract_convention` | Agent-Extract | convention + metadata.scope stored |
| `test_agent_extract_all_6_types` | Agent-Extract | ingest_extracted with all 6 types, verify counts |
| `test_server_extract` | 普通模式 | ingest raw content, LLM extracts, verify memories created |
| `test_server_extract_decision` | 普通模式 | conversation with clear decision → decision type extracted |
| `test_recall_basic` | — | recall returns relevant memories |
| `test_recall_type_filter` | — | memory_types=["decision"] filters correctly |
| `test_list_type_filter` | — | list with memory_type=rejection |
| `test_delete_memory` | — | delete single memory |
| `test_stats` | — | stats by_type counts match ingested |
| `test_digest_agent_extract` | Agent-Extract | digest returns prompt + unreflected memories |
| `test_digest_extracted` | Agent-Extract | digest_extracted stores traits |
| `test_digest_server` | 普通模式 | digest generates traits automatically |
| `test_multi_tenant_isolation` | — | tenant A cannot access tenant B's memories |

### 7.3 Server-extract test example

```python
def test_server_extract_decision(mem_base_server_mode, e2e_client):
    """Ingest a conversation containing a clear decision; verify LLM extracts it async."""
    result = e2e_client.mem_ingest(
        mem_base_server_mode["id"],
        content="我们讨论了用 asyncpg 还是 SQLAlchemy，最终决定用 asyncpg，因为项目是全异步的",
        auto_extract=True,
    )
    assert result["extraction_required"] is False
    assert result["status"] == "extracting"

    # Wait for async extraction to complete (poll with timeout)
    import time
    for _ in range(30):  # max 30s
        time.sleep(1)
        memories = e2e_client.mem_list(mem_base_server_mode["id"], memory_type="decision")
        if memories["total"] >= 1:
            break

    assert memories["total"] >= 1
    assert any("asyncpg" in m["content"] for m in memories["memories"])
```

---

## 8. Helm / Deploy Changes

### 8.1 `memory-service.yaml` — add LLM env vars

```yaml
- name: CHAT_API_URL
  value: {{ .Values.memory.chatApiUrl | default "https://api.siliconflow.cn/v1" }}
- name: CHAT_MODEL
  value: {{ .Values.memory.chatModel | default "Qwen/Qwen2.5-7B-Instruct" }}
- name: CHAT_API_KEY
  valueFrom:
    secretKeyRef:
      name: lakeon-secrets
      key: embedding-api-key   # reuse same key
      optional: true
```

### 8.2 `values.yaml` defaults

```yaml
memory:
  chatApiUrl: ""      # defaults to siliconflow in template
  chatModel: ""       # defaults to Qwen2.5-7B in template
```

---

## 9. Not in Scope

- Hybrid encryption (Form 2) → Future Work (see ROADMAP.md)
- Digest in Agent-Extract mode for `/digest` (reflection_prompt generation) requires neuromem SDK's digest_prompt.py — implement minimally: return last 50 unreflected memories with a hardcoded prompt template
- Triple/graph extraction (neuromem's 4th type) → existing `graph` endpoints handle this separately; not added to extraction prompt now
- MCP tools for memory → separate future work
