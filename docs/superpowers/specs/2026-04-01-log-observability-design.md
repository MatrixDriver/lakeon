# 日志可观测性系统设计

> 日期: 2026-04-01
> 状态: 设计评审

## 1. 目标

CC 和 SRE AI 助手能自动获取结构化日志来诊断和修复线上问题，不需要 SRE 人工介入。SRE 控制台也提供可视化的日志搜索、调用链追踪、错误概览功能。

### 目标用户
- **CC (Claude Code)** — 通过 dbay-sre-mcp 自动拉取日志定位问题
- **SRE AI 助手** — CCE 后端直连日志库，对话式诊断
- **SRE 人员** — SRE 控制台页面可视化查询

### 覆盖组件（第一期）
- lakeon-api (Java, Spring Boot)
- Knowledge Pipeline (Python, Job Pod)

### 非目标
- Neon 组件（pageserver/safekeeper/proxy）的 requestId 注入（第三方 Rust 二进制，改动重且不在业务调用链上）
- 实时告警（现有 AlertService 已覆盖）

---

## 2. 整体架构

```
CCE 组件 (lakeon-api, Neon) → stdout (JSON, 带 requestId/tenantId)
    ↓
Fluent Bit DaemonSet (每个 CCE 节点)
  tail /var/log/containers/*.log
  → HTTP POST 转发
    ↓
log-collector Deployment (Go, CCE 长驻)  ←── HTTP POST ── CCI 组件 (lakeon-log Python 模块)
    ↓
批量 INSERT + 重连/重试/缓冲
    ↓
dbay 日志库 (Serverless PG, 专用 dbay-logs 数据库)
    ↑                 ↑                ↑
CC (dbay-sre-mcp)    SRE AI 助手      SRE 控制台页面
  via PG 连接          via PG 连接      via Admin API
```

### 组件运行位置与日志采集方式

| 组件 | 运行位置 | 生命周期 | 采集方式 |
|------|---------|---------|---------|
| lakeon-api | CCE 固定节点 | 长驻 | Fluent Bit DaemonSet tail stdout |
| Pageserver/Safekeeper/Proxy | CCE 固定节点 | 长驻 | Fluent Bit DaemonSet tail stdout |
| Embedding Service | CCE 弹性节点池 | 长驻 | Fluent Bit DaemonSet tail stdout |
| Compute Pod | CCE 弹性节点池 | 动态 | Fluent Bit DaemonSet tail stdout |
| Knowledge Pipeline Job | CCI (Virtual Kubelet) | 短生命周期 | lakeon-log Python 模块 HTTP 推送 |
| Datalake Python/Ray/Finetune Job | CCI | 短/中生命周期 | lakeon-log Python 模块 HTTP 推送 |
| Notebook Session | CCI (含热池) | 中生命周期 | lakeon-log Python 模块 HTTP 推送 |

### 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 日志存储 | dbay Serverless PG | 吃自己狗粮，SQL 查询灵活，零额外基础设施 |
| CCE 采集 | Fluent Bit DaemonSet → HTTP 转发 | tail 节点日志文件，成熟可靠；不直写 PG 避免 Neon Proxy 兼容性风险 |
| CCI 采集 | 组件内 HTTP 推送 | CCI 无 DaemonSet，推模式；CCI → CCE 网络已验证可达（Job 回调机制） |
| PG 写入 | log-collector (Go) 统一写入 | 唯一写 PG 的组件，psql 直连 dbay，完全可控 |
| log-collector 语言 | Go | 并发性能好，单二进制 ~10MB 内存，适合高吞吐 I/O |
| lakeon-api 日志改造 | 只改 JSON 格式 + MDC Filter | 不加 HTTP appender，由 DaemonSet 采集，零侵入 |
| requestId 传播 | API → Knowledge Pipeline（环境变量注入） | Neon 组件不注入（第三方二进制，不在业务调用链上） |
| CC 接入 | dbay-sre-mcp (独立 MCP) | PG 直连日志库，凭证仅在 SRE/开发者机器 |
| 安全隔离 | dbay-sre-mcp 与用户 dbay-mcp 完全分离 | Admin 凭证不对外暴露 |

---

## 3. 日志格式

### 3.1 结构化 JSON 格式

所有我方组件（Java/Python）统一输出：

