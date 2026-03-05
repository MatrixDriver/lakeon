# Stage 3 验证报告：华为云 CCE 开发集群

## 概述

将全部 Lakeon 组件从本地 Docker Desktop K8s 迁移到华为云 CCE（云容器引擎），OBS 和 RDS 改走 VPC 内网访问。

**验证结果：31/31 E2E 测试全部通过。**

## 环境

| 组件 | 配置 |
|------|------|
| CCE 集群 | CCE Standard, cn-north-4, 名称 lakeon-k8s-cluster |
| 节点 | 1x 8vCPU/16GB (主力) + 2x 4vCPU/8GB |
| 镜像仓库 | 华为云 SWR (swr.cn-north-4.myhuaweicloud.com/lakeon) |
| 对象存储 | 华为云 OBS bucket `lakeon-storage` (VPC 内网) |
| 元数据库 | 华为云 RDS PostgreSQL 192.168.0.210:5432 (VPC 内网) |
| API 入口 | NodePort (端口 31339) |

## 前置操作

### 1. SWR 镜像推送

```bash
# 登录 SWR
docker login -u cn-north-4@<AK> -p <login-key> swr.cn-north-4.myhuaweicloud.com

# 推送镜像
SWR_ORG=lakeon ./deploy/cce/push-images.sh
```

推送镜像清单:
- `neon:latest` — Neon 存储引擎 (pageserver, safekeeper, storage-broker, proxy)
- `compute-node-v17:latest` — PostgreSQL 计算节点
- `busybox:1.36` — init container
- `lakeon-api:0.1.0` — 控制面 API

### 2. CCE 集群准备

- 集群与 RDS 在同一 VPC
- 安全组放通 5432 (RDS) 入方向和 443 (OBS) 出方向
- 下载 kubeconfig（X509 证书方式）并切换 context
- **修复节点 containerd core ulimit**（每个运行 compute pod 的节点）:
  ```bash
  # 通过 privileged DaemonSet 或 SSH 执行
  mkdir -p /etc/systemd/system/containerd.service.d
  printf '[Service]\nLimitCORE=infinity\n' > /etc/systemd/system/containerd.service.d/ulimit-core.conf
  systemctl daemon-reload && systemctl restart containerd
  ```

### 3. 创建 SWR 镜像拉取凭证

```bash
# lakeon namespace
kubectl create secret docker-registry swr-secret \
  --docker-server=swr.cn-north-4.myhuaweicloud.com \
  --docker-username='cn-north-4@<AK>' \
  --docker-password='<login-key>' \
  -n lakeon

# lakeon-compute namespace（compute pod 使用）
kubectl create secret docker-registry swr-secret \
  --docker-server=swr.cn-north-4.myhuaweicloud.com \
  --docker-username='cn-north-4@<AK>' \
  --docker-password='<login-key>' \
  -n lakeon-compute

# Patch default SA 让 compute pod 自动获取凭证
kubectl patch sa default -n lakeon-compute \
  -p '{"imagePullSecrets":[{"name":"swr-secret"}]}'
```

### 4. 部署

```bash
# 创建 namespace
kubectl create namespace lakeon --dry-run=client -o yaml | kubectl apply -f -
kubectl label namespace lakeon app.kubernetes.io/managed-by=Helm --overwrite
kubectl annotate namespace lakeon meta.helm.sh/release-name=lakeon \
  meta.helm.sh/release-namespace=lakeon --overwrite
kubectl create namespace lakeon-compute --dry-run=client -o yaml | kubectl apply -f -

# 部署
helm upgrade --install lakeon deploy/helm/lakeon \
  -f deploy/cce/values-cce.yaml \
  --set obs.accessKey=$OBS_AK --set obs.secretKey=$OBS_SK \
  --set metadataDb.host=$RDS_PRIVATE_IP --set metadataDb.password=$RDS_PASSWORD \
  -n lakeon --timeout 5m --no-hooks
```

## 验证项

### Helm 模板改动

| 改动 | 说明 | 状态 |
|------|------|------|
| `values.yaml` 新增 `initImage` | init container 镜像可配置 | PASS |
| `values.yaml` 新增 `api.serviceType` | API Service 类型可配置 | PASS |
| `values.yaml` 新增 `global.imagePullSecrets` | SWR 私有仓库认证 | PASS |
| `service-api.yaml` 使用 `serviceType` | NodePort/LoadBalancer 支持 | PASS |
| 所有 Deployment/StatefulSet 模板化 imagePullSecrets | SWR 镜像拉取 | PASS |
| `deployment-pageserver.yaml` 模板化 busybox | SWR 镜像支持 | PASS |
| `statefulset-safekeeper.yaml` 模板化 busybox | SWR 镜像支持 | PASS |
| `configmap-api.yaml` 新增 `LAKEON_IMAGE_PULL_SECRETS` | compute pod imagePullSecrets 配置 | PASS |

