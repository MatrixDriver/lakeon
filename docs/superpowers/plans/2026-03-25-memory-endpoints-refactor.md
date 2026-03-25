# Memory Module Endpoints Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align DBay memory service endpoints with neuromem-cloud two-mode design; add LLM extraction, digest, CLI commands, and E2E tests.

**Architecture:** Python memory microservice gets 3 new endpoints (`/ingest` rewrite, `/ingest_extracted`, `/digest_extracted`) plus 2 new prompt files and an LLM client. Java API adds proxy endpoints, `one_llm_mode` field, and database auto-provisioning for memory bases. CLI gets `dbay mem` command group. E2E tests cover all operations via CLI client methods.

**Tech Stack:** Python 3.11 / FastAPI / psycopg2 / httpx, Java 17 / Spring Boot 3.3.5 / Flyway, Typer CLI, pytest E2E

**Spec:** `docs/superpowers/specs/2026-03-25-memory-endpoints-refactor-design.md`

---

## File Structure

### Python service (`memory/service/`)
| File | Action | Responsibility |
|------|--------|----------------|
| `models.py` | Modify | Add new Pydantic request models (IngestRequest v2, IngestExtractedRequest, DigestExtractedRequest) |
| `schema.py` | Modify | Add `raw_messages` and `reflection_watermark` tables to `init_schema()` |
| `extraction_prompt.py` | Create | EN/ZH extraction prompts with 7 memory types (ported from neuromem + 3 new) |
| `digest_prompt.py` | Create | Reflection prompt for digest (ported from neuromem) |
| `llm_client.py` | Create | OpenAI-compatible LLM client for server-side extraction |
| `main.py` | Modify | Rewrite `/ingest`, implement `/ingest_extracted`, `/digest`, `/digest_extracted` |
| `engine.py` | Modify | Add `store_raw_message()`, `ingest_extracted()`, `digest_*()` functions |

### Java API (`lakeon-api/`)
| File | Action | Responsibility |
|------|--------|----------------|
| `V20__add_memory_one_llm_mode.sql` | Create | Add `one_llm_mode` column to `memory_bases` |
| `MemoryBaseEntity.java` | Modify | Add `oneLlmMode` field |
| `MemoryService.java` | Modify | Add `one_llm_mode` header propagation, database auto-provisioning |
| `MemoryController.java` | Modify | Add `/ingest_extracted`, `/digest_extracted` proxy endpoints, `one_llm_mode` in response |

### CLI (`dbay-cli/`)
| File | Action | Responsibility |
|------|--------|----------------|
| `client.py` | Modify | Add 12 memory client methods |
| `commands/mem.py` | Create | `dbay mem` command group (11 commands) |
| `main.py` | Modify | Register `mem` command group |

### Deploy
| File | Action | Responsibility |
|------|--------|----------------|
| `memory-service.yaml` | Modify | Add `CHAT_API_*` env vars |
| `values.yaml` (hwstaff) | Modify | Add `memory.chatApiUrl`, `memory.chatModel` |

### Tests
| File | Action | Responsibility |
|------|--------|----------------|
| `tests/e2e/test_memory.py` | Create | 17 E2E test cases |
| `tests/e2e/conftest.py` | Modify | Add memory base cleanup to teardown |

---

## Task 1: Python Schema — Add `raw_messages` and `reflection_watermark` tables

**Files:**
- Modify: `memory/service/schema.py`

- [ ] **Step 1: Add tables to SCHEMA_SQL**

In `memory/service/schema.py`, add these two tables to the `SCHEMA_SQL` string, after the `graph_edges` table and before the indexes:

```sql
CREATE TABLE IF NOT EXISTS raw_messages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content     TEXT NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'user',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reflection_watermark (
    id              SERIAL PRIMARY KEY,
    last_reflected  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Verify schema.py compiles**

Run: `cd memory/service && python -c "import schema; print('OK')"`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add memory/service/schema.py
git commit -m "feat(memory): add raw_messages and reflection_watermark tables to schema"
```

---

## Task 2: Python Models — New Pydantic request models

**Files:**
- Modify: `memory/service/models.py`

- [ ] **Step 1: Replace IngestRequest and add new models**

Replace the existing `IngestRequest` (lines 43-48) and add new models after `RecallRequest`:

```python
# Replace existing IngestRequest
class IngestRequest(BaseModel):
    """New ingest: raw content, mode-aware extraction."""
    content: str
    role: str = "user"
    auto_extract: Optional[bool] = None  # None = use X-One-Llm-Mode header default

    model_config = {"extra": "ignore"}


# Keep old model as LegacyIngestRequest for backward compat during migration
class LegacyIngestRequest(BaseModel):
    content: str
    role: str = "user"
    memory_type: Literal['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'] = "fact"
    importance: float = 0.5
    metadata: dict = {}


class IngestExtractedItem(BaseModel):
    content: str
    importance: float = 0.5
    category: Optional[str] = None
    timestamp: Optional[str] = None
    rationale: Optional[str] = None
    project: Optional[str] = None
    reason: Optional[str] = None
    scope: Optional[str] = None

    model_config = {"extra": "ignore"}


class IngestExtractedData(BaseModel):
    facts: list[IngestExtractedItem] = []
    episodes: list[IngestExtractedItem] = []
    procedural: list[IngestExtractedItem] = []
    decisions: list[IngestExtractedItem] = []
    rejections: list[IngestExtractedItem] = []
    conventions: list[IngestExtractedItem] = []

    model_config = {"extra": "ignore"}  # silently drop "triples" from LLM response


class IngestExtractedRequest(BaseModel):
    message_id: str
    data: IngestExtractedData


class DigestExtractedTrait(BaseModel):
    content: str
    category: Optional[str] = None
    importance: int = 5  # 1-10 scale; only >= 7 stored


class DigestExtractedData(BaseModel):
    traits: list[DigestExtractedTrait] = []


class DigestExtractedRequest(BaseModel):
    data: DigestExtractedData
```

- [ ] **Step 2: Verify models compile**

Run: `cd memory/service && python -c "from models import IngestRequest, IngestExtractedRequest, DigestExtractedRequest; print('OK')"`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add memory/service/models.py
git commit -m "feat(memory): add Pydantic models for ingest_extracted and digest_extracted"
```

---

## Task 3: Extraction Prompt — Port from neuromem with 3 new types

**Files:**
- Create: `memory/service/extraction_prompt.py`

- [ ] **Step 1: Create extraction_prompt.py**

Port from `~/code/neuromem-cloud/server/src/neuromem_cloud/extraction_prompt.py`. Add `decisions`, `rejections`, `conventions` types (types 5-7) to both EN and ZH prompts. Key functions:

```python
"""Extraction prompt builder — ported from neuromem-cloud, extended with developer memory types."""


def detect_language(content: str) -> str:
    """Detect language based on Chinese character ratio."""
    if not content:
        return "en"
    chinese_chars = sum(1 for c in content if "\u4e00" <= c <= "\u9fff")
    ratio = chinese_chars / len(content)
    return "zh" if ratio > 0.1 else "en"


