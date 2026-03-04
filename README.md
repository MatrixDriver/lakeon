# LakeOn

基于 [Neon](https://github.com/neondatabase/neon) 存储引擎的自托管 Serverless PostgreSQL 平台。

LakeOn 将 Neon 的存算分离架构封装为一套可私有部署的 Kubernetes 原生方案，支持按需创建数据库、自动挂起/唤醒计算节点、多租户隔离和对象存储持久化。

## 架构概览

```
┌─────────────────────────────────────────────────────┐
│                   LakeOn Platform                   │
│                                                     │
│  ┌──────────┐    ┌────────────┐    ┌─────────────┐  │
│  │ lakeon-  │───▶│ pageserver │───▶│ Object      │  │
│  │ api      │    │            │    │ Storage     │  │
│  │ (控制面) │    ├────────────┤    │ (MinIO/OBS) │  │
│  │          │───▶│ safekeeper │    └─────────────┘  │
│  └────┬─────┘    └────────────┘                     │
│       │          ┌────────────┐                     │
│       ├─────────▶│ storage-   │                     │
│       │          │ broker     │                     │
│       ▼          └────────────┘                     │
│  ┌──────────┐                                       │
│  │ compute  │  ← 按需创建的 PostgreSQL 计算节点      │
│  │ pods     │    (自动挂起 / 唤醒)                   │
│  └──────────┘                                       │
│  ┌──────────┐    ┌────────────┐                     │
│  │ proxy    │    │ metadata-  │                     │
│  │ (连接路由)│    │ db (PG)    │                     │
│  └──────────┘    └────────────┘                     │
└─────────────────────────────────────────────────────┘
```

## 核心特性

- **存算分离** — 基于 Neon pageserver + safekeeper，WAL 流式写入，page 按需加载
- **Serverless 计算** — 数据库闲置自动挂起，连接时自动唤醒，计算节点约 10 秒启动
- **多租户隔离** — API Key 认证，租户间数据库和数据完全隔离
- **对象存储持久化** — 支持 S3 兼容存储（MinIO / 华为云 OBS / AWS S3）
- **Kubernetes 原生** — Helm Chart 一键部署，RBAC 权限管理

## 技术栈

| 组件 | 技术 |
|------|------|
| 控制面 API | Spring Boot 3.3, Java 17 |
| 存储引擎 | Neon (pageserver, safekeeper, storage-broker) |
| 计算节点 | Neon compute-node (PostgreSQL 17) |
| 连接代理 | Neon proxy |
| 元数据库 | PostgreSQL 16 |
| 对象存储 | MinIO (本地) / 华为云 OBS (云端) |
| 容器编排 | Kubernetes + Helm |
| CLI | Python (Typer) |

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
│   └── local/           # 本地部署脚本和配置
│       ├── values-local.yaml
│       ├── setup.sh
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

### 阶段 1：本地 K8s + 华为云 OBS

用华为云 OBS 替换 MinIO，验证 Neon 存储层在真实对象存储上的兼容性。

- [ ] 配置 OBS endpoint / AK / SK
- [ ] 更新 pageserver remote_storage 配置
- [ ] 验证数据持久化和跨重启恢复

### 阶段 2：本地 K8s + OBS + 华为云 RDS

用华为云 RDS PostgreSQL 替换 metadata-db Pod。

- [ ] 迁移元数据库到 RDS
- [ ] 更新连接配置和 Secret 管理
- [ ] 验证 API 和数据一致性

### 阶段 3：华为云 CCE 开发集群

全部组件部署到华为云 CCE（云容器引擎）。

- [ ] 镜像推送到华为云 SWR（容器镜像服务）
- [ ] CCE 集群创建和配置
- [ ] 网络策略和安全组配置
- [ ] 端到端功能验证

### 阶段 4：CCE 生产级部署

生产环境加固和运维就绪。

- [ ] ELB + Ingress 入口配置
- [ ] TLS 证书管理
- [ ] 监控和告警（Prometheus / Grafana）
- [ ] 日志收集
- [ ] 高可用配置（多副本 pageserver / safekeeper）
- [ ] 备份和恢复策略

## License

Private — All rights reserved.
