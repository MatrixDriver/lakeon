# Lakeon (DBay) 产品路线图

> 最后更新: 2026-04-10

## 阶段总览

| 阶段 | 名称 | 时间 | 状态 | 验证报告 |
|------|------|------|------|----------|
| Stage 0 | 本地 K8s 开发环境 | 03-03 → 03-04 | ✅ 完成 | `verification/stage0-local-k8s.md` |
| Stage 1 | OBS 对象存储集成 | 03-04 | ✅ 完成 | `verification/stage1-obs-storage.md` |
| Stage 2 | RDS 元数据库 | 03-04 | ✅ 完成 | `verification/stage2-rds-metadata.md` |
| Stage 3 | CCE 集群部署 | 03-04 | ✅ 完成 | `verification/stage3-cce-cluster.md` |
| Stage 4 | Web 控制台 & 用户接入 | 03-04 | ✅ 完成 | `verification/stage4-user-access.md` |
| Stage 5 | SRE 运维控制台 | 03-04 | ✅ 完成 | `verification/stage5-sre-admin.md` |
| Stage 6 | 简化数据库管理 | 03-05 | ✅ 完成 | `verification/simplified-db-manager.md` |
| Stage 6b | PG 数据导入 | 03-06 → 03-07 | ✅ 完成 | — |
| Stage 6c | 持续数据同步 | 03-09 | ✅ 完成 | — |
| Stage 7 | 品牌 & 部署架构升级 | 03-05 → 03-08 | ✅ 完成 | — |
| Stage 8a | 自建可观测性 | 03-05 | ✅ 完成 | `verification/stage8a-observability.md` |
| Stage 8b | 华为云 AOM/CES/SMN | — | 📋 规划中 | — |
| Stage 9 | 性能监控 & 统一日志 | 03-09 | ✅ 完成 | — |
| Stage 10a | 备份与恢复 | 03-08 → 03-09 | ✅ 完成 | — |
| Stage 10b | 分支管理增强 | 03-08 | ✅ 完成 | — |
| Stage 10c | 连接池 | — | ⏸️ 暂缓 | — |
| Stage 10d | SQL 审计日志 | 03-08 | ✅ 完成 | — |
| Stage 10e | 数据库级权限管理 | 03-08 | ✅ 完成 | — |
| Stage 11 | 多版本多分支 (时间旅行) | 03-17 | ✅ 完成 | — |
| Stage 11b | 分支独立 Compute | 03-18 | ✅ 完成 | — |
| Stage 12 | 弹性节点池 & 自动扩缩容 | 03-15 → 03-19 | ✅ 完成 | — |
| Stage 14 | DBay CLI & E2E 测试 | 03-18 → 03-19 | ✅ 完成 | — |
| Stage 15 | Job 框架 & Knowledge Pipeline | 03-18 → 03-28 | ✅ 完成 | — |
| Stage 15b | 知识库增强 (标签/重写/重排/表KB) | 03-19 | ✅ 完成 | — |
| Stage 15c | 切片管理 | 03-18 | ✅ 完成 | — |
| Stage 16 | DBay 数据湖 | 03-20 → 03-25 | ✅ 完成 | — |
| Stage 16b | BM25 → tsvector 全文搜索 | 03-22 | ✅ 完成 | — |
| Stage 17 | Notebook 交互式开发 | 03-27 → 04-01 | ✅ 完成 | — |
| Stage 17b | Ray Notebook + 热池 | 03-28 | ✅ 完成 | — |
| Stage 15d | KB Pipeline 增强 | 03-25 → 03-28 | ✅ 完成 | — |
| Stage 18 | 控制台体验重构 | 03-28 → 04-01 | ✅ 完成 | — |
| Stage 15e | 知识库交互优化 | 03-29 → 04-01 | ✅ 完成 | — |
| Stage 15f | 知识库文件夹 & 并行摄入 | 04-01 → 04-02 | ✅ 完成 | — |
| Stage 19 | 日志可观测性 | 04-01 | ✅ 完成 | — |
| Stage 20 | 数据生产线 | 03-28 → 04-02 | ✅ 完成 | — |
| Stage 21 | 自托管 GPU 推理 | 04-01 → 04-03 | ✅ 完成 | — |
| Stage 22 | 记忆库端到端加密 | 04-08 → 04-09 | ✅ 完成 | — |
| Stage 23 | 知识库分享 | 04-08 | ✅ 完成 | — |
| Stage 24 | Wiki Agent & 知识图谱 | 04-06 → 04-10 | ✅ 完成 | — |
| Stage 25 | Console 港湾风格重构 | 04-07 → 04-08 | ✅ 完成 | — |
| Stage 26 | OAuth 登录 (Google/GitHub) | 04-09 → 04-10 | ✅ 完成 | — |

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

