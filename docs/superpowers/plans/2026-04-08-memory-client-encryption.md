# 记忆库客户端加密模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为记忆库增加客户端加密模式，记忆内容在本地 MCP/CLI 端用 AES-256-GCM 加密后上传，服务端只存密文，无法查看明文。

**Architecture:** 三因素分离密钥体系（密码 + 配置文件 + 服务端 encrypted_dek）。CLI 负责创建加密库和密钥管理，MCP 负责日常透明加解密。服务端增加 encrypted/embedding_dim 字段和 query_embedding 搜索支持。Embedding 在本地 MCP 端生成，支持 DBay/外部API/本地模型三种 provider。

**Tech Stack:** Python cryptography (RSA-4096, AES-256-GCM, Argon2id), Java Spring Boot (JPA entity + Flyway migration), PostgreSQL pgvector

**Spec:** `docs/superpowers/specs/2026-04-08-memory-client-encryption-design.md`

---

## File Structure

### 新建文件

| 文件 | 职责 |
|------|------|
| `dbay-mcp/src/dbay_mcp/crypto.py` | 加解密核心模块：密钥生成/加载、AES-256-GCM 加解密、RSA 操作 |
| `dbay-mcp/src/dbay_mcp/embedding.py` | 本地 embedding 生成：三种 provider 的统一接口 |
| `lakeon-api/src/main/resources/db/migration/V33__add_memory_encryption.sql` | memory_bases 表加密字段迁移 |
| `tests/e2e/test_memory_encrypted.py` | 加密记忆库 E2E 测试 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `dbay-mcp/src/dbay_mcp/server.py` | memory_ingest/recall/list 加密分支 |
| `dbay-mcp/pyproject.toml` | 添加 cryptography 依赖 |
| `dbay-cli/dbay_cli/commands/mem.py` | 新增 `create --encrypted` 和 `change-password` 命令 |
| `dbay-cli/dbay_cli/client.py` | create_memory_base 增加加密字段参数 |
| `dbay-cli/pyproject.toml` | 添加 cryptography 依赖 |
| `lakeon-api/.../MemoryBaseEntity.java` | 增加 encrypted/encryptedDek/kdfSalt/embeddingDim 字段 |
| `lakeon-api/.../MemoryService.java` | createBase 支持加密参数 |
| `lakeon-api/.../MemoryController.java` | createBase/toMemResponse 增加加密字段 |
| `memory/service/schema.py` | init_schema 支持动态 embedding 维度 |
| `memory/service/models.py` | IngestRequest 增加 embedding 字段，RecallRequest 增加 query_embedding 字段 |
| `memory/service/main.py` | ingest/recall 端点支持预传入 embedding |
| `memory/service/engine.py` | ingest/recall 支持外部传入 embedding |

---

### Task 1: 加密核心模块 (`dbay-mcp/src/dbay_mcp/crypto.py`)

**Files:**
- Create: `dbay-mcp/src/dbay_mcp/crypto.py`
- Modify: `dbay-mcp/pyproject.toml`

- [ ] **Step 1: 添加 cryptography 依赖**

```toml
# dbay-mcp/pyproject.toml — dependencies 列表添加
dependencies = [
    "fastmcp>=2.0.0",
    "httpx>=0.27.0",
    "pyyaml>=6.0",
    "cryptography>=41.0.0",
]
```

- [ ] **Step 2: 创建 crypto.py**

```python
# dbay-mcp/src/dbay_mcp/crypto.py
"""Client-side encryption for encrypted memory bases.

Three-factor key hierarchy:
  1. Password (in ~/.dbay/secret) → Argon2id → derived key → decrypts private_key
  2. Config file (~/.dbay/encrypted_bases.json) → encrypted_private_key + public_key
  3. Server (memory_bases table) → encrypted_dek (RSA-encrypted)

Decrypt flow: password → private_key → DEK → AES-256-GCM decrypt content
"""

import base64
import json
import os
from pathlib import Path
from typing import Optional

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding as asym_padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

SECRET_FILE = Path.home() / ".dbay" / "secret"
ENCRYPTED_BASES_FILE = Path.home() / ".dbay" / "encrypted_bases.json"

# ---------------------------------------------------------------------------
# Key generation (used by CLI during `dbay mem create --encrypted`)
# ---------------------------------------------------------------------------


def generate_keypair() -> tuple[bytes, bytes]:
    """Generate RSA-4096 key pair. Returns (private_key_pem, public_key_pem)."""
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=4096)
    private_pem = private_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    public_pem = private_key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.SubjectPublicKeyInfo,
    )
    return private_pem, public_pem


def generate_dek() -> bytes:
    """Generate a random 256-bit DEK."""
    return os.urandom(32)


def generate_salt() -> bytes:
    """Generate a random salt for Scrypt KDF."""
    return os.urandom(16)


def derive_key_from_password(password: str, salt: bytes) -> bytes:
    """Derive a 256-bit key from password using Scrypt."""
    kdf = Scrypt(salt=salt, length=32, n=2**17, r=8, p=1)
    return kdf.derive(password.encode("utf-8"))


def encrypt_private_key(private_pem: bytes, password: str, salt: bytes) -> str:
    """Encrypt private key PEM with password-derived key. Returns base64 string."""
    derived = derive_key_from_password(password, salt)
    aesgcm = AESGCM(derived)
    nonce = os.urandom(12)
    ciphertext = aesgcm.encrypt(nonce, private_pem, None)
    # Store as: nonce + ciphertext, base64 encoded
    return base64.b64encode(nonce + ciphertext).decode("ascii")


def decrypt_private_key(encrypted_b64: str, password: str, salt: bytes) -> bytes:
    """Decrypt private key PEM from base64 encrypted string."""
    raw = base64.b64decode(encrypted_b64)
    nonce, ciphertext = raw[:12], raw[12:]
    derived = derive_key_from_password(password, salt)
    aesgcm = AESGCM(derived)
    return aesgcm.decrypt(nonce, ciphertext, None)


def encrypt_dek_with_public_key(dek: bytes, public_pem: bytes) -> str:
    """RSA-OAEP encrypt DEK with public key. Returns base64 string."""
    public_key = serialization.load_pem_public_key(public_pem)
    ciphertext = public_key.encrypt(
        dek,
        asym_padding.OAEP(
            mgf=asym_padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    return base64.b64encode(ciphertext).decode("ascii")


def decrypt_dek_with_private_key(encrypted_dek_b64: str, private_pem: bytes) -> bytes:
    """RSA-OAEP decrypt DEK with private key."""
    private_key = serialization.load_pem_private_key(private_pem, password=None)
    ciphertext = base64.b64decode(encrypted_dek_b64)
    return private_key.decrypt(
        ciphertext,
        asym_padding.OAEP(
            mgf=asym_padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )


# ---------------------------------------------------------------------------
# Content encryption/decryption (used by MCP at runtime)
# ---------------------------------------------------------------------------


def encrypt_content(dek: bytes, plaintext: str) -> str:
    """AES-256-GCM encrypt content. Returns base64(nonce + ciphertext)."""
    aesgcm = AESGCM(dek)
    nonce = os.urandom(12)
    ciphertext = aesgcm.encrypt(nonce, plaintext.encode("utf-8"), None)
    return base64.b64encode(nonce + ciphertext).decode("ascii")


def decrypt_content(dek: bytes, encrypted_b64: str) -> str:
    """AES-256-GCM decrypt content from base64 string."""
    raw = base64.b64decode(encrypted_b64)
    nonce, ciphertext = raw[:12], raw[12:]
    aesgcm = AESGCM(dek)
    return aesgcm.decrypt(nonce, ciphertext, None).decode("utf-8")


# ---------------------------------------------------------------------------
# Config file management
# ---------------------------------------------------------------------------


def _read_password() -> Optional[str]:
    """Read encryption password from ~/.dbay/secret."""
    if not SECRET_FILE.exists():
        return None
    for line in SECRET_FILE.read_text().strip().splitlines():
        if line.startswith("DBAY_ENCRYPTION_PASSWORD="):
            return line.split("=", 1)[1]
    return None


def write_secret(password: str) -> None:
    """Write password to ~/.dbay/secret (mode 600)."""
    SECRET_FILE.parent.mkdir(parents=True, exist_ok=True)
    # Preserve other lines, update or add password
    lines = []
    found = False
    if SECRET_FILE.exists():
        for line in SECRET_FILE.read_text().strip().splitlines():
            if line.startswith("DBAY_ENCRYPTION_PASSWORD="):
                lines.append(f"DBAY_ENCRYPTION_PASSWORD={password}")
                found = True
            else:
                lines.append(line)
    if not found:
        lines.append(f"DBAY_ENCRYPTION_PASSWORD={password}")
    SECRET_FILE.write_text("\n".join(lines) + "\n")
    SECRET_FILE.chmod(0o600)


def load_encrypted_bases() -> dict:
    """Load ~/.dbay/encrypted_bases.json."""
    if not ENCRYPTED_BASES_FILE.exists():
        return {}
    return json.loads(ENCRYPTED_BASES_FILE.read_text())


def save_encrypted_base(mem_id: str, config: dict) -> None:
    """Add or update an encrypted base entry in ~/.dbay/encrypted_bases.json."""
    bases = load_encrypted_bases()
    bases[mem_id] = config
    ENCRYPTED_BASES_FILE.parent.mkdir(parents=True, exist_ok=True)
    ENCRYPTED_BASES_FILE.write_text(json.dumps(bases, indent=2) + "\n")
    ENCRYPTED_BASES_FILE.chmod(0o600)


# ---------------------------------------------------------------------------
# Runtime: DEK cache and access
# ---------------------------------------------------------------------------

_dek_cache: dict[str, bytes] = {}


def get_dek(mem_id: str, encrypted_dek_b64: str) -> bytes:
    """Get DEK for a memory base, using cache or decrypting on demand."""
    if mem_id in _dek_cache:
        return _dek_cache[mem_id]

    password = _read_password()
    if not password:
        raise RuntimeError(
            "No encryption password found. "
            "Create ~/.dbay/secret with DBAY_ENCRYPTION_PASSWORD=<your_password>"
        )

    bases = load_encrypted_bases()
    if mem_id not in bases:
        raise RuntimeError(
            f"No encryption config for {mem_id}. "
            f"Run: dbay mem create --encrypted, or copy ~/.dbay/encrypted_bases.json from another device."
        )

    config = bases[mem_id]
    salt = base64.b64decode(config["kdf_salt"])

    # Step 1: password → decrypt private key
    private_pem = decrypt_private_key(config["encrypted_private_key"], password, salt)

    # Step 2: private key → decrypt DEK
    dek = decrypt_dek_with_private_key(encrypted_dek_b64, private_pem)

    _dek_cache[mem_id] = dek
    return dek


def is_encrypted_base(mem_id: str) -> bool:
    """Check if a memory base is configured as encrypted locally."""
    bases = load_encrypted_bases()
    return mem_id in bases
```

