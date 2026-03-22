# Lakeon (DBay) 产品路线图

> 最后更新: 2026-03-22

## 阶段总览

| 阶段 | 名称 | 状态 | 验证报告 |
|------|------|------|----------|
| Stage 0 | 本地 K8s 开发环境 | ✅ 完成 | `verification/stage0-local-k8s.md` |
| Stage 1 | OBS 对象存储集成 | ✅ 完成 | `verification/stage1-obs-storage.md` |
| Stage 2 | RDS 元数据库 | ✅ 完成 | `verification/stage2-rds-metadata.md` |
| Stage 3 | CCE 集群部署 | ✅ 完成 | `verification/stage3-cce-cluster.md` |
| Stage 4 | Web 控制台 & 用户接入 | ✅ 完成 | `verification/stage4-user-access.md` |
| Stage 5 | SRE 运维控制台 | ✅ 完成 | `verification/stage5-sre-admin.md` |
| Stage 6 | 简化数据库管理 | ✅ 完成 | `verification/simplified-db-manager.md` |
| Stage 6b | PG 数据导入 | ✅ 完成 | — |
| Stage 6c | 持续数据同步 | ✅ 完成 | — |
| Stage 7 | 品牌 & 部署架构升级 | ✅ 完成 | — |
| Stage 8a | 自建可观测性 | ✅ 完成 | `verification/stage8a-observability.md` |
| Stage 8b | 华为云 AOM/CES/SMN | 📋 规划中 | — |
| Stage 9 | 性能监控 & 统一日志 | ✅ 完成 | — |
| Stage 10a | 备份与恢复 | ✅ 完成 | — |
| Stage 10b | 分支管理增强 | ✅ 完成 | — |
| Stage 10c | 连接池 | ⏸️ 暂缓 | — |
| Stage 10d | SQL 审计日志 | ✅ 完成 | — |
| Stage 10e | 数据库级权限管理 | ✅ 完成 | — |
| Stage 11 | 多版本多分支 (时间旅行) | ✅ 完成 | — |
| Stage 11b | 分支独立 Compute | ✅ 完成 | — |
| Stage 12 | 弹性节点池 & 自动扩缩容 | ✅ 完成 | — |
| Stage 14 | DBay CLI & E2E 测试 | ✅ 完成 | — |
| Stage 15 | Job 框架 & Knowledge Pipeline | 🔨 进行中 | — |
| Stage 15b | 知识库增强 (标签/重写/重排/表KB) | ✅ 完成 | — |
| Stage 15c | 切片管理 | ✅ 完成 | — |
| Stage 16 | DBay 数据湖 | 🔨 进行中 | — |
| Stage 16b | BM25 → tsvector 全文搜索 | ✅ 完成 | — |

---

## ✅ Stage 0: 本地 K8s 开发环境

- Docker Desktop Kubernetes 部署
- MinIO 本地对象存储
- 全组件: metadata-db, minio, pageserver, safekeeper, storage-broker, proxy, lakeon-api
- 31/31 E2E 集成测试通过
- Compute pod 按需创建 (~17s 就绪)

## ✅ Stage 1: OBS 对象存储集成

- 华为云 OBS 替代 MinIO
- `usePathStyle` 标志控制 S3 寻址模式
- `waitForTenantActive()` 处理 OBS 网络延迟

## ✅ Stage 2: RDS 元数据库

- 华为云 RDS PostgreSQL 替代集群内 metadata-db
- VPC 内网直连 (192.168.0.210:5432)

## ✅ Stage 3: CCE 集群部署

- 华为云 CCE 集群 (cn-north-4)
- SWR 镜像仓库 + imagePullSecrets
- containerd core ulimit 修复 (DaemonSet)
- 31/31 E2E 测试在 CCE 上通过
- 一键启停脚本 (`start.sh` / `stop.sh` / `hwcloud.py`)

## ✅ Stage 4: Web 控制台 & 用户接入

- **Web 控制台** (Vue 3 + TinyVue)
  - 登录/注册、总览仪表盘、数据库 CRUD
  - 数据库详情 (基本信息、分支管理、操作历史)
  - API Key 管理 (遮罩、重新生成)
  - 连接文档 (psql / JDBC / Python / Java / Go)