### Future Work: 记忆混合加密模式（Form 2）

参考 neuromem-cloud Form 2 设计，为不信任 DBay 基础设施的用户提供端到端加密。

**架构**：RSA-2048 + AES-256-GCM 混合加密
- 内容在客户端本地加密后上传，私钥永不离开用户设备
- 向量（embedding）明文存储，服务端可做向量搜索但无法读取内容
- 新增 `encrypted_memories` 表和 `/sync/push`、`/sync/recall`、`/sync/pull` 端点

**约束**：加密模式仅兼容 Agent-Extract Mode（服务端 LLM 提取需读明文，不可用）

**前置条件**：记忆端点对齐 neuromem（`/ingest_extracted`、`/digest_extracted`）完成后实施

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

## ✅ Stage 15: Job 框架 & Knowledge Pipeline

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

## ✅ Stage 15d: KB Pipeline 增强

> 2026-03-25 → 03-28

- **多格式支持**: DOC/XLSX/XLS/PPTX/HTML 文档解析 (+ DOCX parser 升级)
- **智能重试**: 错误分类 (transient/rate-limit/permanent) + 分级重试策略
- **Pipeline 可观测性**:
  - StageTracker — 每阶段耗时、内存指标、错误分类
  - SRE Pipeline Monitor Tab — Gantt 图、内存指标、重试状态、筛选器
  - Admin API — 任务列表 + 聚合统计 (JPA Specification 过滤)
- **并发优化**: 并行文档处理，重量级任务不再阻塞队列
- **延迟 Compute 唤醒**: 从 drain 预唤醒改为写入阶段按需唤醒
- **DDL 死锁修复**: 并发文档写入时防止 schema 创建死锁
- **全文高亮修复**: char_offset 越界校验 + 文本匹配回退

## ✅ Stage 16: DBay 数据湖

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
- ✅ PythonJobRunner — K8s Job via VK → CCI（CCI 上 Python 作业端到端跑通）
- ✅ RayJobRunner — RayJob CRD
- ✅ FinetuneJobRunner — Ray Train template
- ✅ DatalakeStatusPoller — sync K8s/RayJob status
- ✅ DatalakeLogService — SSE log streaming
- ✅ Helm values + configmap for datalake config
- ✅ 14 DatalakeService unit tests
- ✅ E2E tests + CCE/CCI deployment fixes
- ✅ Console 前端 — job 管理页面 + sidebar rail
- ✅ CCI per-tenant namespace 自动创建（PythonJobRunner 自动建 namespace，含 tenant label）
- ✅ OBS 数据源连接 (IAM agency 委托)
- ✅ Dataset 文件上传 (FILE_UPLOAD source type + upload-urls + finalize)
- ✅ Dataset 版本列表 API + Console 版本 Tab
- ✅ CCI Pod 安全加固 (K8s API 访问限制 + 云元数据 API 探测防护)
- ✅ OBS STS 策略加固 (防止数据泄露)
- ✅ Ray Head ServiceAccount 最小 RBAC + egress NetworkPolicy

### 待完成
- 📋 Job 计量和配额（core_hours / gpu_hours 字段已有，计算逻辑未实现）
- 📋 日志持久化到 OBS（任务完成后写 logs.txt，当前只有实时 SSE 流）
- 📋 GPU 任务支持 (Ray GPU image + FINETUNE 端到端验证)

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

## ✅ Stage 17: Notebook 交互式开发环境

### Phase 1 — 基础 Notebook (2026-03-27, ✅ 完成)
- repl_server.py (exec 上下文持久化, JSON lines 协议)
- K8s pod per session (python-data 镜像, OBS STS 凭据注入)
- WebSocket exec bridge (Spring @EnableWebSocket + Fabric8 K8s exec)
- Session 管理 (max 1/tenant, 30min 空闲回收, REST CRUD)
- 前端 Notebook 页面 (CodeMirror cells, Shift+Enter 执行)
- 输出渲染: 文本, DataFrame 表格, Plotly 图表, matplotlib PNG, error traceback
- 一键 "Submit as Job" (合并 cells → 预填作业创建页)