def build_extraction_prompt(content: str) -> str:
    """Build extraction prompt for the given content. Auto-detects language."""
    lang = detect_language(content)
    if lang == "zh":
        return _build_zh_prompt(content)
    return _build_en_prompt(content)


def _build_en_prompt(content: str) -> str:
    return f"""Extract structured memory information from the user message.
Use the FULL conversation context available to you (not just the ingested content).
Return results strictly in JSON format.

The content that was ingested:
<user_content>
{content}
</user_content>

Extract the following memories:

1. **Facts**: Objective, persistent information about the user
   - Format: {{"content": "...", "category": "...", "confidence": 0.0-1.0, "importance": 1-10, "entities": {{"people": [], "locations": [], "topics": []}}, "emotion": {{"valence": -1.0, "arousal": 0.0, "label": "..."}} or null}}
   - Categories: identity|work|skill|hobby|personal|education|location|health|relationship|finance|values
   - Captures ONLY persistent, reusable attributes. One-time events → Episodes.
   - Each fact must be atomic and self-contained (explicit subject, no pronouns).

2. **Episodes**: Events, experiences, temporal information
   - Format: {{"content": "...", "timestamp": "ISO or null", "timestamp_original": "original expression or null", "people": [], "location": "...", "confidence": 0.0-1.0, "importance": 1-10, "entities": {{}}, "emotion": {{}} or null}}

3. **Procedural**: Instructions, workflow steps, tool usage patterns
   - Format: {{"content": "...", "category": "workflow|tool_usage|coding_pattern|configuration|process"}}
   - HOW-TOs, not personal facts about the user.

4. **Triples**: Entity-relation triples
   - Format: {{"subject": "...", "subject_type": "user|person|organization|location|skill|entity", "relation": "...", "object": "...", "object_type": "...", "content": "original description", "confidence": 0.0-1.0}}
   - Skip triples with confidence < 0.6.

5. **Decisions**: Intentional choices involving comparison or trade-offs
   - Format: {{"content": "chose X over Y", "rationale": "reason for choice", "project": "project name or null"}}
   - A decision is NOT a fact — it involves choosing between alternatives.
   - content must be self-contained, no pronouns.

6. **Rejections**: Explicitly excluded approaches or tools
   - Format: {{"content": "rejected X", "reason": "why it was excluded", "project": "project name or null"}}
   - Must have a reason. If no clear reason stated, do not extract as rejection.

7. **Conventions**: Project rules, coding standards, architectural patterns
   - Format: {{"content": "rule description", "scope": "naming|style|architecture|testing|other", "project": "project name or null"}}
   - Must be a prescriptive rule, not a one-time action.

Requirements:
- Only extract explicitly mentioned information
- Confidence represents extraction certainty (0.0-1.0)
- Return empty list if no information for a category
- Must return valid JSON only, no additional text

Return format (JSON only):
```json
{{"facts": [], "episodes": [], "procedural": [], "triples": [], "decisions": [], "rejections": [], "conventions": []}}
```"""


def _build_zh_prompt(content: str) -> str:
    return f"""分析刚才存储的用户消息，提取结构化记忆信息。
利用你可用的完整对话上下文（不仅仅是存储的内容）。
请严格按照 JSON 格式返回结果。

刚才存储的内容:
<user_content>
{content}
</user_content>

请提取以下记忆:

1. **Facts（事实）**: 用户及对话中提到的人物的持久性客观信息
   - 格式: {{"content": "事实描述", "category": "分类", "confidence": 0.0-1.0, "importance": 1-10, "entities": {{"people": [], "locations": [], "topics": []}}, "emotion": {{"valence": -1.0, "arousal": 0.0, "label": "情感描述"}} 或 null}}
   - category 可选: identity, work, skill, hobby, personal, education, location, health, relationship, finance, values
   - Facts 只捕获持久、可复用的属性（职业、爱好、技能、性格、关系、价值观、偏好）
   - 一次性事件应放入 Episodes，不要作为 Fact
   - 每个 fact 必须是原子的（一条信息），必须有明确的主语（禁止代词）

2. **Episodes（情景）**: 事件、经历、时间相关信息
   - 格式: {{"content": "事件描述", "timestamp": "ISO日期或null", "timestamp_original": "原始时间表达或null", "people": ["人名"], "location": "地点或null", "confidence": 0.0-1.0, "importance": 1-10, "entities": {{}}, "emotion": {{}} 或 null}}

3. **Procedural（流程）**: 指令、命令、工作流步骤、工具使用模式
   - 格式: {{"content": "流程描述", "category": "workflow|tool_usage|coding_pattern|configuration|process"}}
   - Procedural 记忆捕捉如何做某事——用户给 Agent 的指令、要运行的命令

4. **Triples（实体关系三元组）**: 结构化关系
   - 格式: {{"subject": "主体", "subject_type": "user|person|organization|location|skill|entity", "relation": "关系", "object": "客体", "object_type": "类型", "content": "原始描述", "confidence": 0.0-1.0}}
   - confidence < 0.6 的 triple 不要输出

5. **Decisions（决策）**: 涉及比较或取舍的有意选择
   - 格式: {{"content": "决策描述", "rationale": "理由", "project": "项目名或null"}}
   - decision 不是 fact —— 必须涉及在多个选项之间的选择
   - content 必须自包含，禁止代词

6. **Rejections（排除）**: 明确拒绝的方案或工具
   - 格式: {{"content": "被排除的方案", "reason": "排除理由", "project": "项目名或null"}}
   - 必须有 reason，没有明确理由则不提取

7. **Conventions（约定）**: 项目规则、编码标准、架构模式
   - 格式: {{"content": "约定内容", "scope": "naming|style|architecture|testing|other", "project": "项目名或null"}}
   - 必须是规范性规则，不是一次性操作

要求:
- 只提取明确提到的信息，不要推测
- confidence 表示提取的确信度 (0.0-1.0)
- 如果某类没有信息，返回空列表
- 必须返回有效的 JSON 格式，不要有其他文字说明

返回格式（只返回 JSON）:
```json
{{"facts": [], "episodes": [], "procedural": [], "triples": [], "decisions": [], "rejections": [], "conventions": []}}
```"""
```

- [ ] **Step 2: Verify it compiles**

Run: `cd memory/service && python -c "from extraction_prompt import build_extraction_prompt, detect_language; print(detect_language('你好')); print(len(build_extraction_prompt('test')))"`
Expected: `zh` and a number > 1000

- [ ] **Step 3: Commit**

```bash
git add memory/service/extraction_prompt.py
git commit -m "feat(memory): add extraction prompt with 7 memory types (ported from neuromem)"
```

---

## Task 4: Digest Prompt — Port from neuromem

**Files:**
- Create: `memory/service/digest_prompt.py`

- [ ] **Step 1: Create digest_prompt.py**

Port from `~/code/neuromem-cloud/server/src/neuromem_cloud/digest_prompt.py`:

```python
"""Digest (reflection) prompt builder — ported from neuromem-cloud."""


def build_digest_prompt(memories: list[dict], existing_traits: list[dict] | None = None) -> str:
    """Build reflection prompt for digest."""
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


