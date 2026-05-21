# dbay 数据恢复工具集设计

## 背景

dbay.cloud 采用 Neon 风格存算分离架构（Compute Pod → Safekeeper 3 副本 → Pageserver → OBS Primary），元数据存于华为云 RDS PostgreSQL。当前已有的备份能力是 `BackupService` 基于 Neon Timeline 的快照粒度备份；缺口有两个：

1. **没有 PITR（Point-In-Time Recovery）入口**：用户/SRE 没办法说"恢复到 5 分钟前那一刻"。底层 Neon 用 LSN 寻址支持读任意 LSN，但上层未暴露。
2. **元数据库是单点**：若 RDS 全挂，OBS 上的 WAL/layer 数据虽然存在，但缺失 tenant↔user 映射，"找不着"用户数据。

本设计交付一套**恢复工具集**，把这两个缺口补上。

## 目标

- 用户能从 Console / CLI 把任意数据库恢复到任意时间点（new branch 语义，原数据保留）
- SRE 在 RDS 完全失效时，能从 OBS 自描述 manifest 反向重建元数据
- SRE 在 RDS 挂时能给单个用户开 1h 临时直连，不必等元数据重建完成
- 所有恢复工具能在 lakeon-api 不可用的场景下独立运行

## 非目标（显式 out-of-scope）

| 故障等级 | 场景 | 不做的原因 |
|---|---|---|
| L6 | OBS region 整挂 | 不开 Cross-Region Replication；云提供商级故障不在首批承诺 |
| L7 | OBS 对象被勒索/恶意删 | 不开 Versioning / Object Lock；接受这类风险 |
| Phase 3 | 持续 WAL 重放热备副本（秒级 RTO） | 等首批稳定后再讨论 |
| 自动演练 | 定期 disaster_drill | 等 dbay-rescue 稳定后再做 |

**接受的风险边界**：若同时发生 OBS region 整挂 + 主 bucket 数据被全删，数据不可恢复。

## 故障覆盖矩阵

| Lv | 场景 | 恢复路径 | RTO 目标 |
|---|---|---|---|
| L1 | lakeon-api 抖动 | k8s rollout（已有） | 秒 |
| L2 | lakeon-api 镜像坏 | helm rollback（已有） | 分钟 |
| **L3** | **用户误删数据** | Console "Restore to time" / `dbay db pitr` | 5 分钟 |
| **L4** | **RDS 完全失效** | `dbay-rescue rebuild-metadata --from-obs --to <rds-dsn>` | 30 分钟 |
| **L5** | **RDS 失效 + 用户急用** | `dbay-rescue emergency-mount <tenant> --owner <email>` | 10 分钟 |

## 系统架构

```
┌────────────────────────────────────────────────────────────────┐
│  Tier 1: 用户自助 (lakeon-api 健康时使用)                        │
├────────────────────────────────────────────────────────────────┤
│   Console "Restore to time" 按钮                                │
│   dbay db pitr <db-id> --time '5min ago'                       │
│                          │                                      │
│                          ▼                                      │
│   POST /api/v1/databases/{id}/pitr  {target_time}              │
│                          │                                      │
│                          ▼                                      │
│   lakeon-api · RecoveryService                                  │
│     ├ time → LSN 解析                                           │
│     └ Neon Pageserver branch API (创建 new branch + compute)   │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  写入侧 (AFTER_COMMIT 事件钩子，不新增长驻服务)                   │
├────────────────────────────────────────────────────────────────┤
│   lakeon-api · TenantService / DatabaseService 落库后           │
│   ──► ManifestWriter (Spring AFTER_COMMIT)                     │
│         ├ 写 OBS: tenants/<tenant_id>/_manifest.json           │
│         └ 更新 OBS: _global/owners.idx (email → tenant_id)     │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  Tier 3: 灾难紧急 (lakeon-api 或 RDS 已不可用)                   │
├────────────────────────────────────────────────────────────────┤
│   dbay-rescue (独立 Go 二进制)                                  │
│     SRE 笔记本 / Bastion 跑                                     │
│     仅依赖 OBS + SRE 离线凭据，不依赖 lakeon-api、RDS、k8s API   │
│                                                                  │
│     子命令:                                                      │
│     ├ list-tenants --from obs                                  │
│     ├ owner-lookup --email <e>                                 │
│     ├ pitr <db-id> --time <t>                                  │
│     ├ rebuild-metadata --from-obs --to <rds-dsn>               │
│     └ emergency-mount <tenant> --owner <email>                 │
│                                                                  │
│     ──► 直连: OBS / Pageserver HTTP / Safekeeper HTTP / RDS    │
└────────────────────────────────────────────────────────────────┘
```

