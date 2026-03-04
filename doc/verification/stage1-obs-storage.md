# Stage 1 验证报告：本地 K8s + 华为云 OBS

**日期**: 2026-03-04
**环境**: Docker Desktop Kubernetes (macOS, 7.75 GB RAM)
**存储后端**: 华为云 OBS (cn-north-4, 北京四)
**元数据库**: PostgreSQL 15 (集群内 Pod)
**OBS Bucket**: lakeon-storage

## 变更内容

### 存储切换: MinIO → 华为云 OBS

| 配置项 | Stage 0 (MinIO) | Stage 1 (OBS) |
|--------|-----------------|---------------|
| endpoint | http://minio:9000 | https://obs.cn-north-4.myhuaweicloud.com |
| bucket | lakeon-neon | lakeon-storage |
| region | us-east-1 | cn-north-4 |
| 寻址方式 | path-style | virtual-host-style |
| 网络 | 集群内 | 公网 |

### 关键技术决策

**问题 1: Neon 强制 path-style 寻址**

Neon 源码在检测到自定义 endpoint 时硬编码 `force_path_style(true)`，而华为云 OBS 只支持 virtual-host-style。

**解决方案**: 引入 `usePathStyle` Helm 配置项:
- `usePathStyle=true` (MinIO): 在 pageserver.toml 和 safekeeper 参数中设置 endpoint
- `usePathStyle=false` (OBS): 不设 endpoint（避免触发 force_path_style），通过 `AWS_ENDPOINT_URL` 环境变量传递

**问题 2: 租户创建后状态竞争**

OBS 网络延迟 (~5s) 导致 tenant 在 pageserver 上仍处于 "Attaching" 状态时就尝试创建 timeline，返回 HTTP 503。

**解决方案**: 在 `DatabaseService.create()` 中增加 `waitForTenantActive()` 轮询，最多等待 30s。

**问题 3: Timeline 创建超时**

多租户并发场景下 timeline 创建耗时超过 30s。

**解决方案**: `NeonApiClient.REQUEST_TIMEOUT` 从 30s 增加到 60s。

### Helm Chart 变更

| 文件 | 变更 |
|------|------|
| values.yaml | 新增 `obs.usePathStyle` 字段 |
| configmap-pageserver.yaml | 条件化 endpoint 配置 |
| deployment-pageserver.yaml | 条件化 `AWS_ENDPOINT_URL` 环境变量 |
| statefulset-safekeeper.yaml | 条件化 endpoint 和 `AWS_ENDPOINT_URL` |
| secret-obs.yaml | 条件化创建（accessKey 为空时跳过） |

### API 代码变更

| 文件 | 变更 |
|------|------|
| NeonApiClient.java | 新增 `waitForTenantActive()` 方法；超时从 30s → 60s |
| DatabaseService.java | 在 createTenant → createTimeline 之间插入等待逻辑 |

## 部署方式

```bash
helm upgrade --install lakeon deploy/helm/lakeon \
  -f deploy/local/values-local.yaml \
  -f deploy/local/values-obs.yaml \
  --set obs.accessKey=$OBS_AK --set obs.secretKey=$OBS_SK \
  -n lakeon --timeout 5m --no-hooks
```

AK/SK 通过 `--set` 传递，不写入版本控制。

## 测试结果

**集成测试**: 31/31 通过

### Suite 1: 单租户场景 (18 tests) — 全部通过

关键验证点:
- 数据库创建成功，计算节点 11s 内就绪
- SQL 读写正常 (PostgreSQL 17.5)
- 挂起/唤醒后数据持久化（OBS 上的数据跨计算节点生命周期保留）
- 删除后资源清理正常

### Suite 2: 多租户场景 (13 tests) — 全部通过

关键验证点:
- 两个租户各自创建数据库成功（含 waitForTenantActive 等待）
- 跨租户访问隔离 (404)
- 跨租户列表隔离
- 数据级隔离（租户 A 看不到 B 的表）
- 跨租户删除保护

## 性能数据

| 指标 | Stage 0 (MinIO) | Stage 1 (OBS) | 差异 |
|------|-----------------|---------------|------|
| 计算节点冷启动 | ~11s | ~11s | 无明显差异 |
| 挂起后唤醒 | ~11s | ~11s | 无明显差异 |
| 数据库创建 (含等待 tenant Active) | < 2s | ~8-12s | OBS 延迟影响 |

## 已知问题

1. 数据库状态始终显示 `CREATING`（同 Stage 0，未修复）
2. 多租户并发创建时 timeline 创建耗时较长，依赖增大后的 60s 超时

## 结论

Stage 1 验证通过。Neon 存储层在华为云 OBS 上正常工作，所有 31 个 E2E 测试通过。数据在 OBS 上正确持久化，计算节点启动性能与本地 MinIO 基本一致。主要额外延迟来自 tenant 初始化阶段的 OBS 网络开销，已通过轮询等待机制解决。