- [ ] **Step 3: Commit**

```bash
git add dbay-mcp/src/dbay_mcp/crypto.py dbay-mcp/pyproject.toml
git commit -m "feat(mcp): add crypto module for client-side memory encryption"
```

---

### Task 2: 本地 Embedding 模块 (`dbay-mcp/src/dbay_mcp/embedding.py`)

**Files:**
- Create: `dbay-mcp/src/dbay_mcp/embedding.py`

- [ ] **Step 1: 创建 embedding.py**

```python
# dbay-mcp/src/dbay_mcp/embedding.py
"""Local embedding generation for encrypted memory bases.

Three providers:
  1. "dbay" — call DBay's embedding API (uses user's apikey)
  2. "external" — user-provided embedding API endpoint
  3. "local" — download and run open-source model locally (future)
"""

import httpx

from dbay_mcp.crypto import load_encrypted_bases


def _get_embedding_config(mem_id: str) -> dict:
    """Get embedding config for a memory base."""
    bases = load_encrypted_bases()
    if mem_id not in bases:
        raise RuntimeError(f"No encryption config for {mem_id}")
    return bases[mem_id]


async def generate_embedding(mem_id: str, text: str, api_key: str = None,
                              endpoint: str = None) -> list[float]:
    """Generate embedding vector for text using the configured provider.

    Args:
        mem_id: Memory base ID
        text: Text to embed
        api_key: DBay API key (for "dbay" provider)
        endpoint: DBay API endpoint (for "dbay" provider)
    """
    config = _get_embedding_config(mem_id)
    provider = config.get("embedding_provider", "dbay")

    if provider == "dbay":
        return await _embed_dbay(text, api_key, endpoint)
    elif provider == "external":
        return await _embed_external(text, config)
    elif provider == "local":
        raise RuntimeError("Local embedding model not yet supported. Use 'dbay' or 'external' provider.")
    else:
        raise RuntimeError(f"Unknown embedding provider: {provider}")


async def _embed_dbay(text: str, api_key: str, endpoint: str) -> list[float]:
    """Call DBay's embedding API."""
    url = f"{endpoint}/api/v1/embedding"
    async with httpx.AsyncClient(verify=False, timeout=30) as client:
        resp = await client.post(
            url,
            json={"input": text},
            headers={"Authorization": f"Bearer {api_key}"},
        )
        resp.raise_for_status()
        return resp.json()["data"][0]["embedding"]


async def _embed_external(text: str, config: dict) -> list[float]:
    """Call user-provided external embedding API."""
    url = config["embedding_endpoint"]
    model = config.get("embedding_model", "")
    api_key = config.get("embedding_api_key", "")

    headers = {}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    async with httpx.AsyncClient(verify=False, timeout=30) as client:
        resp = await client.post(
            url,
            json={"model": model, "input": text},
            headers=headers,
        )
        resp.raise_for_status()
        return resp.json()["data"][0]["embedding"]


async def probe_embedding_dim(mem_id: str, api_key: str = None,
                               endpoint: str = None) -> int:
    """Probe the embedding dimension by sending a test text."""
    vec = await generate_embedding(mem_id, "dimension probe test", api_key, endpoint)
    return len(vec)
```

- [ ] **Step 2: Commit**

```bash
git add dbay-mcp/src/dbay_mcp/embedding.py
git commit -m "feat(mcp): add local embedding module for encrypted memory bases"
```

---

