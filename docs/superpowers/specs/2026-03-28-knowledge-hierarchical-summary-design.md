# 知识库层次摘���设计（Per-Document Summary）

> 日期：2026-03-28
> 状态：待实施

## 1. 目标

为知识库新增文档级摘要层（level=1）和 KB 全局摘要层（level=2），解决"跨文档总结性问题回答不了"的痛��。

当前只有 level=0 原始 chunks，用户问"这篇文档讲什么"或"知识库里有哪些主题"时无法命中。新增摘要层后，搜索同时覆盖原始 chunks 和文档摘要，提升 comprehensiveness。

## 2. 层级定义

| level | 内容 | 粒度 | 参与搜索 | 生成方式 |
|-------|------|------|---------|---------|
| 0 | 原始 chunks（现有） | 400 tokens/chunk | 是 | 文档解析时 |
| 1 | 文档摘要（新增） | 每文档 1 条，300-500 字 | 是 | 文档解析完成后异步 |
| 2 | KB 全局摘要（新增） | 每 KB 1 条 | 否（展示用） | 所有 L1 就绪后异步 |

## 3. 管线变更

### 3.1 现有管线（不变）

```
DOWNLOAD → PARSE → CHUNK → EMBED → WRITE(level=0) → callback通知API
```

### 3.2 新增异步阶段

```
callback通知API(文档完成)
  → API入队 DOCUMENT_SUMMARIZE 任务
    → 从OBS读取 fulltext.md
    → 截断到28K字符（预留LLM输出空间）
    → 调LLM生成摘要
    → 对摘要调BGE-M3 embedding
    → 写入 knowledge_chunks(level=1)
    → 检查该KB所有文档是否都有L1
      → 是：入队 KB_SUMMARIZE 任务
        → 读取所有L1摘要拼接
        → 调LLM生成KB全局摘要
        → embedding → 写入 knowledge_chunks(level=2, document_id='__kb_summary__')
```

### 3.3 执行位置

DOCUMENT_SUMMARIZE 和 KB_SUMMARIZE 是**轻量任务**，直接在 API 侧通过 KbWriteQueue 执行（JDBC + HTTP 调 LLM/Embedding），不需要启动 Job Pod。

文档处理完成状态不依赖 SUMMARIZE——用户看到"完成"时 L0 已可用。

## 4. 任务看护

新增 `DOCUMENT_SUMMARIZE` 和 `KB_SUMMARIZE` 两种任务类型，复用 KbWriteQueue 机制。

### 4.1 任务生命周期

```
PENDING → RUNNING → COMPLETED
                  → FAILED(retry_count < 3) → PENDING(重新入队)
                  → FAILED(retry_count >= 3) → ABANDONED(不再重试)
```

### 4.2 三层看护

| 层 | 触发 | 行为 |
|---|------|------|
| 即时重试 | LLM 调用返回 5xx / 超时 | 指��退避 10s/30s/90s，最多 3 次 |
| 卡死检测 | API 启动时 + 每 10 分钟定时扫描 | RUNNING 超 5 分钟的任务重置为 PENDING |
| 放弃清理 | retry_count >= 3 | 标记 ABANDONED，记录错误原因 |

### 4.3 可观测性

- 每次失败写日志（文档 ID + 错误原因 + 第几次重试）
- Admin 控制台可查看各 KB 的摘要生成状态（已完成/进行中/失败）
- 手动重试 API：`POST /admin/knowledge/{kbId}/documents/{docId}/resummarize`

## 5. 搜索增强

### 5.1 SQL 变更

搜��查询的 `WHERE level = 0` 改为 `WHERE level IN (0, 1)`，同时搜索原始 chunks 和文档摘要。

### 5.2 排序策略

- RRF 公式不变：`1/(60+rank_semantic) + 1/(60+rank_fts)`
- 不给 L1 加权重 boost，让 RRF 自然排序
- 搜索��果中标记 `"level": 1`，前端可区分展示

### 5.3 不做的事

- 不做 L1 命中后自动展开 L0 children
- 不做权重调参

## 6. LLM 配置

| 参数 | 值 |
|------|-----|
| API | SiliconFlow（`https://api.siliconflow.cn/v1`） |
| 模型 | `deepseek-ai/DeepSeek-V3.2` |
| temperature | 0.0 |
| fulltext 截断 | 28K 字符 |
| 输出长度 | 300-500 字 |

模型可通过 KB 参数 `summary_model` 覆盖。

### 6.1 Prompt

```
你是一个文档摘要助手。请为以下文档生成一份结构化摘要。

要求：
1. 用中文输出（除非原文是纯英文）
2. 先用一句话概括文档主题
3. 再列出3-7个关键要点
4. 总长度控制在300-500字
5. 保留专业术语原文

文档内容：
{fulltext}
```

## 7. 数据存储

### 7.1 level=1（文档摘要）

写入 `knowledge_chunks` 表：

| 字段 | 值 |
|------|-----|
| document_id | ��源文档相同 |
| chunk_index | 0 |
| content | LLM 生成的摘要文本 |
| embedding | BGE-M3 1024 维向量 |
| level | 1 |
| source_chunks | 该文档所有 L0 chunk 的 id 数组 |
| metadata | `{"type": "document_summary"}` |

### 7.2 level=2（KB 全局摘要）

| 字段 | 值 |
|------|-----|
| document_id | `__kb_summary__` |
| chunk_index | 0 |
| content | LLM 生成的 KB 级摘要 |
| embedding | BGE-M3 1024 维向量 |
| level | 2 |
| source_chunks | 所有 L1 chunk 的 id 数组 |
| metadata | `{"type": "kb_summary"}` |

### 7.3 KB 全局摘要用途

- KB 详情页顶部展示"知识库概览"
- MCP `knowledge_list()` 返回各 KB 摘要，帮助 Agent 选择搜索哪个 KB
- 不参与向量搜索

## 8. 增量���新

| 场景 | 行为 |
|------|------|
| 新文档上传 | 生成该文档的 L1，然后重新生成 L2 |
| 文档删除 | 删除对应 L1，重新生成 L2 |
| 文档重新解析 | 删除旧 L1，重新生成 L1，然后重新生成 L2 |

## 9. 向后兼容

- 现有代码查询 `level = 0` 的逻辑不受影响
- 新增的 level=1/2 数据对现有功能透明
- Chunk 列表 API 已支持 `level` 参数过滤
- HNSW 和 GIN 索引自动覆盖新增行，无需建新索引

## 10. 不在本期范围

- RAPTOR 语义聚类（方案 B）——需要时可在 level=1 之上叠加
- 按 heading 分段生成多条 L1（长文档优化）
- L1 命中后自动展开 L0 children
- 搜索权重 boost 调参
