# Knowledge Pipeline & 数据飞轮路线图

> 2026-03-18 创建，2026-03-28 更新

## Phase 1 进度

### 1a. 通用 Job 框架 ✅ 完成

8 个 Java 文件 (`com.lakeon.job`)，与 Import 系统并行共存。

- JobEntity (job_ + 12 char ID, callbackToken)
- JobService (submit/query/cancel/callback + 异步 Pod 启动)
- JobPodManager (K8s Pod + ConfigMap + /dev/shm + nodeSelector)
- JobCallbackController (token 验证, 进度上报)
- JobScheduledTasks (PENDING 5min + RUNNING timeout 孤儿检测)
- **[2026-03-26] Job Pod 异常终止时捕获详细原因**（exit code、OOM、pod logs）
- **[2026-03-26] Job Pod 运行在 CCI serverless**（virtual-kubelet, 独立 lakeon-jobs namespace）

### 1b. Knowledge Pipeline ✅ 完成（已部署，48+ E2E 测试通过）

**Embedding Service** — BGE-M3 (1024维) + 硅基流动 API（不再自托管）

**Knowledge Job Pod** (`knowledge/job/`)
- parser.py: pymupdf4llm (PDF) + python-docx (DOCX) + ebooklib (EPUB) + 直读 (Markdown/TXT)
- chunker.py: Structure-aware (标题边界 + 代码/表格完整 + overlap + char_offset)
- writer.py: 写入用户 PG (pgvector + tsvector)
- Compute pod 挂起自动唤醒（`_ensure_compute_ready`）

**API Layer** (`com.lakeon.knowledge/`)
- KnowledgeBase CRUD、Document 上传/处理/批量、Chunk CRUD
- OBS 预签名 URL 直传、RRF 混合搜索（vector + tsvector + rerank）
- Fulltext 原文定位（char_offset → stripMarkdown → DOM 高亮）

**Console UI** ✅ 完成
- 知识库管理、文档上传（单文件/批量/目录）、搜索、切片查看/编辑
- 原文定位高亮、切片质量统计
- **[2026-03-26] 文档批量删除**（checkbox 全选 + 批量删除按钮）

### 1c. MCP Server ✅ 完成（2026-03-26）

原生 HTTP MCP 端点内嵌到 lakeon-api，Streamable HTTP transport。

**9 个 Tools：**
- 知识库: `knowledge_list_bases`, `knowledge_search`, `knowledge_list_documents`, `knowledge_get_chunk`
- 记忆库: `memory_recall`, `memory_ingest`, `memory_ingest_extracted`, `memory_list`, `memory_delete`

**接入方式：**
- Claude Code: `claude mcp add --scope user --transport http dbay https://api.dbay.cloud:8443/mcp --header "Authorization: Bearer lk_..."`
- Cursor/Windsurf: `.mcp.json` HTTP transport
- Console 接入指南已更新（记忆库详情页 + 文档页）

**定位：CC 的跨项目大脑**
- CC 原生 memory = L1 缓存（小量、全量注入、per-project）
- DBay memory = L2/L3 存储（大量、语义检索、cross-project、Q-value）
- 工具描述优化：`memory_ingest` 在用户说"记住"时触发，`memory_recall` 在凭据/经验/卡住时触发

### 1d. 记忆库场景类型 ✅ 完成（2026-03-26）

创建记忆库时选择场景（`scene` 字段），系统据此配置提取策略。

| 场景 | 值 | 提取策略 |
|------|---|---------|
| 开发者工具 | `DEVELOPER_TOOL` | fact/procedural/decision/rejection/convention，无 episode，无衰减 |
| 对话助理 | `CHAT_ASSISTANT` | 全类型含 episode，时间衰减，定期 trait 反思 |

- API: 创建时必填 `scene`，通过 `X-Scene` header 传给 Python 微服务
- Python: `extraction_prompt.py` 按 scene 切换提取 prompt
- Console: 两步式创建向导（Step 1 选场景卡片 → Step 2 填配置）
- **[2026-03-26] 修复中文库名编码 bug**（DB 名改用 ASCII slug）

### 其他修复（2026-03-26）
- 原文定位高亮失败：chunk 含 markdown 语法但 DOM 已渲染为纯文本，indexOf 匹配不上。修复：`stripMarkdown()` 去语法后匹配。
- EPUB 上传失败：Job pod 在 CCI 终止但无 callback。增强 `getTerminationReason()` 捕获 exit code、OOM、pod logs。
- Console 错误信息截断：改为可点击展开完整错误。

### 1e. 层次摘要（Per-Document Summary）✅ 完成（2026-03-28）

为知识库新增文档级摘要层（level=1）和 KB 全局摘要层（level=2），解决"跨文档总结性问题回答不了"的痛点。

**三层架构：**