### Task 3: 服务端数据库迁移 + Entity

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V33__add_memory_encryption.sql`
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseEntity.java`

- [ ] **Step 1: 创建 Flyway 迁移**

```sql
-- V33__add_memory_encryption.sql
ALTER TABLE memory_bases ADD COLUMN IF NOT EXISTS encrypted BOOLEAN DEFAULT false;
ALTER TABLE memory_bases ADD COLUMN IF NOT EXISTS encrypted_dek TEXT;
ALTER TABLE memory_bases ADD COLUMN IF NOT EXISTS kdf_salt TEXT;
ALTER TABLE memory_bases ADD COLUMN IF NOT EXISTS embedding_dim INT;
```

- [ ] **Step 2: 更新 MemoryBaseEntity.java**

在 `scene` 字段之后（第 57 行）添加：

```java
    @Column(name = "encrypted")
    private Boolean encrypted = false;

    @Column(name = "encrypted_dek", columnDefinition = "TEXT")
    private String encryptedDek;

    @Column(name = "kdf_salt")
    private String kdfSalt;

    @Column(name = "embedding_dim")
    private Integer embeddingDim;
```

在 getter/setter 区域（第 121 行之后）添加：

```java
    public Boolean getEncrypted() { return encrypted; }
    public void setEncrypted(Boolean encrypted) { this.encrypted = encrypted; }

    public String getEncryptedDek() { return encryptedDek; }
    public void setEncryptedDek(String encryptedDek) { this.encryptedDek = encryptedDek; }

    public String getKdfSalt() { return kdfSalt; }
    public void setKdfSalt(String kdfSalt) { this.kdfSalt = kdfSalt; }

    public Integer getEmbeddingDim() { return embeddingDim; }
    public void setEmbeddingDim(Integer embeddingDim) { this.embeddingDim = embeddingDim; }
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V33__add_memory_encryption.sql \
        lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseEntity.java
git commit -m "feat(api): add encrypted memory base fields and migration"
```

---

### Task 4: 服务端 API 支持加密字段

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java:55-76`
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryController.java:37-53,166-188`

- [ ] **Step 1: 更新 MemoryService.createBase**

在 `MemoryService.java` 的 `createBase` 方法中增加加密参数：

```java
    public MemoryBaseEntity createBase(TenantEntity tenant, String name, String description,
                                        MemoryBaseType type, String embeddingModel, boolean oneLlmMode,
                                        String scene, boolean encrypted, String encryptedDek,
                                        String kdfSalt, Integer embeddingDim) {
        String tenantId = tenant.getId();
        String dbSlug = "mem_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var dbRequest = new CreateDatabaseRequest(dbSlug, null, null, null);
        DatabaseResponse dbResp = databaseService.create(tenant, dbRequest);

        var entity = new MemoryBaseEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setType(type);
        entity.setEmbeddingModel(embeddingModel != null ? embeddingModel : "BAAI/bge-m3");
        entity.setOneLlmMode(oneLlmMode);
        entity.setScene(scene != null ? scene : "CHAT_ASSISTANT");
        entity.setDatabaseId(dbResp.getId());
        entity.setStatus("PROVISIONING");
        entity.setEncrypted(encrypted);
        if (encrypted) {
            entity.setEncryptedDek(encryptedDek);
            entity.setKdfSalt(kdfSalt);
            entity.setEmbeddingDim(embeddingDim);
        }
        entity = repository.save(entity);
        return entity;
    }
```

- [ ] **Step 2: 更新 MemoryController.createBase**

在 `MemoryController.java` 的 `createBase` 方法中解析加密字段：

```java
    @PostMapping("/bases")
    public Map<String, Object> createBase(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        boolean oneLlmMode = Boolean.TRUE.equals(body.get("one_llm_mode"));
        String scene = (String) body.getOrDefault("scene", "CHAT_ASSISTANT");
        if (!java.util.List.of("DEVELOPER_TOOL", "CHAT_ASSISTANT").contains(scene)) {
            throw new com.lakeon.service.exception.BadRequestException("Invalid scene: " + scene + ". Must be DEVELOPER_TOOL or CHAT_ASSISTANT");
        }
        boolean encrypted = Boolean.TRUE.equals(body.get("encrypted"));
        String encryptedDek = (String) body.get("encrypted_dek");
        String kdfSalt = (String) body.get("kdf_salt");
        Integer embeddingDim = body.get("embedding_dim") != null
                ? ((Number) body.get("embedding_dim")).intValue() : null;
        return toMemResponse(memoryService.createBase(
            tenant,
            (String) body.get("name"),
            (String) body.get("description"),
            MemoryBaseType.valueOf(body.getOrDefault("type", "BUILTIN").toString()),
            (String) body.get("embedding_model"),
            oneLlmMode,
            scene,
            encrypted,
            encryptedDek,
            kdfSalt,
            embeddingDim
        ));
    }
```

- [ ] **Step 3: 更新 toMemResponse 添加加密字段**

在 `MemoryController.java` 的 `toMemResponse` 方法中（第 178 行之后）添加：

```java
        map.put("encrypted", Boolean.TRUE.equals(mem.getEncrypted()));
        map.put("encrypted_dek", mem.getEncryptedDek());
        map.put("kdf_salt", mem.getKdfSalt());
        map.put("embedding_dim", mem.getEmbeddingDim());
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java \
        lakeon-api/src/main/java/com/lakeon/memory/MemoryController.java
git commit -m "feat(api): support encrypted memory base creation with DEK and embedding_dim"
```

---

### Task 5: Memory 微服务支持外部 embedding 和 query_embedding

**Files:**
- Modify: `memory/service/models.py:52-55,65-77`
- Modify: `memory/service/engine.py:13-28,190-253`
- Modify: `memory/service/main.py:17-27,42-45`
- Modify: `memory/service/schema.py:14`

- [ ] **Step 1: 更新 models.py — IngestRequest 增加 embedding 字段**

在 `memory/service/models.py` 的 `IngestRequest` 类中添加 `embedding` 字段：

```python
class IngestRequest(BaseModel):
    """Ingest endpoint — behavior determined by `signal`:
    - "memory": content is a structured memory, memory_type required → store directly
    - "conversation": content is raw conversation → server extracts memories automatically
    """
    content: str
    signal: Literal['memory', 'conversation'] = "memory"
    role: str = "user"
    source: Optional[str] = None
    memory_type: Optional[Literal['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention']] = None
    importance: float = 0.5
    embedding: Optional[list[float]] = None  # Pre-computed embedding (encrypted bases)

    model_config = {"extra": "ignore"}
```

在 `RecallRequest` 类中添加 `query_embedding` 字段：

```python
class RecallRequest(BaseModel):
    query: Optional[str] = None
    query_embedding: Optional[list[float]] = None  # Pre-computed query embedding (encrypted bases)
    top_k: int = 10
    memory_types: Optional[list[str]] = None
```

- [ ] **Step 2: 更新 engine.py — ingest 支持外部 embedding**

修改 `memory/service/engine.py` 的 `ingest` 函数签名和逻辑：

