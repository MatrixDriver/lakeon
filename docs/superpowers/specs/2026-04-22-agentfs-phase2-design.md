# AgentFS Phase 2 · 派生 Memory Worker 设计

> Status: draft v1 · 2026-04-22
> Owner: Jacky
> Spec type: design
> 前置依赖（已完成）: bug #1 split-brain 缓解 / bug #2 takeover backfill / 服务端 PUT 幂等

## 1. 背景与目标

Phase 1 让 `~/.claude/memory/*.md` 和 `~/.claude/projects/*/memory/*.md` 透明上云到 `agent_files`。但 `memory_items` / `memories` 表**完全看不到** AgentFS 里有什么 — 用户在 `feedback_no_emoji.md` 里写的规则，`memory_recall "emoji"` 召回不到。

Phase 2 闭合这一环：**agent_files 的变更（新增 / 修改 / 删除）自动派生对应的 memory 条目**，让用户在 FUSE 层的文件写入和通过 `memory_ingest` MCP 的结构化 ingest 两条路径产生的记忆**共享同一召回空间**。

**MVP 单一用户可见价值**：用户用 Claude Code 编辑 `~/.claude/memory/feedback_xxx.md`，秒级 - 分钟级内 `memory_recall "xxx"` 能命中该条。

## 2. 范围

### In scope
- 路径白名单：`/memory/*.md` + `/projects/*/memory/*.md`（排除 `MEMORY.md` 生成视图）
- 衍生粒度：**整文件一条 `memories` 行**（frontmatter 解析出 `memory_type`，正文作 `content`）
- 更新语义：原地覆盖（upsert on `(source_path, source_etag)`）
- 删除语义：硬删 `memories` 行
- Auto-provision：tenant 首次派生时自动建 `agentfs-<agent>` memory base
- Console UI：记忆库页面加 "AgentFS 目标" 互斥 radio + `[auto]` 徽章 + 待派生红条计数
- Backfill：lakeon-api forwarder 首次处理某 tenant 时种子化存量事件
- HA：`agentfs_forwarder_locks` 抢锁，leader-per-tenant

### Out of scope (Phase 3+)
- `scope` 轴（global / per_agent / per_project）—— 先不做，Phase 3 再补
- `origin` 字段（user_authored vs system_authored）
- 虚拟 CLAUDE.md 从 memory_items 聚合渲染
- 精细 type 枚举 (spec §2.2 的 12 种) — MVP 塌成现有 6 种 memory_type
- OpenClaw adapter
- 跨设备 merge / 冲突解决

## 3. 架构

```
Claude Code 写文件
  → FUSE → outbox → lakeon-api put/delete/rename  （Phase 1，已完成）
    → per-tenant agentfs_<uuid> DB                 （Phase 1，已完成）
      ↓ trigger (新增)
      agentfs_events 表 (per-tenant)               （新增）

lakeon-api @Scheduled AgentFSEventForwarder (30s)  （新增）
  → 抢锁 agentfs_forwarder_locks (metadata DB)     （新增）
  → 读 per-tenant events
  → HTTP POST memory-svc /api/v1/agentfs/derive    （新增）
  → ACK events

memory-svc POST /agentfs/derive                    （新增）
  → 查 agentfs_memory_targets                      （新增表）
  → base 不存在 → 异步 provision + 202 Accepted
  → base ready → memory_ingest → 200 OK
  → 幂等由 memories 表 UNIQUE (source_path, source_etag) 保证
```

## 4. 数据模型

### 4.1 per-tenant DB 新增（在 `agentfs_<uuid>` 里）

```sql
-- 事件流水
CREATE TABLE agentfs_events (
  id          BIGSERIAL PRIMARY KEY,
  path        TEXT NOT NULL,
  etag        VARCHAR(64),                      -- NULL for delete
  event_type  VARCHAR(16) NOT NULL,             -- create | update | delete | backfill
  status      VARCHAR(16) NOT NULL DEFAULT 'pending',
                                                -- pending | done | poison
  retry_count INT NOT NULL DEFAULT 0,
  last_error  TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ
);
CREATE INDEX idx_agentfs_events_pending
  ON agentfs_events(status, id) WHERE status = 'pending';

-- trigger：文件变更时产生事件
CREATE FUNCTION agentfs_files_event_fn() RETURNS TRIGGER AS $$
BEGIN
  IF (TG_OP = 'INSERT') THEN
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (NEW.path, NEW.etag, 'create');
  ELSIF (TG_OP = 'UPDATE') THEN
    -- idempotency fix 确保只有 etag 真变了才走到这里
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (NEW.path, NEW.etag, 'update');
  ELSIF (TG_OP = 'DELETE') THEN
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (OLD.path, OLD.etag, 'delete');
  END IF;
  RETURN NULL; -- AFTER trigger, 返回值被忽略
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER agentfs_files_event_trg
  AFTER INSERT OR UPDATE OR DELETE ON files
  FOR EACH ROW EXECUTE FUNCTION agentfs_files_event_fn();
```

