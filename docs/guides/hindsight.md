# Hindsight + dbay.cloud：Serverless Agent 记忆后端

[Hindsight](https://github.com/vectorize-io/hindsight) 是 Vectorize 开源的 Agent 记忆系统（LongMemEval 91%，3.8k Stars），内置 MCP server，可直接在 Claude Code / Cursor 中使用。

Hindsight 原生使用 PostgreSQL + pgvector，天然兼容 dbay.cloud——**零代码改造，一个环境变量搞定**。

## 为什么？

Hindsight 默认使用内嵌 PostgreSQL（pg0），方便开发但不适合生产：
- 数据存在容器里，重启丢失
- 单机，无法弹性扩缩
- 无备份、无分支、无高可用

**换成 dbay.cloud，你得到的是：**

| 能力 | 内嵌 pg0 | dbay.cloud |
|------|---------|-----------|
| **数据持久化** | 容器内，重启丢失 | 云端持久化，存算分离 |
| **空闲成本** | Docker 容器 24/7 运行 | **Scale-to-zero，空闲零成本** |
| **冷启动** | 每次都要初始化 | ~500ms 热启动 / ~8s 冷启动 |
| **算力弹性** | 固定 | **1cu–8cu 按需伸缩** |
| **pgvector** | 需自行确保版本 | 预装，HNSW 索引开箱即用 |
| **BM25 全文检索** | PG 原生 FTS | **pg_search (ParadeDB) 预装，更强** |
| **数据库分支** | 不可能 | **Git 风格 copy-on-write 分支** |
| **运维** | 自己管 | 零运维 |
| **中国合规** | 取决于你部署在哪 | 数据在华为云，不出境 |

### Scale-to-zero：每个用户一个记忆库也不贵

Hindsight 支持 Memory Bank 隔离。如果你想给每个用户一个独立数据库，dbay.cloud 的 scale-to-zero 让空闲用户零成本。只有活跃用户才消耗计算资源。

### 弹性算力：反思任务不卡顿

Hindsight 的 `reflect` 操作（Mental Model 更新、事实整合）是 CPU 密集的。dbay.cloud 可以临时扩到 8cu 跑完，然后自动缩回 1cu。

### 数据库分支：安全地迭代记忆策略

修改了实体提取 prompt？调整了 disposition（skepticism/literalism/empathy）参数？用 dbay.cloud 分支在副本上测试，不影响生产数据。

## 快速开始

### 1. 创建 dbay.cloud 数据库

在 [dbay.cloud](https://dbay.cloud) 注册并创建数据库。

### 2. 启动 Hindsight

**Docker（推荐）：**

```bash
docker run --rm -p 8888:8888 -p 9999:9999 \
  -e HINDSIGHT_API_DATABASE_URL="postgresql://user_xxx:password@pg.dbay.cloud:4432/hindsight?sslmode=require&options=endpoint%3Dhindsight" \
  -e HINDSIGHT_API_LLM_PROVIDER=openai \
  -e HINDSIGHT_API_LLM_API_KEY=sk-... \
  ghcr.io/vectorize-io/hindsight:latest
```

**pip：**

```bash
pip install hindsight-api-slim

export HINDSIGHT_API_DATABASE_URL="postgresql://user_xxx:password@pg.dbay.cloud:4432/hindsight?sslmode=require&options=endpoint%3Dhindsight"
export HINDSIGHT_API_LLM_PROVIDER=openai
export HINDSIGHT_API_LLM_API_KEY=sk-...

hindsight-api
```

**Helm / Kubernetes：**

```bash
helm install hindsight oci://ghcr.io/vectorize-io/charts/hindsight \
  --set postgresql.enabled=false \
  --set api.database.url="postgresql://user_xxx:password@pg.dbay.cloud:4432/hindsight?sslmode=require"
```

### 3. 使用 API

```bash
# 存储记忆
curl -X POST http://localhost:8888/v1/default/banks/default/memories \
  -H "Content-Type: application/json" \
  -d '{"items": [{"role": "user", "content": "Alice 在 Google 当工程师"}]}'

# 检索记忆
curl -X POST http://localhost:8888/v1/default/banks/default/memories/recall \
  -H "Content-Type: application/json" \
  -d '{"query": "Alice 在哪里工作？"}'
```

### 4. 在 Claude Code 中使用（MCP）

Hindsight 内置 MCP server，启动后配置 Claude Code：

```json
{
  "mcpServers": {
    "hindsight": {
      "type": "streamable-http",
      "url": "http://localhost:8888/mcp"
    }
  }
}
```

### 使用国产 LLM（SiliconFlow / DeepSeek）

```bash
export HINDSIGHT_API_LLM_PROVIDER=openai
export HINDSIGHT_API_LLM_API_KEY=sk-...
export HINDSIGHT_API_LLM_BASE_URL=https://api.siliconflow.cn/v1
export HINDSIGHT_API_LLM_MODEL=deepseek-ai/DeepSeek-V3

export HINDSIGHT_API_EMBEDDINGS_PROVIDER=litellm-sdk
export HINDSIGHT_API_EMBEDDINGS_LITELLM_SDK_API_KEY=sk-...
export HINDSIGHT_API_EMBEDDINGS_LITELLM_SDK_API_BASE=https://api.siliconflow.cn/v1
export HINDSIGHT_API_EMBEDDINGS_LITELLM_SDK_MODEL=openai/BAAI/bge-m3
```

## 已知问题

### Alembic Migration 的 URL 转义

Hindsight 启动时自动运行数据库 migration（Alembic）。dbay.cloud 连接串中 `options=endpoint%3D...` 的 `%` 会被 Alembic 误解析。

**解法**：手动运行 migration 后禁用自动 migration：

```bash
# 先手动 migration（仅首次需要）
python -c "
import alembic.config
_orig = alembic.config.Config.set_main_option
def _patched(self, name, value):
    if name == 'sqlalchemy.url' and '%' in value:
        value = value.replace('%', '%%')
    return _orig(self, name, value)
alembic.config.Config.set_main_option = _patched
from hindsight_api.migrations import run_migrations
run_migrations('你的dbay连接串')
"

# 然后启动时禁用自动 migration
export HINDSIGHT_API_RUN_MIGRATIONS_ON_STARTUP=false
hindsight-api
```

Docker 部署通常不会遇到此问题（容器内 libpq 支持 SNI）。

## 参考

- [Hindsight 文档](https://hindsight.vectorize.io/)
- [Hindsight GitHub](https://github.com/vectorize-io/hindsight)
- [Hindsight 配置参考](https://hindsight.vectorize.io/developer/configuration)
- [dbay.cloud](https://dbay.cloud)