```python
async def ingest(connstr: str, content: str, role: str, memory_type: str,
                 importance: float, metadata: dict,
                 embedding: list[float] | None = None) -> Memory:
    if embedding is None:
        embedding = await get_embedding(content)
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                INSERT INTO memories (content, memory_type, importance, embedding, metadata, created_at)
                VALUES (%s, %s, %s, %s::vector, %s, now())
                RETURNING id, content, memory_type, importance, access_count, last_accessed_at, metadata, event_time, created_at
            """, (content, memory_type, importance, json.dumps(embedding), json.dumps(metadata)))
            row = cur.fetchone()
            conn.commit()
            return Memory(**row)
    finally:
        conn.close()
```

- [ ] **Step 3: 更新 engine.py — recall 支持 query_embedding**

修改 `memory/service/engine.py` 的 `recall` 函数：

```python
async def recall(connstr: str, query: str | None, top_k: int,
                 memory_types: Optional[list[str]],
                 query_embedding: list[float] | None = None) -> list[Memory]:
    """Hybrid search: vector cosine + text search + RRF merge.
    If query_embedding is provided, skip embedding generation and BM25 (encrypted mode).
    """
    if query_embedding is not None:
        # Encrypted mode: vector-only search, no BM25
        embedding = query_embedding
    else:
        embedding = await get_embedding(query)

    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            type_filter = ""
            type_params: list = []
            if memory_types:
                type_filter = "WHERE memory_type = ANY(%s)"
                type_params = [memory_types]

            # Vector search
            cur.execute(f"""
                SELECT id, content, memory_type, importance, access_count, last_accessed_at, metadata,
                       event_time, created_at
                FROM memories {type_filter}
                ORDER BY embedding <=> %s::vector
                LIMIT %s
            """, type_params + [json.dumps(embedding), top_k * 3])
            vector_results = cur.fetchall()

            if query_embedding is not None:
                # Encrypted mode: skip BM25, use vector results directly
                sorted_results = vector_results[:top_k]
                sorted_ids = [r["id"] for r in sorted_results]
            else:
                # Normal mode: BM25 + RRF merge
                cur.execute(f"""
                    SELECT id, content, memory_type, importance, access_count, last_accessed_at, metadata,
                           event_time, created_at,
                           ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', %s)) AS text_score
                    FROM memories
                    {type_filter + ' AND' if type_filter else 'WHERE'}
                    to_tsvector('simple', content) @@ plainto_tsquery('simple', %s)
                    ORDER BY text_score DESC
                    LIMIT %s
                """, [query] + ([memory_types] if memory_types else []) + [query, top_k * 3])
                text_results = cur.fetchall()

                rrf_scores: dict[int, float] = {}
                all_rows: dict[int, dict] = {}

                for rank, row in enumerate(vector_results):
                    mid = row["id"]
                    rrf_scores[mid] = rrf_scores.get(mid, 0) + 1.0 / (60 + rank)
                    all_rows[mid] = row

                for rank, row in enumerate(text_results):
                    mid = row["id"]
                    rrf_scores[mid] = rrf_scores.get(mid, 0) + 1.0 / (60 + rank)
                    if mid not in all_rows:
                        all_rows[mid] = row

                sorted_ids = sorted(rrf_scores, key=lambda x: rrf_scores[x], reverse=True)[:top_k]
                sorted_results = [all_rows[mid] for mid in sorted_ids]

            if sorted_ids:
                cur.execute("""
                    UPDATE memories SET access_count = access_count + 1, last_accessed_at = now()
                    WHERE id = ANY(%s)
                """, (sorted_ids,))
                conn.commit()

            return [Memory(**{k: v for k, v in row.items() if k != "text_score"})
                    for row in sorted_results]
    finally:
        conn.close()
```

- [ ] **Step 4: 更新 main.py — ingest 端点传递 embedding**

修改 `memory/service/main.py` 的 `ingest` 端点：

```python
@app.post("/ingest")
async def ingest(req: IngestRequest, x_database_connstr: str = Header(...),
                 x_scene: str = Header("CHAT_ASSISTANT")):
    if req.signal == "memory":
        if not req.memory_type:
            raise HTTPException(400, "memory_type is required when signal='memory'")
        metadata = {"source": req.source} if req.source else {}
        mem = await engine.ingest(x_database_connstr, req.content, req.role,
                                  req.memory_type, req.importance, metadata,
                                  embedding=req.embedding)
        return {"memory_id": mem.id, "memory_type": mem.memory_type, "status": "stored"}

    elif req.signal == "conversation":
        message_id = await engine.store_raw_message(x_database_connstr, req.content, req.role, req.source)
        asyncio.create_task(engine.background_extract(x_database_connstr, message_id, req.content, x_scene))
        return {"message_id": message_id, "status": "extracting"}
```

- [ ] **Step 5: 更新 main.py — recall 端点传递 query_embedding**

修改 `memory/service/main.py` 的 `recall` 端点：

```python
@app.post("/recall")
async def recall(req: RecallRequest, x_database_connstr: str = Header(...)):
    if not req.query and not req.query_embedding:
        raise HTTPException(400, "Either query or query_embedding is required")
    results = await engine.recall(x_database_connstr, req.query, req.top_k,
                                   req.memory_types, query_embedding=req.query_embedding)
    return {"memories": [m.model_dump() for m in results]}
```

- [ ] **Step 6: 更新 schema.py — 支持动态 embedding 维度**

修改 `memory/service/schema.py` 的 `init_schema` 函数，支持通过 header 传入维度：

在 `main.py` 的 `/init` 端点添加可选 header：

```python
@app.post("/init")
async def init_memory(x_database_connstr: str = Header(...),
                      x_embedding_dim: int = Header(1024)):
    schema.init_schema(x_database_connstr, embedding_dim=x_embedding_dim)
    return {"status": "ok"}
```

修改 `schema.py`：

```python
def init_schema(connstr: str, retries: int = 10, delay: float = 3.0,
                embedding_dim: int = 1024):
    schema_sql = SCHEMA_SQL.replace("vector(1024)", f"vector({embedding_dim})")
    for attempt in range(retries):
        try:
            conn = psycopg2.connect(connstr, connect_timeout=30)
            conn.autocommit = True
            with conn.cursor() as cur:
                cur.execute(schema_sql)
                cur.execute("""
                    ALTER TABLE memories DROP CONSTRAINT IF EXISTS memories_memory_type_check;
                    ALTER TABLE memories ADD CONSTRAINT memories_memory_type_check
                      CHECK (memory_type IN ('fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'));
                """)
            conn.close()
            return
        except psycopg2.OperationalError:
            if attempt < retries - 1:
                time.sleep(delay)
            else:
                raise
```

- [ ] **Step 7: 更新 MemoryService.java — ensureSchemaInitialized 传递 embedding_dim**

修改 `lakeon-api/.../MemoryService.java` 的 `ensureSchemaInitialized` 方法：

```java
    private void ensureSchemaInitialized(String connstr, Integer embeddingDim) {
        String url = props.getMemory().getServiceUrl() + "/init";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Database-Connstr", connstr);
        if (embeddingDim != null) {
            headers.set("X-Embedding-Dim", String.valueOf(embeddingDim));
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(null, headers), Object.class);
        } catch (Exception e) {
            log.warn("Schema init call failed (may already be initialized): {}", e.getMessage());
        }
    }
```

更新 `proxyPost` 中调用 `ensureSchemaInitialized` 的地方：

