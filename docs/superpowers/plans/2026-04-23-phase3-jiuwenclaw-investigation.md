# Phase 3 · jiuwenclaw 集成可行性调研

> 2026-04-23 调研结论：**jiuwenclaw 的 memory 架构与 openjiuwen 的 LongTermMemory 完全独立，原 Phase 3 plan 里"jiuwenclaw 集成 PR"任务的技术前提不成立**。需要重新 scope。

## 关键事实

### 1. jiuwenclaw 自带一套 memory 实现（不走 openjiuwen LongTermMemory）

- 入口：`jiuwenclaw/jiuwenclaw/agentserver/memory/manager.py`
- 类名：`MemoryIndexManager`
- 实现：**纯本地 SQLite + FTS5 + 二进制 blob 存 embedding**
  - `vector_to_blob(List[float]) -> bytes` 用 `struct.pack('{n}f', ...)`
  - `blob_to_vector(bytes) -> List[float]`
  - 表：`chunks_vec` / `chunks_fts` / `embedding_cache`
- 不实现 `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` 任何契约
- 不调用 `create_vector_store()`
- 配置：`config.yaml` 的 `memory.mode`，唯一可识别的值是 `${MEMORY_MODE:-local}`（默认 `local`）

### 2. 配置层拓扑

```yaml
# jiuwenclaw/resources/config.yaml
memory:
  mode: ${MEMORY_MODE:-local}   # 只有一种 mode
  # 没有 provider 枚举
  # 没有 vector_store_type 字段
  forbidden_memory_definition: ...

models: ...                      # 模型配置独立
embed:                           # embedding 独立拉
  api_base: ...
  model: ...
```

相比之下 openjiuwen 的 `LongTermMemory` 需要通过 `register_store(kv_store=, vector_store=, db_store=)` 注入三层存储——**jiuwenclaw 不经过这里**。

### 3. 唯一的插入点：`set_global_memory_manager`

`jiuwenclaw/agentserver/tools/memory_tools.py` 有：

```python
_global_manager: Optional[MemoryIndexManager] = None

def set_global_memory_manager(manager: Optional[MemoryIndexManager]) -> None:
    global _global_manager
    _global_manager = manager

async def init_memory_manager_async(...) -> Optional[MemoryIndexManager]:
    ...
    _global_manager = await MemoryIndexManager.get(...)
```

这是唯一一个"可被外部替换"的 hook——但需要传一个 `MemoryIndexManager` 实例（duck-typed 等价物也行，因为 Python 鸭子类型）。

### 4. 其他 embedding 来源：`jiuwenclaw.tools.browser-move` vendored patch

`jiuwenclaw/agentserver/tools/browser-move/src/openjiuwen_patch_sources/` 是 **vendored openjiuwen 源码副本**，在 tools 沙箱里跑 —— 跟 jiuwenclaw 主干的 memory 流程没有交集。

## 原 Phase 3 Plan 的假设 vs 实际

| 原 Plan 假设 | 实际 |
|---|---|
| jiuwenclaw 在某处调用 `create_vector_store(backend_name, ...)` | ❌ 不调用 |
| jiuwenclaw 的 config.yaml 里有 `backend` / `vector_store_type` 字段可以加 `dbay` | ❌ 没有这种字段 |
| 小改几行就能让 `backend=dbay` 生效 | ❌ 结构上不兼容 |

## 可选路径

### 路径 A：放弃 jiuwenclaw 集成目标（改 Phase 3 scope）

- Phase 2 插件 `openjiuwen-dbay-store` 只面向**直接用 openjiuwen SDK** 的开发者
- Phase 3 公告和博客的叙事："openJiuwen 社区第一个外部存储 backend 插件" —— 不提 jiuwenclaw
- 删掉 Phase 3 Task 3，保留 Task 1/4/5/6/7

**成本**：几乎为 0（只是调整叙事边界）
**影响**：放弃 jiuwenclaw 的产品级曝光，但插件本身核心价值不受影响

### 路径 B：写 `jiuwenclaw-memory-dbay` 桥接包（独立项目）

技术方案：
1. 新建独立仓 `jiuwenclaw-memory-dbay`
2. 实现类 `DbayMemoryManager`，duck-type 实现 jiuwenclaw `MemoryIndexManager` 的公开接口（`get()` / `search()` / `add()` / `remove()` 等）
3. 内部用 `openjiuwen-dbay-store` 的 `DbayVectorStore` + `DbayKVStore` 做底层
4. 用户在 jiuwenclaw 启动脚本里：
   ```python
   from jiuwenclaw_memory_dbay import DbayMemoryManager
   from jiuwenclaw.agentserver.tools import set_global_memory_manager
   set_global_memory_manager(DbayMemoryManager(dsn="..."))
   ```

**成本**：
- 需要逆向 `MemoryIndexManager` 所有被外部调用的方法（~1200 行 SQL-heavy 代码）
- duck-type 接口可能被 jiuwenclaw 内部假设扩大（例如期待 `.db` 属性是 sqlite3.Connection）
- 用户集成门槛高——需要改启动脚本
- 估计工作量：1-2 周

**影响**：可以让 jiuwenclaw 用户可选切换到托管 PG 后端

### 路径 C：推 jiuwenclaw 架构级演进（RFC 规模）

给 jiuwenclaw 提 RFC：
1. `memory.mode` 枚举扩展：加 `openjiuwen`（走 LongTermMemory）
2. 或者更大改动：把 memory 层从"`MemoryIndexManager` 唯一实现"改成"`MemoryProvider` 接口 + 多个 provider（`local_sqlite` / `openjiuwen` / ...）"
3. 需要 jiuwenclaw 核心团队接受

**成本**：
- RFC 讨论 → 实现 → review 流程可能几个月
- 被接受的概率不好预估

**影响**：最干净的长期方案，但时间长

## 推荐

**短期（本周）：走路径 A** —— Phase 3 只服务 openjiuwen agent-core 直接用户。

**中期（Phase 1 PR merge 后）：评估是否上路径 B** —— 等 openjiuwen-dbay-store 有真实用户 + feedback 再决定。

**长期：如果有 jiuwenclaw 核心团队合作机会，推路径 C**。

## 相关文件

- jiuwenclaw memory 入口：`~/code/jiuwenclaw/jiuwenclaw/agentserver/memory/manager.py:56` `MemoryIndexManager`
- jiuwenclaw memory hook：`~/code/jiuwenclaw/jiuwenclaw/agentserver/tools/memory_tools.py:86` `set_global_memory_manager()`
- jiuwenclaw memory config：`~/code/jiuwenclaw/jiuwenclaw/resources/config.yaml:16` `memory.mode`
- Phase 3 原始 plan：`~/code/lakeon/docs/superpowers/plans/2026-04-23-phase3-community-rollout.md`