**装载时机**：`AgentFSDatabaseManager.initSchemaAndMarkReady` 的 `FILES_SCHEMA` 常量扩展，新 tenant 首次 provision 时自动带。现有 3 个 tenant 一次性脚本补装。

### 4.2 metadata DB 新增

```sql
-- tenant → 派生目标 memory base 的映射（和 agentfs_assignments 对称）
CREATE TABLE agentfs_memory_targets (
  tenant_id       VARCHAR(32) PRIMARY KEY,
  memory_base_id  VARCHAR(32) NOT NULL,
  auto_created    BOOLEAN NOT NULL DEFAULT false,  -- 区分 auto-provision vs 用户选择
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agentfs_memory_targets_base
  ON agentfs_memory_targets(memory_base_id);

-- forwarder HA 抢锁
CREATE TABLE agentfs_forwarder_locks (
  tenant_id      VARCHAR(32) PRIMARY KEY,
  locked_by      VARCHAR(64) NOT NULL,            -- pod hostname
  locked_until   TIMESTAMPTZ NOT NULL,
  last_event_id  BIGINT DEFAULT 0,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 4.3 per-base DB 新增（在每个 memory base 的 `memories` 表上）

```sql
-- 幂等索引：同 (source_path, source_etag) 只能一行
CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_source_idempotent
  ON memories ((metadata->>'source_path'), (metadata->>'source_etag'))
  WHERE metadata ? 'source_path';
```

**metadata JSON 约定**（AgentFS 派生来的行）：
```json
{
  "source_system": "agentfs",
  "source_path": "/memory/feedback_no_emoji.md",
  "source_etag": "sha256-hex",
  "source_agent": "claude",
  "source_frontmatter": { "name": "...", "description": "...", "type": "feedback" }
}
```

## 5. 组件

### 5.1 lakeon-api · `AgentFSEventForwarder`（新增）

```java
@Component
@ConditionalOnProperty(name = "lakeon.agentfs.forwarder.enabled", havingValue = "true", matchIfMissing = true)
public class AgentFSEventForwarder {