### Phase 2 — Notebook 持久化 + 管理 (✅ 完成)
- NotebookEntity + OBS 持久化 (`notebooks/{tenantId}/{notebookId}/notebook.json`)
- NotebookStorageService — OBS 读写 + 版本快照
- NotebookCrudController — CRUD + 版本端点
- Notebook 列表页 (名称、最后修改时间、打开/复制/删除)
- 自动保存 (debounce 3s) + Ctrl+S 版本保存
- 版本历史面板 + 还原功能

### Phase 3 — 开发体验增强 (✅ 完成)
- **Magic commands**: `%pip install`, `%sh`, `%sql` (连用户数据库), `%md` (Markdown cell)
- **Markdown cell**: Code/Markdown 类型切换, 编辑→渲染切换
- **Variable explorer**: 侧边栏显示当前变量名/类型/值
- **Reference panel**: Magic commands + 快捷键参考面板
- **启动进度**: 内核启动进度条 + 计时
- **自动重连**: 页面加载时自动重连已有 session，防止重复启动
- **Notebook 镜像选择**: 卡片式选择替代下拉框

### Phase 4 — 分布式 Ray Notebook + 热池 (2026-03-28, ✅ 完成)
- Ray head + N worker pods，用户可选 worker 数量和规格 (small/medium/large)
- `ray.init(address="auto")` 直连 Ray 集群
- **Ray Head 热池 (Warm Pool)**:
  - WarmPoolManager 维护 2 个预创建 idle Ray head pods (CCI)
  - Session 创建时从池中领取 (~0.5s)，池空时冷启动降级 (~18s)
  - 用完即销毁不回收 (安全)，reconcile 自动补充
  - CCI 实测: 冷启 23s → 热启 0.5s (session创建) + 7-11s (worker加入)
- **RBAC**: ClusterRole 增加 pods/patch + configmaps/update/patch
- **SRE 监控**: Admin API `/admin/datalake/warm-pool` + SRE 控制台热池 Tab
- **资源清理**: reconcile 清理 Failed pods、超时 idle pods、孤儿 workers
- **E2E 测试**: Python CLI 测试 (session → WebSocket → 代码执行 → 验证输出)
- Session 销毁时清理整个 Ray 集群 (head + workers + ConfigMap)

### Phase 5 — 协作与版本控制 (📋 远期)
- 版本历史 (每次保存记录快照, 可 diff/回滚)
- GitHub 联动 (OAuth + commit/push notebook 到 repo)
- Cell 评论/注释 (团队讨论)
- 实时协作 (多人同时编辑, 需 OT/CRDT)

## ✅ Stage 18: 控制台体验重构

> 2026-03-28 → 04-01

用户控制台和 SRE 运维控制台统一为「港湾暖色调」设计语言，以工具效率为核心重构交互。

### 用户控制台 (lakeon-console)
- **单侧边栏布局**: 移除双栏 icon rail，合并为单侧边栏导航
- **⌘K 命令面板**: 全局快捷操作入口，替代各页面独立搜索栏
- **卡片/表格视图切换**: 数据库、知识库、数据湖、记忆库列表页支持 card/table 双视图
- **ResourceCard 组件**: 统一卡片设计，自动填充网格布局，hover 显示操作按钮
- **CardMenu 下拉菜单**: 卡片右上角操作菜单 (删除等)
- **页面合并精简**:
  - 数据源/消息/统计 → 吸收到数据库详情页 Tab
  - 反思洞察 → 吸收到记忆浏览页
  - 数据迁移 → 归入数据库侧栏
