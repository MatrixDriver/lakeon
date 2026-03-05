# Lakeon

基于 [Neon](https://github.com/neondatabase/neon) 存储引擎的自托管 Serverless PostgreSQL 平台。

Lakeon 将 Neon 的存算分离架构封装为一套可私有部署的 Kubernetes 原生方案，支持按需创建数据库、自动挂起/唤醒计算节点、多租户隔离和对象存储持久化。

详细架构设计请参考 [架构文档](docs/architecture.md)。使用 Neon 官方镜像遇到的兼容性问题及修改建议见 [Neon 修改建议](docs/neon-modifications.md)。

## 项目结构

```
lakeon/
├── lakeon-api/          # 控制面 API (Spring Boot)
│   ├── src/main/java/   # Java 源码
│   ├── src/test/        # 单元测试 + 集成测试
│   └── Dockerfile
├── lakeon-console/      # Web 控制台 (Vue 3 + TinyVue)
│   ├── src/             # 前端源码
│   ├── Dockerfile       # Nginx 静态文件托管
│   └── nginx.conf       # 反向代理 API 请求
├── lakeon-cli/          # 命令行客户端 (Python)
├── deploy/
│   ├── helm/lakeon/     # Helm Chart
│   ├── local/           # 本地部署脚本和配置
│   │   ├── values-local.yaml
│   │   ├── setup.sh
│   │   └── integration-test.sh
│   └── cce/             # 华为云 CCE 部署
│       ├── values-cce.yaml
│       ├── push-images.sh
│       └── integration-test.sh
└── docs/                # 设计文档、验证报告和实施计划
```

## 快速开始（本地部署）

### 前置条件

- Docker Desktop（RAM ≥ 6 GB，启用 Kubernetes）
- kubectl, helm, jq

### 部署

```bash
# 创建 namespace
kubectl create namespace lakeon --dry-run=client -o yaml | kubectl apply -f -
kubectl label namespace lakeon app.kubernetes.io/managed-by=Helm --overwrite
kubectl annotate namespace lakeon meta.helm.sh/release-name=lakeon \
  meta.helm.sh/release-namespace=lakeon --overwrite
kubectl create namespace lakeon-compute --dry-run=client -o yaml | kubectl apply -f -

# 部署 Helm Chart
helm upgrade --install lakeon deploy/helm/lakeon \
  -f deploy/local/values-local.yaml -n lakeon --timeout 5m --no-hooks

# 手动创建 MinIO bucket
kubectl run minio-init --image=minio/mc:latest --restart=Never -n lakeon \
  --command -- sh -c \
  'mc alias set local http://minio:9000 minioadmin minioadmin && \
   mc mb --ignore-existing local/lakeon-neon'
```

### 验证

```bash
# 运行集成测试（31 个 E2E 测试用例）
./deploy/local/integration-test.sh
```

### 基本使用

```bash
# 端口转发
kubectl port-forward -n lakeon svc/lakeon-api 8080:8080 &

# 创建租户
curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "my-tenant"}' --noproxy localhost

# 创建数据库（使用返回的 api_key）
curl -s -X POST http://localhost:8080/api/v1/databases \
  -H "Authorization: Bearer <api_key>" \
  -H "Content-Type: application/json" \
  -d '{"name": "mydb"}' --noproxy localhost
```

### 端到端演示（CCE）

一键演示完整流程：创建租户 → 建库 → 等待计算节点 → 建表写入 10 条数据 → 查询 → 清理。

```bash
KUBECONFIG=~/.kube/cce-lakeon-config ./deploy/cce/demo.sh
```

<details>
<summary>运行效果（点击展开）</summary>

```
── 连接 API (port-forward) ──
✓ API 连接成功

── 1. 创建租户 ──
✓ 租户: tn_d4993013
✓ API Key: lk_c619662802bfc6236...

── 2. 创建数据库 ──
✓ 数据库: db_3528cc68
✓ 连接串: postgres://user_ea248906@compute-db-3528cc68.lakeon-compute:55433/demodb

── 3. 等待计算节点就绪 ──
✓ 计算节点就绪 (9s)

── 4. 写入 10 条数据 ──
✓ 建表: users
✓ 插入 10 条记录

── 5. 查询数据 ──

  SELECT * FROM users:
  ┌────┬──────┬──────────────────────────┬─────────────────────┐
  │ ID │ 姓名 │ 邮箱                     │ 创建时间            │
  ├────┼──────┼──────────────────────────┼─────────────────────┤
  │  1 │ 张三 │ zhangsan@example.com     │ 2026-03-04 08:55:50 │
  │  2 │ 李四 │ lisi@example.com         │ 2026-03-04 08:55:50 │
  │  3 │ 王五 │ wangwu@example.com       │ 2026-03-04 08:55:50 │
  │  4 │ 赵六 │ zhaoliu@example.com      │ 2026-03-04 08:55:50 │
  │  5 │ 孙七 │ sunqi@example.com        │ 2026-03-04 08:55:50 │
  │  6 │ 周八 │ zhouba@example.com       │ 2026-03-04 08:55:50 │
  │  7 │ 吴九 │ wujiu@example.com        │ 2026-03-04 08:55:50 │
  │  8 │ 郑十 │ zhengshi@example.com     │ 2026-03-04 08:55:50 │
  │  9 │ 陈一 │ chenyi@example.com       │ 2026-03-04 08:55:50 │
  │ 10 │ 林二 │ liner@example.com        │ 2026-03-04 08:55:50 │
  └────┴──────┴──────────────────────────┴─────────────────────┘

  ✓ 共 10 条记录

── 6. 聚合查询 ──
  PG 版本: PostgreSQL 17.5 on x86_64-pc-linux-gnu
  数据库大小: 7504 kB
  公共表数量: 2

── 7. 清理 ──
✓ 数据库已删除
✓ 计算节点已回收

═══ 演示完成 ═══
```

</details>

## 部署路线图

从本地开发到华为云生产环境的渐进式部署路径：

### 阶段 0：本地 Docker Desktop ✅

单节点 Kubernetes，MinIO 作为对象存储，所有组件跑在本地。

- [x] Helm Chart 部署
- [x] 多租户 API
- [x] 计算节点按需创建 / 挂起 / 唤醒
- [x] 集成测试（31 个用例全部通过）

📋 [验证报告](docs/verification/stage0-local-k8s.md)

### 阶段 1：本地 K8s + 华为云 OBS ✅

用华为云 OBS 替换 MinIO，验证 Neon 存储层在真实对象存储上的兼容性。

- [x] 配置 OBS endpoint / AK / SK（通过 `--set` 传递，不入库）
- [x] 解决 Neon path-style 寻址与 OBS virtual-host-style 不兼容问题
- [x] 处理 OBS 网络延迟导致的 tenant 状态竞争
- [x] 集成测试（31 个用例全部通过，数据持久化到 OBS）

📋 [验证报告](docs/verification/stage1-obs-storage.md)

### 阶段 2：本地 K8s + OBS + 华为云 RDS ✅

用华为云 RDS PostgreSQL 替换 metadata-db Pod，提升元数据持久性。

- [x] 创建 RDS 覆盖配置 (`values-rds.yaml`)
- [x] 华为云创建 RDS 实例并初始化 Schema
- [x] 部署验证（集成测试 31 个用例全部通过）
- [x] 数据持久化验证（Pod 重启后数据不丢失）

📋 [验证报告](docs/verification/stage2-rds-metadata.md)

### 阶段 3：华为云 CCE 开发集群 ✅

全部组件部署到华为云 CCE（云容器引擎），OBS/RDS 改走 VPC 内网。

- [x] 镜像推送到华为云 SWR（容器镜像服务）
- [x] Helm 模板改造（imagePullSecrets、initImage、serviceType 参数化）
- [x] CCE 集群创建和 Helm 部署
- [x] NodePort 入口 + VPC 内网访问 OBS/RDS
- [x] 节点 containerd core ulimit 修复（compute_ctl 兼容）
- [x] 集成测试（31/31 通过，compute 启动 ~8s）

📋 [验证报告](docs/verification/stage3-cce-cluster.md)

```bash
# 快速体验：创建租户 → 建库 → 写入 10 条数据 → 查询 → 清理
KUBECONFIG=~/.kube/cce-lakeon-config ./deploy/cce/demo.sh
```

### 阶段 4：用户接入与连接体验 ✅

完善用户接入层和连接体验，为邀请外部用户测试做准备。

📋 [验证报告](docs/verification/stage4-user-access.md)

#### 用户接入层
- [x] Web 控制台（基于 Vue 3 + TinyVue，华为云风格）
  - 登录页（API Key）、总览仪表盘、数据库列表/详情（含操作历史）、分支管理、API Key 管理
  - 操作历史记录（suspend/resume 耗时追踪，为唤醒优化提供数据支撑）
  - Nginx 反向代理 + Docker 多阶段构建 + Helm 部署
- [x] 用户注册与 API Key 自助管理
  - 登录页注册标签，自助创建租户并获取 API Key
  - API Key 重新生成（旧 Key 立即失效）
- [x] 资源配额（每租户数据库数量、CPU / 存储上限）
  - 租户级配额字段（max_databases / max_storage_gb / max_compute_cu）
  - 数据库创建时自动检查配额，超限返回 403
  - Admin API（`/api/v1/admin/`）+ Admin Token 认证
- [ ] TLS 证书管理（ELB HTTPS 终止）→ 移至阶段 9

#### 连接体验
- [x] 用户文档（psql / JDBC / Python / Java / Go 连接示例 + FAQ）
- [x] 用量计量（基于 operation_logs 生命周期事件计算实际 compute 运行时长）

### 阶段 5：SRE 运维控制台 ✅

独立部署的管理控制台（`lakeon-admin`），供 SRE/管理员监控和管理 Lakeon 云服务。

📋 [设计文档](docs/plans/2026-03-04-sre-admin-design.md) | [验证报告](docs/verification/stage5-sre-admin.md)

#### 后端 Admin API
- [x] 总览仪表盘 API（租户/实例统计、24h 操作统计、成本预估、组件健康）
- [x] 全局数据库列表（跨租户，按状态/租户筛选）
- [x] 系统组件健康检查（pageserver / safekeeper / proxy / RDS 连通性）
- [x] 全局操作审计日志（跨租户筛选、分页）
- [x] 成本估算 API（基于资源单价配置 + 用量自行计算，按资源拆分 + 按租户分摊）
- [x] 华为云 CBC 实际账单 API（AK/SK 签名调用 BSS 账单接口）
- [x] 唤醒延迟统计（P50/P90/P99，从 operation_logs 聚合，等待 Pod Ready 后记录真实耗时）
- [x] 租户/数据库批量删除 API
- [x] 用量计量 API（全局/租户/数据库维度，compute CU·hours + 存储用量）
- [x] 租户禁用/启用（`disabled` 字段 + API + 登录拦截）
- [x] OBS 存储连通性检查（HEAD bucket 请求）
- [x] 日成本趋势 API（最近 30 天逐日成本）

#### 前端控制台
- [x] Admin Token 登录页
- [x] 总览仪表盘（指标卡片、组件状态灯、操作统计、成本明细）
- [x] 租户管理（列表、搜索、配额调整弹窗、批量删除）
- [x] 数据库实例监控（全局列表、状态/租户筛选、批量删除）
- [x] 操作审计日志（全局列表、租户/类型/状态筛选、分页）
- [x] 系统组件健康（组件名称 + 连通性状态、唤醒延迟 P50/P90/P99 秒级显示）
- [x] 成本监控（CBC 实际账单 + 预估成本双模式、每小时/每天/每月拆分、租户成本分摊）
- [x] 租户启用/禁用操作按钮
- [x] 成本趋势图（日折线图，Canvas 原生渲染）
- [x] 操作日志导出（CSV）
- [x] 数据库详情页（从 admin 角度查看单个实例）

#### 部署
- [x] Docker 多阶段构建（node:20-alpine → nginx:alpine）
- [x] Helm Chart 模板（Deployment + Service）
- [x] CCE values 配置 + SWR 镜像推送脚本
- [x] CCE 部署验证（ELB 绑定，admin:0.1.8 / api:0.1.11）
- [ ] NAT 网关配置（Pod 出公网，CBC 账单 API 依赖）→ 移至阶段 9

### 阶段 6：数据库对象管理（Web Database Manager）

在用户 Web 控制台中集成类似 pgAdmin/DBeaver 的数据库对象浏览与管理功能，让用户无需安装客户端即可管理数据库内部对象。

#### 后端 API（`/api/v1/databases/{id}/objects/...`）

连接方式：通过 `cloud_admin` 直连 compute pod 内网（K8s Service DNS），无需用户提供密码。

- [ ] Database 列表与管理（`\l`）— 查看、创建、删除数据库
- [ ] Schema 列表与管理 — 查看、创建、删除 schema
- [ ] Table 浏览与管理
  - [ ] 列表（表名、行数估算、磁盘大小）
  - [ ] 表结构查看（列名、类型、默认值、约束、注释）
  - [ ] 创建表（列定义、主键、约束）
  - [ ] 删除表（DROP TABLE）
  - [ ] 修改表结构（ALTER TABLE：增删改列、增删约束）
- [ ] 数据浏览与编辑
  - [ ] 分页查询表数据（SELECT * 带分页/排序/过滤）
  - [ ] 行级编辑（UPDATE 单行）
  - [ ] 行级删除（DELETE 单行）
  - [ ] 插入新行（INSERT）
- [ ] SQL 查询编辑器
  - [ ] 自由执行 SQL（SELECT / INSERT / UPDATE / DELETE / DDL）
  - [ ] 查询结果表格展示（带列类型）
  - [ ] 查询结果导出 CSV
  - [ ] 查询历史记录
- [ ] Index 管理 — 查看、创建、删除索引
- [ ] View 管理 — 查看定义、创建、删除视图
- [ ] Sequence 管理 — 查看、重置序列

#### 前端控制台（`lakeon-console`）

- [ ] 左侧对象树（Database → Schema → Tables/Views/Indexes/Sequences）
  - [ ] 树形展开，懒加载子节点
  - [ ] 右键菜单（创建、删除、刷新）
- [ ] 表结构面板（Columns / Indexes / Constraints 分标签页）
- [ ] 数据网格（可编辑表格，行内编辑 + 新增行 + 删除行）
  - [ ] 分页、排序、列过滤
  - [ ] 修改后高亮标记，统一提交/回滚
- [ ] SQL 编辑器面板
  - [ ] 语法高亮 + 自动补全（表名、列名）
  - [ ] 执行按钮 + 快捷键（Ctrl+Enter）
  - [ ] 多标签页
  - [ ] 查询耗时和影响行数显示
- [ ] DDL 可视化建表向导（表单式创建表、添加列和约束）

#### 安全与限制
- [ ] 查询超时限制（防止超长查询阻塞 compute）
- [ ] 结果集大小限制（防止 OOM）
- [ ] 危险操作二次确认（DROP TABLE / DROP DATABASE / TRUNCATE）
- [ ] 操作审计日志（记录用户的 DDL/DML 操作）

### 阶段 7：CCE + CCI 混合架构验证

验证混合部署方案：有状态组件运行在 CCE，compute 节点弹性调度到 CCI（云容器实例），实现 serverless 计算层。

📋 [研究报告](docs/verification/stage5-cci-research.md)

#### 7a：CCI compute 兼容性验证（关键路径）
- [ ] 购买 VPC 终端节点（SWR），解决 CCI 镜像拉取网络不通问题
- [ ] 在 CCI 上部署测试 Pod，验证 `ulimit -c` 是否为 unlimited
- [ ] 部署 compute-node-v17 镜像，验证 `compute_ctl` 的 `setrlimit(CORE, INFINITY)` 是否通过
- [ ] 若 CCI 默认 ulimit 不满足，评估替代方案（patch compute_ctl / CCI 配置项）

#### 7b：混合调度集成
- [ ] 验证 CCI Pod 到 VPC 内网的网络连通性（pageserver / safekeeper / RDS / OBS）
- [ ] 配置 lakeon-api 将 compute Pod 创建到 CCI namespace
- [ ] 对比 Pod 启动速度（CCI vs 普通 CCE）

#### 7c：评估与结论
- [ ] 运行完整集成测试（compute 在 CCI，其余在 CCE）
- [ ] 输出评估报告：混合架构可行性、启动延迟、成本对比
- [ ] 确定后续阶段的部署架构选型

### 阶段 8：华为云可观测性与运维

对接华为云原生运维服务，替代自建 Prometheus / Grafana 方案。基于阶段 7 结论选择部署架构。

#### 日志（LTS 云日志服务）
- [ ] 添加 logback-spring.xml，输出 JSON 结构化日志
- [ ] CCE 集群安装 ICAgent，采集容器 stdout 日志到 LTS
- [ ] LTS 配置日志流、索引和查询模板

#### 指标监控（AOM 应用运维管理）
- [ ] Pod 添加 Prometheus annotations（scrape/port/path）
- [ ] AOM 自动采集 Micrometer 指标（lakeon-api）和 Neon 组件指标（pageserver / safekeeper / proxy）
- [ ] 迁移现有 Grafana Dashboard 到 AOM 仪表盘

#### 告警通知（AOM + SMN 消息通知服务）
- [ ] 迁移现有 5 条 Prometheus 告警规则到 AOM 告警策略
- [ ] 对接 SMN 实现短信 / 邮件 / 企业微信告警通知

#### 链路追踪（APM 应用性能管理）
- [ ] 引入 OpenTelemetry Spring Boot Starter
- [ ] 配置 OTLP exporter 对接华为 APM endpoint
- [ ] 覆盖关键链路：proxy → compute → pageserver 调用链

#### 基础设施监控（CES 云监控服务）
- [ ] CCE 节点 CPU / 内存 / 磁盘监控（自动接入）
- [ ] OBS 存储容量和请求量监控
- [ ] RDS 连接数和慢查询监控

### 阶段 9：计算节点弹性唤醒优化

从 ~10s 唤醒延迟优化到亚秒级。依赖阶段 8 的可观测性基础来量化优化效果。

📋 [技术方案](docs/compute-wakeup-optimization.md)

#### 9a：Pod 保留 + 进程冻结（目标 500ms-1s）
- [ ] 验证 compute_ctl HTTP API（停止/重启 PG 进程）
- [ ] suspend 改为停进程而非删 Pod
- [ ] resume 检测 Pod 存在性，原地重启 PG
- [ ] 分层超时回收（短期保留 → 长期销毁）
- [ ] Readiness Probe 调优（initialDelay 5s→1s）

#### 9b：Warm Pool 预热池（目标 200-500ms）
- [ ] 验证 compute_ctl 动态绑定 tenant/timeline API
- [ ] 实现 WarmPoolManager（池创建/分配/补充）
- [ ] Cold 路径唤醒改为从预热池分配
- [ ] 池大小自适应策略 + 监控指标

#### 9c：Proxy 连接缓冲
- [ ] Proxy 唤醒逻辑改为异步，连接立即成功
- [ ] 首条 SQL 等待 compute 就绪后透明转发

### 阶段 10：产品化加固

面向正式上线的生产环境加固，完成安全、高可用和运维闭环。

#### 高可用与容灾
- [ ] 多副本 pageserver / safekeeper 部署
- [ ] 备份和恢复策略（OBS 快照 + RDS 自动备份）
- [ ] 故障转移验证（节点宕机、组件重启后自动恢复）

#### 安全与认证
- [ ] 华为云 IAM 集成（单点登录，替代 API Key 登录）
- [ ] TLS 证书管理（ELB HTTPS 终止，从阶段 4 移入）
- [ ] 数据库连接 TLS 加密（端到端）
- [ ] API 限流与错误重试策略
- [ ] 安全审计日志

#### 网络与运营
- [ ] NAT 网关配置（Pod 出公网，CBC 账单 API 依赖，从阶段 5 移入）
- [ ] 连接池（PgBouncer / Supavisor）
- [ ] 用量计费系统（基于计量数据）
- [ ] 租户 SLA 保障（自动扩缩容策略）
- [ ] 运维 Runbook 和应急预案

## License

Private — All rights reserved.