    private static final int BATCH_SIZE = 100;
    private static final int LOCK_SECONDS = 30;
    private static final int MAX_RETRY = 5;
    private final String myId = computePodId();  // hostname

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        for (AgentFSAssignmentEntity a : assignmentRepo.findByStatus("READY")) {
            if (!tryAcquireLock(a.getTenantId())) continue;
            try { processTenant(a); }
            finally { releaseLock(a.getTenantId()); }
        }
    }

    private boolean tryAcquireLock(String tenantId) {
        // INSERT ... ON CONFLICT DO UPDATE WHERE locked_until < now()
        // 返回 rows_affected == 1 表示抢到
    }

    private void processTenant(AgentFSAssignmentEntity a) {
        try (Connection c = dbm.openConnection(tenant(a))) {
            // 1. backfill seed（首次处理）
            seedBackfillIfEmpty(c);

            // 2. 读 pending events
            List<Event> events = loadPending(c, BATCH_SIZE);

            // 3. 过滤白名单 + 解析 frontmatter
            List<DeriveRequest> reqs = events.stream()
                .filter(e -> isWhitelist(e.path))
                .map(e -> buildDerive(c, e))
                .toList();

            // 4. HTTP POST memory-svc（批量）
            for (DeriveRequest req : reqs) {
                DeriveResponse resp = memorySvcClient.derive(req);
                if (resp.status == 200) markDone(c, req.eventId);
                else if (resp.status == 202) { /* leave pending, retry next cycle */ }
                else { bumpRetry(c, req.eventId, resp.error); }
            }
        }
    }
}
```

**关键细节**：
- **抢锁 upsert**：`INSERT INTO agentfs_forwarder_locks (tenant_id, locked_by, locked_until) VALUES (?, ?, now() + '30s') ON CONFLICT (tenant_id) DO UPDATE SET locked_by = EXCLUDED.locked_by, locked_until = EXCLUDED.locked_until WHERE agentfs_forwarder_locks.locked_until < now() RETURNING tenant_id` — 返回非空表示抢到
- **seedBackfillIfEmpty**：`INSERT INTO agentfs_events(path, etag, event_type) SELECT path, etag, 'backfill' FROM files WHERE kind='file' ON CONFLICT DO NOTHING` — 通过 `idx_agentfs_events_pending` 检查 `SELECT 1 FROM events WHERE status='pending' LIMIT 1`，空才种子化
- **白名单过滤**：Java regex `^/memory/[^/]+\.md$|^/projects/[^/]+/memory/[^/]+\.md$`，且排除 `.endsWith("/MEMORY.md")`
- **frontmatter 解析**：读文件头的 YAML frontmatter（`---` 包裹），提取 `type` / `name` / `description`，映射到 memories.memory_type：
  - `feedback` → `procedural`
  - `project` → `episode`
  - `reference` → `fact`
  - `user` → `fact`
  - 无 frontmatter → `fact`（兜底）
- **重试**：5xx / 超时 / 连接错 → `retry_count++`，`MAX_RETRY` 次后 `status='poison'` + `last_error` 保留，admin 可见

### 5.2 lakeon-api · `AgentFSMemoryTargetController`（新增）

```
POST /api/v1/agentfs/memory-target
  body: { base_id: "mem_xxx" }
  → upsert agentfs_memory_targets(tenant_id, base_id, auto_created=false)

GET /api/v1/agentfs/memory-target
  → { base_id, auto_created, updated_at, pending_derivation_count }

GET /api/v1/memory/bases  （现有，响应扩展）
  → 每条 base 增加 is_agentfs_target: true|false
```

### 5.3 memory-svc · `POST /api/v1/agentfs/derive`（新增）

```python
@app.post("/api/v1/agentfs/derive")
async def derive(req: DeriveRequest):
    # 1. 查 target
    target = await fetch_target_or_provision(req.tenant_id, req.agent_hint)
    if target.status == "PROVISIONING":
        return Response(status_code=202)  # forwarder 下轮重试

    # 2. dispatch
    if req.op == "delete":
        await delete_by_source_path(target.base_connstr, req.path)
    else:
        # create / update / backfill 都 → memory_ingest
        # INSERT ... ON CONFLICT ON CONSTRAINT idx_memories_source_idempotent DO NOTHING
        await ingest_idempotent(
            target.base_connstr,
            content=req.content,
            memory_type=req.memory_type,
            metadata={
                "source_system": "agentfs",
                "source_path": req.path,
                "source_etag": req.source_etag,
                "source_agent": req.agent,
                "source_frontmatter": req.frontmatter,
            },
        )
    return Response(status_code=200)