def format_memories_for_digest(memories: list[dict]) -> str:
    """Format memories as numbered list for digest prompt context."""
    lines = []
    for i, m in enumerate(memories):
        content = m.get("content", "")
        mtype = m.get("memory_type", "unknown")
        lines.append(f"{i+1}. [{mtype}] {content}")
    return "\n".join(lines)
```

- [ ] **Step 2: Verify it compiles**

Run: `cd memory/service && python -c "from digest_prompt import build_digest_prompt, format_memories_for_digest; print(len(build_digest_prompt([])))"`
Expected: A number > 200

- [ ] **Step 3: Commit**

```bash
git add memory/service/digest_prompt.py
git commit -m "feat(memory): add digest prompt (ported from neuromem)"
```

---

## Task 5: LLM Client — OpenAI-compatible chat API

**Files:**
- Create: `memory/service/llm_client.py`

- [ ] **Step 1: Create llm_client.py**

```python
"""OpenAI-compatible LLM client for server-side memory extraction and digest."""
import json
import logging
import os

import httpx

logger = logging.getLogger(__name__)

CHAT_API_URL = os.getenv("CHAT_API_URL", "https://api.siliconflow.cn/v1")
CHAT_API_KEY = os.getenv("CHAT_API_KEY", os.getenv("EMBEDDING_API_KEY", ""))
CHAT_MODEL = os.getenv("CHAT_MODEL", "Qwen/Qwen2.5-7B-Instruct")


async def chat_extract(prompt: str) -> dict:
    """Call LLM with extraction/digest prompt. Returns parsed JSON dict."""
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{CHAT_API_URL}/chat/completions",
            headers={"Authorization": f"Bearer {CHAT_API_KEY}"},
            json={
                "model": CHAT_MODEL,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.1,
                "response_format": {"type": "json_object"},
            },
        )
        resp.raise_for_status()
        text = resp.json()["choices"][0]["message"]["content"]
        return json.loads(text)
```

- [ ] **Step 2: Verify it compiles**

Run: `cd memory/service && python -c "from llm_client import CHAT_API_URL, CHAT_MODEL; print(CHAT_API_URL, CHAT_MODEL)"`
Expected: prints the default URL and model

- [ ] **Step 3: Commit**

```bash
git add memory/service/llm_client.py
git commit -m "feat(memory): add OpenAI-compatible LLM client for extraction"
```

---

## Task 6: Python Engine — Add `store_raw_message`, `ingest_extracted`, digest functions

**Files:**
- Modify: `memory/service/engine.py`

- [ ] **Step 1: Add `store_raw_message` function**

Add after the `ingest()` function (line 28):

```python
async def store_raw_message(connstr: str, content: str, role: str) -> str:
    """Store raw message and return its UUID."""
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                INSERT INTO raw_messages (content, role) VALUES (%s, %s) RETURNING id
            """, (content, role))
            row = cur.fetchone()
            conn.commit()
            return str(row["id"])
    finally:
        conn.close()
```

- [ ] **Step 2: Add `ingest_extracted` function**

```python
async def ingest_extracted(connstr: str, message_id: str, data: dict) -> dict:
    """Store pre-extracted structured memories. Returns counts per type."""
    from models import IngestExtractedData
    parsed = IngestExtractedData.model_validate(data)

    conn = _connect(connstr)
    counts = {}
    try:
        with conn.cursor() as cur:
            # Validate message_id exists
            cur.execute("SELECT id FROM raw_messages WHERE id = %s", (message_id,))
            if not cur.fetchone():
                raise ValueError(f"Message not found: {message_id}")

            type_map = {
                "facts": ("fact", parsed.facts),
                "episodes": ("episode", parsed.episodes),
                "procedural": ("procedural", parsed.procedural),
                "decisions": ("decision", parsed.decisions),
                "rejections": ("rejection", parsed.rejections),
                "conventions": ("convention", parsed.conventions),
            }
            for key, (mem_type, items) in type_map.items():
                count = 0
                for item in items:
                    if not item.content:
                        continue
                    embedding = await get_embedding(item.content)
                    metadata = {k: v for k, v in item.model_dump().items()
                                if k not in ("content", "importance") and v is not None}
                    cur.execute("""
                        INSERT INTO memories (content, memory_type, importance, embedding, metadata, created_at)
                        VALUES (%s, %s, %s, %s::vector, %s, now())
                    """, (item.content, mem_type, item.importance, json.dumps(embedding), json.dumps(metadata)))
                    count += 1
                counts[f"{key}_stored"] = count
            conn.commit()
    finally:
        conn.close()
    return counts
```

- [ ] **Step 3: Add `background_extract` function**

```python
async def background_extract(connstr: str, message_id: str, content: str):
    """Background task: call LLM to extract memories, then store them."""
    import logging
    from extraction_prompt import build_extraction_prompt
    from llm_client import chat_extract

    logger = logging.getLogger(__name__)
    try:
        prompt = build_extraction_prompt(content)
        result = await chat_extract(prompt)
        counts = await ingest_extracted(connstr, message_id, result)
        logger.info("Background extraction for %s: %s", message_id, counts)
    except Exception as e:
        logger.error("Background extraction failed for %s: %s", message_id, e, exc_info=True)
```

- [ ] **Step 4: Add digest functions**

```python
async def get_unreflected_memories(connstr: str, limit: int = 50) -> tuple[list[dict], int]:
    """Get memories created after the last reflection watermark. Returns (memories, total_count)."""
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            # Read watermark
            cur.execute("SELECT last_reflected FROM reflection_watermark ORDER BY id DESC LIMIT 1")
            row = cur.fetchone()
            watermark = row["last_reflected"] if row else None

            # Count unreflected
            if watermark:
                cur.execute("SELECT count(*) as cnt FROM memories WHERE created_at > %s", (watermark,))
            else:
                cur.execute("SELECT count(*) as cnt FROM memories")
            total = cur.fetchone()["cnt"]

            if total == 0:
                return [], 0

            # Fetch memories
            if watermark:
                cur.execute("""
                    SELECT id, content, memory_type, metadata, created_at FROM memories
                    WHERE created_at > %s ORDER BY created_at ASC LIMIT %s
                """, (watermark, limit))
            else:
                cur.execute("""
                    SELECT id, content, memory_type, metadata, created_at FROM memories
                    ORDER BY created_at ASC LIMIT %s
                """, (limit,))
            memories = [dict(r) for r in cur.fetchall()]
            # Convert datetime to string for JSON serialization
            for m in memories:
                m["created_at"] = str(m["created_at"]) if m["created_at"] else None
            return memories, total
    finally:
        conn.close()


async def get_existing_traits(connstr: str, limit: int = 20) -> list[dict]:
    """Get recent traits for dedup context in digest."""
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                SELECT id, content, trait_stage FROM traits
                ORDER BY created_at DESC LIMIT %s
            """, (limit,))
            return [dict(r) for r in cur.fetchall()]
    finally:
        conn.close()