```json
{
  "ts": "2026-04-01T10:23:45.123Z",
  "level": "INFO",
  "component": "lakeon-api",
  "logger": "c.l.knowledge.KnowledgeService",
  "requestId": "req_a1b2c3d4",
  "tenantId": "tn_xyz",
  "dbId": "db_abc",
  "msg": "Hybrid search completed",
  "durationMs": 142,
  "extra": { "kbId": "kb_001", "resultCount": 5 },
  "thread": "http-nio-8090-exec-3"
}
```

Neon 组件保持原始日志格式（非 JSON），log-collector 解析时 component 设为 pageserver/safekeeper/proxy，其余字段尽量从文本中提取，无法提取的为 null。

### 3.2 requestId 跨组件传播

```
用户请求 → lakeon-api
  1. RequestContextFilter 生成 requestId (req_ + 8位 hex)
  2. 从 Authorization header 解析 tenantId
  3. 放入 SLF4J MDC，后续所有 log 自动携带
  4. 响应 header 返回 X-Request-Id
      ↓
  创建 Knowledge Job Pod
  5. requestId 注入为环境变量 LAKEON_REQUEST_ID
  6. tenantId 注入为环境变量 LAKEON_TENANT_ID
      ↓
  Knowledge Pipeline
  7. lakeon-log 模块读取环境变量，logging formatter 自动携带
```

---

## 4. 日志采集

### 4.1 Fluent Bit DaemonSet (CCE 侧)

部署在每个 CCE 节点上（固定节点 + 弹性节点池），toleration 覆盖弹性节点 taint。

功能：
- tail `/var/log/containers/*.log`（K8s 容器日志文件）
- 解析 JSON（我方组件）或保持原文（Neon 组件）
- HTTP POST 批量转发到 log-collector Deployment

```yaml
# Fluent Bit 配置核心
[INPUT]
    Name              tail
    Path              /var/log/containers/*.log
    Parser            docker
    Tag               kube.*
    Refresh_Interval  5
    Mem_Buf_Limit     5MB

[OUTPUT]
    Name              http
    Match             *
    Host              log-collector.lakeon.svc.cluster.local
    Port              9880
    URI               /logs
    Format            json
    Retry_Limit       3
```

DaemonSet toleration 覆盖弹性节点池：
```yaml
tolerations:
  - key: "lakeon/role"
    operator: "Exists"
    effect: "NoSchedule"
```

资源限制：CPU 100m / 内存 128Mi。

### 4.2 lakeon-log Python 模块 (CCI 侧)

共享 Python 模块，打入所有 CCI Job 镜像。

功能：
- `LakeonJsonFormatter` — JSON 格式化，自动带 requestId/tenantId/component
- `FluentBitHandler` — logging handler，异步批量 HTTP POST 到 log-collector
- 从环境变量读取上下文：`LAKEON_REQUEST_ID`, `LAKEON_TENANT_ID`, `LAKEON_LOG_ENDPOINT`

```python
# Job Pod 环境变量注入（由 JobPodManager 设置）
env:
  - name: LAKEON_REQUEST_ID
    value: "req_a1b2c3d4"
  - name: LAKEON_TENANT_ID
    value: "tn_xyz"
  - name: LAKEON_LOG_ENDPOINT
    value: "http://log-collector.lakeon.svc.cluster.local:9880/logs"
```

CCI → CCE 网络可达性已验证（现有 Job 回调机制 `JOB_CALLBACK_URL` 使用同一网络路径）。

如果 log-collector 不可达，降级为仅 stdout 输出（不阻塞 Job 执行）。

---

## 5. log-collector 服务 (Go)

CCE 上独立 Deployment（1 副本），是唯一写入 dbay 日志库的组件。

### 5.1 核心功能

- **HTTP 接收端点** `POST /logs` — 接收 Fluent Bit 转发和 CCI 组件推送的日志
- **内存缓冲** — 日志先进 channel，批量处理
- **批量写入** — 每 2 秒或攒满 500 条，批量 INSERT 到 dbay PG
- **重连/重试** — dbay 日志库可能休眠，连接时触发唤醒，含指数退避重试
- **背压控制** — channel 满时丢弃最旧日志（日志丢失优于阻塞上游）
- **健康检查** — `/healthz` 端点

### 5.2 连接 dbay 日志库

使用标准 `lib/pq` (Go PostgreSQL driver) 通过 Neon Proxy 连接：

```
postgres://user@pg.dbay.cloud:4432/dbay-logs
```

连接池配置：max 5 连接，idle timeout 5min。

### 5.3 资源与部署

