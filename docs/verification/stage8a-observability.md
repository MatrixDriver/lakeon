# Stage 8a 验证报告：自建可观测性

## 概述

在 SRE 控制台中内嵌可观测性功能：自定义 Micrometer 指标、日志查看器、应用指标仪表盘、告警服务、基础设施监控。同时为 Stage 8b（华为云 AOM/CES/SMN）铺设共享基础。

**验证结果：所有功能项已实现，集成测试 10/10 通过。**

## 环境

| 组件 | 配置 |
|------|------|
| lakeon-api | 0.1.23, Spring Boot 3.3.5 + Micrometer + Fabric8 K8s Client |
| lakeon-admin | 0.1.7, Vue 3 + TypeScript + Canvas 图表 |
| CCE 集群 | 1x 8C16G 节点 |
| ELB | 114.116.210.49 (proxy:4432, console:80, admin:8081) |

## 验证项

### 1. 自定义 Micrometer 指标

| 指标 | 类型 | 状态 |
|------|------|------|
| `lakeon_tenants_total` | Gauge | PASS |
| `lakeon_databases_total` | Gauge (tag: status) | PASS |
| `lakeon_compute_pods_active` | Gauge | PASS |
| `lakeon_compute_wakeup_seconds` | Timer | PASS |
| `lakeon_compute_wakeup_failures_total` | Counter | PASS |
| `lakeon_storage_used_bytes` | Gauge | PASS |
| `lakeon_api_operations_total` | Counter (tag: type, status) | PASS |
| Prometheus alerts.yml 5 条规则 | 全部指标名匹配 | PASS |

### 2. 新增 Admin API

| 端点 | 方法 | 说明 | 状态 |
|------|------|------|------|
| `/admin/logs/{component}` | GET | 组件日志 (tail 参数) | PASS |
| `/admin/metrics/summary` | GET | 应用指标汇总 | PASS |
| `/admin/alerts` | GET | 告警历史 | PASS |
| `/admin/alerts/rules` | GET | 告警规则列表 | PASS |
| `/admin/alerts/rules/{id}` | PUT | 更新告警规则 | PASS |
| `/admin/alerts/test-webhook` | POST | 测试 Webhook | PASS |
| `/admin/infra/nodes` | GET | 节点资源 + Pod 排行 | PASS |

### 3. 前端页面

| 页面 | 路由 | 功能 | 状态 |
|------|------|------|------|
| 应用指标 | /metrics | JVM/API/Compute/DB/Storage 卡片 + Canvas 趋势图 | PASS |
| 日志查看 | /logs | 终端风格面板、组件切换、搜索高亮、自动刷新 | PASS |
| 告警管理 | /alerts | 规则列表、告警历史、启用/禁用、Webhook 测试 | PASS |
| 基础设施 | /infra | 节点 CPU/内存进度条 + Pod 资源排行 | PASS |

### 4. Helm 模板

| 改动 | 状态 |
|------|------|
| API Deployment prometheus.io annotations | PASS |
| Pageserver Deployment prometheus.io annotations (port 9898) | PASS |
| Proxy Deployment prometheus.io annotations (port 7000) | PASS |
| Safekeeper StatefulSet prometheus.io annotations (port 7676) | PASS |
| ConfigMap SMN 告警环境变量 | PASS |

### 5. 告警服务

| 巡检规则 | 说明 | 状态 |
|----------|------|------|
| 组件宕机 | checkAllComponents() 任一 unhealthy | PASS |
| 唤醒失败 | wakeup_failures Counter 增量 > 0 | PASS |
| API 高延迟 | P99 > 阈值 | PASS |
| 存储超限 | 数据库存储 > 90% 配额 | PASS |
| Pod 异常 | compute pod 非 Running > 5 分钟 | PASS |

通知渠道：企业微信、钉钉、通用 Webhook（通过 AlertRule.webhookUrl 配置）。

### 6. 端到端集成测试 (10/10)

使用本地 psycopg2 客户端通过外网 EIP 连接 Lakeon：

| # | 测试 | 结果 |
|---|------|------|
| 1 | 列出现有数据库 | PASS |
| 2 | 创建数据库 | PASS |
| 3 | 获取数据库详情 | PASS |
| 4 | 等待 RUNNING 状态 | PASS |
| 5 | SQL: CREATE TABLE + INSERT + SELECT (psycopg2) | PASS |
| 6 | 挂起数据库 | PASS |
| 7 | 恢复数据库 | PASS |
| 8 | 数据持久化验证 (suspend/resume 后) | PASS |
| 9 | 无效认证返回 401 | PASS |
| 10 | 删除数据库 + 确认 404 | PASS |

## 已知问题

### Pageserver 重启后 Tenant 丢失

**现象**：Helm 升级重启 pageserver 后，tenant 列表为空，compute pod 报 `Tenant not found`。

**原因**：Pageserver 重启后需要重新 attach tenant（从 OBS 加载数据）。

**临时修复**：
```bash
# 查询 tenant ID
kubectl exec -n lakeon deployment/lakeon-api -- curl -s http://pageserver:9898/v1/tenant

# 若为空，手动 attach
kubectl exec -n lakeon deployment/pageserver -- curl -s -X PUT \
  "http://localhost:9898/v1/tenant/<neon_tenant_id>/location_config" \
  -H "Content-Type: application/json" \
  -d '{"mode": "AttachedSingle", "tenant_conf": {}, "generation": 1}'
```

**后续优化**：lakeon-api 启动时应自动检查并 re-attach 所有已知 tenant。

## 镜像版本

| 组件 | 版本 | 变更 |
|------|------|------|
| lakeon-api | 0.1.23 | +Micrometer 指标, +日志/指标/告警/基础设施 API |
| lakeon-admin | 0.1.7 | +应用指标, +日志查看, +告警管理, +基础设施 |