- **后端**
  - 用户注册 & API Key (`lk_` + 64 hex)
  - 资源配额 (max_databases, max_storage_gb, max_compute_cu)
  - 操作日志 (CREATE/SUSPEND/RESUME/DELETE/UPDATE)
  - 用量计量 (CU·hours 三级粒度)

## ✅ Stage 5: SRE 运维控制台

- **Admin API** (Bearer Token 认证)
  - 仪表盘、租户管理 (禁用/启用/批量删除)
  - 数据库全局管理、操作审计日志 (CSV 导出)
  - 系统健康检查 (含 OBS)、唤醒延迟统计
  - 成本监控 (CBC 实际账单 + 预估双模式)
  - 日成本趋势图、租户成本分摊
- **云资源页面**
  - 部署架构拓扑图 (Railway → ELB → CCE → RDS/OBS)
  - 资源清单表 (名称/区域/服务/状态/华为云控制台直链)
- 27 个单元测试全部通过

## ✅ Stage 6: 简化数据库管理

- 移除索引/约束展示
- 数据预览 (前 100 行、空表提示、大表提示)
- Schema 缓存避免重复查询
- 表头固定 + 横/纵滚动

## ✅ Stage 6b: PG 数据导入

- **导入向导** (Console 前端)
  - 连接测试、源表浏览与选择
  - 进度跟踪 (表级粒度)
- **导入引擎** (后端)
  - Job Pod 运行 pg_dump | pg_restore
  - 异步导入、按表进度回调
  - 自动唤醒计算节点
  - 导入操作记录到操作日志
  - 过滤扩展私有表 (extension-owned)
- **网络**: NAT 网关出公网 → hostNetwork 回退方案

## ✅ Stage 6c: 持续数据同步

- **PostgreSQL 逻辑复制** (Publication / Subscription / Replication Slot)
  - SYNC 导入模式 — 实时增量同步外部 PG 数据
  - 初始全量复制 + 之后增量同步 (copy_data=true)
  - sync-setup.sh Job Pod 创建 Publication/Subscription 后退出
  - PG 原生逻辑复制接管持续同步
- **同步监控** (SyncStatusCollector)
  - 定时轮询 pg_stat_subscription / pg_subscription_rel
  - 复制延迟 (replay_lag_seconds)、WAL 占用 (wal_retained_bytes)
  - 每表同步状态 (i/d/f/s/r)
  - WAL 占用超阈值告警 (默认 1GB)
  - CATCHING_UP → SYNCING 自动状态转换 (延迟 < 10s)
- **同步控制**
  - 暂停/恢复 (ALTER SUBSCRIPTION DISABLE/ENABLE)
  - 停止同步 (可选清理源库 Publication + Slot)
  - 同步任务数限制 (默认最多 10 个)
- **Console 前端**
  - 导入向导新增「持续同步」模式 (需 wal_level=logical + replication 权限)
  - 同步状态面板 (延迟、WAL 占用、最后同步时间)
  - 停止同步对话框 (可选清理源库资源)
  - 任务列表显示延迟代替进度条
- **API 端点**
  - `GET /databases/{dbId}/import/{taskId}/sync-status`
  - `POST /databases/{dbId}/import/{taskId}/stop`
- **Bug 修复** (线上集成测试中发现)
  - 竞态条件: 异步任务在事务提交前执行 → `TransactionSynchronization.afterCommit()`
  - 目标表未预创建: sync-setup.sh 增加 `pg_dump --schema-only` 步骤
  - 回调 URL 端口/协议错误: 动态检测 HTTPS + K8s service port (8443)
  - 回调 TLS: `curl -sk` 支持自签名证书
- **测试**: ImportServiceSyncTest (7) + SyncStatusCollectorTest (5)
- **线上集成测试**: 14/14 用例通过 (华为云 RDS → Neon 计算节点)
  - 连接测试、源表浏览、任务创建、状态流转
  - INSERT/UPDATE/DELETE 增量同步验证
  - 数据一致性校验 (source = target)
  - sync-status API、暂停/恢复/停止同步
  - 源库清理验证 (publication + slot)
  - 批量数据同步 (100 rows)

