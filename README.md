# Lakeon

基于 [Neon](https://github.com/neondatabase/neon) 存储引擎的自托管 Serverless PostgreSQL 平台。

Lakeon 将 Neon 的存算分离架构封装为一套可私有部署的 Kubernetes 原生方案，支持按需创建数据库、自动挂起/唤醒计算节点、多租户隔离和对象存储持久化。

详细架构设计请参考 [架构文档](doc/architecture.md)。

## 项目结构

```
lakeon/
├── lakeon-api/          # 控制面 API (Spring Boot)
│   ├── src/main/java/   # Java 源码
│   ├── src/test/        # 单元测试 + 集成测试
│   └── Dockerfile
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
└── rpiv/                # 需求、设计文档
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

## 部署路线图

从本地开发到华为云生产环境的渐进式部署路径：

### 阶段 0：本地 Docker Desktop ✅

单节点 Kubernetes，MinIO 作为对象存储，所有组件跑在本地。

- [x] Helm Chart 部署
- [x] 多租户 API
- [x] 计算节点按需创建 / 挂起 / 唤醒
- [x] 集成测试（31 个用例全部通过）

📋 [验证报告](doc/verification/stage0-local-k8s.md)

### 阶段 1：本地 K8s + 华为云 OBS ✅

用华为云 OBS 替换 MinIO，验证 Neon 存储层在真实对象存储上的兼容性。

- [x] 配置 OBS endpoint / AK / SK（通过 `--set` 传递，不入库）
- [x] 解决 Neon path-style 寻址与 OBS virtual-host-style 不兼容问题
- [x] 处理 OBS 网络延迟导致的 tenant 状态竞争
- [x] 集成测试（31 个用例全部通过，数据持久化到 OBS）

📋 [验证报告](doc/verification/stage1-obs-storage.md)

### 阶段 2：本地 K8s + OBS + 华为云 RDS ✅

用华为云 RDS PostgreSQL 替换 metadata-db Pod，提升元数据持久性。

- [x] 创建 RDS 覆盖配置 (`values-rds.yaml`)
- [x] 华为云创建 RDS 实例并初始化 Schema
- [x] 部署验证（集成测试 31 个用例全部通过）
- [x] 数据持久化验证（Pod 重启后数据不丢失）

📋 [验证报告](doc/verification/stage2-rds-metadata.md)

### 阶段 3：华为云 CCE 开发集群

全部组件部署到华为云 CCE（云容器引擎），OBS/RDS 改走 VPC 内网。

- [x] 镜像推送到华为云 SWR（容器镜像服务）
- [x] CCE 集群创建和 Helm 部署
- [x] ELB 入口 + VPC 内网访问 OBS/RDS
- [x] 集成测试（31 个用例，CCE 环境）

📋 [验证报告](doc/verification/stage3-cce-cluster.md)

### 阶段 4：生产加固与用户接入

生产环境加固，建立用户接入层，为邀请外部用户测试做准备。

#### 基础设施加固
- [ ] TLS 证书管理（ELB HTTPS 终止）
- [ ] 高可用配置（多副本 pageserver / safekeeper）
- [ ] 备份和恢复策略

#### 用户接入层
- [ ] Web 控制台（基于 OpenTiny TinyPro 云服务控制台模板，华为云风格）
  - 登录页（API Key）、总览仪表盘、数据库列表/详情、分支管理、API Key 管理
- [ ] 用户注册与 API Key 自助管理
- [ ] 资源配额（每租户数据库数量、CPU / 存储上限）
- [ ] API 限流与错误重试策略
- [ ] 华为云 IAM 集成（单点登录，替代 API Key 登录）

#### 连接体验
- [ ] 连接池（PgBouncer / Supavisor）
- [ ] 用户文档（psql / JDBC / Python 等连接示例）
- [ ] 用量计量（为后续计费做准备）

### 阶段 5：CCE Autopilot 兼容性验证

在 Autopilot 测试集群上验证全组件部署，为后续阶段的平台选型提供依据。

- [ ] 创建 CCE Autopilot 测试集群
- [ ] 验证 pageserver / safekeeper / proxy 部署兼容性（volume、网络、特权限制）
- [ ] 验证 compute Pod 动态创建和镜像快照加速
- [ ] 对比 Pod 启动速度（Autopilot vs 普通 CCE）
- [ ] 确认 ICAgent / AOM 等可观测性组件在 Autopilot 上的安装方式
- [ ] 输出评估结论：后续阶段使用 Autopilot 还是普通 CCE

### 阶段 6：华为云可观测性与运维

对接华为云原生运维服务，替代自建 Prometheus / Grafana 方案。基于阶段 5 结论选择部署平台。

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

### 阶段 7：计算节点弹性唤醒优化

从 ~10s 唤醒延迟优化到亚秒级。依赖阶段 6 的可观测性基础来量化优化效果。

📋 [技术方案](doc/compute-wakeup-optimization.md)

#### 7a：Pod 保留 + 进程冻结（目标 500ms-1s）
- [ ] 验证 compute_ctl HTTP API（停止/重启 PG 进程）
- [ ] suspend 改为停进程而非删 Pod
- [ ] resume 检测 Pod 存在性，原地重启 PG
- [ ] 分层超时回收（短期保留 → 长期销毁）
- [ ] Readiness Probe 调优（initialDelay 5s→1s）

#### 7b：Warm Pool 预热池（目标 200-500ms）
- [ ] 验证 compute_ctl 动态绑定 tenant/timeline API
- [ ] 实现 WarmPoolManager（池创建/分配/补充）
- [ ] Cold 路径唤醒改为从预热池分配
- [ ] 池大小自适应策略 + 监控指标

#### 7c：Proxy 连接缓冲
- [ ] Proxy 唤醒逻辑改为异步，连接立即成功
- [ ] 首条 SQL 等待 compute 就绪后透明转发

## License

Private — All rights reserved.