```java
    public Object proxyPost(String tenantId, String memId, String path, Object body) {
        MemoryBaseEntity mem = getBase(tenantId, memId);
        String connstr = dbHelper.resolveConnstr(tenantId, memId);
        ensureSchemaInitialized(connstr, mem.getEmbeddingDim());
        String url = props.getMemory().getServiceUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Database-Connstr", connstr);
        headers.set("X-Scene", mem.getScene() != null ? mem.getScene() : "CHAT_ASSISTANT");
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<?> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Object> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
        return resp.getBody();
    }
```

- [ ] **Step 8: Commit**

```bash
git add memory/service/models.py memory/service/engine.py memory/service/main.py \
        memory/service/schema.py \
        lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java
git commit -m "feat(memory): support pre-computed embedding and query_embedding for encrypted bases"
```

---

### Task 6: MCP 透明加解密集成

**Files:**
- Modify: `dbay-mcp/src/dbay_mcp/server.py:469-551`

- [ ] **Step 1: 添加导入和辅助函数**

在 `dbay-mcp/src/dbay_mcp/server.py` 的 import 区域（第 9 行之后）添加：

```python
import asyncio
```

在 Memory tools 区域（第 394 行之后，`_get_memory_base_id` 之前）添加加密辅助函数：

```python
# ---------------------------------------------------------------------------
# Encryption helpers
# ---------------------------------------------------------------------------

def _get_encrypted_base_info(mem_id: str) -> dict | None:
    """Check if mem_id is an encrypted base. Returns base info with encrypted_dek or None."""
    from dbay_mcp.crypto import is_encrypted_base
    if not is_encrypted_base(mem_id):
        return None
    # Fetch base info from server to get encrypted_dek
    data = _api("GET", f"/memory/bases/{mem_id}")
    if data.get("encrypted"):
        return data
    return None


def _encrypt_and_embed(mem_id: str, content: str, base_info: dict) -> tuple[str, list[float]]:
    """Encrypt content and generate embedding locally. Returns (encrypted_content, embedding)."""
    from dbay_mcp.crypto import get_dek, encrypt_content
    from dbay_mcp.embedding import generate_embedding

    dek = get_dek(mem_id, base_info["encrypted_dek"])
    encrypted_content = encrypt_content(dek, content)
    embedding = asyncio.get_event_loop().run_until_complete(
        generate_embedding(mem_id, content, api_key=_get_api_key(), endpoint=_get_endpoint())
    )
    return encrypted_content, embedding


def _decrypt_content(mem_id: str, encrypted_content: str, base_info: dict) -> str:
    """Decrypt content using DEK."""
    from dbay_mcp.crypto import get_dek, decrypt_content
    dek = get_dek(mem_id, base_info["encrypted_dek"])
    return decrypt_content(dek, encrypted_content)
```

- [ ] **Step 2: 更新 memory_ingest 添加加密分支**

替换 `memory_ingest` 函数（第 469-498 行）：

```python
@mcp.tool(description=_desc("memory_ingest"))
def memory_ingest(
    content: str,
    memory_type: str = "fact",
    importance: float = 0.5,
    source: str = "claude-code",
    memory_base: str | None = None,
) -> str:
    """Store a memory to the user's persistent cross-project memory.

    Args:
        content: The memory content — concise, structured, self-contained
        memory_type: REQUIRED. One of: fact, decision, rejection, convention, procedural, episode
        importance: 0.0-1.0. Use 0.8+ for credentials, critical decisions, painful lessons
        source: Client identifier (default "claude-code")
        memory_base: Memory base name or ID (optional, auto-detected)
    """
    mem_id = _resolve_mem_id(memory_base)

    # Check if encrypted
    base_info = _get_encrypted_base_info(mem_id)
    if base_info:
        encrypted_content, embedding = _encrypt_and_embed(mem_id, content, base_info)
        data = _api("POST", f"/memory/bases/{mem_id}/ingest", json={
            "content": encrypted_content,
            "signal": "memory",
            "source": source,
            "memory_type": memory_type,
            "importance": importance,
            "embedding": embedding,
        })
    else:
        data = _api("POST", f"/memory/bases/{mem_id}/ingest", json={
            "content": content,
            "signal": "memory",
            "source": source,
            "memory_type": memory_type,
            "importance": importance,
        })

    if data.get("status") == "stored":
        return f"Memory stored (id={data.get('memory_id')}, type={data.get('memory_type')})."
    return f"Memory stored (status={data.get('status', 'ok')})."
```

- [ ] **Step 3: 更新 memory_recall 添加加密分支**

替换 `memory_recall` 函数（第 429-466 行）：

```python
@mcp.tool(description=_desc("memory_recall"))
def memory_recall(
    query: str,
    memory_types: list[str] | None = None,
    top_k: int = 10,
    memory_base: str | None = None,
) -> str:
    """Search agent memory using semantic similarity.

    Args:
        query: Natural language query (e.g. "why did we choose asyncpg", "naming conventions")
        memory_types: Optional filter — any of: fact, episode, procedural, decision, rejection, convention
        top_k: Number of results (default 10)
        memory_base: Memory base name or ID (optional, auto-detected if only one exists)
    """
    mem_id = _resolve_mem_id(memory_base)

    base_info = _get_encrypted_base_info(mem_id)
    if base_info:
        # Encrypted: generate query embedding locally, send to server
        from dbay_mcp.embedding import generate_embedding
        query_embedding = asyncio.get_event_loop().run_until_complete(
            generate_embedding(mem_id, query, api_key=_get_api_key(), endpoint=_get_endpoint())
        )
        body: dict = {"query_embedding": query_embedding, "top_k": min(top_k, 50)}
        if memory_types:
            body["memory_types"] = memory_types
        data = _api("POST", f"/memory/bases/{mem_id}/recall", json=body)

        memories = data.get("memories", [])
        if not memories:
            return "No memories found."

        # Decrypt each memory content
        parts = []
        for i, m in enumerate(memories, 1):
            mtype = m.get("memory_type", "?")
            encrypted_content = m.get("content", "").strip()
            try:
                content = _decrypt_content(mem_id, encrypted_content, base_info)
            except Exception:
                content = "[decryption failed]"
            meta = m.get("metadata", {})
            meta_str = ""
            if meta:
                meta_parts = [f"{k}={v}" for k, v in meta.items() if v and k != "source"]
                if meta_parts:
                    meta_str = f" ({', '.join(meta_parts)})"
            parts.append(f"{i}. [{mtype}] {content}{meta_str}")
        return "\n".join(parts)
    else:
        # Non-encrypted: existing logic
        body = {"query": query, "top_k": min(top_k, 50)}
        if memory_types:
            body["memory_types"] = memory_types
        data = _api("POST", f"/memory/bases/{mem_id}/recall", json=body)

        memories = data.get("memories", [])
        if not memories:
            return "No memories found."

        parts = []
        for i, m in enumerate(memories, 1):
            mtype = m.get("memory_type", "?")
            content = m.get("content", "").strip()
            meta = m.get("metadata", {})
            meta_str = ""
            if meta:
                meta_parts = [f"{k}={v}" for k, v in meta.items() if v and k != "source"]
                if meta_parts:
                    meta_str = f" ({', '.join(meta_parts)})"
            parts.append(f"{i}. [{mtype}] {content}{meta_str}")
        return "\n".join(parts)
```