## PITR 语义

**恢复总是创建 new branch**，不做原地恢复。

理由：
- Neon 原生模型就是分支化，new branch 不影响现有数据
- 原地恢复一旦选错时间点不可逆，操作风险高
- new branch 让 SRE/用户可以"先看看再切流量"

### `POST /api/v1/databases/{id}/pitr` 接口

请求：
```json
{
  "target_time": "2026-05-21T14:30:00Z",
  "new_db_name": "mydb_restored_20260521"   // 可选，默认自动生成
}
```

响应：
```json
{
  "new_db_id": "db_xxx",
  "branch_id": "br_yyy",
  "lsn": "0/A1B2C3D4",
  "compute_endpoint": "postgresql://...",
  "status": "ready"
}
```

### time → LSN 解析

Neon 的 Safekeeper/Pageserver 已经有 `lsn_from_timestamp` 接口（Neon 官方 mgmt API）。`RecoveryService` 调它即可，不自己存映射表。

如果 timestamp 早于数据库创建时间或晚于当前 LSN，返回 400 + 明确错误信息（提示用户能恢复到的最早/最晚时间窗）。

## 元数据 OBS 双写（ManifestWriter）

### 触发点与一致性模型

每次 `TenantService` / `DatabaseService` / `BranchService` 落库（创建/更新/软删除）后，由 Spring `@TransactionalEventListener(phase = AFTER_COMMIT)` hook 写一份到 OBS。

**一致性模型：最终一致**。

RDS 是写权威，OBS manifest 是 RDS 的派生副本。AFTER_COMMIT 已过 RDS 事务边界，**不能回滚 RDS**。OBS 写失败的处理：

1. 同步重试 3 次（指数退避）
2. 仍失败 → 写入 retry queue（独立 OBS 路径 `_retry_queue/<timestamp>-<uuid>.json` 存待重试事件）
3. 后台 Scheduler 每分钟扫 retry queue 重试
4. Prometheus 告警 `dbay_manifest_write_failures_total > 0 for 5min` → P1

**短暂不一致窗口的影响**：
- 正常运行：用户走 lakeon-api，看到的永远是 RDS（权威），不一致不可见
- L4 灾难（RDS 挂）：rebuild-metadata 拿到的可能是 lag 几分钟的 manifest——可接受，因为这种场景本身就在做应急恢复

如果 lag 持续超过 5 分钟未恢复，告警升级 + 暂停接受新的 control plane 写入（保证不让 lag 继续扩大）。

### OBS 对象布局

```
s3://lakeon-prod/
├── tenants/<tenant_id>/
│   ├── _manifest.json              ← 该 tenant 的全量元数据快照
│   └── timelines/<timeline_id>/    ← Neon 原有
│
└── _global/
    └── owners.idx                  ← email → [tenant_id, ...] 倒排
```

### `_manifest.json` schema

```json
{
  "manifest_version": 1,
  "tenant_id": "tn_abc123",
  "owner_email": "alice@example.com",
  "created_at": "2026-04-15T10:00:00Z",
  "updated_at": "2026-05-21T14:30:00Z",
  "version": 42,
  "databases": [
    {
      "db_id": "db_xxx",
      "name": "mydb",
      "timeline_id": "tl_yyy",
      "created_at": "...",
      "deleted_at": null,
      "branches": [
        {"branch_id": "br_main", "parent": null, "lsn": "0/0"},
        {"branch_id": "br_dev", "parent": "br_main", "lsn": "0/AB12"}
      ]
    }
  ]
}
```

### `_global/owners.idx` schema

