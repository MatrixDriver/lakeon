# Lakeon Web 控制台设计文档

## 1. 概述

为 Lakeon Serverless PostgreSQL 平台构建 Web 控制台，采用华为云服务控制台风格，基于 OpenTiny TinyPro 云服务控制台模板。

### 1.1 目标

- 提供自助式数据库管理界面，替代 curl / CLI 操作
- 风格与华为云其他服务（RDS、ECS 等）控制台一致
- 支持操作历史记录，可视化 suspend/resume 耗时，为唤醒优化提供数据支撑

### 1.2 范围（标准版 MVP）

6 个页面：登录、总览仪表盘、数据库列表、数据库详情（含操作历史 Tab、分支 Tab）、API Key 管理

## 2. 技术架构

### 2.1 技术栈

| 层次 | 技术选型 |
|------|----------|
| 框架 | Vue 3 + TypeScript |
| 构建工具 | Vite |
| UI 组件库 | @opentiny/vue（TinyVue） |
| 项目模板 | TinyPro 云服务控制台模板 |
| HTTP 客户端 | Axios |
| 状态管理 | Pinia |
| 路由 | Vue Router |

### 2.2 项目结构

```
lakeon-console/           # 仓库根目录下独立目录
├── src/
│   ├── api/              # API 接口封装
│   │   ├── client.ts     # Axios 实例 + 拦截器
│   │   ├── database.ts   # 数据库 CRUD
│   │   ├── branch.ts     # 分支管理
│   │   ├── tenant.ts     # 租户信息
│   │   └── operation.ts  # 操作历史
│   ├── views/            # 页面组件
│   │   ├── login/        # 登录页
│   │   ├── dashboard/    # 总览仪表盘
│   │   ├── database/     # 数据库列表 + 详情
│   │   ├── branch/       # 分支管理（详情页 Tab）
│   │   └── apikey/       # API Key 管理
│   ├── stores/           # Pinia 状态
│   │   ├── auth.ts       # 认证状态（API Key）
│   │   └── database.ts   # 数据库列表缓存
│   ├── router/           # 路由配置
│   ├── layouts/          # TinyPro 布局组件
│   └── App.vue
├── Dockerfile            # Nginx 静态文件托管
├── package.json
├── vite.config.ts
└── tsconfig.json
```

### 2.3 部署架构

```
CCE 集群 (lakeon namespace)
├── lakeon-console (Deployment)
│   └── Nginx 容器，托管 Vue 构建产物
│       └── /etc/nginx/conf.d/ 反向代理 /api/* → lakeon-api:8080
├── lakeon-api (Deployment)
│   └── Spring Boot，提供 REST API
└── ...（其他组件）
```

独立 K8s Deployment + Service，通过 Nginx 反向代理转发 API 请求，避免 CORS 问题。

### 2.4 认证流程

```
用户输入 API Key → POST 验证（调用 GET /api/v1/tenants 列表接口验证 Key 有效性）
→ 有效：存入 localStorage，Axios 拦截器自动附加 Authorization header
→ 无效：提示错误
→ 401 响应：自动跳转登录页
```

## 3. 页面设计

### 3.1 全局布局（华为云服务控制台风格）

- **顶部导航栏**：黑色通栏，左侧 Lakeon Logo + 服务名，右侧租户名 + 登出
- **左侧菜单**：白底可折叠，一级菜单项：总览、数据库、API Key
- **内容区**：面包屑导航 + 页面内容
- **主色调**：蓝色 #0073E6，与华为云一致

### 3.2 登录页

- 居中卡片布局，Lakeon Logo + 服务描述
- 单输入框：API Key（password 类型，可切换显示）
- 登录按钮
- 底部提示：联系管理员获取 API Key

### 3.3 总览仪表盘

- **统计卡片行**：数据库总数、运行中、已挂起、异常（带状态色圆点）
- **最近操作列表**：最近 10 条操作记录（来自操作历史接口）
  - 列：数据库名、操作类型、状态、耗时、时间

### 3.4 数据库列表页