## ✅ Stage 7: 品牌 & 部署架构升级

- **品牌**: Lakeon → **DBay 数据港湾**
  - Console 全面重新品牌
  - SRE Admin 重新品牌
  - Landing Page (双语 zh/en, AI 数据平台定位)
- **HTTPS 接入**
  - Let's Encrypt RSA 证书 → PKCS12 Keystore
  - Spring Boot 应用层 TLS (端口 8090)
  - ELB TCP:8443 透传 → 节点 8090
  - 共享型 ELB 端口 443 被 ADM 拦截，改用 8443
- **Railway 部署**
  - Console 和 Admin 部署到 Railway (海外)
  - CCE 不再部署前端容器
  - 浏览器直连 `https://api.dbay.cloud:8443`
- **hostNetwork API**
  - API Pod 使用 hostNetwork (绕过 overlay 网络直连 RDS)
  - `strategy: Recreate` 避免端口冲突
- **SQL 编辑器**
  - Console 内置 SQL 编辑器
- **用户名密码认证**
  - 替代纯 API Key 认证
  - 简化注册流程
- **移动端适配**
  - 全页面响应式设计
- **分页优化**
  - 页码 + 省略号分页组件

## ✅ Stage 8a: 自建可观测性

- **自定义 Micrometer 指标** (7 项)
  - tenants_total, databases_total, compute_pods_active
  - compute_wakeup_seconds, wakeup_failures_total
  - storage_used_bytes, api_operations_total
- **日志查看器**: 终端风格、组件切换、搜索高亮、自动刷新
- **应用指标仪表盘**: JVM/API/Compute/DB/Storage + Canvas 趋势图
- **告警服务**: 5 条巡检规则 + 企业微信/钉钉/Webhook 通知
- **基础设施监控**: 节点 CPU/内存 + Pod 资源排行
- **唤醒统计**: 冷启动/热启动分离统计
- Prometheus alerts.yml 5 条告警规则
- 10/10 集成测试通过

## ✅ Stage 9: 性能监控 & 统一日志

- **性能诊断仪表盘** (SRE Admin)
  - 4 Tab 结构: 概览/性能诊断/日志管理/告警
  - 活跃会话监控 (pg_stat_activity)
  - 活跃会话过滤系统连接 (compute_ctl 等)
- **统一日志管理**
  - 组件级日志查看 (pageserver/safekeeper/proxy/api)
  - 终端风格、搜索高亮、自动刷新
- **弹性节点池运维** (SRE Admin)
  - 节点池概览仪表盘 (当前/最小/最大节点数, CPU/内存利用率)
  - 每节点详情卡片 (状态、利用率、Pod 数、空闲检测、缩容倒计时)
  - 弹性伸缩事件时间线 (过去 48h, 扩容/缩容次数统计)
  - API: `/admin/infra/node-pool`, `/admin/infra/autoscaling-events`, `/admin/infra/nodes`, `/admin/infra/events`
  - 可配置: nodePoolName, nodePoolMin/Max, scaleDownUnneededMinutes
  - RBAC: ClusterRole 授权 metrics.k8s.io + events 读取

## ✅ 运维工具链

- **一键启停** (`deploy/cce/start.sh` / `stop.sh`)
  - 默认模式: 关停 ECS+RDS，保留 ELB+EIP
  - `--full` 模式: 释放全部资源
- **华为云资源管理** (`deploy/cce/hwcloud.py`)
  - `discover` — 资源发现与缓存
  - `info` — 资源规格详情 (ECS CPU/内存/IP, ELB 监听器, RDS, EIP)
  - `status` — 资源状态概览
  - `start-cloud` / `stop-cloud` — ECS 开关机 (不再创建/删除节点)
  - `list-resources` — 生成 JSON 供 SRE 控制台
- **Warm Wake 优化**: 挂起时保留 Pod，连接到来即时恢复 (~1s vs ~10s)
- **SWR Secret 自动刷新**: SwrSecretRefreshService 每 12h 用 IAM token 刷新

---

## 📋 Stage 8b: 华为云 AOM/CES/SMN (规划中)

- AOM 指标上报
- CES 基础设施监控
- SMN 告警通知渠道
- 与自建方案对比评估