```json
{
  "index_version": 1,
  "updated_at": "2026-05-21T14:30:00Z",
  "owners": {
    "alice@example.com": ["tn_abc123", "tn_def456"],
    "bob@example.com":   ["tn_ghi789"]
  }
}
```

### 并发与版本控制

- 用 OBS If-Match (ETag) 实现 CAS：读 manifest 时拿 ETag，写时带 If-Match
- 冲突时（ETag 不匹配）→ 重试（最多 3 次）→ 仍失败则报错回滚
- `_global/owners.idx` 同样用 If-Match，但争用更激烈 → 改为**按邮箱分片**：`_global/owners/<sha256(email)[0:2]>.idx`，把全局倒排拆成 256 个小索引

## dbay-rescue (Go binary)

### 为什么独立 Go binary

| 维度 | Go binary | Python CLI | Java CLI |
|---|---|---|---|
| 单文件分发 | ✅ scp 即跑 | ❌ pip 装包 | ❌ JVM |
| Air-gapped 启动 | ✅ 零依赖 | ❌ | ❌ |
| 应急启动速度 | ✅ ms 级 | △ 秒级 | ❌ 十秒级 |
| 华为云 OBS SDK | ✅ 官方 Go SDK 成熟 | ✅ | △ |

灾难时 SRE 可能在飞机上、咖啡店 WiFi、连华为云内网 VPN 的笔记本上跑。**一个单文件、零依赖、几十 MB 的 binary** 比任何带 runtime 的方案都可靠。

### 子命令规约

#### `dbay-rescue list-tenants --from obs`

- 扫 OBS `tenants/*/_manifest.json`
- 输出每个 tenant 的 owner_email / created_at / 数据库数
- 用途：RDS 挂时确认数据范围
- 不需要 RDS 凭据，只需要 OBS 读权限

#### `dbay-rescue owner-lookup --email alice@example.com`

- 读 `_global/owners/<shard>.idx`
- 输出该邮箱拥有的所有 tenant_id 和它们的 manifest 摘要
- 用途：用户登录不了系统时找回数据

#### `dbay-rescue pitr <db-id> --time <t> [--tenant <id>]`

绕过 lakeon-api，用于 API 已挂场景。

流程：
1. **定位 timeline**：
   - 如果传了 `--tenant`：直接读 `tenants/<tenant>/_manifest.json` 找 db_id → timeline_id
   - 否则：扫 `tenants/*/_manifest.json`（并发，按 mtime 倒序）找包含该 db_id 的，第一个命中即停
2. 调 Pageserver `/lsn_from_timestamp` 解析 LSN
3. 调 Pageserver `/branch` 创建 new branch
4. 调 Pageserver `/compute` 起新 compute pod
5. 输出 connection string

不写 RDS（因为可能挂了）。lakeon-api 恢复后由 SRE 决定是否补元数据（rebuild-metadata 会自动包含这次新建的 branch，因为 ManifestWriter 不可用时 dbay-rescue 直接更新 OBS manifest）。

#### `dbay-rescue rebuild-metadata --from-obs --to <rds-dsn>`

核心命令。流程：

1. List OBS `tenants/` 前缀，取所有 `_manifest.json`
2. 并发下载并解析
3. 对每个 manifest，按 schema 写入目标 RDS：
   - `INSERT INTO tenants ...`
   - `INSERT INTO databases ...`
   - `INSERT INTO branches ...`
4. 重建 `_global/owners.idx`（也写回 OBS，确保一致）
5. 输出报告：成功/失败/跳过的 tenant 数

幂等性：用 manifest_version + tenant_id 做 upsert key，可重复执行。

#### `dbay-rescue emergency-mount <tenant> --owner <email>`

为单个 tenant 临时拉起 compute pod 给用户直连：

