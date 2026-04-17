# Pageserver Tenant 挂靠韧性设计

## 背景

2026-04-16 事故：pageserver Pod 重启后，`control_plane_emergency_mode = true` 模式下只从本地磁盘目录恢复了 30 个 tenant，10 个活跃 DB 的 tenant 静默丢失。所有 SUSPENDED DB 唤醒时 compute pod 向 pageserver 请求 `get_basebackup` 返回 `Tenant not found` 404，compute CrashLoopBackOff，用户 Console schema 树无限 loading。

手动 `PUT /v1/tenant/<id>/location_config` re-attach 后恢复。但系统缺少自愈和纵深防御，任何单点故障都可能再次导致同样问题。

## 目标

- pageserver 重启后所有 tenant 自动恢复，零人工干预
- 任何单层失效时有兜底层，不静默丢失
- 运维可观测：异常秒级感知，不依赖用户点 Console 发现

## 四层纵深防御

```
┌─────────────────────────────────────────────────┐
│  L4  OBS 数据校验（定期）                         │
│  list OBS prefix 对比控制面，缺失→告警             │
├─────────────────────────────────────────────────┤
│  L3  Prometheus 告警                             │
│  attached 数 vs 控制面活跃 DB 数，差值>0→告警       │
├─────────────────────────────────────────────────┤
│  L2  lakeon-api reconcile（@Scheduled 每分钟）    │
│  GET /v1/tenant/<id>，404→PUT location_config    │
├─────────────────────────────────────────────────┤
│  L1  storage-controller（Neon 官方架构）           │
│  PS 启动时注册，controller 下发全量 tenant attach   │
└─────────────────────────────────────────────────┘
```

| 层 | 防什么 | 恢复时间 |
|---|---|---|
| L1 storage-controller | PS 重启丢 tenant | 秒级（PS 启动时同步） |
| L2 lakeon-api reconcile | controller 挂/漏/网络分区 | ≤60s（下一个扫描周期） |
| L3 Prometheus 告警 | 所有自动层都没兜住 | 人工介入，分钟级 |
| L4 OBS 数据校验 | 存储层损坏/丢失 | 人工介入，告警周期 |

## L1: 部署 storage-controller

### 组件职责

Neon storage-controller 是 tenant→pageserver 分配的权威来源：
- 持有 tenant shard 分配表（tenant_id → node_id → generation）
- pageserver 启动时调 controller `/re-attach` 接口，controller 返回完整 tenant 清单
- pageserver 按清单逐个 attach，generation 递增
- 支持多 pageserver shard 迁移

### 部署

```yaml
# deploy/cce/storage-controller.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: storage-controller
  namespace: lakeon
spec:
  replicas: 1
  selector:
    matchLabels:
      app: storage-controller
  template:
    spec:
      containers:
      - name: storage-controller
        image: swr.cn-north-4.myhuaweicloud.com/flex/storage-controller:latest
        ports:
        - containerPort: 1234   # HTTP API
        env:
        - name: DATABASE_URL
          value: "postgresql://lakeon:Admin@2026@192.168.0.176:5432/lakeon"
        - name: PAGESERVER_NODES
          value: "http://pageserver:9898"
```

### pageserver.toml 变更

```diff
- control_plane_emergency_mode = true
+ # control_plane_emergency_mode = false  (默认值，删除该行)
+ control_plane_api = 'http://storage-controller:1234'
```

### DB schema

storage-controller 需要表存 tenant 分配：

```sql
CREATE TABLE tenant_shards (
  tenant_id       TEXT NOT NULL,
  shard_number    INT NOT NULL DEFAULT 0,
  generation      INT NOT NULL DEFAULT 1,
  node_id         BIGINT NOT NULL,    -- pageserver node
  placement_policy TEXT DEFAULT 'Attached',
  PRIMARY KEY (tenant_id, shard_number)
);

CREATE TABLE nodes (
  node_id         BIGSERIAL PRIMARY KEY,
  listen_http     TEXT NOT NULL,     -- e.g. 'http://pageserver:9898'
  listen_pg       TEXT NOT NULL      -- e.g. 'pageserver:6400'
);
```

### generation 管理

每次 re-attach generation 递增，存到 `tenant_shards.generation`。同时在 `database_instances` 表加字段：

```sql
ALTER TABLE database_instances ADD COLUMN neon_generation INT DEFAULT 1;
```

lakeon-api 创建 DB 时写入初始 generation=1，后续 re-attach 同步更新。

## L2: lakeon-api reconcile 定时任务

即使 storage-controller 部署完毕，也保留这一层作为兜底。

### 实现

在 `ComputeLifecycleService` 或新建 `TenantReconcileService` 中：

