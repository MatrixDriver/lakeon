# Stage 0 验证报告：本地 Docker Desktop K8s

**日期**: 2026-03-03
**环境**: Docker Desktop Kubernetes (macOS, 7.75 GB RAM)
**存储后端**: MinIO (集群内)
**元数据库**: PostgreSQL 15 (集群内 Pod)

## 部署组件

| 组件 | 镜像 | 副本 | 状态 |
|------|------|------|------|
| pageserver | ghcr.io/neondatabase/neon:latest | 1 | Running |
| safekeeper | ghcr.io/neondatabase/neon:latest | 1 | Running |
| storage-broker | ghcr.io/neondatabase/neon:latest | 1 | Running |
| proxy | ghcr.io/neondatabase/neon:latest | 1 | Running |
| lakeon-api | lakeon/lakeon-api:local | 1 | Running |
| metadata-db | postgres:15-alpine | 1 | Running |
| minio | minio/minio:latest | 1 | Running |

## 测试结果

**集成测试**: 31/31 通过

### Suite 1: 单租户场景 (18 tests)

| 测试 | 说明 | 结果 |
|------|------|------|
| IT-E2E-001 | 创建租户 | PASS |
| IT-E2E-002a | 创建数据库 | PASS |
| IT-E2E-002b | 创建时返回密码 | PASS |
| IT-E2E-002c | 连接 URI 格式正确 | PASS |
| IT-E2E-002d | 计算节点就绪 | PASS |
| IT-E2E-003a | PostgreSQL 版本 (17.5) | PASS |
| IT-E2E-003b | 建表成功 | PASS |
| IT-E2E-003c | 插入和查询数据 | PASS |
| IT-E2E-004a | 获取数据库详情 | PASS |
| IT-E2E-004b | GET 不暴露密码 | PASS |
| IT-E2E-005 | 列出数据库 | PASS |
| IT-E2E-006a | 挂起后删除计算 Pod | PASS |
| IT-E2E-006b | 挂起后状态变更 | PASS |
| IT-E2E-007a | 唤醒后计算 Pod 就绪 | PASS |
| IT-E2E-007b | 挂起/唤醒后数据持久化 | PASS |
| IT-E2E-008a | 删除后清理计算 Pod | PASS |
| IT-E2E-008b | 删除后返回 404 | PASS |

### Suite 2: 多租户场景 (13 tests)

| 测试 | 说明 | 结果 |
|------|------|------|
| IT-E2E-010 | 创建两个租户 | PASS |
| IT-E2E-011a | 租户 A 创建数据库 | PASS |
| IT-E2E-011b | 租户 B 创建数据库 | PASS |
| IT-E2E-012a | 租户 A 无法访问 B 的数据库 | PASS |
| IT-E2E-012b | 租户 B 无法访问 A 的数据库 | PASS |
| IT-E2E-013a | 租户 A 列表只看到自己的 | PASS |
| IT-E2E-013b | 租户 B 列表只看到自己的 | PASS |
| IT-E2E-014a | 租户 A 读取自己的数据 | PASS |
| IT-E2E-014b | 租户 A 看不到 B 的表 | PASS |
| IT-E2E-014c | 租户 B 读取自己的数据 | PASS |
| IT-E2E-015 | 跨租户删除保护 (404) | PASS |
| IT-E2E-016a | 无效 API Key 返回 401 | PASS |
| IT-E2E-016b | 缺少认证返回 401 | PASS |

## 性能数据

| 指标 | 数值 |
|------|------|
| 计算节点冷启动 | ~11s |
| 挂起后唤醒 | ~11s |
| 数据库创建 API 响应 | < 2s (不含计算启动) |

## 已知问题

1. 数据库状态始终显示 `CREATING`，缺少状态更新回调机制（计算就绪后应更新为 `RUNNING`）
2. Helm `--no-hooks` 跳过 MinIO bucket 初始化 Job，需手动创建

## 结论

Stage 0 验证通过。所有核心功能在本地 K8s + MinIO 环境中正常工作，包括多租户隔离、计算节点生命周期管理和数据持久化。