```

**fetch_target_or_provision** 调用链：
1. memory-svc HTTP GET lakeon-api 内部端点 `GET /api/v1/internal/agentfs/memory-target?tenant_id=X`（Authorization 用 service-to-service token）
   - 200 `{base_id, base_status}` → 若 `base_status=READY` 返回 base connstr；否则返回 PROVISIONING
   - 404 → base 未绑定
2. base 未绑定 / target 不存在 → memory-svc HTTP POST lakeon-api `POST /api/v1/internal/agentfs/auto-provision-target`
   - body: `{tenant_id, agent_hint}`
   - lakeon-api 内部：
     - 调 `MemoryBaseService.create(tenant, name=f"agentfs-{agent}")` 产生 PROVISIONING base
     - 立即 upsert `agentfs_memory_targets(tenant_id, memory_base_id, auto_created=true)`
     - 返回 `{base_id, base_status=PROVISIONING}`
3. memory-svc 收到 PROVISIONING → 返回 **202 Accepted** 给 forwarder
4. forwarder 见 202 保留 event pending，下一轮（30s 后）重试 → base ready → memory-svc 返回 200

**两个新内部端点（仅 service-to-service，不暴露给用户）**：
- `GET /api/v1/internal/agentfs/memory-target` — 查 target
- `POST /api/v1/internal/agentfs/auto-provision-target` — auto-provision 首次派生时的 base
- 都通过 `Authorization: Bearer $INTERNAL_SERVICE_TOKEN` 保护（env 注入到 memory-svc）

### 5.4 Console 前端

**"记忆库" 页面改动**（~80 行 Vue）：
- 列表每行新增列 "AgentFS 目标"：radio 单选，互斥
- 默认选中 `is_agentfs_target=true` 那行（通过扩展的 `GET /memory/bases` 拿到）
- 切换时调 `POST /api/v1/agentfs/memory-target`
- base 名字旁徽章：`auto_created=true` 时显示 `[auto]` + tooltip "系统自动创建（AgentFS 派生库）"
- 顶栏红条（挂在页面 header 上，全局可见）：
  - `GET /api/v1/agentfs/memory-target` 返回 `pending_derivation_count > 0` 且无 target
  - 显示 "AgentFS 有 N 条待派生 memory，请选一个目标 base"
  - 点击跳到记忆库页面 + 高亮 radio 列

## 6. 数据流

### 6.1 写入（hot path）

```
CC 写 ~/.claude/memory/feedback_x.md
  → FUSE → outbox Op::Put
  → dbay-fuse uplink → POST /agentfs/files/put
    → lakeon-api AgentFSService.put
    → per-tenant files INSERT / UPDATE (etag 变了才 UPDATE)
      → trigger → agentfs_events INSERT (event_type=create|update)
```

### 6.2 派生（cold path, ~30s 后）

```
AgentFSEventForwarder @Scheduled (30s)
  → 抢锁 agentfs_forwarder_locks for tenant X
    → open per-tenant DB
    → SELECT * FROM agentfs_events WHERE status=pending AND path match LIMIT 100
    → parse frontmatter → memory_type
    → POST memory-svc /derive {base_id, path, op, content, memory_type, metadata}
      ├─ 200 → UPDATE events SET status=done, processed_at=now()
      ├─ 202 → 保留 pending，下轮重试（base 在 provision）
      └─ 5xx → retry_count++（N 次后 poison）
```

### 6.3 删除

```
CC rm 文件 → FUSE Op::Delete → lakeon-api delete → files DELETE
  → trigger → agentfs_events (event_type=delete, etag=OLD.etag)
  → forwarder → memory-svc /derive (op=delete, path=..., source_etag=OLD)
    → memory-svc: DELETE FROM memories WHERE metadata->>'source_path'=?
```

### 6.4 首次 takeover 场景（新用户）

```
用户：安装 dbay-fuse → mount → takeover
  ↓
Phase 1：takeover 写 rescan.trigger → daemon scan state → 上传 N 文件
  → lakeon-api 触发 N 条 INSERT trigger → agentfs_events 出现 N 条 pending
  ↓
Phase 2：@Scheduled 下轮（<30s）
  → forwarder 抢到 tenant X 的锁
  → 读 pending events
  → 首次 HTTP POST memory-svc /derive
  → memory-svc 发现无 target base → 异步 provision + 202
  → forwarder 保留 events pending
  ↓
~30s 后：base ready
  → forwarder 再轮询 → memory-svc 现在返回 200
  → memories 表 UNIQUE 索引确保幂等 → events 逐步 done
  ↓