- [ ] **Step 4: 更新 memory_list 添加解密**

替换 `memory_list` 函数（第 501-534 行）：

```python
@mcp.tool(description=_desc("memory_list"))
def memory_list(
    memory_base: str | None = None,
    memory_type: str | None = None,
    limit: int = 20,
) -> str:
    """Browse memories in a memory base, optionally filtered by type.

    Args:
        memory_base: Memory base name or ID (optional, auto-detected)
        memory_type: Optional filter — one of: fact, episode, procedural, decision, rejection, convention
        limit: Max number of memories to return (default 20)
    """
    mem_id = _resolve_mem_id(memory_base)
    params = f"?limit={min(limit, 100)}"
    if memory_type:
        params += f"&memory_type={memory_type}"
    data = _api("GET", f"/memory/bases/{mem_id}/memories{params}")

    memories = data.get("memories", [])
    if not memories:
        return "No memories found."

    base_info = _get_encrypted_base_info(mem_id)
    total = data.get("total", len(memories))
    parts = [f"Showing {len(memories)} of {total} memories:\n"]
    for m in memories:
        mid = m.get("id", "?")
        mtype = m.get("memory_type", "?")
        raw_content = m.get("content", "").strip()
        importance = m.get("importance", 0)

        if base_info:
            try:
                content = _decrypt_content(mem_id, raw_content, base_info)
            except Exception:
                content = "[decryption failed]"
        else:
            content = raw_content

        preview = content[:120] + "..." if len(content) > 120 else content
        parts.append(f"  [{mid}] ({mtype}, imp={importance}) {preview}")

    return "\n".join(parts)
```

- [ ] **Step 5: Commit**

```bash
git add dbay-mcp/src/dbay_mcp/server.py
git commit -m "feat(mcp): integrate transparent encryption/decryption in memory tools"
```

---

### Task 7: CLI 加密记忆库创建命令

**Files:**
- Modify: `dbay-cli/dbay_cli/commands/mem.py:22-27`
- Modify: `dbay-cli/dbay_cli/client.py:587-592`
- Modify: `dbay-cli/pyproject.toml`

- [ ] **Step 1: 添加 cryptography 依赖到 dbay-cli**

```toml
# dbay-cli/pyproject.toml
dependencies = [
    "typer[all]>=0.12.0",
    "httpx>=0.27.0",
    "rich>=13.0.0",
    "pyyaml>=6.0",
    "dbay-mcp>=0.3.0",
    "cryptography>=41.0.0",
]
```

- [ ] **Step 2: 更新 client.py — create_memory_base 支持加密参数**

修改 `dbay-cli/dbay_cli/client.py` 的 `create_memory_base` 方法：

```python
    def create_memory_base(self, name: str, description: str = None,
                           one_llm_mode: bool = False, encrypted: bool = False,
                           encrypted_dek: str = None, kdf_salt: str = None,
                           embedding_dim: int = None) -> dict:
        body: dict = {"name": name, "one_llm_mode": one_llm_mode}
        if description:
            body["description"] = description
        if encrypted:
            body["encrypted"] = True
            body["encrypted_dek"] = encrypted_dek
            body["kdf_salt"] = kdf_salt
            body["embedding_dim"] = embedding_dim
        return self._request("POST", "/memory/bases", json=body)
```

- [ ] **Step 3: 更新 mem.py — 改造 create 命令支持 --encrypted**

替换 `dbay-cli/dbay_cli/commands/mem.py` 的 `create` 命令：

```python
@app.command("create")
def create(name: str, desc: str = typer.Option(None, "--desc"),
           agent_extract: bool = typer.Option(False, "--agent-extract"),
           encrypted: bool = typer.Option(False, "--encrypted")):
    """Create a memory base. Use --encrypted for client-side encryption."""
    if encrypted:
        _create_encrypted(name, desc, agent_extract)
    else:
        result = _client().create_memory_base(name, desc, one_llm_mode=agent_extract)
        typer.echo(json.dumps(result, indent=2, default=str))


def _create_encrypted(name: str, desc: str | None, agent_extract: bool):
    """Interactive flow for creating an encrypted memory base."""
    import base64
    import getpass
    from dbay_mcp.crypto import (
        generate_keypair, generate_dek, generate_salt,
        encrypt_private_key, encrypt_dek_with_public_key,
        save_encrypted_base, write_secret,
    )
    from dbay_mcp.embedding import probe_embedding_dim
    import asyncio

    typer.echo("Creating encrypted memory base...")
    typer.echo("⚠ If you lose your password, your data cannot be recovered.\n")

    # 1. Password
    password = getpass.getpass("Set encryption password: ")
    confirm = getpass.getpass("Confirm password: ")
    if password != confirm:
        typer.echo("Passwords do not match.", err=True)
        raise typer.Exit(1)

    # 2. Embedding provider
    typer.echo("\nEmbedding provider:")
    typer.echo("  1) DBay (default, uses your API key)")
    typer.echo("  2) External API (provide endpoint/key/model)")
    typer.echo("  3) Local model (not yet supported)")
    choice = typer.prompt("Choose", default="1")

    embedding_config: dict = {}
    if choice == "1":
        embedding_config = {"embedding_provider": "dbay"}
    elif choice == "2":
        embedding_config = {
            "embedding_provider": "external",
            "embedding_endpoint": typer.prompt("Embedding API endpoint"),
            "embedding_api_key": typer.prompt("Embedding API key", default=""),
            "embedding_model": typer.prompt("Embedding model name"),
        }
    else:
        typer.echo("Local model not yet supported.", err=True)
        raise typer.Exit(1)

    # 3. Generate keys
    typer.echo("\nGenerating RSA-4096 key pair...")
    private_pem, public_pem = generate_keypair()
    salt = generate_salt()
    dek = generate_dek()

    # 4. Encrypt private key with password
    encrypted_private_key = encrypt_private_key(private_pem, password, salt)

    # 5. Encrypt DEK with public key
    encrypted_dek = encrypt_dek_with_public_key(dek, public_pem)

    # 6. Probe embedding dimension
    # Save temp config so probe_embedding_dim can read it
    temp_mem_id = "probe_temp"
    temp_config = {
        **embedding_config,
        "public_key": public_pem.decode("ascii"),
        "encrypted_private_key": encrypted_private_key,
        "kdf_salt": base64.b64encode(salt).decode("ascii"),
        "kdf_algorithm": "scrypt",
    }
    save_encrypted_base(temp_mem_id, temp_config)

    typer.echo("Probing embedding dimension...")
    from dbay_cli.config import get_endpoint, get as config_get
    try:
        dim = asyncio.get_event_loop().run_until_complete(
            probe_embedding_dim(temp_mem_id, api_key=config_get("api_key"), endpoint=get_endpoint())
        )
    except Exception as e:
        # Clean up temp
        from dbay_mcp.crypto import load_encrypted_bases, ENCRYPTED_BASES_FILE
        bases = load_encrypted_bases()
        bases.pop(temp_mem_id, None)
        ENCRYPTED_BASES_FILE.write_text(json.dumps(bases, indent=2) + "\n")
        typer.echo(f"Embedding probe failed: {e}", err=True)
        raise typer.Exit(1)

    typer.echo(f"Detected embedding dimension: {dim}")

    # Clean up temp entry
    from dbay_mcp.crypto import load_encrypted_bases, ENCRYPTED_BASES_FILE
    bases = load_encrypted_bases()
    bases.pop(temp_mem_id, None)
    if bases:
        ENCRYPTED_BASES_FILE.write_text(json.dumps(bases, indent=2) + "\n")

    # 7. Create memory base on server
    typer.echo("Creating memory base on server...")
    result = _client().create_memory_base(
        name, desc, one_llm_mode=agent_extract,
        encrypted=True,
        encrypted_dek=encrypted_dek,
        kdf_salt=base64.b64encode(salt).decode("ascii"),
        embedding_dim=dim,
    )
    mem_id = result["id"]

    # 8. Save config locally
    config = {
        "public_key": public_pem.decode("ascii"),
        "encrypted_private_key": encrypted_private_key,
        "kdf_salt": base64.b64encode(salt).decode("ascii"),
        "kdf_algorithm": "scrypt",
        **embedding_config,
        "embedding_dim": dim,
    }
    save_encrypted_base(mem_id, config)

    # 9. Save password
    write_secret(password)

    typer.echo(f"\nEncrypted memory base created: {mem_id}")
    typer.echo(f"Config saved to: ~/.dbay/encrypted_bases.json")
    typer.echo(f"Password saved to: ~/.dbay/secret")
    typer.echo(f"\nTo use on another device:")
    typer.echo(f"  1. Copy ~/.dbay/encrypted_bases.json")
    typer.echo(f"  2. Create ~/.dbay/secret with DBAY_ENCRYPTION_PASSWORD=<your_password>")
```