1. 从 OBS 读该 tenant 的 manifest
2. 验证 owner_email 匹配（防 SRE 误操作给错人）
3. 调 Neon Pageserver API 起一个临时 compute（attach 现有 timeline）
4. 在该 compute 上创建临时 PG ROLE：`CREATE ROLE em_<short_uuid> WITH LOGIN PASSWORD '<random32>' VALID UNTIL 'now+1h'`，授予所有 schema 的只读权限（不给 SUPERUSER）
5. 输出 connection string：`postgresql://em_xxx:<pwd>@<compute-host>:<port>/<db>?sslmode=require`
6. **审计日志**：写到独立的 OBS audit bucket（who/when/which tenant/which email/role_name）
7. 1h 后自动清理：单独的 cleanup job 每 5 分钟扫过期 ROLE → `DROP ROLE` + 销毁 compute pod

只读权限是默认；如需写权限要 `--writable` 显式开启并多记一条 audit 标记。

**安全考量**：这条命令权限敏感（SRE 凭一份凭据能给任何 tenant 开访问）。控制：
- 必须传 `--owner <email>` 且匹配 manifest 中的 owner_email
- 所有调用强制审计日志，不能跳过
- SRE 凭据仅 break-glass 账号有，平时离线托管

### 凭据管理

`dbay-rescue` 从 `~/.dbay/rescue-credentials.yaml` 读取：

```yaml
obs:
  endpoint: obs.cn-east-3.myhuaweicloud.com
  access_key: <SRE break-glass AK>
  secret_key: <SRE break-glass SK>
pageserver:
  mgmt_endpoint: https://pageserver.internal:9898
  token: <pageserver mgmt token>
rds:
  default_dsn: postgres://...  # 可选，rebuild-metadata 时用
```

凭据文件平时**不在线**——仅在响应灾难时从离线介质（YubiKey / 加密 USB / 公司保险箱）取出。

## RecoveryService（lakeon-api 内）

复用现有 `BackupService` 的 Neon HTTP 客户端代码。新增：

```java
public class RecoveryService {
  // 用户走的 PITR
  public PitrResult pitr(String dbId, Instant targetTime, String newDbName);

  // 给 ManifestWriter 用的
  public void rebuildOwnersIndexShard(String emailShard);
}

public class ManifestWriter {
  // 同事务后置 hook
  @TransactionalEventListener(phase = AFTER_COMMIT)
  public void onTenantChanged(TenantChangedEvent e);

  @TransactionalEventListener(phase = AFTER_COMMIT)
  public void onDatabaseChanged(DatabaseChangedEvent e);

  @TransactionalEventListener(phase = AFTER_COMMIT)
  public void onBranchChanged(BranchChangedEvent e);
}
```

`ManifestWriter` 用 Spring 的 `@TransactionalEventListener(phase = AFTER_COMMIT)`，保证 RDS 事务提交后才写 OBS。OBS 写失败 → 立刻报错并触发补偿（重试 + 告警）。

## Console UI

`lakeon-console` 在数据库详情页加 "Restore to time" 按钮：

- 弹出时间选择器（默认显示该 db 可恢复的时间窗：[created_at, now]）
- 提交后展示 LSN 和将要创建的新数据库名
- 用户确认 → POST `/api/v1/databases/{id}/pitr`
- Polling 状态直到 `ready`
- 完成后展示新数据库的连接信息

时间窗的边界：调 `GET /api/v1/databases/{id}/pitr-window` 获取（最早 = created_at，最晚 = 当前 head LSN 时间）。

## 错误处理

| 错误 | 行为 |
|---|---|
| target_time 超出可恢复窗口 | 400，附带可用窗口 |
| Pageserver mgmt API 不可达 | 503，记录到 operation_logs |
| ManifestWriter 写 OBS 失败 | 异步重试 3 次，仍失败 → 写到 retry queue + Prometheus 告警，不阻塞用户请求（因为是 AFTER_COMMIT） |
| `dbay-rescue rebuild-metadata` 解析某个 manifest 失败 | 跳过该 tenant 继续，最终报告中列出 |
| `emergency-mount` owner_email 不匹配 | 拒绝，记录审计 |
| `dbay-rescue` 网络中断 | 所有命令支持 `--resume <checkpoint>` 续跑 |

## 测试策略

### 单元测试

- `RecoveryService`：mock Neon client，覆盖 time→LSN 边界（早于/晚于/等于）
- `ManifestWriter`：mock OBS，验证 AFTER_COMMIT 顺序、ETag 重试、shard 写入
- `dbay-rescue` 每个子命令：mock OBS/Pageserver，覆盖正常 + 异常路径

