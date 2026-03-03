---
description: "交付报告: serverless-pg"
status: completed
created_at: 2026-03-03T00:00:00
updated_at: 2026-03-03T00:00:00
archived_at: null
related_files:
  - rpiv/requirements/prd-serverless-pg.md
  - rpiv/plans/plan-serverless-pg.md
  - rpiv/research-serverless-pg.md
  - rpiv/validation/test-strategy-serverless-pg.md
  - rpiv/validation/test-specs-serverless-pg.md
  - rpiv/validation/code-review-serverless-pg.md
  - rpiv/validation/alignment-review-serverless-pg.md
---

# 交付报告：LakeOn Serverless PostgreSQL

## 完成摘要

| 维度 | 详情 |
|------|------|
| PRD 文件 | `rpiv/requirements/prd-serverless-pg.md` |
| 实施计划 | `rpiv/plans/plan-serverless-pg.md` |
| 技术调研 | `rpiv/research-serverless-pg.md` |
| 代码变更 | 43 个 Java 文件 + 9 个 Python 文件 + 21 个部署配置文件 = **73 个文件** |
| 测试覆盖 | Java 58 tests + Python 21 tests = **79 tests，全部通过** |
| 代码审查 | 21 个问题（3 CRITICAL / 5 HIGH / 8 MEDIUM / 5 LOW） |
| 对齐审查 | 完成度 **92%**，13 项偏离（大部分为正面优化） |

## 交付物清单

### 管控面 API（`lakeon-api/`）

Spring Boot 3.3.5 + Java 17 项目，包含：

- **实体层**：TenantEntity、DatabaseEntity、BranchEntity + 枚举类
- **Repository 层**：JPA Repository，含自定义查询方法
- **Service 层**：
  - TenantService（创建、查看、API Key 认证）
  - DatabaseService（CRUD + 启停 + Neon/K8s 集成 + 回滚逻辑）
  - BranchService（创建/删除/列出分支，基于 Neon Timeline）
  - ComputeLifecycleService（唤醒 + 自动休眠调度）
- **Controller 层**：
  - TenantController、DatabaseController、BranchController（RESTful API）
  - ProxyAdapterController（Neon Proxy 适配：wake_compute / get_endpoint_access_control）
  - GlobalExceptionHandler
- **集成层**：
  - NeonApiClient（Pageserver HTTP API 客户端）
  - ComputePodManager（Fabric8 K8s Pod 生命周期管理）
- **安全**：ApiKeyFilter + ScramUtils（SCRAM-SHA-256）
- **配置**：LakeonProperties + application.yml + schema.sql

### CLI 工具（`lakeon-cli/`）

Python 3.11+ + Typer 项目，包含：

- **13 个命令**：db（create/list/status/suspend/resume/update/delete）、branch（create/list/delete）、tenant（create）、config（set/show）
- **API 客户端**：httpx + rich 表格输出
- **配置管理**：`~/.lakeon/config.toml`

### 部署配置（`deploy/`）

- **Helm Charts**（18 个模板）：Namespace、Pageserver、Safekeeper（StatefulSet x3）、Storage Broker、Proxy、API、ConfigMap、Secret
- **监控**：Prometheus 抓取配置 + 5 条告警规则 + Grafana Dashboard（6 面板）

### 测试代码

- **Java**：58 个测试用例（Service 单元测试 + Controller 集成测试 + Repository 测试 + Neon API WireMock 测试）
- **Python**：21 个测试用例（db/branch/config 命令测试）

## 关键决策记录

| 决策 | 原因 |
|------|------|
| JDK 21 → 17 | 开发环境 JDK 版本为 17，优先保证编译通过 |
| 实体命名 `XxxEntity` | 与 QA 预写测试代码对齐，避免修改测试 |
| DTO 使用 Builder 模式（非 record） | 与测试代码中的 `.builder()` 调用兼容 |
| MVP 使用单一 Neon 镜像 | 简化部署，通过不同 command 启动不同组件 |
| Compute 信息存储在 Database 级别 | 简化 MVP，每个实例默认一个 compute |
| CLI 配置使用 TOML 格式 | 比 JSON 更适合手动编辑的配置文件 |

## 遗留问题

### CRITICAL（需在上线前修复）

1. **CR-CRIT-001**：明文密码存储在 `connectionUri` 中并通过 API 返回 — 需改为仅创建时一次性返回密码
2. **CR-CRIT-002**：`/proxy/**` 端点缺乏认证 — 需添加内部 Token 验证（`--control-plane-token`）
3. **CR-CRIT-003**：ComputePodManager init 容器通过 shell echo 传递配置存在命令注入风险 — 需改用 ConfigMap 挂载

### HIGH（建议尽快修复）

1. NeonApiClient URL 拼接未做编码
2. 数据库密码存储策略需重新设计
3. NeonApiClient 未配置超时和重试
4. 自动休眠调度器并发问题
5. API Key 生成使用 UUID 不够安全

### 功能遗漏（中优先级）

1. 分支级 Proxy 路由未完整实现（branchName 被解析但未使用）
2. 异步 provisioning 未实现（同步方式可能导致 API 超时）
3. 部分测试文件缺失（ProxyAdapterControllerTest、ScramUtilsTest、ApiKeyFilterTest）

## 建议后续步骤

1. **P0 - 安全修复**：修复 3 个 CRITICAL 问题，这是上线的前置条件
2. **P1 - 集成验证**：在华为云 CCE 上部署，验证 OBS S3 兼容性和 Neon 组件联通
3. **P2 - Proxy 分支路由**：完善分支级连接路由功能
4. **P3 - 异步化**：创建实例/分支改为异步操作，避免 API 超时
5. **P4 - 端到端测试**：在真实环境执行完整验收测试（创建→连接→写数据→休眠→唤醒→读数据→分支→删除）
