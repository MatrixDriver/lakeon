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

## 路线图

从本地开发到华为云生产环境的渐进式部署路径，详见 [产品路线图](docs/ROADMAP.md)。

## License

Private — All rights reserved.
