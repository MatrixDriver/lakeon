# Lakeon SRE Admin Console — 设计文档

## 定位

独立部署的管理控制台（`lakeon-admin`），供 SRE/管理员监控和管理 Lakeon 云服务。
与用户控制台（`lakeon-console`）分离部署，使用 Admin Token 认证。

## 技术栈

- 前端：Vue 3 + TypeScript + Vite（复用 lakeon-console 的 UI 风格）
- 后端：扩展 lakeon-api 的 `/api/v1/admin/` 端点
- 部署：Docker 多阶段构建 + Helm Chart + CCE

## 六大模块

### 1. 总览仪表盘（Dashboard）

**卡片指标：**
- 租户总数
- 数据库实例总数（运行中 / 已挂起 / 异常）
- 计算节点总数（当前活跃 Pod 数）
- 当月预估成本

**图表：**
- 24h 操作统计柱状图（CREATE / SUSPEND / RESUME / DELETE）
- 7 天成本趋势折线图

**组件状态灯：**
- Pageserver / Safekeeper / Proxy / OBS / RDS — 绿灯（正常）/ 红灯（异常）

**后端 API：**
```
GET /api/v1/admin/dashboard
→ {
    tenant_count, database_count,
    databases_by_status: {running, suspended, creating, error},
    active_compute_pods,
    operation_stats_24h: {create, suspend, resume, delete},
    estimated_monthly_cost,
    component_health: {pageserver, safekeeper, proxy, obs, rds}
  }
```

### 2. 租户管理（Tenants）

**列表页：**
- 搜索（按名称）
- 排序（创建时间、数据库数量）
- 字段：名称、ID、数据库数/配额、存储用量/配额、计算配额、创建时间、状态

**操作：**
- 调整配额（max_databases, max_storage_gb, max_compute_cu）
- 禁用/启用租户（新增 `disabled` 字段）
- 查看租户下所有数据库

**后端 API（已有 + 扩展）：**
```
GET    /api/v1/admin/tenants              — 租户列表（已有）
GET    /api/v1/admin/tenants/:id          — 租户详情（已有）
PUT    /api/v1/admin/tenants/:id/quota    — 更新配额（已有）
PUT    /api/v1/admin/tenants/:id/status   — 启用/禁用（新增）
```

### 3. 实例与计算节点监控（Databases & Compute）

**全局数据库列表：**
- 跨租户展示所有数据库
- 筛选：按状态、租户、名称
- 字段：名称、租户、状态、规格、存储用量、compute Pod、唤醒耗时、最后活跃时间

**计算节点详情：**
- 当前 compute Pod 列表（从 K8s 读取）
- 启动耗时统计（从 operation_logs 的 RESUME/CREATE 操作的 duration_ms 聚合）
- P50/P90/P99 唤醒延迟

**后端 API：**
```
GET /api/v1/admin/databases                 — 全局数据库列表
GET /api/v1/admin/databases/:id             — 数据库详情（含 compute 信息）
GET /api/v1/admin/compute/pods              — 当前所有 compute Pod（K8s）
GET /api/v1/admin/compute/stats             — 唤醒延迟统计
```

### 4. 系统组件健康（System Health）

**组件列表：**

| 组件 | 检查方式 | 额外信息 |
|------|---------|---------|
| Pageserver | GET pageserver:9898/v1/status | tenant 数量 |
| Safekeeper | GET safekeeper:7676/v1/status | 各实例状态 |
| Proxy | TCP 连接检查 4432 | 连接数 |
| OBS | HEAD bucket 请求 | 存储用量 |
| RDS | JDBC 连通性 | 连接池状态 |
| API | /actuator/health | JVM 指标 |

**后端 API：**
```
GET /api/v1/admin/system/health             — 所有组件健康状态
GET /api/v1/admin/system/health/:component  — 单个组件详情
```

### 5. 操作审计日志（Audit Logs）