### 集成测试

每个新 feature 必须有 E2E 测试（项目惯例）：

1. **PITR E2E** (pytest)：创建租户 → 写数据 → 等几秒 → 再写数据 → PITR 恢复到中间时间点 → 断言数据为中间状态
2. **ManifestWriter E2E**：创建租户/数据库/分支 → 直接读 OBS manifest → 断言内容与 RDS 一致
3. **rebuild-metadata E2E**：起一个测试 OBS bucket + 空 RDS → 跑 rebuild → 断言 RDS 恢复完整
4. **emergency-mount E2E**：跑命令 → 断言能拿到临时连接串并能查询数据库
5. **Console UI** (Playwright)：浏览器点 Restore 按钮 → 选时间 → 确认 → 新数据库出现在列表

### 灾难恢复演练（手工，不在首批自动化）

每季度跑一次：
1. 在 staging 完整跑 `dbay-rescue rebuild-metadata`（用真实 OBS 副本 + 空 RDS）
2. 验证恢复后所有 tenant 元数据正确
3. 抽样 5 个 tenant 跑 PITR 验证可访问性

## 实施计划

| Phase | 交付项 | 估时 |
|---|---|---|
| **1.1** | `lakeon-api` `POST /databases/{id}/pitr` + `pitr-window` | 5-7 天 |
| **1.2** | Console "Restore to time" 按钮 + UI | 3-5 天 |
| **1.3** | `dbay db pitr` 子命令 | 1-2 天 |
| **1.4** | `ManifestWriter` hook + OBS schema 落地 | 5-7 天 |
| **1.5** | `dbay-rescue` Go binary (5 子命令) + E2E | 10-15 天 |
| **1.6** | 灾难恢复 runbook 文档 | 2-3 天 |

**总计**: 约 4-6 周一人，或 2-3 周两人并行（1.1/1.2/1.3 一条线，1.4/1.5/1.6 一条线）。

## 部署与发布

- `lakeon-api` 走现有 CCE 部署流程（`build-and-push-api.sh`）
- `dbay-cli` 走 PyPI 发布
- `dbay-rescue` Go binary 走独立 release：
  - 编译三平台（linux-amd64, linux-arm64, darwin-arm64）
  - 上传到内部 release 仓
  - SRE 负责本地 checksum 验证后使用
- OBS bucket 不需要任何新配置变更

## 监控告警

新增 Prometheus 指标：

- `dbay_recovery_pitr_requests_total{status}` — PITR 调用计数
- `dbay_recovery_pitr_duration_seconds` — PITR 端到端耗时
- `dbay_manifest_write_failures_total` — ManifestWriter 写 OBS 失败计数
- `dbay_manifest_lag_seconds` — RDS 落库 → OBS 写入完成的延迟（应 < 5s）

告警：
- `dbay_manifest_write_failures_total > 0 for 5min` → P1
- `dbay_manifest_lag_seconds > 60s` → P2

## 风险与已知限制

| 风险 | 应对 |
|---|---|
| OBS 写 manifest 慢拖累 API 响应 | AFTER_COMMIT 异步执行，不阻塞用户请求；失败有重试 + 告警 |
| `owners.idx` 单文件并发竞争 | 按 sha256(email)[0:2] 分 256 片 |
| `emergency-mount` 被滥用 | 强制 owner_email 校验 + 审计日志 + break-glass 凭据离线托管 |
| `dbay-rescue` binary 被攻击者拿到 | 凭据不打包进 binary，仅从本地文件读取；binary 只代表"能力"不代表"权限" |
| L6/L7 风险被忘记 | 在 runbook 和监控大盘上显式标注 "不在恢复能力范围内" |

## 后续迭代（明确不在首批）

- `dbay-sre-mcp` 适配（让 AI agent 能调 dbay-rescue 的能力）
- 自动 disaster_drill（定时跑 dry-run-rebuild）
- L6/L7 覆盖：OBS Cross-Region Replication + Versioning + Object Lock
- Phase 3：持续 WAL 重放的 hot standby compute
