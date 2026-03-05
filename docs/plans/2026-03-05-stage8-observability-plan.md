# 阶段 8：可观测性与运维 — 双轨对比方案

## 概述

同时上线两套可观测性方案进行实际对比：

- **Track A（自建）**：日志、指标、告警全部内嵌到 SRE 控制台
- **Track B（华为云）**：AOM 指标 + CES 基础设施监控 + SMN 告警通知，日志用自建

不使用 LTS（日志）和 APM（追踪）。

## 8a：自建可观测性 + 共享基础

### 自定义 Micrometer 指标

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `lakeon_tenants_total` | Gauge | 租户总数 |
| `lakeon_databases_total` | Gauge | 数据库总数 |
| `lakeon_compute_pods_active` | Gauge | 活跃 compute pod |
| `lakeon_compute_wakeup_seconds` | Timer | 唤醒耗时 |
| `lakeon_compute_wakeup_failures_total` | Counter | 唤醒失败 |
| `lakeon_storage_used_bytes` | Gauge | OBS 已用存储 |
| `lakeon_api_operations_total` | Counter(tag: type, status) | 操作计数 |

### 内嵌日志查看器

- `GET /api/v1/admin/logs/{component}?tail=200` — 通过 Fabric8 K8s API 获取 Pod 日志
- 终端风格面板，支持组件切换、搜索高亮、自动刷新

### 应用指标仪表盘

- `GET /api/v1/admin/metrics/summary` — 从 MeterRegistry 直接读取
- JVM / API 性能 / Compute / 数据库 / 存储 五大板块
- 30 秒自动刷新 + 客户端趋势图

### 告警服务

- `@Scheduled(fixedRate = 60000)` 定时巡检
- 5 条默认规则：组件宕机、唤醒失败、API 高延迟、存储超限、Pod 异常
- 支持企业微信 / 钉钉 / 通用 Webhook 通知
- 告警冷却机制 + 自动恢复

### 基础设施监控

- `GET /api/v1/admin/infra/nodes` — K8s metrics API
- 节点 CPU/内存使用率 + Pod 资源占用排行

## 8b：华为云服务接入

### AOM 指标采集

- Pod annotations：`prometheus.io/scrape: "true"`
- AOM 自动发现并采集所有自定义指标
- MetricsView 添加 AOM 控制台外链

### CES 基础设施监控

- CCE 节点指标自动采集（免费）
- InfraMonitor 添加 CES 控制台外链

### SMN 告警通知

- AlertService 扩展 SMN SDK 通知渠道
- 支持邮件 + 短信
- 需要 NAT 网关（Pod 出公网）

## 对比维度

| 维度 | 自建 (Track A) | 华为云 (Track B) |
|------|----------------|-----------------|
| 日志 | K8s API + 内嵌终端 | 同左（不用 LTS） |
| 指标 | MeterRegistry 直读 | AOM 自动采集 |
| 告警 | AlertService + Webhook | SMN + 邮件/短信 |
| 基础设施 | K8s metrics API | CES 自动采集 |
| 成本 | 零额外成本 | AOM/SMN 按量 |
| 运维复杂度 | 代码维护 | 云服务配置 |