async def store_digest_traits(connstr: str, traits: list[dict]) -> int:
    """Store digest traits (importance >= 7) and advance watermark."""
    valid = [t for t in traits if t.get("content") and t.get("importance", 0) >= 7]
    if not valid:
        return 0

    conn = _connect(connstr)
    stored = 0
    try:
        with conn.cursor() as cur:
            for t in valid:
                cur.execute("""
                    INSERT INTO traits (content, trait_stage, trait_subtype, confidence, created_at)
                    VALUES (%s, 'trend', %s, %s, now())
                """, (t["content"], t.get("category", "pattern"), round(t.get("importance", 7) / 10.0, 2)))
                stored += 1

            # Advance watermark
            cur.execute("SELECT max(created_at) as max_ts FROM memories")
            row = cur.fetchone()
            if row and row[0]:
                cur.execute("INSERT INTO reflection_watermark (last_reflected) VALUES (%s)", (row[0],))

            conn.commit()
    finally:
        conn.close()
    return stored
```

- [ ] **Step 5: Add import for `get_embedding` at top of file**

The `get_embedding` import already exists (line 6). Verify no duplicate.

- [ ] **Step 6: Verify engine compiles**

Run: `cd memory/service && python -c "import engine; print('OK')"`
Expected: `OK`

- [ ] **Step 7: Commit**

```bash
git add memory/service/engine.py
git commit -m "feat(memory): add ingest_extracted, background_extract, and digest engine functions"
```

---

## Task 7: Python Endpoints — Rewrite `/ingest`, add `/ingest_extracted`, `/digest`, `/digest_extracted`

**Files:**
- Modify: `memory/service/main.py`

- [ ] **Step 1: Add new imports at top of main.py**

```python
import asyncio
from models import IngestRequest, IngestExtractedRequest, DigestExtractedRequest, RecallRequest
```

- [ ] **Step 2: Rewrite `/ingest` endpoint**

**Breaking change:** The old `/ingest` accepted `memory_type`, `importance`, `metadata` for direct storage. The new `/ingest` accepts `content`, `role`, `auto_extract` for raw message + extraction flow. The old direct-store behavior is replaced by `/ingest_extracted`. This is intentional — the old API has no callers outside of tests.

Replace the existing `ingest()` function (lines 16-20):

```python
@app.post("/ingest")
async def ingest(req: IngestRequest, x_database_connstr: str = Header(...),
                 x_one_llm_mode: str = Header("false")):
    # Determine auto_extract: default based on mode header
    one_llm = x_one_llm_mode.lower() == "true"
    auto_extract = req.auto_extract if req.auto_extract is not None else (not one_llm)

    # Store raw message
    message_id = await engine.store_raw_message(x_database_connstr, req.content, req.role)

    if auto_extract:
        # Fire background extraction — returns immediately
        asyncio.create_task(engine.background_extract(x_database_connstr, message_id, req.content))
        return {"message_id": message_id, "extraction_required": False, "status": "extracting"}
    else:
        # Agent-Extract mode: return prompt for client to extract
        from extraction_prompt import build_extraction_prompt
        prompt = build_extraction_prompt(req.content)
        return {"message_id": message_id, "extraction_required": True, "extraction_prompt": prompt}
```

- [ ] **Step 3: Add `/ingest_extracted` endpoint**

```python
@app.post("/ingest_extracted")
async def ingest_extracted(req: IngestExtractedRequest, x_database_connstr: str = Header(...)):
    counts = await engine.ingest_extracted(x_database_connstr, req.message_id, req.data.model_dump())
    return counts
```

- [ ] **Step 4: Rewrite `/digest` endpoint**

Replace the stub (lines 72-74):

```python
@app.post("/digest")
async def digest(x_database_connstr: str = Header(...),
                 x_one_llm_mode: str = Header("false")):
    one_llm = x_one_llm_mode.lower() == "true"
    memories, total = await engine.get_unreflected_memories(x_database_connstr)

    if total == 0:
        return {"one_llm_mode": one_llm, "unreflected_count": 0, "traits_generated": 0}

    if one_llm:
        # Agent-Extract: return memories + prompt for client LLM
        from digest_prompt import build_digest_prompt, format_memories_for_digest
        existing = await engine.get_existing_traits(x_database_connstr)
        prompt = build_digest_prompt(memories, existing)
        return {
            "one_llm_mode": True,
            "unreflected_count": total,
            "memories": memories,
            "existing_traits": existing,
            "reflection_prompt": prompt,
        }
    else:
        # Server-side: call LLM, store traits
        from digest_prompt import build_digest_prompt, format_memories_for_digest
        from llm_client import chat_extract
        existing = await engine.get_existing_traits(x_database_connstr)
        prompt = build_digest_prompt(memories, existing)
        formatted = format_memories_for_digest(memories)
        full_prompt = f"{formatted}\n\n{prompt}"
        result = await chat_extract(full_prompt)
        traits = result.get("traits", [])
        stored = await engine.store_digest_traits(x_database_connstr, traits)
        return {"one_llm_mode": False, "traits_generated": stored}
```

- [ ] **Step 5: Add `/digest_extracted` endpoint**

```python
@app.post("/digest_extracted")
async def digest_extracted(req: DigestExtractedRequest, x_database_connstr: str = Header(...)):
    traits = [t.model_dump() for t in req.data.traits]
    stored = await engine.store_digest_traits(x_database_connstr, traits)
    return {"traits_stored": stored}
```

- [ ] **Step 6: Verify main.py compiles**

Run: `cd memory/service && python -c "from main import app; print([r.path for r in app.routes])"`
Expected: list including `/ingest`, `/ingest_extracted`, `/digest`, `/digest_extracted`

- [ ] **Step 7: Commit**

```bash
git add memory/service/main.py
git commit -m "feat(memory): rewrite /ingest with async extraction, add /ingest_extracted /digest /digest_extracted"
```

---

## Task 8: Java — Flyway migration + Entity field

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V20__add_memory_one_llm_mode.sql`
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseEntity.java`

- [ ] **Step 1: Create V20 migration**

```sql
ALTER TABLE memory_bases ADD COLUMN one_llm_mode BOOLEAN NOT NULL DEFAULT false;
```

- [ ] **Step 2: Add field to MemoryBaseEntity**

After line 51 (`private String error;`), add:

```java
@Column(name = "one_llm_mode")
private Boolean oneLlmMode = false;
```

Add getter/setter after existing ones (after line 115):

```java
public Boolean getOneLlmMode() { return oneLlmMode; }
public void setOneLlmMode(Boolean oneLlmMode) { this.oneLlmMode = oneLlmMode; }
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q 2>&1 | tail -3`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V20__add_memory_one_llm_mode.sql
git add lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseEntity.java
git commit -m "feat(memory): add one_llm_mode column and entity field"
```

---