- **数据库详情增强**: 新增备份 Tab
- **记忆浏览增强**: 特征 (traits) 作为独立 Tab，类型筛选标签暖色调重设计
- **港湾暖色调**: 统一品牌色板 (harbor blue #2a4d6a + warm gold/sand)

### SRE 运维控制台 (lakeon-admin)
- **港湾暖色调全面重设计**: style.css + AdminLayout + 所有视图文件统一色板
- **⌘K 搜索占位**: 顶栏搜索入口
- **精简导航**: 移除仪表盘，默认跳转数据库管理页
- **加载状态**: 知识库、数据湖列表页增加加载骨架屏

## ✅ Stage 15e: 知识库交互优化

> 2026-03-29 → 04-01

- **切片检索 UI**: 替换对话式搜索为切片检索界面，更贴合知识库管理场景
- **增量切片加载**: 分页加载切片列表，跳转定位 + 滚动加载
- **全文高亮优化**: 选中切片即时滚动到高亮位置
- **文档摘要折叠**: 默认折叠，显示首行预览
- **上传进度优化**: 列表式进度替换为紧凑进度条
- **清空文档**: 一键清空所有文档，自动重试直到完全清除
- **删除知识库**: 概览 Tab 新增删除知识库操作
- **zhparser 容错**: 隔离 zhparser 安装失败，回退到 simple 分词
- **分页获取全部切片**: 解决大文档切片截断问题
- **自动唤醒日志**: RESUME 操作 (warm + cold 路径) 写入操作日志

## ✅ Stage 15f: 知识库文件夹 & 并行摄入

> 2026-04-01 → 04-02

- **文件夹管理**: 文档按 folder 字段组织，文件夹树形视图 + 面包屑导航
- **文件夹聚合**: folders aggregation API，按文件夹筛选文档列表
- **文档元数据**: metadata JSONB 字段，单条/批量编辑 API，上传时 folder 自动生成 tags
- **知识搜索增强**: 支持 metadata 和 folder 过滤
- **并行摄入**: `/knowledge/ingest` API，按租户配额控制并发 Pod 数，Job launcher 线程池扩大
- **跨库搜索**: 一次搜索所有知识库 (cross-KB search)
- **分页文档列表**: 服务端分页 + 筛选 + 排序，文档统计端点
- **失败重试**: 失败文档单条/批量重试按钮
- **Console**: 进度卡片、筛选 Tab、可排序表头、分页组件

## ✅ Stage 19: 日志可观测性

> 2026-04-01

全链路结构化日志系统，从应用到 SRE AI 诊断。

- **Go log-collector**: HTTP 接收 + PG 批量写入，部署为独立 Deployment
- **Fluent Bit DaemonSet**: CRI parser 采集容器日志，转发到 log-collector
- **结构化日志 (Java)**: JSON 格式 + RequestIdFilter (requestId/tenantId MDC)
- **结构化日志 (Python)**: lakeon-log 模块 (JSON formatter + HTTP batch handler)
- **Knowledge Pipeline 接入**: requestId 透传，全链路追踪
- **dbay-logs 系统租户**: 日志存储迁移到 system 租户，不占用用户配额
- **SRE MCP 工具**: dbay-sre-mcp 提供 log_search/log_trace/log_errors/log_stats
- **Admin API**: 结构化日志查询端点
- **SRE Console**: 日志诊断页面 (搜索、追踪、错误列表、统计)，整合为单 Tab 视图
- **SRE AI 助手**: 日志查询工具集成到 AI 对话

## ✅ Stage 20: 数据生产线

> 2026-03-28 → 04-02

完整的数据处理流水线系统，支持可视化 DAG 编辑、双引擎执行、组件库。

### 后端架构
- **Pipeline 数据模型**: Pipeline/PipelineVersion/PipelineRun/StepRun + Component/ComponentVersion 实体
- **Pipeline CRUD**: PipelineService + PipelineController (含版本管理)
- **Component CRUD**: ComponentService + ComponentController
- **Run 管理**: PipelineRunService + PipelineRunController (提交/查询/取消)
- **Dataset 版本**: DatasetVersionEntity + 版本列表 API
- **Dataset 文件上传**: FILE_UPLOAD source type，upload-urls + finalize 端点
- **预置组件迁移**: V29 migration 预填 12 个视频/文本处理组件 + 2 个模板

### Orchestrator (Python FastAPI)
- **DAG 解析器**: 拓扑排序 + 依赖验证
- **DAG 调度器**: 状态机调度 (PENDING → RUNNING → COMPLETED/FAILED)
- **双执行引擎**:
  - PythonJobRunner — 单 Pod K8s Job (轻量任务)
  - RayJobRunner — RayJob CRD (分布式计算)
- **组件框架**: @Component 装饰器 + ComponentContext + 动态加载
- **高级调度**: fan-out 并行、branch 路由、pause 人工审核、checkpoint 持久化
- **状态管理**: SQLAlchemy StateManager + FastAPI REST 端点

### 预置组件 (12 个)
- **视频**: video_normalize, video_scene_split, video_crop, video_labeling_mock
- **文本**: text_dedup, text_clean, text_tokenize, text_quality_score
- **通用**: rule_filter, model_filter_mock, quality_check (HUMAN_REVIEW), dataset_publish

### Console 前端
- **DAG 编辑器**: Vue Flow + 自定义节点样式 + 拖放添加组件 + YAML 双向同步
- **Pipeline 列表**: 卡片/表格视图 + 数据类型筛选 + 模板创建
- **Pipeline 详情**: 版本列表 + 运行历史
- **运行监控**: 实时 DAG 状态视图 + Step 详情 + 人工审核面板
- **组件库**: pill 筛选 + 滑入式详情面板 + 组件注册页
- **触发对话框**: 运行预览 + DAG-to-Python 代码生成 + 代码预览
- **数据集**: 版本列表 Tab + 文件上传创建

### CLI & SRE
- **dbay pipeline**: CLI 命令支持 pipeline 管理
- **SRE Admin**: Pipeline 监控页面
- **E2E 测试**: 视频流水线 + 文本流水线端到端验证
- **Playwright E2E**: Pipeline 页面 + 组件库页面浏览器自动化测试

## ✅ Stage 21: 自托管 GPU 推理

> 2026-04-01 → 04-03

将 AI 服务从外部 API (硅基流动) 迁移到自托管 GPU 推理，降本增效。

### Embedding Service
- **OpenAI 兼容 API**: `/v1/embeddings` 端点，BGE-M3 模型
- **模型加载**: PVC/OBS 运行时加载，轻量化镜像 (CPU-only PyTorch)
- **GPU 部署**: V100 节点，Helm 模板 + build-and-push 脚本

### LLM Service (vLLM)
- **模型**: Qwen3.5-9B FP16 (从 AWQ 量化切换到 FP16 以适配 V100)
- **部署**: PVC 模型挂载 + 离线模式，GPU memory utilization 0.9
- **健康检查**: 600s liveness probe (大模型加载耗时)

### 内部路由
- **统一模型覆盖**: 可配置 `internal.llm.*` 参数，所有 AI 服务 (摘要、查询重写、AI SQL、SRE AI) 路由到内部 LLM
- **零改动切换**: 通过 Helm values 配置 internal LLM URL/model，应用层代码不变

## ✅ Stage 22: 记忆库端到端加密

> 2026-04-08 → 04-09

用户持有密钥的客户端加密，服务端只存密文。即便数据库泄露也无法读取用户记忆。

### 加密架构
- **三因素密钥**: 密码 (PBKDF2 600k 轮) → 私钥 → DEK → 内容
- **RSA-4096 OAEP-SHA256**: 加密 DEK，服务端存储加密后的 DEK
- **AES-256-GCM**: 内容加密 (12-byte nonce)
- **密钥存储**: `~/.dbay/encrypted_bases.json` (私钥、公钥、盐)，`~/.dbay/secret` (密码)

### Embedding 三种模式
- **DBay API**: 默认，用用户 API key 调服务端 embedding
- **External API**: 用户自有 OpenAI 兼容端点
- **本地模型**: `sentence-transformers` + BAAI/bge-m3，首次自动下载，模型缓存
- **服务端零明文**: 所有 embedding 在客户端生成，服务端只收到密文 + 向量

### Console 与 CLI 双入口
- **Console**: Account 页面加密库管理，密码锁/解锁状态显示
- **CLI**: `dbay mem create --encrypted` 交互式引导创建
- **跨设备迁移**: 复制 `~/.dbay/` 到新机器即可

## ✅ Stage 23: 知识库分享

> 2026-04-08

知识库跨租户只读分享，通过 ShareEntity + Panel UI 实现。

- **分享 API**: 创建/撤销/列表分享，被分享租户通过 `owned` / `shared` 两个列表访问
- **权限模型**: 只读分享，不允许修改
- **Console UI**: 分享面板 + 接收方标识 + 分享列表管理
- **已部署**: hwstaff 生产环境

## ✅ Stage 24: Wiki Agent & 知识图谱

> 2026-04-06 → 04-10

知识库自动生成 Wiki 页面和概念图谱，文档摄入后由 Agent 分析生成结构化知识。

### Wiki Agent
- **Ingest Prompt**: 文档入库后自动识别核心概念 (2-5 个)，生成 wiki 页面，优先更新而非新增
- **Curate Prompt**: "整理 Wiki" 按钮触发重组、合并、翻译、删除泛泛通用概念
- **通用概念过滤**: prompt 明确要求只生成跟知识库主题相关的概念，排除 "AI 产品设计" 等泛词
- **Lint & Chat**: Wiki 内容质量检查 + 基于 Wiki 的对话路由

### 知识图谱
- **可视化**: Wiki 页面交叉引用生成 D3 力导图
- **导航**: 点击节点跳转对应页面，页面间 `[[wikilinks]]` 关联

### 用户侧删除
- **DELETE /wiki/pages/{docId}**: 用户级 API，租户隔离
- **前端删除按钮**: Wiki 页面列表 hover 显示，确认后删除

### KB 详情 3-Tab
- **概览 / 文档 / Wiki**: 引导图放到列表页，详情页聚焦内容

## ✅ Stage 25: Console 港湾风格重构

> 2026-04-07 → 04-08

Console 和 Landing 统一港湾暖色调设计，去掉 emoji 和通用 AI 模板风格。

- **配色**: 暖色调 `#8b6914` / `#5a4a3a` / `#faf5f0`
- **Integrations 页**: 步骤式快速上手 + Tab 面板
- **MCP 引导**: 所有页面 MCP 设置文案统一
- **计量说明**: 只做计量不做计费，去除商业性内容

## ✅ Stage 26: OAuth 登录 (Google/GitHub)

> 2026-04-09 → 04-10

用户可以用 Google 或 GitHub 账号登录 DBay，CLI 支持 `dbay login --google/--github` 浏览器授权流。

### 后端
- **OAuthService**: Authorization Code flow，无 Spring Security OAuth2 依赖，纯 HttpClient 实现
- **数据模型**: `oauth_connections` 表 + `tenants.email/avatar_url`，provider + provider_user_id 唯一索引
- **账号匹配**: 按 provider_user_id 或 email 匹配已有租户，否则自动创建 (无密码，自动生成 username)
- **API**: `GET /auth/oauth/{provider}` 跳转、`/callback` 处理、Hash fragment 直接返回 api_key (多副本兼容)
- **限流**: OAuth 端点 20/min per IP

### GFW 绕过
- **问题**: CCE Pod (中国) 无法访问 Google/GitHub OAuth 端点
- **Google relay**: Railway nginx `proxy_pass` 到 `oauth2.googleapis.com`
- **GitHub relay**: Railway Node.js 脚本 (github-relay.js)，nginx proxy_pass 到 github.com 在 Railway 环境有问题
- **配置**: `LAKEON_OAUTH_RELAY_BASE_URL=https://dbay.cloud`

### 前端
- **Login 页**: Google / GitHub 按钮 + SVG 图标，港湾风格
- **OAuth Callback 页**: 从 URL hash fragment 读取 api_key，存 localStorage 后跳 dashboard
- **错误处理**: OAuth 失败重定向到前端 `/login?error=oauth_failed`

### CLI
- **`dbay login --google/--github`**: 启动本地 HTTP server (随机端口) → 开浏览器 → Hash fragment 两步回调
- **Desktop OAuth Client**: Google 创建 Desktop 类型客户端，用于未来直连场景

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
| lakeon-api | 0.9.215 | CCE (hostNetwork, HTTPS) |
| lakeon-console | — | Railway |
| lakeon-admin | — | Railway |
| lakeon-orchestrator | 0.1.4 | CCE (FastAPI) |
| lakeon-knowledge-job | 0.2.13 | CCI (Job Pod) |
| lakeon-import | 0.2.0 | CCE (Job Pod) |
| lakeon-memory | 0.2.5 | CCE (Sidecar) |
| lakeon-datalake | 0.3.12 | CCI (Serverless) |
| lakeon-embedding | — | CCE (GPU, V100) |
| lakeon-llm | — | CCE (GPU, V100, vLLM) |
| log-collector | — | CCE (Go) |
| dbay-cli | 0.2.7 | pip install |
| dbay-mcp | 0.5.6 | MCP Server |
| dbay-sre-mcp | — | MCP Server |

## 基础设施

| 资源 | 规格 |
|------|------|
| CCE 集群 | lakeon-k8s-cluster (cn-north-4) |
| 固定节点 | 2x c9.2xlarge.2 (8C16G) — 管控面 |
| 弹性节点池 | 1~5x c9.xlarge.2 (4C8G) — compute pod |
| GPU 节点 | V100 — embedding-svc + llm-svc (vLLM) |
| RDS | PostgreSQL (VPC 内网) |
| ELB | 独享型 (TCP:8443, TCP:4432) |
| OBS | lakeon-storage (cn-north-4) |
| 域名 | api.dbay.cloud (API), dbay.cloud (Console), admin.dbay.cloud (Admin), pg.dbay.cloud (PG) |