```java
@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
public void reconcilePageserverTenants() {
    List<DatabaseEntity> dbs = databaseRepository
        .findAllByDeletedAtIsNull();
    
    for (DatabaseEntity db : dbs) {
        String tenantId = db.getNeonTenantId();
        if (tenantId == null) continue;
        
        try {
            // GET /v1/tenant/<id> — 检查是否 attached
            neonApiClient.getTenant(tenantId);
        } catch (NeonApiException e) {
            if (e.getStatusCode() == 404) {
                log.warn("Tenant {} ({}) not found on pageserver, re-attaching",
                    tenantId, db.getName());
                try {
                    neonApiClient.createTenant(tenantId);
                    log.info("Re-attached tenant {} ({})", tenantId, db.getName());
                } catch (Exception ex) {
                    log.error("Failed to re-attach tenant {} ({}): {}",
                        tenantId, db.getName(), ex.getMessage());
                }
            }
        }
    }
}
```

### 注意事项

- **幂等**：`PUT location_config` 对已 attached 的 tenant 是 no-op
- **速率**：21 个 DB → 21 次 GET，pageserver 本地查询，<100ms/次，无压力
- **generation**：re-attach 时使用 `database_instances.neon_generation + 1`，成功后更新回 DB
- **启动首次延迟 30s**：等 pageserver 和 storage-controller 先完成自己的 reconcile

## L3: Prometheus 监控告警

### 指标采集

lakeon-api 暴露自定义 Prometheus 指标：

```java
@Scheduled(fixedDelay = 60_000)
public void updateTenantMetrics() {
    // 控制面应有数
    long expected = databaseRepository.countByDeletedAtIsNull();
    
    // pageserver 实际 attached 数
    long attached = neonApiClient.listTenants().size();
    
    // 记录指标
    Metrics.gauge("lakeon_tenant_expected_total", expected);
    Metrics.gauge("lakeon_tenant_attached_total", attached);
    Metrics.gauge("lakeon_tenant_missing_total", Math.max(0, expected - attached));
}
```

### 告警规则

```yaml
# deploy/cce/prometheus-rules.yaml
groups:
- name: lakeon-pageserver
  rules:
  - alert: PageserverTenantMissing
    expr: lakeon_tenant_missing_total > 0
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "{{ $value }} tenant(s) missing from pageserver"
      description: "控制面有 {{ $labels.expected }} 个活跃 DB，pageserver 只 attached {{ $labels.attached }} 个"
```

## L4: OBS 数据校验

优先级最低，排进后续 roadmap。

### 思路

定期（每天）扫描 OBS `s3://dbay-mainstore/pageserver/tenants/` prefix，对比 `database_instances.neon_tenant_id`：
- 控制面有但 OBS 无 → 数据丢失告警（critical）
- OBS 有但控制面无 → 孤儿数据，可清理（info）

### 实现方式

`@Scheduled(cron = "0 0 3 * * ?")` 每天 3 点跑一次，用 AWS S3 SDK listObjectsV2 扫 prefix。

## 实施计划

| 阶段 | 内容 | 耗时 |
|---|---|---|
| Phase 1 | L2 reconcile + L3 Prometheus 指标 + 清 Error Pod | 1 天 |
| Phase 2 | L1 storage-controller 部署 + pageserver.toml 切换 + generation 管理 | 3-5 天 |
| Phase 3 | L4 OBS 校验 | 1 天 |

Phase 1 先上线兜底，Phase 2 做彻底修复，Phase 3 补最后一层。

## 回滚方案

- Phase 2 失败可回退：pageserver.toml 恢复 `control_plane_emergency_mode = true`，L2 reconcile 继续兜底
- storage-controller 挂了不影响已有 tenant 运行（PS 不会主动 detach），只影响新建 DB 和 PS 重启后的 reconcile

## 实施记录

| 阶段 | 完成时间 | 结果 |
|---|---|---|
| Phase 1 (L2+L3) | 2026-04-17 | TenantReconcileService 每60s扫描 + SRE控制台 Neon tab tenant健康卡片 + 清 Error Pod |
| Phase 2 (L1) | 2026-04-17 | storage-controller 部署（Neon镜像内置binary），pageserver re-attach 自愈验证通过。压力测试：删PS Pod后21 tenant全量自动恢复，tpch-bench端到端27s返回。shard_stripe_size需32768非0，control_plane_api路径需含/upcall/v1前缀，--control-plane-url需指向lakeon-api避免compute_hook panic |

## 相关文件

- `pageserver.toml`：`/data/pageserver.toml`（pageserver Pod 内）
- `NeonApiClient.java`：tenant CRUD + location_config
- `BranchService.java`：创建分支依赖 tenant attached
- `DatabaseService.java:wakeCompute`：唤醒 compute 依赖 tenant attached
- `ComputeLifecycleService.java`：auto-suspend/resume 生命周期