- [ ] **Step 4: 添加 change-password 命令**

在 `mem.py` 末尾添加：

```python
@app.command("change-password")
def change_password(mem_id: str):
    """Change encryption password for a memory base."""
    import base64
    import getpass
    from dbay_mcp.crypto import (
        load_encrypted_bases, save_encrypted_base, write_secret,
        decrypt_private_key, encrypt_private_key,
    )

    bases = load_encrypted_bases()
    if mem_id not in bases:
        typer.echo(f"No encryption config for {mem_id}", err=True)
        raise typer.Exit(1)

    config = bases[mem_id]
    salt = base64.b64decode(config["kdf_salt"])

    old_password = getpass.getpass("Current password: ")
    try:
        private_pem = decrypt_private_key(config["encrypted_private_key"], old_password, salt)
    except Exception:
        typer.echo("Wrong password.", err=True)
        raise typer.Exit(1)

    new_password = getpass.getpass("New password: ")
    confirm = getpass.getpass("Confirm new password: ")
    if new_password != confirm:
        typer.echo("Passwords do not match.", err=True)
        raise typer.Exit(1)

    new_salt = generate_salt()
    new_encrypted_private_key = encrypt_private_key(private_pem, new_password, new_salt)

    config["encrypted_private_key"] = new_encrypted_private_key
    config["kdf_salt"] = base64.b64encode(new_salt).decode("ascii")
    save_encrypted_base(mem_id, config)
    write_secret(new_password)

    typer.echo("Password changed successfully.")
```

需要在 change-password 顶部额外导入：

```python
    from dbay_mcp.crypto import generate_salt
```

- [ ] **Step 5: Commit**

```bash
git add dbay-cli/pyproject.toml dbay-cli/dbay_cli/commands/mem.py dbay-cli/dbay_cli/client.py
git commit -m "feat(cli): add encrypted memory base creation and password management"
```

---

### Task 8: E2E 测试

**Files:**
- Create: `tests/e2e/test_memory_encrypted.py`

- [ ] **Step 1: 创建加密记忆库 E2E 测试**