## ✅ Stage 10a: 备份与恢复

- 手动备份 (基于 Neon timeline branching, LSN 快照点)
- 备份列表 (名称、大小、LSN、时间戳)
- 从备份恢复 (创建新 timeline 分支)
- 备份自动清理 (可配置保留数量)
- 备份管理独立页面 (侧栏入口 + 创建备份)
- **测试**: BackupServiceTest (12) + BackupControllerTest (7) + backup-api.test.ts (5)

## ✅ Stage 10b: 分支管理增强

- Console 分支可视化 (SVG DAG 树形图)
- 创建分支 (指定父分支 + 可选 LSN)
- 切换活跃分支 (timeline 切换)
- 删除分支
- **测试**: BranchServiceTest (11) + BranchControllerTest (8) + BranchTreeView.test.ts (9) + CreateBranchDialog.test.ts (8) + branch-api.test.ts (5)

## ⏸️ Stage 10c: 连接池 (暂缓)

- PgBouncer sidecar vs proxy 层连接池 — 架构待讨论
- 连接池配置 (pool_mode, pool_size)
- 连接池监控 (SRE 仪表盘)
- 用户可配置池大小

## ✅ Stage 10d: SQL 审计日志

- 审计配置 (启用/关闭, DDL/DML/SELECT 分类, 保留天数)
- 审计日志记录 (SQL 语句、用户、耗时、对象名)
- SQL 编辑器集成审计 (执行 SQL 时自动记录)
- Console 审计日志 Tab (类型筛选 + 分页)
- SRE Admin 全局审计日志页面 (租户/数据库筛选 + CSV 导出)
- **测试**: AuditServiceTest (18) + AuditControllerTest (6) + audit-api.test.ts (4) + AuditLogs.test.ts (12) + admin-api.test.ts (5)

## ✅ Stage 10e: 数据库级权限管理

- 多用户支持 (每个数据库独立用户)
- 三级角色模板 (ADMIN / WRITER / READER)
- PG 角色 SQL 执行 (CREATE ROLE / GRANT / REVOKE)
- 用户创建 (自动生成密码 + 显示一次)
- 修改角色、重置密码、删除用户
- Console 用户管理 Tab
- **测试**: DatabaseUserServiceTest (12) + DatabaseUserControllerTest (9) + CreateUserDialog.test.ts (13) + dbuser-api.test.ts (5)

## ✅ Stage 11: 多版本多分支 — 时间旅行

- **版本管理** (类 git 数据库版本控制)
  - 版本 = 分支上某个 LSN 点的命名书签 + 物化快照 timeline
  - 创建版本 (填版本号 + 提示上次版本号)
  - 版本列表 (最新在前, LSN 标识)
  - 版本创建者显示 tenant 用户名
- **Schema Diff**
  - 版本之间 Schema 对比 (表结构差异)
  - 临时 compute 自动创建/回收 (无 compute 的分支自动启动临时节点)
  - 加载时显示「正在启动临时计算节点」提示
- **分支 Promote**
  - 将分支提升为主干，原主干降为普通分支
- **Console 页面**
  - 「时间旅行」独立页面 (分支列表 + 版本时间线)
  - 去掉旧 BranchTreeView SVG 图，简化为分支列表
  - 去掉数据库详情页分支 Tab (迁移到时间旅行页面)
- **去商业化**
  - 去掉计费，只保留计量 (资源用量)
  - 去掉 landing 页面所有商业性内容 (定价、免费版等)
- **测试**: version API + diff API + LsnUtil + 组件测试

## ✅ Stage 11b: 分支独立 Compute

- **架构升级**: 从「单数据库单 compute」→「每个分支独立 compute（按需启动）」
- **连接路由** (Neon Proxy 回调)
  - `postgres://user@pg.dbay.cloud:4432/mydb--dev` 路由到 dev 分支
  - 不指定分支时路由到默认分支 (is_default=true)
  - ProxyAdapterController 解析 `endpointish` = `dbName--branchName`
- **分支 Compute 生命周期**
  - `createComputePodForBranch()` — 为分支创建独立 compute pod
  - compute 状态 (RUNNING / SUSPENDED / STARTING / ERROR) 在 BranchEntity 管理
  - 空闲超时自动挂起 (继承数据库的 suspendTimeout)
  - DatabaseEntity 的 computePodName/Host/Port 废弃
