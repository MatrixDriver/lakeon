# Stage 2 验证报告：本地 K8s + OBS + 华为云 RDS

**日期**: 2026-03-04
**环境**: Docker Desktop Kubernetes (macOS, 7.75 GB RAM)
**存储后端**: 华为云 OBS (cn-north-4, 北京四)
**元数据库**: 华为云 RDS PostgreSQL 17.7 (cn-north-4, 北京四)
**RDS 实例名**: lakeon-control-metadata

## 变更内容

### 元数据库切换: 集群内 Pod → 华为云 RDS

| 配置项 | Stage 1 (Pod) | Stage 2 (RDS) |
|--------|---------------|---------------|
| host | metadata-db (ClusterIP) | RDS 公网 EIP |
| 引擎 | PostgreSQL 15-alpine | PostgreSQL 17.7 |
| 存储 | emptyDir (随 Pod 重启丢失) | RDS 云盘 (持久化) |
| 高可用 | 无 | RDS 单机 |
| Schema 初始化 | docker-entrypoint-initdb.d | 手动执行 psql |

### Helm Chart 变更

无模板变更。`metadataDb.enabled` 条件渲染已在 Stage 0 实现。

| 文件 | 变更 |
|------|------|
| deploy/local/values-rds.yaml | 新建：`metadataDb.enabled: false`，禁用集群内 Pod |

### 部署方式

```bash
# 初始化 RDS schema（仅首次）
psql "postgresql://lakeon:<password>@<host>:5432/lakeon" \
  -f lakeon-api/src/main/resources/db/migration/V1__init_schema.sql

# Helm 部署
helm upgrade --install lakeon deploy/helm/lakeon \
  -f deploy/local/values-local.yaml \
  -f deploy/local/values-obs.yaml \
  -f deploy/local/values-rds.yaml \
  --set obs.accessKey=$OBS_AK --set obs.secretKey=$OBS_SK \
  --set metadataDb.host=$RDS_HOST \
  --set metadataDb.password=$RDS_PASSWORD \
  -n lakeon --timeout 5m --no-hooks
```

RDS host 和密码通过 `--set` 传递，不写入版本控制。

## 验证结果

### 1. 部署验证

- [x] `metadataDb.enabled=false` 生效：集群内无 metadata-db Pod
- [x] lakeon-api Pod 启动正常，HikariPool 连接 RDS 成功
- [x] `LAKEON_DB_DSN` 指向 RDS 地址：`jdbc:postgresql://<RDS_EIP>:5432/lakeon`

### 2. 集成测试

**结果**: 31/31 通过

#### Suite 1: 单租户场景 (18 tests) — 全部通过

关键验证点:
- 数据库创建成功，计算节点 10s 内就绪
- SQL 读写正常 (PostgreSQL 17.5)
- 挂起/唤醒后数据持久化
- 删除后资源清理正常

#### Suite 2: 多租户场景 (13 tests) — 全部通过

关键验证点:
- 两个租户各自创建数据库成功
- 跨租户访问隔离 (404)
- 数据级隔离
- 认证检查 (401)

### 3. 性能数据

| 指标 | Stage 1 (Pod) | Stage 2 (RDS) | 差异 |
|------|---------------|---------------|------|
| API 启动时间 | ~12s | ~16s | RDS 网络延迟 (+4s) |
| 数据库创建 | ~8-12s | ~8-12s | 无明显差异 |
| 计算节点冷启动 | ~11s | ~10s | 无明显差异 |
| 挂起后唤醒 | ~11s | ~10s | 无明显差异 |

### 4. 数据持久化验证

- [x] lakeon-api Pod 重启后，租户和数据库元数据保留
- [x] 与 Stage 0/1（emptyDir Pod 重启丢数据）相比，RDS 彻底解决了元数据持久性问题

## 已知问题

1. 数据库状态始终显示 `CREATING`（同 Stage 0/1，未修复）
2. API 启动时间比集群内 Pod 略长，因 RDS 公网网络延迟

## 结论

Stage 2 验证通过。元数据库成功迁移到华为云 RDS PostgreSQL 17.7，所有 31 个 E2E 测试通过。API 通过公网 EIP 连接 RDS，性能与集群内 Pod 基本一致（仅 API 启动阶段多 ~4s 连接延迟）。元数据持久性得到根本性解决，不再依赖 Pod 生命周期。
