# Lakeon (DBay) 产品路线图

> 最后更新: 2026-03-09

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
| Stage 7 | 品牌 & 部署架构升级 | ✅ 完成 | — |
| Stage 8a | 自建可观测性 | ✅ 完成 | `verification/stage8a-observability.md` |
| Stage 8b | 华为云 AOM/CES/SMN | 📋 规划中 | — |
| Stage 10a | 备份与恢复 | ✅ 完成 | — |
| Stage 10b | 分支管理增强 | ✅ 完成 | — |
| Stage 10c | 连接池 | ⏸️ 暂缓 | — |
| Stage 10d | SQL 审计日志 | ✅ 完成 | — |
| Stage 10e | 数据库级权限管理 | ✅ 完成 | — |

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
- Console 备份管理 Tab + API
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

### Backlog
- 多副本 pageserver / safekeeper
- 故障转移验证
- 华为云 IAM SSO
- 数据库连接端到端 TLS
- API 限流
- 用量计费系统
- CI/CD 流水线

## 📋 待修复: 代码审查遗留项 (7 项)

| 优先级 | 问题 | 文件 |
|--------|------|------|
| HIGH | CreateTimelineRequest 构造函数语义不一致 | BranchService.java |
| MEDIUM | HttpClient 生命周期管理 | NeonApiClient.java |
| MEDIUM | waitForPodReady 阻塞事务 | ComputePodManager.java |
| MEDIUM | 密码缺少特殊字符 | DatabaseService.java |
| LOW | DatabaseStatus 序列化风格不一致 | DatabaseStatus.java |
| LOW | endpointish 参数缺注释 | ProxyAdapterController.java |
| LOW | CLI 重复错误处理模式 | lakeon-cli commands |

详见 `rpiv/todo/issue-remaining-code-review-fixes.md`

---

## 当前版本

| 组件 | 版本 | 部署位置 |
|------|------|----------|
| lakeon-api | 0.3.4 | CCE (hostNetwork, HTTPS) |
| lakeon-console | 0.3.7 | Railway |
| lakeon-admin | 0.2.0 | Railway |
| lakeon-import | 0.2.0 | CCE (Job Pod) |

## 基础设施

| 资源 | 规格 |
|------|------|
| CCE 集群 | lakeon-k8s-cluster (cn-north-4) |
| 计算节点 | 1x x1.4u.8g (4C8G) |
| RDS | PostgreSQL (VPC 内网) |
| ELB | 共享型 (TCP:8443, TCP:4432, TCP:80, TCP:8081) |
| OBS | lakeon-storage (cn-north-4) |
| 域名 | api.dbay.cloud (API), dbay.cloud (Console), admin.dbay.cloud (Admin) |