- **Console 展示**
  - 分支列表显示 compute 状态和连接 URI
  - 移除 switch 按钮 (每个分支独立 compute，无需切换)
  - Promote 简化 (切换 is_default 标记)

## ✅ Stage 12: 弹性节点池 & 自动扩缩容

- **弹性节点池**: `dbay-compute-pool` (c9.xlarge.2, 4C/8G)
  - 弹性伸缩: min=1, max=5
  - 节点标签: `lakeon/role=compute`
  - autoscaler 插件 v1.33.57, scaleDownUnneededTime=10min
- **compute pod 调度**: nodeSelector 定向到弹性池，管控面留在固定节点
- **node-init DaemonSet**: 自动在新弹性节点修复 containerd `LimitCORE=infinity`
- **启动时间**: 弹性节点已存在时冷启动 ~8s，热启动 ~100ms
- **固定节点** (2x c9.2xlarge.2) 运行管控面，弹性节点运行 compute pod

## 📋 Stage 13: 记忆系统集成 & LoCoMo Benchmark

> 详细方案: [`memory-integration-locomo-benchmark.md`](memory-integration-locomo-benchmark.md)

目标：让 AI Agent 记忆系统用户在获得 token 下降好处的同时，获得 dbay.cloud Serverless PG 的弹性、免运维、scale-to-zero。

### Phase A: MemOS + dbay（当前）
- 补齐 MemOS PG 后端缺失方法（`search_by_fulltext`, `get_subgraph` 签名对齐, `delete_node_by_prams`, `drop_database`）
- IVFFlat → HNSW, 连接池调优适配 Serverless
- 用 MemOS 自带 `evaluation/scripts/locomo/` 跑 `locomo10.json`
- 验证：token 下降 ≥ 72% + 准确率无损 + scale-to-zero 行为正常

### Phase B: OpenClaw 端到端验证（后续）
- MemOS + dbay 做成 OpenClaw 插件
- 用 `openclaw-eval` 跑 `locomo10.json`，与 OpenViking 83% 直接对比

### Phase C: OpenViking + dbay（探索性）
- OpenViking 存储自包含（C++ + LevelDB），需写 HTTP adapter 适配 pgvector
- 优先级低，等 OpenViking 社区活跃或原生支持 PG 时再考虑

## ✅ Stage 14: DBay CLI & E2E 测试

目标：开发 CLI 工具 (`dbay`) 作为用户产品和 E2E 测试基础设施，Claude 在每次功能开发后自动通过 CLI + psql 端到端验证。

- **Phase A: CLI 核心** ✅ — `dbay` CLI with db/branch/version/user/kb commands
- **Phase B: E2E 测试框架** ✅ — pytest-based, 48+ test cases covering all features
- **Phase C: MCP Server** ✅ — `dbay-mcp` package, knowledge_search/upload/list tools

### Phase A: CLI 核心 (Python, pip install dbay)
- `dbay login` / `dbay config` — API Key 配置
- `dbay db list / create / delete / info` — 数据库管理
- `dbay db connstr <name> [--branch <name>]` — 输出连接串 (可直接传给 psql)
- `dbay branch list / create / delete / promote` — 分支管理
- `dbay version list / create / delete / restore` — 版本管理
- `dbay sql <db> "SELECT ..."` — 快捷执行 SQL

### Phase B: E2E 测试框架
- 测试用例用 CLI + psql 编写，验证完整链路 (API → Proxy → Compute → PG)
- 覆盖: 数据库 CRUD、分支创建/隔离/Promote、版本创建/回滚、数据持久化
- 每个功能开发完成后必须补充对应 E2E 用例并通过
- 测试结果作为功能完成的验收标准

### Phase C: MCP Server 集成
- 将 CLI 能力封装为 MCP Server，Claude Code 可直接调用
- 实现对话中自动化测试：开发 → CLI 验证 → 报告结果

## 🔨 Stage 15: Job 框架 & Knowledge Pipeline

> 详细方案: [`plans/2026-03-18-job-framework-and-knowledge-pipeline.md`](plans/2026-03-18-job-framework-and-knowledge-pipeline.md)