```yaml
resources:
  requests:
    cpu: "50m"
    memory: "64Mi"
  limits:
    cpu: "500m"
    memory: "256Mi"
```

单二进制 Go 镜像，scratch 基础镜像，镜像大小 ~15MB。

---

## 6. dbay 日志库

### 6.1 数据库

在 dbay 上创建专用数据库 `dbay-logs`，1cu 规格，suspend_timeout 30min。

### 6.2 表结构

```sql
CREATE TABLE logs (
    id          BIGSERIAL PRIMARY KEY,
    ts          TIMESTAMPTZ NOT NULL,
    level       VARCHAR(8) NOT NULL,
    component   VARCHAR(32) NOT NULL,
    request_id  VARCHAR(32),
    tenant_id   VARCHAR(64),
    db_id       VARCHAR(64),
    logger      VARCHAR(128),
    msg         TEXT NOT NULL,
    duration_ms INTEGER,
    extra       JSONB,
    thread      VARCHAR(64)
);

-- 核心索引
CREATE INDEX idx_logs_ts ON logs (ts DESC);
CREATE INDEX idx_logs_request_id ON logs (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX idx_logs_tenant_ts ON logs (tenant_id, ts DESC) WHERE tenant_id IS NOT NULL;
CREATE INDEX idx_logs_level_ts ON logs (level, ts DESC);
CREATE INDEX idx_logs_component_ts ON logs (component, ts DESC);

-- 全文搜索（可选，用于关键词搜索）
CREATE INDEX idx_logs_msg_tsvector ON logs USING GIN (to_tsvector('simple', msg));
```

### 6.3 日志保留

pg_cron 每天凌晨清理超过 30 天的日志：

```sql
SELECT cron.schedule('log-cleanup', '0 3 * * *', $$DELETE FROM logs WHERE ts < now() - interval '30 days'$$);
```

### 6.4 日志量估算

| 场景 | 日志量/天 | 30 天累积 |
|------|----------|----------|
| hwstaff (演示环境) | ~15-25 MB | ~500-750 MB |
| 未来生产环境 (10x) | ~150-250 MB | ~5-7.5 GB |

PG 单表在这个量级无需分区。如日志量增长到 50GB+ 再考虑按月分区。

---

## 7. 日志消费

### 7.1 dbay-sre-mcp

独立 MCP Server (Python + FastMCP)，仅部署在 SRE/开发者机器上。通过 PG 连接串直连 dbay 日志库。

**四个 tool：**

#### log_search
灵活搜索日志。
- 入参：component?, level?, keyword?, tenant_id?, db_id?, since? (默认 1h), limit? (默认 100)
- SQL：`WHERE` 条件动态组合 + `ORDER BY ts DESC`
- keyword 搜索使用 `to_tsvector('simple', msg) @@ plainto_tsquery('simple', ?)`

#### log_trace
调用链追踪，拉出一个请求的完整日志。
- 入参：request_id
- SQL：`WHERE request_id = ? ORDER BY ts`
- 返回按时间排序的日志列表，包含跨组件（api + pipeline）的完整链路

#### log_errors
查看最近错误。
- 入参：since? (默认 1h), component?
- SQL：`WHERE level IN ('ERROR', 'WARN') AND ts > ? ORDER BY ts DESC`

#### log_stats
概览统计。
- 入参：since? (默认 24h)
- SQL：`GROUP BY component, level` 返回各组件的日志量和错误数
- 附加：慢操作 Top 10（`ORDER BY duration_ms DESC LIMIT 10`）

**CC 自动诊断流程示例：**

```
用户："知识库搜索有问题"
CC → log_errors(component="knowledge-pipeline", since="1h")
   → 发现 3 条 ERROR，拿到 request_id
CC → log_trace(request_id="req_a1b2c3d4")
   → 看到 API 层收到请求 → Pipeline 启动 → embedding 调用超时
CC → 定位代码 → 修复
```

### 7.2 SRE 控制台页面

在 lakeon-admin 新增「日志诊断」菜单，4 个子页面：

#### 日志搜索
- 筛选栏：组件、级别、租户、时间范围、关键词
- 结果表格：时间、级别、组件、requestId（可点击跳转调用链）、消息摘要
- 点击展开单条日志详情（完整 msg + extra JSON）

#### 调用链追踪
- 输入 requestId 或从日志搜索点击进入
- 时间线视图：按时间排列同一 requestId 的所有日志，不同组件用不同颜色区分
- 相邻日志之间的耗时标注

