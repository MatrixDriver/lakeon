# Knowledge Pipeline & 数据飞轮路线图

> 2026-03-18 创建，记录实现进度和下一步

## Phase 1 进度

### 1a. 通用 Job 框架 ✅ 完成

8 个 Java 文件 (`com.lakeon.job`)，与 Import 系统并行共存。

- JobEntity (job_ + 12 char ID, callbackToken)
- JobService (submit/query/cancel/callback + 异步 Pod 启动)
- JobPodManager (K8s Pod + ConfigMap + /dev/shm + nodeSelector)
- JobCallbackController (token 验证, 进度上报)
- JobScheduledTasks (PENDING 5min + RUNNING timeout 孤儿检测)

### 1b. Knowledge Pipeline MVP ✅ 代码完成，⏳ 待部署

三个组件，19 个新文件：

**Embedding Service** (`knowledge/embedding-service/`)
- BGE-M3 (568M, 1024维) + FastAPI 常驻 Pod
- Helm template: `deploy/helm/lakeon/templates/embedding-service.yaml`

**Knowledge Job Pod** (`knowledge/job/`)
- parser.py: Marker (PDF) + python-docx (DOCX) + 直读 (Markdown)
- chunker.py: Structure-aware (标题边界 + 代码/表格完整 + overlap)
- writer.py: 写入用户 PG (pgvector + pg_search)
- 不用 Ray，普通 Python Pod，HTTP 调 Embedding Service

**API Layer** (`com.lakeon.knowledge/`)
- DocumentEntity + KnowledgeService + KnowledgeController
- OBS 预签名 URL 直传、Job 提交、RRF 搜索
- chunks 存在用户自己的 PG，搜索通过 proxy 连接

### 部署待办
- [ ] 构建 embedding-service 镜像推 SWR
- [ ] 构建 knowledge-job 镜像推 SWR
- [ ] 构建新版 API 镜像部署 CCE
- [ ] Helm 部署 embedding-service
- [ ] 端到端 smoke test（上传 PDF → 解析 → 搜索）

### 后续待办
- [ ] Console UI（文档上传/管理/搜索页面）
- [ ] MCP endpoint（knowledge_search tool for Claude Code/Cursor）

## Phase 2：高级 RAG + 数据飞轮

| 技术 | 作用 | 依赖 |
|------|------|------|
| RAPTOR 层次摘要 | L0/L1/L2 分层检索 | LLM |
| v_inferred chunk 增强 | 补全代词/引用 | LLM |
| ColBERT 重排序 | 精排 top-K | 额外模型 |
| LightRAG 知识图谱 | 跨文档实体关联 | 实体抽取 |
| DuckDB → Parquet 导出 | PG 数据导出到 OBS | Job 框架 |
| OpenRLHF 训练 | SFT/DPO/PPO | GPU 节点, 用户数据 |

## Phase 3：用户自定义 Job (CCI)

CCI 验证已通过 (2026-03-18)：Ray + RDS + OBS + 公网全部 OK。
技术风险已消除，等有用户需求时启动。

## CCI 验证记录

关键配置：
- CCI Network `attachedVPC` 必须是 `d66706ac`（CCE/RDS/NAT 所在 VPC）
- `networkID` = `f28729ee`，`subnetID` = `2455b2f4`
- `availableZone` 只能选 `cn-north-4a` 或 `cn-north-4g`
- SWR 镜像拉取需要 `imagepull-secret`（`hcloud SWR CreateAuthorizationToken`）

## 决策记录

| 决策 | 原因 |
|------|------|
| Trusted Plane 用 CCE 弹性节点池 | 冷启动 8s, RDS/OBS 直连, 我们的代码不需要 Kata 隔离 |
| Untrusted Plane 用 CCI (Phase 3) | 用户代码需要 Kata microVM 隔离 |
| 不用 KubeRay | Job 短生命周期, API 直接编排 Pod |
| Embedding 独立服务 | 模型只加载一次, Job Pod 和搜索共用 |
| chunks 存用户 PG | 天然租户隔离, 避免 RDS 膨胀 |
| OBS 预签名 URL 直传 | 大文件不过 API |
| Phase 1 只做 pgvector + BM25 + RRF | 不需要 LLM 的技术先做 |
| Phase 1-2 用 Parquet, 多模态引入 Lance | Parquet 生态成熟, 顺序扫描最优 |
| RL 训练用 OpenRLHF | Ray 原生, 4B-8B 匹配 |
| Embedding 用 BGE-M3 | 568M 参数, 4C/8G 够跑 |