### 双轨架构决策 (2026-03-18)

| 平面 | 运行环境 | 场景 | 原因 |
|------|---------|------|------|
| **Trusted Plane** (我们的代码) | CCE 弹性节点池 (`lakeon/role=compute`) | 知识管线、训练数据导出、平台模型训练 | 冷启动 8s、本地 NVMe、RDS/OBS 直连、setrlimit 已解决 |
| **Untrusted Plane** (用户代码) | CCI Serverless (per-tenant namespace) | 用户自定义 Ray Job / UDF | Kata microVM 安全隔离、不影响管控面和其他租户 |

- **Phase 1 只做 Trusted Plane** — CCE 弹性节点池跑知识管线和导出，复用现有基础设施
- **Untrusted Plane 留到 Phase 3** — 等有用户需要自定义数据处理逻辑时再做 CCI 接入

### Phase 1: 通用 Job 框架 + Knowledge Pipeline MVP

- **通用 Job 框架**: JobEntity / JobService / JobManager / JobCallback
  - 基于现有 ImportJobPodManager 模式，Pod 跑在 CCE 弹性节点池
  - Job 类型: `DOCUMENT_PARSE`, `EMBEDDING`, `EXPORT_LANCE`, `TRAINING`
  - API: `POST /jobs`, `GET /jobs/{id}`, `GET /jobs`, `DELETE /jobs/{id}`
- **Knowledge Pipeline MVP**:
  - 用户上传文档 → OBS → Job Pod (parse → chunk → embed) → 写入用户 PG (pgvector)
  - 单 Pod Ray (ray start)，串行处理
  - MCP 端点: `knowledge_search` (pgvector + tsvector hybrid retrieval with RRF)
- **Job 框架** ✅
- **Knowledge Pipeline MVP** ✅ (parse → chunk → embed → pgvector)
- **KbWriteQueue 重构** ✅ — 复用用户 compute pod 写入，避免 WAL 冲突
- **BM25 → tsvector** ✅ — pg_search BM25 index 与 Neon SMGR 不兼容，改用 GIN tsvector

### Phase 2: 数据飞轮管线

- PG → OBS Parquet 导出 (DuckDB postgres_scan 直写，一条 SQL)
- Ray Cluster on CCE 弹性节点池 (训练数据清洗/标注)
- RL 训练: OpenRLHF (Ray 原生，SFT/DPO/PPO/GRPO)
- 训练数据集 + 模型产物输出到 OBS

### Phase 3: 用户自定义 Job (CCI Serverless) + 多模态

- CCI 接入: 用户 Ray Job 运行在 CCI Serverless (Kata microVM 隔离)
- Per-tenant CCI namespace + NetworkPolicy
- 用户 Job 不连 RDS — 通过 API 读写数据，只读 OBS 数据集
- Job 提交 API + 状态回调 + 计量
- 多模态存储: 引入 Lance 格式存储图片/音频/视频 + 元数据

## ✅ Stage 15b: 知识库增强

- **文档标签**: JSONB 字段 + GIN 索引，支持标签过滤搜索
- **查询重写**: LLM-based query rewrite，结合对话历史上下文
- **重排序**: BGE-Reranker-v2-m3 集成，搜索流水线自动重排
- **表知识库**: 基于已有数据库表创建 KB，自然语言查询 (AiSqlService)
- **Per-KB Embedding 模型选择**: 每个 KB 可独立配置 embedding 模型
- **Console 前端**: 标签 UI、聊天式搜索、表 KB 管理
- **48 个 E2E 测试通过**

## ✅ Stage 15c: 切片管理

- **切片读取 API**: 列表、详情、上下文、统计、全文高亮
- **切片写入 API**: 编辑、删除、创建（自动重新生成 embedding）
- **重切片**: 参数化重切片 + 进度跟踪 + 前后对比
- **Console 前端**:
  - 文档详情页 — 切片列表和内容查看
  - 全文高亮 — Markdown 渲染 + 切片位置高亮
  - KB 级切片 Tab — 统计、直方图、可过滤切片表
  - 重切片对话框 — 参数输入、进度、对比视图