#### 错误概览
- 最近 N 小时的错误/警告列表
- 按组件分组统计
- 错误消息聚合（相同错误归类，显示出现次数）

#### 统计概览
- 各组件日志量趋势（按小时）
- 错误率趋势
- 慢操作 Top 10（按 durationMs 排序）

SRE 控制台页面通过现有 Admin API 查询（新增日志相关端点），Admin Token 认证。

### 7.3 SRE AI 助手

AI 助手后端跑在 CCE 上，直接 SQL 查 dbay 日志库（内部网络），能力等同于 dbay-sre-mcp 的 4 个 tool。用户在 SRE 控制台对话框里提问，AI 助手自动查询并返回诊断结果。

---

## 8. lakeon-api 改造

改动最小化，不侵入业务逻辑：

### 8.1 RequestContextFilter (新增)

Servlet Filter，拦截所有 `/api/v1/**` 请求：
- 生成 `req_` + 8 位随机 hex 作为 requestId
- 从 SecurityContext 或 ApiKeyFilter 解析 tenantId
- 放入 SLF4J MDC：`requestId`, `tenantId`
- 响应 header 设置 `X-Request-Id`
- 请求完成后清理 MDC

### 8.2 logback-spring.xml (新增)

替代 Spring Boot 默认日志配置：
- Console appender 输出 JSON 格式（logstash-logback-encoder）
- MDC 字段自动包含在 JSON 输出中
- 保持 stdout 输出（K8s 原生日志查看不受影响）

### 8.3 pom.xml (依赖)

新增：`net.logstash.logback:logstash-logback-encoder`

### 8.4 JobPodManager (修改)

创建 Job Pod 时注入 requestId 相关环境变量：
- `LAKEON_REQUEST_ID`
- `LAKEON_TENANT_ID`
- `LAKEON_LOG_ENDPOINT`

---

## 9. Knowledge Pipeline 改造

### 9.1 lakeon-log Python 模块 (新增)

共享模块，安装到所有 CCI Job 镜像中：
- `LakeonJsonFormatter` — JSON 格式化
- `FluentBitHandler` — 异步 HTTP POST 到 log-collector
- `setup_logging()` — 一行初始化，从环境变量读取 requestId/tenantId/endpoint

### 9.2 Knowledge Pipeline 接入

在 `main.py` 入口调用 `setup_logging(component="knowledge-pipeline")`，其余代码的 logging 调用不变，自动输出结构化 JSON。

日志级别：INFO（生产默认），per-chunk 细节用 DEBUG。

---

## 10. 实施阶段

### Phase 1：日志基础设施（地基）
- 创建 dbay-logs 数据库 + 表结构 + 索引
- log-collector Go 服务开发 (HTTP 接收 + PG 批量写入)
- Fluent Bit DaemonSet Helm chart
- Helm 集成部署

### Phase 2：日志生产（接入组件）
- lakeon-api：RequestContextFilter + logback-spring.xml (JSON)
- lakeon-log Python 模块
- Knowledge Pipeline 接入
- JobPodManager 注入 requestId 环境变量
- 端到端验证：发知识库请求 → 确认日志库有完整调用链

### Phase 3：日志消费 — MCP + SRE 页面
- dbay-sre-mcp：4 个 tool (log_search / log_trace / log_errors / log_stats)
- SRE 控制台：日志搜索、调用链追踪、错误概览、统计概览 4 个页面
- CC 端到端验证：CC 通过 MCP 自动诊断模拟问题

### Phase 4：SRE AI 助手集成
- SRE 控制台 AI 助手后端接入日志库查询
- 对话式诊断验证

---

## 11. 红蓝对抗结论

经过 2 轮攻防，主要变更：

1. **Fluent Bit 不直写 PG** — PG 插件与 Neon Proxy 兼容性未验证，改为 HTTP 转发到 log-collector
2. **lakeon-api 不加 HTTP appender** — 只改 JSON stdout，DaemonSet 采集，零侵入
3. **HTTP 推送仅限 CCI 组件** — CCE 上统一 DaemonSet 采集
4. **职责拆分** — Fluent Bit 做采集缓冲（擅长），log-collector 做 PG 写入（可控）

已知风险：
- 日志库持续运行成本（1cu，通过长 suspend_timeout 缓解）
- Pipeline 大批量日志（通过日志级别控制 + log-collector 限速缓解）