### 集群部署

| 验证项 | 状态 |
|--------|------|
| SWR 镜像全部推送成功 | PASS |
| 所有 Pod 正常运行 (5/5) | PASS |
| API 健康检查通过 | PASS |
| OBS VPC 内网连通 | PASS |
| RDS VPC 内网连通 (HikariPool) | PASS |
| Compute pod 镜像拉取成功 | PASS |

### 集成测试 (2026-03-04)

```
  Total:  31
  Passed: 31
  Failed: 0
  ALL TESTS PASSED
```

| 测试套件 | 说明 | 状态 |
|----------|------|------|
| Suite 1: 单租户 (IT-E2E-001~008) | 创建/查询/挂起/恢复/删除 + SQL + 持久化 | PASS |
| Suite 2: 多租户 (IT-E2E-010~017) | 隔离、列表过滤、数据隔离、认证 | PASS |

### 性能数据

| 指标 | CCE | 本地 Docker Desktop |
|------|-----|-------------------|
| Compute 冷启动 | 8s | 10-11s |
| Compute Resume | 8s | 10-11s |
| 多租户并发启动 | 各 8s | - |

## 遇到的问题及解决方案

| 问题 | 根因 | 解决方案 |
|------|------|---------|
| SWR 镜像拉取 401 | SWR 私有组织需要认证 | 创建 docker-registry secret + Helm imagePullSecrets |
| compute pod ErrImagePull | lakeon-compute namespace 无 imagePullSecret | patch default ServiceAccount |
| compute_ctl "Operation not permitted" | `setrlimit(CORE, INFINITY)` 超过 containerd 硬限制 (5GB) | 修改节点 containerd systemd 配置 LimitCORE=infinity |
| OBS NoSuchBucket | bucket 名称不匹配 (lakeon-neon vs lakeon-storage) | 修正 values-cce.yaml 中 bucket 名称 |
| 节点资源不足 | 2x4C/8G 被系统组件占用 | 新增 8C/16G 节点 |
| RDS 连接超时 | 安全组未放通 CCE 子网到 RDS:5432 | 添加安全组入方向规则 |
| LoadBalancer PENDING | 未绑定 ELB | 改用 NodePort 测试 |

## 架构

```
Stage 3 (CCE):
  用户 ──→ NodePort:31339 ──→ lakeon-api
  lakeon-api ──→ pageserver/safekeeper/proxy/storage-broker
  lakeon-api ──→ K8s API ──→ compute pod (lakeon-compute ns)
  pageserver/safekeeper ──→ VPC 内网 ──→ OBS (lakeon-storage)
  lakeon-api ──→ VPC 内网 ──→ RDS PostgreSQL
  所有镜像 ←── SWR (swr.cn-north-4.myhuaweicloud.com/lakeon)
```

## 文件变更

| 文件 | 操作 |
|------|------|
| `deploy/cce/push-images.sh` | 新建 — SWR 镜像推送脚本 |
| `deploy/cce/values-cce.yaml` | 新建 — CCE Helm values |
| `deploy/cce/integration-test.sh` | 新建 — CCE 集成测试 |
| `deploy/helm/lakeon/values.yaml` | 修改 — 新增 initImage, api.serviceType, imagePullSecrets |
| `templates/service-api.yaml` | 修改 — 新增 type 字段 |
| `templates/deployment-pageserver.yaml` | 修改 — 模板化 init 镜像 + imagePullSecrets |
| `templates/statefulset-safekeeper.yaml` | 修改 — 模板化 init 镜像 + imagePullSecrets |
| `templates/deployment-api.yaml` | 修改 — imagePullSecrets |
| `templates/deployment-proxy.yaml` | 修改 — imagePullSecrets |
| `templates/deployment-storage-broker.yaml` | 修改 — imagePullSecrets |
| `templates/configmap-api.yaml` | 修改 — LAKEON_IMAGE_PULL_SECRETS |
| `lakeon-api/.../LakeonProperties.java` | 修改 — K8sConfig 新增 imagePullSecrets |
| `lakeon-api/.../ComputePodManager.java` | 修改 — compute pod 注入 imagePullSecrets |
| `lakeon-api/.../application.yml` | 修改 — 新增 image-pull-secrets 配置 |