## 🔨 Stage 16: DBay 数据湖

> 详细架构: [`AI-DataLake.md`](AI-DataLake.md)

三类任务统一调度，CCI Serverless (Kata VM 隔离) 运行用户代码：

### 架构
- **DatalakeController + DatalakeService**: REST API for job submit/get/list/cancel
- **PythonJobRunner**: K8s Job via Virtual Kubelet → CCI
- **RayJobRunner**: RayJob CRD via Fabric8 GenericKubernetesResource
- **FinetuneJobRunner**: Ray Train 模板注入
- **DatalakeStatusPoller**: K8s Job/RayJob 状态同步
- **DatalakeLogService**: SSE 日志流 (从 CCI Pod via VK 读取)

### 任务类型
| 类型 | 运行环境 | 用途 |
|------|---------|------|
| PYTHON | CCI K8s Job | 数据处理、ETL |
| RAY | CCI RayJob | 分布式计算 |
| FINETUNE | CCI Ray Train | 模型微调 (SFT/DPO/GRPO) |

### 已完成
- ✅ DatalakeJobEntity + Repository + V16/V17 migration
- ✅ DatalakeController + DatalakeService skeleton
- ✅ PythonJobRunner — K8s Job via VK → CCI
- ✅ RayJobRunner — RayJob CRD
- ✅ FinetuneJobRunner — Ray Train template
- ✅ DatalakeStatusPoller — sync K8s/RayJob status
- ✅ DatalakeLogService — SSE log streaming
- ✅ Helm values + configmap for datalake config
- ✅ 14 DatalakeService unit tests
- ✅ E2E tests + CCE/CCI deployment fixes
- ✅ Console 前端 — job 管理页面 + sidebar rail

### 待完成
- 📋 CCI namespace 自动创建 + 租户隔离
- 📋 Job 计量和配额
- 📋 GPU 任务支持 (Ray GPU image)

## ✅ Stage 16b: BM25 → tsvector 全文搜索

- **根因**: pg_search (ParadeDB) 的 BM25 index 与 Neon 自研 SMGR 不兼容
  - `CREATE INDEX ... USING bm25` 触发 `PANIC: Page X of relation Y is evicted with zero LSN`
  - Compute pod 进入 CrashLoopBackOff
- **修复**:
  - writer.py: 移除 `CREATE EXTENSION pg_search` 和 BM25 index，改用 GIN tsvector
  - DatabaseService: 从 DEFAULT_EXTENSIONS 移除 `pg_search`
  - KnowledgeService: hybrid search SQL 从 `paradedb.score()` + `content @@@ ?` 改为 `ts_rank_cd()` + `plainto_tsquery()` (RRF 融合不变)
- **影响**: 搜索质量略低于 BM25（tsvector 是简单词频匹配），但 Neon 兼容性保证稳定运行

---

### Backlog
- 多副本 pageserver / safekeeper
- 故障转移验证
- 华为云 IAM SSO
- 数据库连接端到端 TLS
- API 限流
- CI/CD 流水线

---

## 当前版本

| 组件 | 版本 | 部署位置 |
|------|------|----------|
| lakeon-api | 0.9.21 | CCE (hostNetwork, HTTPS) |
| lakeon-console | — | Railway |
| lakeon-admin | — | Railway |
| lakeon-knowledge-job | 0.2.3 | CCE (Job Pod) |
| lakeon-import | 0.2.0 | CCE (Job Pod) |
| dbay-cli | 0.1.0 | pip install |
| dbay-mcp | 0.1.0 | MCP Server |

## 基础设施

| 资源 | 规格 |
|------|------|
| CCE 集群 | lakeon-k8s-cluster (cn-north-4) |
| 固定节点 | 2x c9.2xlarge.2 (8C16G) — 管控面 |
| 弹性节点池 | 1~5x c9.xlarge.2 (4C8G) — compute pod |
| RDS | PostgreSQL (VPC 内网) |
| ELB | 独享型 (TCP:8443, TCP:4432) |
| OBS | lakeon-storage (cn-north-4) |
| 域名 | api.dbay.cloud (API), dbay.cloud (Console), admin.dbay.cloud (Admin), pg.dbay.cloud (PG) |