**全局操作列表：**
- 跨租户展示所有操作
- 筛选：按租户、操作类型、状态（成功/失败）、时间范围
- 失败操作红色高亮 + 错误详情展开
- 分页

**后端 API：**
```
GET /api/v1/admin/operations?tenant_id=&type=&status=&from=&to=&page=&size=
```

### 6. 成本监控（Cost）

**成本模型（自行估算）：**

基于已知华为云单价和资源用量在 Lakeon 内部计算，不依赖云 API。

| 资源 | 计费维度 | 估算方式 |
|------|---------|---------|
| CCE 节点 | 节点数 × 规格单价 × 运行小时 | 配置文件中设定节点规格和单价 |
| ELB | 固定月费 + 带宽 | 配置文件中设定 |
| OBS 存储 | 容量 × 单价/GB/月 | 从 pageserver 获取实际存储量 |
| RDS | 实例规格 × 单价 | 配置文件中设定 |
| EIP | 带宽 × 单价 | 配置文件中设定 |
| Compute Pod | CU × 运行时长 | 从 operation_logs 聚合 |

**租户成本分摊：**
- 按 compute 运行时长（从 suspend/resume 时间差计算）
- 按存储用量占比

**配置文件（application.yml 或 admin 配置）：**
```yaml
lakeon:
  cost:
    cce-node-hourly: 1.5       # 元/节点/小时
    cce-node-count: 3
    elb-monthly: 30            # 元/月
    obs-per-gb-monthly: 0.099  # 元/GB/月
    rds-monthly: 500           # 元/月
    eip-monthly: 150           # 元/月
    compute-cu-hourly: 0.5     # 元/CU/小时
```

**页面内容：**
- 当月成本总览（按资源类型饼图）
- 日成本趋势折线图（最近 30 天）
- 按租户成本排行（TOP 10）
- 成本明细表

**后端 API：**
```
GET /api/v1/admin/cost/summary              — 当月成本总览
GET /api/v1/admin/cost/daily?days=30        — 日成本趋势
GET /api/v1/admin/cost/tenants              — 按租户成本分摊
```

## 前端页面结构

```
lakeon-admin/
├── src/
│   ├── views/
│   │   ├── login/LoginView.vue          # Admin Token 登录
│   │   ├── dashboard/DashboardView.vue  # 总览仪表盘
│   │   ├── tenants/
│   │   │   ├── TenantList.vue           # 租户列表
│   │   │   └── TenantDetail.vue         # 租户详情（含配额编辑）
│   │   ├── databases/
│   │   │   └── DatabaseList.vue         # 全局数据库列表
│   │   ├── system/
│   │   │   └── SystemHealth.vue         # 组件健康
│   │   ├── operations/
│   │   │   └── OperationList.vue        # 审计日志
│   │   └── cost/
│   │       └── CostView.vue            # 成本监控
│   ├── layouts/AdminLayout.vue          # 侧边栏 + 顶栏
│   ├── api/                             # Admin API 客户端
│   └── router/index.ts
```

## 侧边栏导航

```
 总览
 ├─ 仪表盘
 运维管理
 ├─ 租户管理
 ├─ 数据库实例
 ├─ 操作日志
 系统
 ├─ 组件健康
 ├─ 成本监控
```

## 认证

- 登录页输入 Admin Token（即 `LAKEON_ADMIN_TOKEN`）
- 前端存储到 localStorage
- 所有 API 请求带 `Authorization: Bearer <admin_token>`
- 后端 ApiKeyFilter 已支持 `/api/v1/admin/` 路径的 admin token 验证

## 部署

- 独立 Docker 镜像 `lakeon-admin`
- Helm Chart 中新增 `admin` 配置块（类似 console）
- Nginx 反向代理 `/api/` 请求到 lakeon-api
- 建议 CCE 上通过 NodePort 或内部 ELB 暴露（不对外公开）