- 顶部操作栏：创建数据库按钮 + 搜索框
- 数据表格列：名称、状态（彩色圆点+文字）、规格、存储用量（进度条）、创建时间、操作
- 状态颜色：绿=运行中、灰=已挂起、红=异常、蓝=创建中
- 操作列：根据状态动态显示（运行中→挂起/删除，已挂起→恢复/删除）
- 创建弹窗（Modal）：
  - 名称（必填）
  - 规格：1cu / 2cu / 4cu / 8cu（下拉）
  - 挂起超时：5m / 10m / 30m（下拉）
  - 存储上限：5 / 10 / 50 / 100 GB（下拉）
- 删除操作需二次确认弹窗

### 3.5 数据库详情页

**顶部资源概要卡片**：
- 数据库名称、ID、状态、规格、挂起超时、存储用量
- 连接地址 + 复制按钮
- 操作按钮：挂起/恢复（根据状态）、修改配置

**Tab 切换**：

#### Tab 1：基本信息
- 连接信息卡片（host、port、用户名、数据库名、连接串）
- 配置信息（规格、挂起超时、存储上限）

#### Tab 2：分支
- 分支列表表格：名称、父分支、状态、计算状态、创建时间、操作
- 创建分支按钮 + 弹窗
- main 分支不可删除

#### Tab 3：操作历史
- 操作记录表格：操作类型、状态、开始时间、完成时间、耗时、备注
- 耗时列颜色标识：<1s 绿色、1-5s 橙色、>5s 红色
- 支持按操作类型筛选
- 分页

### 3.6 API Key 管理页

- 显示当前 API Key（脱敏显示，如 `lk_a1b2...****`）
- 复制按钮
- 重新生成按钮（二次确认弹窗，提示旧 Key 立即失效）

## 4. 后端改动

### 4.1 新增：操作历史

#### OperationLogEntity

```
表: operation_logs

| 字段            | 类型         | 说明 |
|----------------|-------------|------|
| id             | String (64)  | PK, 自动生成 "op_" + 8-char UUID |
| database_id    | String (64)  | FK → database_instances |
| tenant_id      | String (64)  | 租户 ID |
| operation_type | Enum         | CREATE / SUSPEND / RESUME / DELETE / UPDATE |
| status         | Enum         | IN_PROGRESS / SUCCESS / FAILED |
| started_at     | Instant      | 操作开始时间 |
| completed_at   | Instant      | 操作完成时间（nullable） |
| duration_ms    | Long         | 耗时毫秒（nullable） |
| error_message  | String (512) | 错误信息（nullable） |
| created_at     | Instant      | 记录创建时间 |
```

#### API 端点

```
GET /api/v1/databases/{dbId}/operations
    Query: type (可选, 筛选操作类型), page, size
    Response: Page<OperationLogResponse>

GET /api/v1/operations/recent
    Query: limit (默认 10)
    Response: List<OperationLogResponse>（总览仪表盘用）
```

### 4.2 新增：API Key 重新生成

```
POST /api/v1/tenants/{tenantId}/regenerate-key
    Response: { api_key: "lk_..." }（新 Key，旧 Key 立即失效）
```

### 4.3 改动：操作记录埋点

在 `DatabaseService` 的 create / suspend / resume / delete / update 方法中，记录操作开始和完成时间。

## 5. 数据流

### 5.1 数据库状态轮询

创建/挂起/恢复等异步操作后，前端每 2 秒轮询 `GET /api/v1/databases/{dbId}` 直到状态变为终态（RUNNING / SUSPENDED / ERROR）。

### 5.2 操作历史刷新

操作完成后自动刷新操作历史列表，显示最新记录和耗时。

## 6. Helm Chart 扩展

新增 `deployment-console.yaml`：

```yaml
# Nginx 容器，托管 Vue 构建产物
# ConfigMap 挂载 nginx.conf（反向代理 API）
# Service: ClusterIP / LoadBalancer
# 端口: 80 (HTTP)
```

values.yaml 新增：

```yaml
console:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-console:latest
  replicas: 1
  resources:
    requests:
      cpu: "100m"
      memory: "128Mi"
```