| level | 内容 | 参与搜索 | 生成方式 |
|-------|------|---------|---------|
| 0 | 原始 chunks（已有） | 是 | 文档解析时 |
| 1 | 文档摘要（每文档 1 条） | 是 | 解析完成后异步 LLM 生成 |
| 2 | KB 全局摘要（每 KB 1 条） | 否（展示用） | 所有 L1 就绪后生成 |

**实现要点：**
- SummaryService: 读 OBS fulltext.md → DeepSeek-V3.2 摘要 → BGE-M3 embedding → 写入 compute pod
- 复用 KbWriteQueue 轻量任务机制，DOCUMENT_SUMMARIZE + KB_SUMMARIZE 两种任务类型
- 三层看护：指数退避重试 × 3、5 分钟卡死检测、超限放弃
- 搜索增强：`WHERE level IN (0, 1)` + RRF，L1 命中标记"文档摘要"
- 增量更新：文档删除/重解析自动清理 L1 并重新生成 L2
- Console: 搜索结果"文档摘要"标签、KB 概览卡片、文档详情摘要 tab
- Admin: 摘要状态列、任务类型筛选、重新摘要按钮、覆盖率统计
- MCP: knowledge_list 显示 KB 摘要、knowledge_search 标记 L1
- 启动时自动同步 kb_write_tasks CHECK 约束（SchemaMigration）

**部署修复（0.9.152）：**
- SummaryService 改用 KbWriteQueue 传入的 Connection（而非自行解析 connstr）
- CHECK 约束自动同步支持新 task type

**设计文档：** `docs/superpowers/specs/2026-03-28-knowledge-hierarchical-summary-design.md`

## Phase 2：高级 RAG + 数据飞轮

| 技术 | 作用 | 依赖 | 状态 |
|------|------|------|------|
| ~~RAPTOR 层次摘要~~ | ~~L0/L1/L2 分层检索~~ | ~~LLM~~ | ✅ 已完成（1e，按文档摘要方案） |
| RAPTOR 语义聚类 | 跨文档主题发现（方案 B） | scikit-learn + LLM | 待启动（可叠加在 L1 之上） |
| v_inferred chunk 增强 | 补全代词/引用 | LLM | 待启动 |
| Reranker 重排序 | 精排 top-K | 硅基流动 API | ✅ 已集成 |
| LightRAG 知识图谱 | 跨文档实体关联 | 实体抽取 | 待启动 |
| DuckDB → Parquet 导出 | PG 数据导出到 OBS | Job 框架 | ✅ 代码完成 |
| Q-value 记忆检索 | 记忆质量排序 | 用户反馈信号 | 待启动 |

## Phase 3：数据湖 — 用户自定义 Job (CCI)

CCI 验证已通过。三种任务类型：

| 类型 | 运行环境 | 状态 |
|------|---------|------|
| Python 脚本 | CCI Pod (virtual-kubelet) | ✅ 完成 |
| Ray 分布式 | KubeRay on CCI | ✅ 完成 |
| 微调训练 | GPU 节点 | 待启动 |

Console UI: 作业管理（分步创建向导 + 代码编辑器 + SSE 日志流 + 资源配置）

## 决策记录

| 决策 | 原因 | 日期 |
|------|------|------|
| Trusted Plane 用 CCE 弹性节点池 | 冷启动 8s, RDS/OBS 直连 | 03-18 |
| Untrusted Plane 用 CCI | 用户代码 Kata microVM 隔离 | 03-18 |
| KB Job Pod 迁移到 CCI | 释放弹性节点资源给 compute pod | 03-26 |
| MCP 原生 HTTP 内嵌 API | 零额外部署，复用 Service 层和认证 | 03-26 |
| 不用 Spring AI MCP starter | 协议简单（3 个 JSON-RPC 方法），避免重依赖 | 03-26 |
| 记忆库按场景区分提取策略 | 编码助手 vs 对话助理需求根本不同 | 03-26 |
| 记忆库 DB 名用 ASCII slug | 中文名在 HTTP header 编码损坏 | 03-26 |
| Embedding 用硅基流动 API | 省去自托管 GPU，降本 | 03-20 |
| chunks 存用户 PG | 天然租户隔离, 避免 RDS 膨胀 | 03-18 |
| Phase 1 只做 pgvector + tsvector + RRF | 不需要 LLM 的技术先做 | 03-18 |
| 层次摘要用 per-document（非 RAPTOR 聚类） | 实现简单、可叠加、跨文档聚类留 Phase 2 | 03-28 |
| 摘要 LLM 用 DeepSeek-V3.2 | 长文档理解强、SiliconFlow 成本低、与 SRE AI 统一 | 03-28 |
| 摘要异步生成不阻塞文档状态 | 用户看到 READY 时 L0 已可用，L1 后台补齐 | 03-28 |
| SummaryService 复用 KbWriteQueue 的 Connection | compute pod 通过 proxy 连接，自行解析 connstr 不可靠 | 03-28 |