## Task 9: Java — MemoryService: header propagation + proxy endpoints

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryController.java`

- [ ] **Step 1: Update `proxyPost()` to inject `X-One-Llm-Mode` header**

Replace `proxyPost()` method (lines 59-68) in `MemoryService.java`:

```java
public Object proxyPost(String tenantId, String memId, String path, Object body) {
    // Look up entity to get oneLlmMode
    MemoryBaseEntity mem = getBase(tenantId, memId);
    String connstr = dbHelper.resolveConnstr(tenantId, memId);
    String url = props.getMemory().getServiceUrl() + path;
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Database-Connstr", connstr);
    headers.set("X-One-Llm-Mode", String.valueOf(Boolean.TRUE.equals(mem.getOneLlmMode())));
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<?> entity = new HttpEntity<>(body, headers);
    ResponseEntity<Object> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
    return resp.getBody();
}
```

- [ ] **Step 2: Add new proxy endpoints to MemoryController**

After the existing `digest()` endpoint (line 70), add:

```java
@PostMapping("/bases/{id}/ingest_extracted")
public Object ingestExtracted(HttpServletRequest req, @PathVariable String id,
                               @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyPost(tenant.getId(), id, "/ingest_extracted", body);
}

@PostMapping("/bases/{id}/digest_extracted")
public Object digestExtracted(HttpServletRequest req, @PathVariable String id,
                               @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyPost(tenant.getId(), id, "/digest_extracted", body);
}
```

- [ ] **Step 3: Add `one_llm_mode` to response and `createBase()` update**

In `MemoryController.toMemResponse()` (line 131, after `"error"`), add:

```java
map.put("one_llm_mode", Boolean.TRUE.equals(mem.getOneLlmMode()));
```

In `MemoryService.createBase()` (line 47, after `setEmbeddingModel`), the `one_llm_mode` is already set by the default field value (`false`). Update `createBase()` signature to accept it:

```java
public MemoryBaseEntity createBase(String tenantId, String name, String description,
                                    MemoryBaseType type, String embeddingModel, boolean oneLlmMode) {
    var entity = new MemoryBaseEntity();
    entity.setTenantId(tenantId);
    entity.setName(name);
    entity.setDescription(description);
    entity.setType(type);
    entity.setEmbeddingModel(embeddingModel != null ? embeddingModel : "BAAI/bge-m3");
    entity.setOneLlmMode(oneLlmMode);
    entity.setStatus("READY");
    entity = repository.save(entity);
    return entity;
}
```

Update the controller's `createBase()` call. **Important:** change `@RequestBody` type from `Map<String, String>` to `Map<String, Object>` because `one_llm_mode` is a boolean:

```java
@PostMapping("/bases")
public Map<String, Object> createBase(HttpServletRequest req, @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    boolean oneLlmMode = Boolean.TRUE.equals(body.get("one_llm_mode"));
    return toMemResponse(memoryService.createBase(
        tenant.getId(),
        (String) body.get("name"),
        (String) body.get("description"),
        MemoryBaseType.valueOf(body.getOrDefault("type", "BUILTIN").toString()),
        (String) body.get("embedding_model"),
        oneLlmMode
    ));
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && mvn compile -q 2>&1 | tail -3`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Run existing tests**

Run: `cd lakeon-api && mvn test -q 2>&1 | tail -5`
Expected: Tests pass (may need to update test mocks if `createBase()` signature changed)

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java
git add lakeon-api/src/main/java/com/lakeon/memory/MemoryController.java
git commit -m "feat(memory): add one_llm_mode header propagation and ingest_extracted/digest_extracted endpoints"
```

---

## Task 10: Java — Database auto-provisioning for memory bases

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java`

Note: This task addresses the pre-existing bug where `createBase()` never provisions a backing database, causing all memory operations to fail with 400.

- [ ] **Step 1: Inject DatabaseService and update createBase()**

Add to MemoryService constructor:

```java
private final DatabaseService databaseService;

public MemoryService(MemoryBaseRepository repository,
                     MemoryDbHelper dbHelper,
                     LakeonProperties props,
                     @org.springframework.context.annotation.Lazy DatabaseService databaseService) {
    this.repository = repository;
    this.dbHelper = dbHelper;
    this.props = props;
    this.databaseService = databaseService;
    this.restTemplate = new RestTemplate();
}
```

Update `createBase()` to provision a database. Note: `CreateDatabaseRequest` is a record with 4 params `(name, computeSize, suspendTimeout, storageLimitGb)`. `DatabaseResponse` is a class with getter methods (`.getId()`, not `.id()`).

```java
public MemoryBaseEntity createBase(String tenantId, String name, String description,
                                    MemoryBaseType type, String embeddingModel, boolean oneLlmMode) {
    // Create backing database via same flow as DatabaseService.create()
    var dbRequest = new CreateDatabaseRequest("mem_" + name, null, null, null);
    var tenant = new TenantEntity();
    tenant.setId(tenantId);
    DatabaseResponse dbResp = databaseService.create(tenant, dbRequest);

    var entity = new MemoryBaseEntity();
    entity.setTenantId(tenantId);
    entity.setName(name);
    entity.setDescription(description);
    entity.setType(type);
    entity.setEmbeddingModel(embeddingModel != null ? embeddingModel : "BAAI/bge-m3");
    entity.setOneLlmMode(oneLlmMode);
    entity.setDatabaseId(dbResp.getId());
    entity.setStatus("PROVISIONING");  // async provisioning takes ~10-17s
    entity = repository.save(entity);

    return entity;
}
```

- [ ] **Step 2: Wire status callback in MemoryDbHelper**

Instead of adding a separate callback (which requires modifying `DatabaseProvisioningService`), update `MemoryDbHelper.resolveConnstr()` to auto-sync the memory base status from the backing database status. This is simpler and avoids touching the provisioning service:

In `MemoryDbHelper.java`, after loading the `MemoryBaseEntity` (line 41-42), replace the status check (lines 43-45) with:

```java
if (!"READY".equals(mem.getStatus())) {
    // Check if backing database has become ready
    if (mem.getDatabaseId() != null) {
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(mem.getDatabaseId(), tenantId)
                .orElse(null);
        if (db != null && "ACTIVE".equals(db.getStatus().name())) {
            // Database is ready — sync memory base status
            mem.setStatus("READY");
            memoryBaseRepository.save(mem);
            log.info("Memory base {} status synced to READY (db={})", memId, mem.getDatabaseId());
        } else {
            throw new BadRequestException("Memory base is not ready. Current status: " + mem.getStatus());
        }
    } else {
        throw new BadRequestException("Memory base has no backing database");
    }
}
```

This avoids the need for a separate `onDatabaseReady()` callback — `resolveConnstr()` lazily syncs the status on first access after provisioning completes.

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q 2>&1 | tail -3`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && mvn compile -q 2>&1 | tail -3`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java
git commit -m "feat(memory): auto-provision backing database on memory base creation"
```

---

## Task 11: CLI Client — Add memory methods

**Files:**
- Modify: `dbay-cli/dbay_cli/client.py`

- [ ] **Step 1: Add memory base CRUD methods**

Add after the last method in `DbayClient`:

```python
# ── Memory Bases ──────────────────────────────────────────────
def list_memory_bases(self) -> list:
    return self._request("GET", "/memory/bases")

def create_memory_base(self, name: str, description: str = None,
                       one_llm_mode: bool = False) -> dict:
    body = {"name": name, "one_llm_mode": one_llm_mode}
    if description:
        body["description"] = description
    return self._request("POST", "/memory/bases", json=body)

def get_memory_base(self, mem_id: str) -> dict:
    return self._request("GET", f"/memory/bases/{mem_id}")

def delete_memory_base(self, mem_id: str) -> dict:
    return self._request("DELETE", f"/memory/bases/{mem_id}")
```

- [ ] **Step 2: Add memory operation methods**

```python
# ── Memory Operations ─────────────────────────────────────────
def mem_ingest(self, mem_id: str, content: str, role: str = "user",
               auto_extract: bool = None) -> dict:
    body: dict = {"content": content, "role": role}
    if auto_extract is not None:
        body["auto_extract"] = auto_extract
    return self._request("POST", f"/memory/bases/{mem_id}/ingest", json=body)

def mem_ingest_extracted(self, mem_id: str, message_id: str, data: dict) -> dict:
    return self._request("POST", f"/memory/bases/{mem_id}/ingest_extracted",
                         json={"message_id": message_id, "data": data})

def mem_recall(self, mem_id: str, query: str, top_k: int = 10,
               memory_types: list = None) -> dict:
    body: dict = {"query": query, "top_k": top_k}
    if memory_types:
        body["memory_types"] = memory_types
    return self._request("POST", f"/memory/bases/{mem_id}/recall", json=body)

def mem_list(self, mem_id: str, memory_type: str = None,
             offset: int = 0, limit: int = 20) -> dict:
    params = {"offset": str(offset), "limit": str(limit)}
    if memory_type:
        params["memory_type"] = memory_type
    return self._request("GET", f"/memory/bases/{mem_id}/memories", params=params)

def mem_delete(self, mem_id: str, memory_id: int) -> dict:
    return self._request("DELETE", f"/memory/bases/{mem_id}/memories/{memory_id}")

def mem_stats(self, mem_id: str) -> dict:
    return self._request("GET", f"/memory/bases/{mem_id}/stats")

def mem_digest(self, mem_id: str) -> dict:
    return self._request("POST", f"/memory/bases/{mem_id}/digest")

def mem_digest_extracted(self, mem_id: str, data: dict) -> dict:
    return self._request("POST", f"/memory/bases/{mem_id}/digest_extracted",
                         json={"data": data})
```

- [ ] **Step 3: Verify it compiles**

Run: `cd dbay-cli && python -c "from dbay_cli.client import DbayClient; print('OK')"`
Expected: `OK`

- [ ] **Step 4: Commit**

```bash
git add dbay-cli/dbay_cli/client.py
git commit -m "feat(memory): add 12 memory methods to DbayClient"
```

---

## Task 12: CLI Commands — `dbay mem` command group

**Files:**
- Create: `dbay-cli/dbay_cli/commands/mem.py`
- Modify: `dbay-cli/dbay_cli/main.py`

- [ ] **Step 1: Create `mem.py` command group**

```python
import json
import typer

app = typer.Typer()


def _client():
    from dbay_cli.config import get_endpoint, get as config_get
    from dbay_cli.client import DbayClient
    return DbayClient(endpoint=get_endpoint(), api_key=config_get("api_key"))


@app.command("list")
def list_bases():
    """List memory bases."""
    bases = _client().list_memory_bases()
    for b in bases:
        mode = "agent-extract" if b.get("one_llm_mode") else "normal"
        typer.echo(f"{b['id']}  {b['name']}  [{b['status']}]  mode={mode}")


@app.command("create")
def create(name: str, desc: str = typer.Option(None, "--desc"),
           agent_extract: bool = typer.Option(False, "--agent-extract")):
    """Create a memory base."""
    result = _client().create_memory_base(name, desc, one_llm_mode=agent_extract)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("info")
def info(mem_id: str):
    """Show memory base details."""
    result = _client().get_memory_base(mem_id)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("delete")
def delete(mem_id: str, yes: bool = typer.Option(False, "-y")):
    """Delete a memory base."""
    if not yes:
        typer.confirm(f"Delete memory base {mem_id}?", abort=True)
    _client().delete_memory_base(mem_id)
    typer.echo("Deleted.")


@app.command("ingest")
def ingest(mem_id: str, content: str,
           role: str = typer.Option("user", "--role"),
           no_extract: bool = typer.Option(False, "--no-extract")):
    """Ingest content into memory base."""
    auto_extract = False if no_extract else None
    result = _client().mem_ingest(mem_id, content, role, auto_extract)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("ingest-extracted")
def ingest_extracted(mem_id: str,
                     message_id: str = typer.Option(..., "--message-id"),
                     data: str = typer.Option(..., "--data")):
    """Store pre-extracted memories."""
    result = _client().mem_ingest_extracted(mem_id, message_id, json.loads(data))
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("recall")
def recall(mem_id: str, query: str,
           types: str = typer.Option(None, "--types"),
           limit: int = typer.Option(10, "--limit")):
    """Recall memories by semantic search."""
    memory_types = types.split(",") if types else None
    result = _client().mem_recall(mem_id, query, limit, memory_types)
    for m in result.get("memories", []):
        typer.echo(f"  [{m.get('memory_type', '?')}] {m.get('content', '')}")


@app.command("list-memories")
def list_memories(mem_id: str,
                  type: str = typer.Option(None, "--type"),
                  limit: int = typer.Option(20, "--limit"),
                  offset: int = typer.Option(0, "--offset")):
    """List memories in a base."""
    result = _client().mem_list(mem_id, type, offset, limit)
    typer.echo(f"Total: {result.get('total', 0)}")
    for m in result.get("memories", []):
        typer.echo(f"  #{m['id']} [{m['memory_type']}] {m['content'][:80]}")


@app.command("delete-memory")
def delete_memory(mem_id: str, memory_id: int):
    """Delete a single memory."""
    _client().mem_delete(mem_id, memory_id)
    typer.echo("Deleted.")


@app.command("stats")
def stats(mem_id: str):
    """Show memory statistics."""
    result = _client().mem_stats(mem_id)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("digest")
def digest(mem_id: str):
    """Run digest (reflection) on unreflected memories."""
    result = _client().mem_digest(mem_id)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("digest-extracted")
def digest_extracted(mem_id: str,
                     data: str = typer.Option(..., "--data")):
    """Store pre-extracted digest traits."""
    result = _client().mem_digest_extracted(mem_id, json.loads(data))
    typer.echo(json.dumps(result, indent=2, default=str))
```

- [ ] **Step 2: Register in main.py**

In `dbay-cli/dbay_cli/main.py`, add after line 5:

```python
from dbay_cli.commands import auth, db, branch, version, user, kb, datalake, mem
```

Add after line 13:

```python
app.add_typer(mem.app, name="mem", help="Memory base management")
```

- [ ] **Step 3: Verify CLI loads**

Run: `cd dbay-cli && python -m dbay_cli.main mem --help`
Expected: Shows mem subcommands (list, create, info, delete, ingest, ...)

- [ ] **Step 4: Commit**

```bash
git add dbay-cli/dbay_cli/commands/mem.py dbay-cli/dbay_cli/main.py
git commit -m "feat(memory): add dbay mem CLI command group (11 commands)"
```

---

## Task 13: Helm — Add LLM env vars to memory-service

**Files:**
- Modify: `deploy/helm/lakeon/templates/memory-service.yaml`
- Modify: `deploy/cce/sites/hwstaff/values.yaml`

- [ ] **Step 1: Add CHAT_API env vars to memory-service.yaml**

After the `EMBEDDING_MODEL` env var (line 38), add:

```yaml
            - name: CHAT_API_URL
              value: {{ .Values.memory.chatApiUrl | default "https://api.siliconflow.cn/v1" }}
            - name: CHAT_MODEL
              value: {{ .Values.memory.chatModel | default "Qwen/Qwen2.5-7B-Instruct" }}
            - name: CHAT_API_KEY
              valueFrom:
                secretKeyRef:
                  name: lakeon-secrets
                  key: embedding-api-key
                  optional: true
```

- [ ] **Step 2: Add defaults to hwstaff values.yaml**

In `deploy/cce/sites/hwstaff/values.yaml`, under the `memory:` block (line 221-223), add:

```yaml
memory:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory:0.1.1
  chatApiUrl: ""
  chatModel: ""
```

- [ ] **Step 3: Commit**

```bash
git add deploy/helm/lakeon/templates/memory-service.yaml deploy/cce/sites/hwstaff/values.yaml
git commit -m "feat(memory): add CHAT_API env vars to Helm memory-service template"
```

---

## Task 14: E2E Tests — Memory module

**Files:**
- Create: `tests/e2e/test_memory.py`
- Modify: `tests/e2e/conftest.py`

- [ ] **Step 1: Add memory cleanup to conftest.py**

In `tests/e2e/conftest.py`, add memory base cleanup to the `e2e_tenant` fixture teardown, after KB cleanup (line 128) and before database cleanup (line 130):

```python
    # Cleanup: delete all memory bases created during the session
    try:
        for mb in client.list_memory_bases():
            try:
                client.delete_memory_base(mb["id"])
            except Exception:
                pass
    except Exception:
        pass
```

- [ ] **Step 2: Create test_memory.py with fixtures**

```python
"""E2E tests for memory module."""
import json
import time
import pytest


@pytest.fixture(scope="module")
def e2e_client(e2e_tenant):
    return e2e_tenant["client"]


@pytest.fixture(scope="module")
def mem_base(e2e_client):
    """Memory base in Agent-Extract mode (one_llm_mode=True)."""
    base = e2e_client.create_memory_base(
        name=f"e2e-mem-{int(time.time())}", one_llm_mode=True
    )
    # Wait for provisioning
    for _ in range(30):
        info = e2e_client.get_memory_base(base["id"])
        if info["status"] == "READY":
            break
        time.sleep(1)
    yield info
    try:
        e2e_client.delete_memory_base(base["id"])
    except Exception:
        pass


@pytest.fixture(scope="module")
def mem_base_server_mode(e2e_client):
    """Memory base in 普通模式 (one_llm_mode=False)."""
    base = e2e_client.create_memory_base(
        name=f"e2e-mem-server-{int(time.time())}", one_llm_mode=False
    )
    for _ in range(30):
        info = e2e_client.get_memory_base(base["id"])
        if info["status"] == "READY":
            break
        time.sleep(1)
    yield info
    try:
        e2e_client.delete_memory_base(base["id"])
    except Exception:
        pass
```

- [ ] **Step 3: Add CRUD tests**

```python
def test_base_crud(e2e_client):
    """Create / get / list / delete memory base."""
    base = e2e_client.create_memory_base(name=f"crud-test-{int(time.time())}")
    assert base["name"].startswith("crud-test-")
    assert "id" in base

    # Wait for READY
    for _ in range(30):
        info = e2e_client.get_memory_base(base["id"])
        if info["status"] == "READY":
            break
        time.sleep(1)
    assert info["status"] == "READY"

    # List should include it
    bases = e2e_client.list_memory_bases()
    assert any(b["id"] == base["id"] for b in bases)

    # Delete
    e2e_client.delete_memory_base(base["id"])
```

- [ ] **Step 4: Add Agent-Extract ingest tests**

```python
def test_agent_extract_fact(mem_base, e2e_client):
    """Ingest → ingest_extracted (fact) in Agent-Extract mode."""
    result = e2e_client.mem_ingest(mem_base["id"], content="User is a software engineer")
    assert result["extraction_required"] is True
    assert "extraction_prompt" in result
    msg_id = result["message_id"]

    # Store extracted fact
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "facts": [{"content": "User is a software engineer", "category": "work", "importance": 0.8}]
    })
    assert counts["facts_stored"] == 1


def test_agent_extract_decision(mem_base, e2e_client):
    """Decision with metadata.rationale stored correctly."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Chose asyncpg over SQLAlchemy")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "decisions": [{"content": "Chose asyncpg over SQLAlchemy", "rationale": "Full async project", "project": "lakeon"}]
    })
    assert counts["decisions_stored"] == 1

    # Verify metadata
    memories = e2e_client.mem_list(mem_base["id"], memory_type="decision")
    assert memories["total"] >= 1
    decision = next(m for m in memories["memories"] if "asyncpg" in m["content"])
    assert decision["metadata"]["rationale"] == "Full async project"
    assert decision["metadata"]["project"] == "lakeon"


def test_agent_extract_rejection(mem_base, e2e_client):
    """Rejection with metadata.reason stored."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Rejected Redis")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "rejections": [{"content": "Rejected Redis caching", "reason": "Too much ops overhead", "project": "lakeon"}]
    })
    assert counts["rejections_stored"] == 1


def test_agent_extract_convention(mem_base, e2e_client):
    """Convention with metadata.scope stored."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Use HTTPException for errors")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "conventions": [{"content": "All API errors use HTTPException", "scope": "architecture", "project": "lakeon"}]
    })
    assert counts["conventions_stored"] == 1


def test_agent_extract_all_6_types(mem_base, e2e_client):
    """Ingest_extracted with all 6 types, verify counts."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Full extraction test")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "facts": [{"content": "Fact one"}],
        "episodes": [{"content": "Episode one"}],
        "procedural": [{"content": "Run pytest"}],
        "decisions": [{"content": "Decision one", "rationale": "Because"}],
        "rejections": [{"content": "Rejection one", "reason": "Bad fit"}],
        "conventions": [{"content": "Convention one", "scope": "style"}],
    })
    assert counts["facts_stored"] == 1
    assert counts["episodes_stored"] == 1
    assert counts["procedural_stored"] == 1
    assert counts["decisions_stored"] == 1
    assert counts["rejections_stored"] == 1
    assert counts["conventions_stored"] == 1
```

- [ ] **Step 5: Add recall and list tests**

```python
def test_recall_basic(mem_base, e2e_client):
    """Recall returns relevant memories."""
    result = e2e_client.mem_recall(mem_base["id"], query="asyncpg database choice")
    assert "memories" in result
    assert len(result["memories"]) > 0


def test_recall_type_filter(mem_base, e2e_client):
    """memory_types filter works."""
    result = e2e_client.mem_recall(mem_base["id"], query="asyncpg",
                                    memory_types=["decision"])
    for m in result["memories"]:
        assert m["memory_type"] == "decision"


def test_list_type_filter(mem_base, e2e_client):
    """List with memory_type filter."""
    result = e2e_client.mem_list(mem_base["id"], memory_type="rejection")
    assert result["total"] >= 1
    for m in result["memories"]:
        assert m["memory_type"] == "rejection"


def test_delete_memory(mem_base, e2e_client):
    """Delete a single memory."""
    # Ingest then delete
    result = e2e_client.mem_ingest(mem_base["id"], content="Temporary memory")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "facts": [{"content": "To be deleted"}]
    })
    assert counts["facts_stored"] == 1

    memories = e2e_client.mem_list(mem_base["id"])
    target = next(m for m in memories["memories"] if m["content"] == "To be deleted")
    e2e_client.mem_delete(mem_base["id"], target["id"])

    # Verify deleted
    memories_after = e2e_client.mem_list(mem_base["id"])
    assert not any(m["id"] == target["id"] for m in memories_after["memories"])


def test_stats(mem_base, e2e_client):
    """Stats by_type counts."""
    stats = e2e_client.mem_stats(mem_base["id"])
    assert stats["total"] > 0
    assert "decision" in stats["by_type"]
```

- [ ] **Step 6: Add digest tests**

```python
def test_digest_agent_extract(mem_base, e2e_client):
    """Digest in Agent-Extract mode returns prompt + unreflected memories."""
    result = e2e_client.mem_digest(mem_base["id"])
    assert result["one_llm_mode"] is True
    assert result["unreflected_count"] > 0
    assert "reflection_prompt" in result
    assert len(result["memories"]) > 0


def test_digest_extracted(mem_base, e2e_client):
    """digest_extracted stores traits."""
    result = e2e_client.mem_digest_extracted(mem_base["id"], {
        "traits": [
            {"content": "Prefers async libraries over ORMs", "category": "pattern", "importance": 8}
        ]
    })
    assert result["traits_stored"] == 1
```

- [ ] **Step 7: Add LLM-dependent tests (marked @pytest.mark.llm)**

```python
@pytest.mark.llm
def test_server_extract(mem_base_server_mode, e2e_client):
    """Ingest in 普通模式: async extraction produces memories."""
    result = e2e_client.mem_ingest(mem_base_server_mode["id"],
                                    content="I work as a data engineer at Google")
    assert result["extraction_required"] is False
    assert result["status"] == "extracting"

    # Poll for extraction to complete
    for _ in range(30):
        time.sleep(1)
        stats = e2e_client.mem_stats(mem_base_server_mode["id"])
        if stats["total"] > 0:
            break
    assert stats["total"] > 0


@pytest.mark.llm
def test_server_extract_decision(mem_base_server_mode, e2e_client):
    """Server extracts decision type from conversation."""
    result = e2e_client.mem_ingest(
        mem_base_server_mode["id"],
        content="我们讨论了用 asyncpg 还是 SQLAlchemy，最终决定用 asyncpg，因为项目是全异步的",
        auto_extract=True,
    )
    assert result["status"] == "extracting"

    for _ in range(30):
        time.sleep(1)
        memories = e2e_client.mem_list(mem_base_server_mode["id"], memory_type="decision")
        if memories["total"] >= 1:
            break
    assert memories["total"] >= 1
    assert any("asyncpg" in m["content"] for m in memories["memories"])


@pytest.mark.llm
def test_digest_server(mem_base_server_mode, e2e_client):
    """Digest in 普通模式 generates traits automatically."""
    result = e2e_client.mem_digest(mem_base_server_mode["id"])
    assert result["one_llm_mode"] is False
    # May or may not have generated traits depending on memory count
    assert "traits_generated" in result
```

- [ ] **Step 8: Add multi-tenant isolation test**

```python
def test_multi_tenant_isolation(e2e_client, e2e_tenant):
    """Tenant A cannot access tenant B's memories."""
    import random
    from tests.e2e.conftest import _create_tenant_with_invite, ENDPOINT, ADMIN_TOKEN

    ts = int(time.time())
    client_b, tenant_b = _create_tenant_with_invite(
        ENDPOINT, ADMIN_TOKEN,
        f"e2e-memb-{ts}", f"E2eTest@{ts}", f"Tenant B {ts}"
    )

    # Tenant A creates a memory base
    base_a = e2e_client.create_memory_base(name=f"isolation-a-{ts}")
    for _ in range(30):
        info = e2e_client.get_memory_base(base_a["id"])
        if info["status"] == "READY":
            break
        time.sleep(1)

    # Tenant B cannot access it
    from dbay_cli.client import DbayApiError
    with pytest.raises(DbayApiError) as exc:
        client_b.get_memory_base(base_a["id"])
    assert exc.value.status_code == 404

    # Cleanup
    try:
        e2e_client.delete_memory_base(base_a["id"])
    except Exception:
        pass
    try:
        from dbay_cli.client import DbayClient
        admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
        admin.admin_batch_delete_tenants([tenant_b.get("id")])
    except Exception:
        pass
```

- [ ] **Step 9: Register pytest marker**

Add to `pytest.ini` or `pyproject.toml`:

```ini
[tool:pytest]
markers =
    llm: tests requiring live LLM endpoint (deselect with -m "not llm")
```

- [ ] **Step 10: Commit**

```bash
git add tests/e2e/test_memory.py tests/e2e/conftest.py
git commit -m "feat(memory): add 17 E2E tests for memory module"
```

---

## Task 15: Build, Deploy, Verify

**Files:** No new files — uses existing deploy scripts.

- [ ] **Step 1: Build and push memory service image**

```bash
IMAGE_TAG=0.2.0 ./deploy/cce/build-and-push-memory.sh
```

Update `deploy/cce/sites/hwstaff/values.yaml`:
```yaml
memory:
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory:0.2.0
```

- [ ] **Step 2: Build and push API image**

```bash
# Bump tag in values.yaml first
IMAGE_TAG=0.9.50 ./deploy/cce/build-and-push-api.sh
```

- [ ] **Step 3: Deploy**

```bash
./deploy/cce/deploy.sh
```

- [ ] **Step 4: Verify with smoke test**

```bash
# Create a memory base
dbay mem create "test-refactor" --agent-extract
# Wait for READY
dbay mem info <mem_id>
# Ingest → should return extraction_prompt
dbay mem ingest <mem_id> "We chose FastAPI over Flask because async support"
# Store extracted
dbay mem ingest-extracted <mem_id> --message-id <uuid> --data '{"decisions":[{"content":"Chose FastAPI over Flask","rationale":"Async support","project":"test"}]}'
# Recall
dbay mem recall <mem_id> "FastAPI"
# Stats
dbay mem stats <mem_id>
# Digest
dbay mem digest <mem_id>
# Cleanup
dbay mem delete <mem_id> -y
```

- [ ] **Step 5: Run E2E tests**

```bash
# Agent-Extract tests only (no LLM required)
pytest tests/e2e/test_memory.py -m "not llm" -v

# Full suite including LLM tests
pytest tests/e2e/test_memory.py -v
```

- [ ] **Step 6: Commit deploy changes**

```bash
git add deploy/cce/sites/hwstaff/values.yaml
git commit -m "deploy: memory service 0.2.0 + API 0.9.50 with memory endpoints refactor"
```