用户 memory_recall "xyz" → 命中 ✓
```

## 7. 错误处理

| 场景 | 处理 |
|---|---|
| memory-svc 返回 202（provision 中）| forwarder 不 ACK，下轮重试（无上限）|
| memory-svc 返回 5xx / 网络超时 | retry_count++，5 次后 `status=poison`，保留 last_error |
| forwarder pod 崩 | 锁 30s 超时后另一 pod 接管；已 `status=done` 的 event 不重处理 |
| 两 pod 同时抢锁 | upsert WHERE locked_until < now() 只一个成功 |
| trigger 内部 exception | AFTER trigger 的异常会 abort 主事务（PUT 失败）— trigger 逻辑必须简单幂等（只 INSERT events），不做复杂处理 |
| base provision 失败 | memory-svc 返回 5xx，forwarder 重试；5 次后 poison + admin 告警 |
| 用户切换 target base | forwarder 下轮看到新 target_id，新事件去新 base；旧 base 里派生过的条目保留（不自动 migrate，用户手动或 Phase 3 做）|
| 用户删 target base | `agentfs_memory_targets` 记录不变，下次 derive 失败 → memory-svc 检测到 base 不存在 → 自动清除 targets 记录 + re-provision auto base |

## 8. 迁移 / rollout

### 8.1 Schema migrations

- **lakeon-api metadata DB**（Flyway 新 migration）：
  - V38: `CREATE TABLE agentfs_memory_targets`
  - V39: `CREATE TABLE agentfs_forwarder_locks`

- **每个 agentfs_\<uuid\> DB**：
  - 新 tenant：`AgentFSDatabaseManager.FILES_SCHEMA` 扩展，自动带 `agentfs_events` + trigger
  - 现有 3 个 tenant：一次性运维脚本（`deploy/cce/migrate-agentfs-events.sh`）逐个连接 + 装 schema
    ```sql
    -- 幂等：IF NOT EXISTS
    CREATE TABLE IF NOT EXISTS agentfs_events (...);
    CREATE OR REPLACE FUNCTION agentfs_files_event_fn() ...;
    DROP TRIGGER IF EXISTS agentfs_files_event_trg ON files;
    CREATE TRIGGER agentfs_files_event_trg ...;
    ```

- **per-base memories 表**（已有多 base）：
  - 另一运维脚本扫所有 memory_bases，逐个连接加 `CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_source_idempotent`

### 8.2 Rollout 阶段

1. **Phase 2a**（后端）：
   - 部署 schema migrations（metadata + per-tenant + per-base）
   - 部署 lakeon-api 新版（带 forwarder，但用 feature flag `lakeon.agentfs.forwarder.enabled=false` 先关）
   - 部署 memory-svc 新版（带 /derive endpoint，独立可用）
   - 测试：手动 POST /derive，验证 memories 表插入 + 幂等
   - 打开 feature flag → forwarder 开始工作，通过现有 tenant 观察一天
2. **Phase 2b**（前端 UI）：
   - 部署 Console 更新（radio + badge + 红条）
   - 用户首次看到可选配置
3. **Phase 2c**（backfill）：
   - forwarder 对每 tenant 首次处理时自动 seed backfill events
   - 监控：admin 看 dashboard `pending_events / poison_events` 按 tenant 分布

## 9. 测试策略

### 9.1 单元测试
- lakeon-api：
  - AgentFSEventForwarder 抢锁逻辑（两 pod 模拟并发）
  - frontmatter parser（feedback/project/reference/user + 无 frontmatter + 畸形）
  - backfill seed 幂等
- memory-svc：
  - /derive endpoint（create/update/delete/backfill）
  - 幂等索引触发（同 source_etag 二次 POST 返回 200 但不插入第二行）
  - auto-provision 202 返回
  - target base 缺失时触发 provision

### 9.2 E2E 机制测试（pytest）
- `tests/e2e/test_agentfs_phase2.py`：
  1. 创建 disposable tenant
  2. PUT 一个 `feedback_no_emoji.md` 到 AgentFS
  3. poll_until `memory_recall "emoji"` 返回该条（超时 2 分钟，覆盖 forwarder 30s 周期 + base provision 30s）
  4. 更新同文件 → 验证 memory 行的 source_etag 更新
  5. DELETE 文件 → 验证 memory 行被删
  6. PUT MEMORY.md → 验证**不**派生
  7. PUT `/projects/X/memory/project_foo.md` → 验证派生，scope 暂时全平坦
  8. 并发场景：同时 2 pod 跑 forwarder → 事件不重复派生
  9. 切换 target base → 新事件去新 base
- 断言端到端业务结果，不止 API 返回 200（per CLAUDE.md E2E 纪律）

### 9.3 幂等性回归
- Rerun 已有 `test_agentfs_idempotent.py`（服务端 PUT 幂等）
- 新 `test_derive_idempotent.py`：POST /derive 同一 body 2 次 → 第二次 200，memories 表只 1 行

### 9.4 记忆质量测试（pytest）— 用真实记忆语料验证质量

**测试语料** = 项目主 maintainer 的 43 个真实 memory md 文件（`~/.claude/projects/-Users-jacky-code-lakeon/memory/`），覆盖 4 种 frontmatter type、中英混排、真实业务主题（部署、E2E 测试、memory 加密、TPC-H、GPU 推理、数据湖...）。

**隔离与安全**（per `feedback_prod_isolation.md` 和 `universal-memory.md §6`）：
- 测试在 **disposable tenant** 上跑，teardown 自动删
- fixture 读取前**自动 redact** `user_api_credentials.md` / `reference_cross_project_tokens.md` 里的 api key 正则（`lk_[0-9a-f]+`, `sk-[A-Za-z0-9]+`），换成 `lk_REDACTED` / `sk_REDACTED` 再上传
- 生产 tenant 的数据只读，测试输出写进 disposable tenant，不回写

**新增 `tests/e2e/test_agentfs_phase2_quality.py`**

```python
@pytest.fixture(scope="module")
def real_memory_corpus():
    """读主 maintainer 的 43 个 md 文件 + redact 敏感信息"""
    corpus_dir = Path("~/.claude/projects/-Users-jacky-code-lakeon/memory").expanduser()
    files = []
    for p in corpus_dir.glob("*.md"):
        if p.name == "MEMORY.md":
            continue  # 视图文件，不应派生
        content = p.read_text()
        content = _redact_secrets(content)
        files.append({"path": p.name, "content": content})
    return files  # 42 条（43 - MEMORY.md）