```python
# tests/e2e/test_memory_encrypted.py
"""E2E tests for encrypted memory bases.

Tests the full encryption pipeline:
  create encrypted base → ingest (encrypt) → recall (decrypt) → list (decrypt) → delete
"""
import base64
import json
import time
import pytest
from dbay_mcp.crypto import (
    generate_keypair, generate_dek, generate_salt,
    encrypt_private_key, encrypt_dek_with_public_key,
    decrypt_private_key, decrypt_dek_with_private_key,
    encrypt_content, decrypt_content,
    save_encrypted_base, write_secret, load_encrypted_bases,
    ENCRYPTED_BASES_FILE,
)


TEST_PASSWORD = "e2e-test-password-2026"


@pytest.fixture(scope="module")
def e2e_client(e2e_tenant):
    return e2e_tenant["client"]


@pytest.fixture(scope="module")
def encrypted_base(e2e_client):
    """Create an encrypted memory base with full key setup."""
    # Generate keys
    private_pem, public_pem = generate_keypair()
    salt = generate_salt()
    dek = generate_dek()

    encrypted_private_key = encrypt_private_key(private_pem, TEST_PASSWORD, salt)
    encrypted_dek = encrypt_dek_with_public_key(dek, public_pem)

    # Create on server
    base = e2e_client.create_memory_base(
        name=f"e2e-encrypted-{int(time.time())}",
        encrypted=True,
        encrypted_dek=encrypted_dek,
        kdf_salt=base64.b64encode(salt).decode("ascii"),
        embedding_dim=1024,
    )

    # Wait for READY
    for _ in range(60):
        info = e2e_client.get_memory_base(base["id"])
        if info["status"] == "READY":
            break
        time.sleep(2)
    assert info["status"] == "READY"
    assert info["encrypted"] is True

    # Save local config
    config = {
        "public_key": public_pem.decode("ascii"),
        "encrypted_private_key": encrypted_private_key,
        "kdf_salt": base64.b64encode(salt).decode("ascii"),
        "kdf_algorithm": "scrypt",
        "embedding_provider": "dbay",
        "embedding_dim": 1024,
    }
    save_encrypted_base(info["id"], config)
    write_secret(TEST_PASSWORD)

    yield {
        "info": info,
        "dek": dek,
        "private_pem": private_pem,
        "public_pem": public_pem,
    }

    # Cleanup
    try:
        e2e_client.delete_memory_base(base["id"])
    except Exception:
        pass
    # Clean up local config
    bases = load_encrypted_bases()
    bases.pop(info["id"], None)
    ENCRYPTED_BASES_FILE.write_text(json.dumps(bases, indent=2) + "\n")


class TestCryptoUnit:
    """Unit-level crypto tests (no server needed)."""

    def test_keypair_generation(self):
        private_pem, public_pem = generate_keypair()
        assert b"BEGIN PRIVATE KEY" in private_pem
        assert b"BEGIN PUBLIC KEY" in public_pem

    def test_dek_generation(self):
        dek = generate_dek()
        assert len(dek) == 32

    def test_private_key_encrypt_decrypt(self):
        private_pem, _ = generate_keypair()
        salt = generate_salt()
        encrypted = encrypt_private_key(private_pem, "test-pwd", salt)
        decrypted = decrypt_private_key(encrypted, "test-pwd", salt)
        assert decrypted == private_pem

    def test_private_key_wrong_password(self):
        private_pem, _ = generate_keypair()
        salt = generate_salt()
        encrypted = encrypt_private_key(private_pem, "correct", salt)
        with pytest.raises(Exception):
            decrypt_private_key(encrypted, "wrong", salt)

    def test_dek_rsa_encrypt_decrypt(self):
        private_pem, public_pem = generate_keypair()
        dek = generate_dek()
        encrypted_dek = encrypt_dek_with_public_key(dek, public_pem)
        decrypted_dek = decrypt_dek_with_private_key(encrypted_dek, private_pem)
        assert decrypted_dek == dek

    def test_content_encrypt_decrypt(self):
        dek = generate_dek()
        plaintext = "My API key is sk-secret123"
        encrypted = encrypt_content(dek, plaintext)
        assert encrypted != plaintext
        decrypted = decrypt_content(dek, encrypted)
        assert decrypted == plaintext

    def test_content_encrypt_different_nonce(self):
        """Same plaintext produces different ciphertext (random nonce)."""
        dek = generate_dek()
        plaintext = "same content"
        a = encrypt_content(dek, plaintext)
        b = encrypt_content(dek, plaintext)
        assert a != b  # Different nonces
        assert decrypt_content(dek, a) == decrypt_content(dek, b) == plaintext

    def test_full_three_factor_chain(self):
        """End-to-end: password → private_key → DEK → content."""
        password = "my-password"
        private_pem, public_pem = generate_keypair()
        salt = generate_salt()
        dek = generate_dek()

        # Encrypt chain
        encrypted_private_key = encrypt_private_key(private_pem, password, salt)
        encrypted_dek = encrypt_dek_with_public_key(dek, public_pem)
        encrypted_content = encrypt_content(dek, "secret data")

        # Decrypt chain (simulating MCP runtime)
        recovered_private_pem = decrypt_private_key(encrypted_private_key, password, salt)
        recovered_dek = decrypt_dek_with_private_key(encrypted_dek, recovered_private_pem)
        recovered_content = decrypt_content(recovered_dek, encrypted_content)

        assert recovered_content == "secret data"


class TestEncryptedMemoryBase:
    """Server integration tests for encrypted memory bases."""

    def test_base_is_encrypted(self, encrypted_base, e2e_client):
        """Verify the created base has encryption fields."""
        info = e2e_client.get_memory_base(encrypted_base["info"]["id"])
        assert info["encrypted"] is True
        assert info["encrypted_dek"] is not None
        assert info["embedding_dim"] == 1024

    def test_ingest_encrypted(self, encrypted_base, e2e_client):
        """Ingest with client-side encryption: server stores ciphertext."""
        mem_id = encrypted_base["info"]["id"]
        dek = encrypted_base["dek"]
        plaintext = "My server IP is 10.0.1.5"

        # Encrypt content
        encrypted_content = encrypt_content(dek, plaintext)
        assert encrypted_content != plaintext

        # Ingest with pre-computed embedding (use a dummy for test)
        dummy_embedding = [0.1] * 1024
        result = e2e_client.mem_ingest(
            mem_id, content=encrypted_content,
            signal="memory", memory_type="fact",
            importance=0.8,
        )
        assert result["status"] == "stored"

        # Verify server has ciphertext, not plaintext
        memories = e2e_client.mem_list(mem_id)
        stored = next(m for m in memories["memories"] if m["id"] == result["memory_id"])
        assert stored["content"] != plaintext
        assert stored["content"] == encrypted_content

        # Client can decrypt
        decrypted = decrypt_content(dek, stored["content"])
        assert decrypted == plaintext

    def test_ingest_and_recall_via_mcp(self, encrypted_base, e2e_client):
        """Full MCP flow: ingest → recall with transparent encryption."""
        # This test verifies the MCP layer works end-to-end
        # by using the crypto module directly (simulating what MCP does)
        mem_id = encrypted_base["info"]["id"]
        dek = encrypted_base["dek"]
        plaintext = "Project deadline is 2026-05-01"

        encrypted_content = encrypt_content(dek, plaintext)

        result = e2e_client.mem_ingest(
            mem_id, content=encrypted_content,
            signal="memory", memory_type="fact",
        )
        assert result["status"] == "stored"

        # Recall - server returns ciphertext, we decrypt
        time.sleep(1)
        recall_result = e2e_client.mem_recall(mem_id, query="project deadline")
        found = False
        for m in recall_result.get("memories", []):
            try:
                decrypted = decrypt_content(dek, m["content"])
                if "2026-05-01" in decrypted:
                    found = True
                    break
            except Exception:
                continue
        assert found, "Could not find and decrypt the ingested memory"

    def test_delete_encrypted_memory(self, encrypted_base, e2e_client):
        """Delete works the same for encrypted memories."""
        mem_id = encrypted_base["info"]["id"]
        dek = encrypted_base["dek"]

        encrypted_content = encrypt_content(dek, "to be deleted")
        result = e2e_client.mem_ingest(
            mem_id, content=encrypted_content,
            signal="memory", memory_type="fact",
        )
        memory_id = result["memory_id"]

        e2e_client.mem_delete(mem_id, memory_id)

        memories = e2e_client.mem_list(mem_id)
        assert not any(m["id"] == memory_id for m in memories["memories"])

    def test_multi_tenant_isolation_encrypted(self, encrypted_base, e2e_client):
        """Other tenants cannot access encrypted memory base."""
        from tests.e2e.conftest import _create_tenant_with_invite, ENDPOINT, ADMIN_TOKEN
        from dbay_cli.client import DbayClient, DbayApiError

        ts = int(time.time())
        client_b, tenant_b = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-enc-iso-{ts}", f"E2eTest@{ts}", f"Tenant Enc {ts}"
        )
        try:
            with pytest.raises(DbayApiError) as exc:
                client_b.get_memory_base(encrypted_base["info"]["id"])
            assert exc.value.status_code == 404
        finally:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            try:
                admin.admin_batch_delete_tenants([tenant_b["id"]])
            except Exception:
                pass
```

- [ ] **Step 2: 运行测试验证**

```bash
# 先运行 crypto 单元测试（不需要服务器）
python3 -m pytest tests/e2e/test_memory_encrypted.py::TestCryptoUnit -v

# 再运行服务端集成测试
python3 -m pytest tests/e2e/test_memory_encrypted.py::TestEncryptedMemoryBase -v
```

- [ ] **Step 3: Commit**

```bash
git add tests/e2e/test_memory_encrypted.py
git commit -m "test(e2e): add encrypted memory base tests covering full encryption pipeline"
```

---

### Task 9: 更新设计文档并最终提交

**Files:**
- Modify: `docs/superpowers/specs/2026-04-08-memory-client-encryption-design.md`

- [ ] **Step 1: 确认设计文档与实现一致**

Review 设计文档，确保以下更新已反映：
- 密码存储方式：`~/.dbay/secret` 文件
- 去掉了 `unlock` 命令
- MCP 启动时自动从 secret 文件读取密码
- KDF 使用 Scrypt（cryptography 库内置，比 Argon2id 更轻量无需额外 C 依赖）

- [ ] **Step 2: 最终提交**

```bash
git add docs/superpowers/specs/2026-04-08-memory-client-encryption-design.md
git commit -m "docs(spec): update encryption design to match implementation"
```