```

#### 测试用例

1. **全语料派生完成** (`test_full_corpus_derives_all`)
   - fixture 上传 42 个文件到 disposable tenant 的 AgentFS
   - poll_until memories 表行数 == 42（超时 5 分钟，覆盖首次 base provision + 42 × forwarder 批次）
   - 断言：每个文件都恰好产生 1 条 memory 行（按 `metadata->>'source_path'` 对应）

2. **frontmatter → memory_type 映射** (`test_corpus_type_mapping`)
   - 对语料里每个文件，检查产生的 memories.memory_type：
     - `feedback_*.md` (11 个) → 全部 `procedural`
     - `project_*.md` (26 个) → 全部 `episode`
     - `reference_*.md` (3 个) → 全部 `fact`
     - `user_*.md` (2 个) → 全部 `fact`
     - 其他无 frontmatter → `fact`（兜底）

3. **召回命中真实 query** (`test_recall_hits_known_truth`)
   - 用户常问的 10 条真实 query，每条标注"应该召回哪个文件"作为 ground truth：

     | query | 预期文件 (top-3 含) |
     |---|---|
     | "hwstaff 部署" | feedback_deploy_hwstaff.md |
     | "E2E 测试纪律" | feedback_e2e_testing.md |
     | "don't use emoji" | feedback_design_preferences.md |
     | "memory 加密实现" | project_memory_encryption.md |
     | "TPC-H benchmark 结果" | project_tpch_benchmark.md |
     | "华为云 MaaS DeepSeek" | project_llm_provider.md |
     | "pageserver re-attach" | project_pageserver_reattach_gap.md |
     | "cross-project tokens" | reference_cross_project_tokens.md |
     | "KB sharing API" | project_kb_sharing.md |
     | "CCE 基础设施" | project_cce_infrastructure.md |

   - 每条 query 调 `memory_recall(top_k=3)`，断言 ground truth 文件在返回内
   - 通过门槛：**10/10 命中**（否则 embedding 质量不合格）

4. **语义区分度** (`test_corpus_topic_discrimination`)
   - 4 对近义但主题不同的 query + ground truth：

     | 相近主题 | query A 应命中 | query B 应命中 |
     |---|---|---|
     | E2E vs 单元测试 | feedback_e2e_testing.md | feedback_prepush_typecheck.md |
     | 部署 vs CI/CD | feedback_deploy_hwstaff.md | feedback_pull_before_push.md |
     | memory 加密 vs KB 分享 | project_memory_encryption.md | project_kb_sharing.md |
     | TPC-H vs MemOS benchmark | project_tpch_benchmark.md | project_memos_locomo_benchmark.md |

   - 每对调两次 recall(top_k=1)，断言分别命中 A 和 B
   - 通过门槛：**4/4 完全区分**

5. **不污染现有 memory** (`test_no_recall_pollution`)
   - 在 disposable tenant 先通过 `memory_ingest` 写 10 条无关 memory（"我喜欢吃苹果" 之类）
   - 记录 10 条 query 的 top-3 基线
   - 派生 42 条真实语料
   - 重跑 10 条 query，断言 top-3 与基线 Kendall tau ≥ 0.5（小扰动可接受）

6. **内容保真** (`test_corpus_content_fidelity`)
   - 对每个派生的 memory：`memory.content` == `文件原内容 - frontmatter block`
   - 覆盖中英混排、代码块、bullet list 格式

7. **metadata 完整透传** (`test_corpus_metadata_preserved`)
   - 任取 5 个派生 memory，断言 metadata 含：
     - `source_system="agentfs"`
     - `source_path` 以 `/projects/.../memory/` 开头
     - `source_etag` 是 64-char hex
     - `source_agent` = "claude"
     - `source_frontmatter.name` / `.description` / `.type` 与原文件一致

8. **删除彻底** (`test_corpus_delete_removes_from_recall`)
   - 从 42 条里选 `feedback_no_emoji.md`，确认 recall "emoji" 命中
   - DELETE via AgentFS
   - poll_until recall "emoji" 不再返回该条

9. **跨设备一致** (`test_corpus_cross_device_visibility`)
   - 用 disposable tenant 的 api_key 起两个 `DbayClient` 实例（模拟 A 机器 B 机器）
   - A 上传 file X → B `memory_recall(file X 关键词)` 命中

10. **并发 forwarder 无重复派生** (`test_concurrent_forwarder_no_dup`)
    - 模拟 2 pod（起 2 个 forwarder 协程）对 disposable tenant 同时跑
    - 42 条语料全派生完后，每条 source_path 在 memories 表恰好 1 行（UNIQUE 索引保证）
    - `agentfs_events` 里无 `status='poison'`

#### 质量门槛总表

| # | 名称 | PASS 条件 |
|---|---|---|
| 1 | 全语料派生 | 42/42 派生成功 |
| 2 | 类型映射 | 42/42 正确 |
| 3 | 召回命中 ground truth | **10/10 hit in top-3** |
| 4 | 语义区分度 | **4/4 pair 区分对** |
| 5 | 不污染 | Kendall tau ≥ 0.5 |
| 6 | 内容保真 | 42/42 byte-equal |
| 7 | metadata 透传 | 5/5 抽样完整 |
| 8 | 删除彻底 | 命中→ 60s 内消失 |
| 9 | 跨设备 | 命中 |
| 10 | 并发无重复 | 42/42 唯一 + 0 poison |

**任何一项不达门槛 = Phase 2 不能上线**（per CLAUDE.md E2E 纪律"全 PASSED 才算成功，不允许 skip"）。

## 10. 已知风险与技术债

| 风险 | 处理 |
|---|---|
| **scope 轴缺失**：global / per_project 区分不了，多 agent 共用同 base 会混 | Phase 3 补 `memories.scope + scope_ref` 列 + 回填 |
| **origin 轴缺失**：用户手写 vs 系统派生无法区分 | Phase 3 补 `memories.origin` |
| **精细 memory_type 塌成 6 种** | Phase 3 演进到 universal-memory spec §2.2 的 12 种 |
| **split-brain（缓解未根治）**：Hibernate 2nd-level cache 过期 | 独立 tech-debt：`openConnection` 强制 cache evict |
| **@WebMvcTest 全失效**：TrialDemoFilter 无 @MockBean | 独立工单 #12 |
| **forwarder 单 pod 处理吞吐上限**：~1000 事件/分钟 | Phase 3 压力测试后再看 |
| **base 切换不迁移历史**：切新 base 后旧 base 派生过的条目留在原地 | 用户需要自行处理；Phase 3 加 migrate API |
| **auto-provision 首次 30s 延迟** | 正常行为，UI 给用户明确提示"首次派生约 30s" |

## 11. 参考
- `specs/universal-memory.md` - 泛记忆规范 v0.1（本 MVP 是其简化子集）
- `specs/agentfs-openapi.yaml` - AgentFS REST API 契约
- `docs/agentfs-user-guide.md` - 用户侧使用指南
- `AgentFSService.java`, `AgentFSDatabaseManager.java` - Phase 1 后端
- `memory/service/engine.py` - 现有 memory_ingest 实现
- Red-blue 对抗记录（会话内，变更来源可溯）
