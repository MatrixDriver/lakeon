# dbay-rescue 灾难恢复 Runbook

> **受众**：SRE 工程师，事故现场可能在咖啡店笔记本 + VPN 上，时间紧张。
> **目标**：复制 → 粘贴 → 恢复服务。每一步都可在 5 分钟内决定下一步。
> **背景**：详细设计见 `docs/superpowers/specs/2026-05-21-dbay-data-recovery-design.md`。

---

## 0. 30 秒诊断分诊（先看这张表）

| Lv | 现象 | 工具 | 第一条命令 | 大致 RTO |
|----|------|------|-----------|---------|
| L1 | `lakeon-api` 抖动（5xx 但 RDS 健康） | k8s | `kubectl rollout restart deployment/lakeon-api -n lakeon` | < 1 min |
| L2 | 最新镜像有 bug 导致服务起不来 | helm | `helm rollback lakeon-api -n lakeon` | 2–5 min |
| L3 | 单个用户误删数据 | Console / `dbay db pitr` | 用户在 Console 点 "Restore to time" | 5 min |
| L4 | RDS 完全失效，OBS 仍健康 | `dbay-rescue rebuild-metadata` | 见 [场景 1](#场景-1rds-完全失效-l4) | ~30 min |
| L5 | RDS 挂 + 某个用户火烧眉毛 | `dbay-rescue emergency-mount` | 见 [场景 2](#场景-2rds-挂但某用户急用-l5) | ~10 min |
| L6 | OBS region 整挂 | **不在能力范围** | 见 [已知限制](#已知限制) | — |
| L7 | OBS 对象被恶意删 / 勒索 | **不在能力范围** | 见 [已知限制](#已知限制) | — |

> 不知道是哪一级？先跑 `dbay-rescue list-tenants`：
> - 命令返回正常清单 → OBS 健康，最差是 L4/L5
> - OBS 报 403/404 但 lakeon-api 200 → 不是灾难，先看 OBS 权限
> - 两边全挂 → 看 [L6/L7](#已知限制)

---

## 1. 准备工作（事故前 / 事故初始几分钟）

### 1.1 取出离线 SRE 凭据

`dbay-rescue` 平时**没有任何在线凭据**，break-glass AK/SK 仅存放在：

1. 公司保险箱里的加密 USB（首选）
2. YubiKey + 离线 Vault（次选）
3. 1Password 业务版的 `dbay-sre-break-glass` 项（应急通道）

凭据文件落到本地 `~/.dbay/rescue-credentials.yaml`：

```yaml
obs:
  endpoint: obs.cn-east-3.myhuaweicloud.com
  bucket:   lakeon-prod
  access_key: <BREAK-GLASS AK>
  secret_key: <BREAK-GLASS SK>
pageserver:
  mgmt_endpoint: https://pageserver.internal:9898
  token: <pageserver mgmt token>
rds:
  default_dsn: ""   # 留空，rebuild 时通过 --to 显式传，避免误操作
```

权限设置：`chmod 600 ~/.dbay/rescue-credentials.yaml`。

### 1.2 下载 `dbay-rescue` 二进制

发布版本上传在公司内部 release 仓 `swr.cn-east-3.myhuaweicloud.com/lakeon/releases/dbay-rescue/`：

```bash
# 选你笔记本对应的平台
curl -fsSLO https://release.dbay.cloud/dbay-rescue/v0.1.0/dbay-rescue-darwin-arm64
curl -fsSLO https://release.dbay.cloud/dbay-rescue/v0.1.0/SHA256SUMS

# 校验
shasum -a 256 -c SHA256SUMS --ignore-missing
# 必须看到：dbay-rescue-darwin-arm64: OK
# 校验失败立即停手，找另一个分发源。

chmod +x dbay-rescue-darwin-arm64
sudo mv dbay-rescue-darwin-arm64 /usr/local/bin/dbay-rescue
dbay-rescue --version
```

### 1.3 连通性自检

```bash
# 一行命令同时验证 OBS 读权限 + 凭据正确
dbay-rescue list-tenants | head -5
```

如果 5 秒内没输出 → 检查 VPN 是否连上华为云内网（必须能解析 `obs.cn-east-3.myhuaweicloud.com`）。

---

## 场景 1：RDS 完全失效 (L4)

**触发条件**：RDS 实例完全不响应，但 `dbay-rescue list-tenants` 能列出租户（OBS 健康）。

**目标 RTO**：约 30 分钟。

### Step 1 — 确认 RDS 真挂了，不是网络抖动

```bash
# 三次 ping，任一次成功就别走 L4 流程
for i in 1 2 3; do
  PGCONNECT_TIMEOUT=5 psql "$LAKEON_RDS_DSN" -c 'SELECT 1' && break
  sleep 5
done
```

判定：

| 结果 | 含义 | 下一步 |
|------|------|--------|
| `1` 至少返回一次 | 网络抖动 | 走 L1（rollout restart） |
| 三次全 timeout | RDS 不可达 | 继续 Step 2 |
| 三次全 `FATAL: ...` | RDS 拒绝连接（认证/状态异常） | 继续 Step 2，但先看华为云控制台实例状态 |

打开华为云 RDS 控制台 → 实例 `lakeon-prod-rds` → 看状态：
- `异常` / `故障` / `已删除` → 走 Step 3（必须重建）
- `恢复中` / `备份中` → 等 5 分钟再判定，先用 [场景 2](#场景-2rds-挂但某用户急用-l5) 给最紧迫的用户应急

### Step 2 — 从 OBS 确认数据范围

```bash
# 列出所有 tenant 看数据完整性
dbay-rescue list-tenants

# 输出形如：
# TENANT_ID    OWNER_EMAIL          DBS  CREATED_AT
# tn_abc123    alice@example.com    3    2026-04-15T10:00:00Z
# tn_def456    bob@example.com      1    2026-05-01T08:30:00Z
# ...

# 数一下行数，对比你印象中的租户规模
dbay-rescue list-tenants | tail -n +2 | wc -l
```

如果数量远低于预期 → OBS 也可能有问题，**先停手**，去 [L6/L7 章节](#已知限制)。

### Step 3 — 建空 RDS 实例

华为云 Console → RDS → 创建实例：

- 引擎：PostgreSQL **17**（与生产同版本，看 `lakeon-api/pom.xml` 里 `postgresql.version`）
- 规格：与原实例同档（避免 OOM 把 rebuild 卡住）
- 网络：与 CCE 集群同 VPC
- 安全组：放通 5432（CCE worker 子网 + 你的 SRE 跳板机 IP）
- 命名约定：`lakeon-prod-rds-recovered-YYYYMMDD`

实例创建好后，登上去建主库 + 应用账号：

```sql
CREATE DATABASE lakeon OWNER lakeon_app;
CREATE USER lakeon_app WITH PASSWORD '<新密码，存到密钥管理>';
GRANT ALL ON DATABASE lakeon TO lakeon_app;
```

把新实例 DSN 记到环境变量：

```bash
export NEW_RDS_DSN='postgres://lakeon_app:<pwd>@<new-rds-host>:5432/lakeon?sslmode=require'
```

### Step 4 — 跑 Flyway 迁移建 schema

```bash
cd /path/to/lakeon-api

# 用环境变量驱动 Flyway，避免在 pom.xml 里改 url
mvn flyway:migrate \
  -Dflyway.url="jdbc:postgresql://<new-rds-host>:5432/lakeon?sslmode=require" \
  -Dflyway.user=lakeon_app \
  -Dflyway.password='<pwd>'

# 校验
psql "$NEW_RDS_DSN" -c '\dt' | head
# 必须看到 tenants / database_instances / branches / users 等表
```

### Step 5 — 从 OBS rebuild 元数据

```bash
dbay-rescue rebuild-metadata --to "$NEW_RDS_DSN"

# 进度输出：
#   rebuilt 10 tenants...
#   rebuilt 20 tenants...
# Done. ok=137 failed=0
```

**失败处理**：

- `failed > 0` → 命令最终会非 0 退出。看 stderr 上的 `! apply tenant <id>: ...` 行，逐个排查（通常是 manifest schema 异常或 RDS 唯一键冲突）。
- 命令是**幂等**的（per-tenant upsert），失败的可以等修好 manifest 后重跑同一命令，不会重复插。

### Step 6 — 重要：rebuild 出来的字段有占位符（必读）

Rebuild 之后的元数据**不是位级精确还原**。下列字段是合成占位符，灾后必须人工补齐 / 轮换 / 校验：

| 表 / 列 | 占位符 | 必须做的事 |
|---------|--------|-----------|
| `tenants.name` | `<tenant_id>` | 业务可见名丢失，按需从用户/审计日志反查后 `UPDATE` |
| `tenants.api_key` | `REBUILT-<tenant_id>` | **立即轮换**：通过 Console "Reset API Key" 让用户生成新 key，老占位符不能放外网 |
| `database_instances.status_message` | `rebuilt: deleted_at=...`（仅软删除行） | 仅审计用，无需操作 |
| `branches.name` | `rebuilt@<lsn>` 或 `rebuilt-<branch_id>` | 应用如果按 branch name 跳转（比如 `main`/`dev`）会**断裂**，需要参考 Console UI 与用户确认重命名 |
| `branches.is_default` | 全部 `FALSE` | 每个 database 至少要有一行 `is_default=TRUE`，否则 Console "默认分支" 视图会空。手工 `UPDATE`（见下方 SQL） |
| `database_instances.compute_size` / `suspend_timeout` / `storage_limit_gb` | 列默认值 | 原值未进 manifest（Phase 2 工作）。如有用户付费等级差异要从计费记录反查后 `UPDATE` |
| `users` / `user_tenant_membership` / `operation_logs` / `databases.environment_variables` | 全部丢失 | 这些不在 Phase 1 manifest 范畴。能补则补，不能补就在沟通中告知用户 |

修每个 database 缺 default 分支：

```sql
-- 检查
SELECT database_id, count(*) FILTER (WHERE is_default) AS defaults
FROM branches GROUP BY database_id HAVING count(*) FILTER (WHERE is_default) <> 1;

-- 修复（每个 database 把 name 含 'main' 或最老的那条置为 default）
UPDATE branches b SET is_default = TRUE
WHERE b.id IN (
  SELECT DISTINCT ON (database_id) id FROM branches
  ORDER BY database_id, (name LIKE '%main%') DESC, created_at ASC
);
```

> 这些占位符是设计上的取舍（manifest 不存敏感字段以降低 OBS 泄露面）。规划修复 Phase 2 的工作：让 manifest 多带 `compute_size` 等非敏感字段做完整回环。

### Step 7 — 让 lakeon-api 指向新 RDS

```bash
export KUBECONFIG=~/.kube/cce-lakeon-config

# 改 secret（不是 ConfigMap），用 kubectl edit 或 patch
kubectl -n lakeon set env deployment/lakeon-api \
  SPRING_DATASOURCE_URL="jdbc:postgresql://<new-rds-host>:5432/lakeon?sslmode=require" \
  SPRING_DATASOURCE_PASSWORD='<new pwd>'

# rollout
kubectl rollout restart deployment/lakeon-api -n lakeon
kubectl rollout status  deployment/lakeon-api -n lakeon --timeout=5m
```

### Step 8 — 验证服务恢复

```bash
# 健康
curl -sf https://api.dbay.cloud:8443/api/v1/healthz | jq .

# 列租户数（与 Step 2 对比）
curl -sf -H "Authorization: Bearer lakeon-sre-2026" \
  https://api.dbay.cloud:8443/api/v1/admin/tenants?page_size=1 | jq '.total'

# 抽查一个租户的数据库列表能正常加载（替换 tenant_id）
curl -sf -H "X-Tenant-Id: tn_abc123" -H "Authorization: Bearer <token>" \
  https://api.dbay.cloud:8443/api/v1/databases | jq '.[].id'

# 抽样让 1–2 个用户登 Console 验证：数据库列表能开、能跑 SQL。
```

通报恢复 → **重要后续**：

- 把 Step 6 里的 `api_key` 轮换通知发给所有租户管理员
- 把丢失的字段（`users` 表等）的影响发给客户成功团队
- 写事故报告时引用本节 Step 6 表格

---

## 场景 2：RDS 挂但某用户急用 (L5)

**触发条件**：场景 1 还在做，但某客户（通常 P1 升级过来的）要"现在就要用"。

**目标 RTO**：约 10 分钟。

### Step 1 — 用邮箱定位租户

```bash
dbay-rescue owner-lookup --email alice@example.com

# 输出：
# OWNER         TENANT_ID    DBS  CREATED_AT
# alice@...     tn_abc123    3    2026-04-15T10:00:00Z
# alice@...     tn_def456    1    2026-05-01T08:30:00Z
```

如果有多个 tenant，跟用户**电话/视频确认**要哪一个（不能只靠工单文字）。

### Step 2 — 起临时 compute

```bash
dbay-rescue emergency-mount \
  --tenant tn_abc123 \
  --owner  alice@example.com \
  --hours  1
# 默认只读、1 小时有效期、写权限要显式 --writable
```

输出形如：

```text
Mounting emergency compute for tn_abc123...
  Database: mydb (timeline=tl_xyz)
  Pod: em-tn_abc12-a1b2c3

Emergency mount ready (valid 1h):

  postgresql://em_AbCdEfGh:<pwd>@em-tn_abc12-a1b2c3.lakeon.svc:5432/mydb?sslmode=require

  Audit: _audit/emergency_mount/1716345678000-em_AbCdEfGh.json
  Cleanup: pod expires at 2026-05-22T15:30:00Z — separate cleanup job removes pods + roles.
```

### Step 3 — 把连接信息**安全地**交给用户

- 走加密通道（如客户工单系统私密回复 / 1Password 共享 / 临时加密链接）
- **不要**贴到群聊 / 邮件正文 / 截图
- 同时告知：
  - 仅 1 小时有效（如需续期，再跑一次命令）
  - 默认只读（如确需写要再求 SRE 用 `--writable` 重跑）
  - 这是应急通道，正常服务恢复后 pod 会被回收

### 安全 / 审计要点

| 项 | 说明 |
|----|------|
| `--owner` 必须匹配 manifest | 不匹配命令直接拒绝，防止 SRE 误把 A 的库给了 B |
| 临时角色名 `em_<8 char>` | 与正常应用角色明显区分，便于审计 |
| `VALID UNTIL` 由 PG 强制 | 即使清理 job 滞后，PG 自己也会拒绝过期登录 |
| 审计写到 `_audit/emergency_mount/<ts>-<role>.json` | 包含 `actor`（`$USER`）、`tenant_id`、`owner_email`、`writable`、`valid_until` |
| `--writable` 必须显式 | 默认 `pg_read_all_data`，加 flag 才追加 `pg_write_all_data` |
| 清理责任 | 独立 cleanup job 每 5 min 扫 `expires-at` label 过期的 pod → `DROP ROLE` + 删 pod |

### `--writable` 决策提示

| 场景 | 给写权限？ |
|------|----------|
| 用户只想看数据救出来 | ❌ 只读 |
| 用户需要导出 / 跑 SELECT | ❌ 只读 |
| 用户需要应急写入（如修改外部系统依赖的开关行） | ✅ 加 `--writable` + 改用 30 min 短时 + 通知风险 |
| 你不确定 | ❌ 先只读，让用户尝试后再说 |

---

## 场景 3：lakeon-api 部署失败 (L1/L2, RDS 健康)

**触发条件**：RDS 健康（`psql` 能连通），但 `https://api.dbay.cloud:8443/api/v1/healthz` 5xx 或不响应。

```bash
export KUBECONFIG=~/.kube/cce-lakeon-config

# Step 1: 看现状
kubectl -n lakeon get deploy lakeon-api
kubectl -n lakeon get pods -l app=lakeon-api
kubectl -n lakeon describe pod -l app=lakeon-api | tail -50
kubectl -n lakeon logs -l app=lakeon-api --tail=200 | grep -iE 'error|exception|fatal'

# Step 2: 抖动 → 重启（L1）
kubectl rollout restart deployment/lakeon-api -n lakeon
kubectl rollout status  deployment/lakeon-api -n lakeon --timeout=5m

# Step 3: 镜像坏 → 回退（L2）
# 看上次部署 revision
kubectl rollout history deployment/lakeon-api -n lakeon

# 回退到上一个能跑的 revision
kubectl rollout undo deployment/lakeon-api -n lakeon
# 或精确：
kubectl rollout undo deployment/lakeon-api -n lakeon --to-revision=<N>

# Helm 部署的，等价命令：
helm history lakeon-api -n lakeon
helm rollback lakeon-api <revision> -n lakeon
```

**注意**：如果是镜像层 `ImagePullBackOff` 且 SWR 限流，先看 `kubectl describe`，**不要**盲目 rollback——可能上一版镜像也被回收了。这种情况要 `SITE=hwstaff bash deploy/cce/build-and-push-api.sh` 重新推。

恢复后查 root cause（看最近一个 commit / Helm value 改动），写事故根因。

---

## 场景 4：用户误删数据 (L3)

**默认是用户自助流程**——SRE 一般不需要介入。仅当用户：

- Console 操作有 bug 卡住
- 时间窗超出 UI 显示范围
- 一次性恢复多个数据库不想点 N 次

才走 SRE 通道。

### 4.1 用户自助（推荐）

Console → 数据库详情页 → 右上 **Restore to time** 按钮：

1. 选时间（默认窗口是 `[created_at, now]`，从 `GET /api/v1/databases/{id}/pitr-window` 取边界）
2. 可选改名（默认 `<name>_restored_<YYYYMMDD>`）
3. 提交 → 后台 `POST /api/v1/databases/{id}/pitr` → 新数据库出现在列表

**关键语义**：原数据库不动，恢复总是创建一个新数据库（Neon new branch 语义）。

### 4.2 SRE 直接调 API

```bash
# 拿到 tenant 的某个 token，或用 admin token 走 admin route
curl -sfX POST \
  -H "X-Tenant-Id: tn_abc123" \
  -H "Authorization: Bearer <tenant-token>" \
  -H 'Content-Type: application/json' \
  -d '{"target_time":"2026-05-22T10:30:00Z","new_db_name":"mydb_restored_oncall"}' \
  https://api.dbay.cloud:8443/api/v1/databases/<db_id>/pitr | jq .

# 响应：
# {
#   "new_db_id": "db_xxx",
#   "branch_id": "br_yyy",
#   "lsn": "0/A1B2C3D4",
#   "compute_endpoint": "postgresql://...",
#   "status": "ready"
# }
```

### 4.3 SRE 经 CLI（最快）

```bash
# 用 dbay-cli（pip install dbay-cli 已就绪）
dbay db pitr <db_id> --time '15min ago'
dbay db pitr <db_id> --time '2026-05-22T10:30:00Z' --new-name mydb_pre_incident
```

### 4.4 RDS 已挂、还想做 PITR

走 `dbay-rescue pitr`（**仅在 lakeon-api/RDS 不可用时使用**，正常情况下用上面三种）：

```bash
dbay-rescue pitr --db <db_id> --time '10min ago' --tenant <tenant_id>
# 输出：
#   Located: neon_tenant=... timeline=tl_...
#   LSN at 2026-05-22T...: 0/A1B2C3D4
#   New timeline created: tl_<new>
#     Ancestor: tl_<old> @ 0/A1B2C3D4
# Next step: run emergency-mount on this tenant to get a connection string.
```

它**不写 RDS**（因为正在挂）。要让用户连上，紧接着走 [场景 2](#场景-2rds-挂但某用户急用-l5) 的 `emergency-mount`。

恢复后 `rebuild-metadata` 会从 OBS manifest 把这次新建的 branch 拉进 RDS（如果 `dbay-rescue pitr` 之后更新了 manifest）。

---

## 场景 5：季度灾难恢复演练

**目标**：每季度跑一次，验证 `dbay-rescue rebuild-metadata` 真的能从 OBS 恢复出可用的 RDS。

> 自动化演练（CI 每周/定时）属于 Phase 2，本节描述**手工流程**。

### Step 1 — 选演练时间窗 & 通知

- 与值班 SRE 排好，提前 1 周通知客户成功 / 工程团队
- 选低峰期（华东工作日 22:00+ 或周末上午）
- 演练**完全在 staging** 进行，不动 prod

### Step 2 — 准备演练资源

```bash
# 1. Snapshot prod OBS bucket 到 drill bucket（华为云 OBS 同 region cp）
obsutil cp -r obs://lakeon-prod/tenants/ obs://lakeon-drill/tenants/
obsutil cp -r obs://lakeon-prod/_global/  obs://lakeon-drill/_global/

# 2. 起空的演练 RDS（与场景 1 Step 3 完全相同）
export DRILL_RDS_DSN='postgres://drill:<pwd>@drill-rds:5432/lakeon?sslmode=require'

# 3. 跑 Flyway
cd lakeon-api && mvn flyway:migrate \
  -Dflyway.url="jdbc:postgresql://drill-rds:5432/lakeon" \
  -Dflyway.user=drill -Dflyway.password='<pwd>'
```

### Step 3 — 用演练凭据跑 rebuild

`~/.dbay/rescue-credentials-drill.yaml`：

```yaml
obs:
  endpoint: obs.cn-east-3.myhuaweicloud.com
  bucket:   lakeon-drill         # ← 注意是 drill bucket
  access_key: <drill AK>
  secret_key: <drill SK>
```

```bash
# 计时
time dbay-rescue --creds ~/.dbay/rescue-credentials-drill.yaml \
  rebuild-metadata --to "$DRILL_RDS_DSN"
```

记录：

- 总 tenant 数 vs `ok=` 数 → 必须 `failed=0`
- 总耗时 → 应 < 30 min 才算达标，否则 P2 优化任务
- `failed > 0` → 把失败 tenant 一一查清，每一类都开 ticket

### Step 4 — 抽样 PITR 验证

```bash
# 从 drill RDS 抽 5 个 tenant，每个挑 1 个 db
psql "$DRILL_RDS_DSN" -c "
  SELECT t.id, di.id FROM tenants t
  JOIN database_instances di ON di.tenant_id = t.id
  WHERE di.status = 'ACTIVE' ORDER BY random() LIMIT 5;
"

# 对每一行：
dbay-rescue --creds ~/.dbay/rescue-credentials-drill.yaml \
  pitr --db <db_id> --time '1h ago' --tenant <tenant_id>

# 然后 emergency-mount 起来跑一句 SELECT 1
dbay-rescue --creds ~/.dbay/rescue-credentials-drill.yaml \
  emergency-mount --tenant <tenant_id> --owner <email> --hours 1
```

### Step 5 — 演练后

- 删 drill RDS 实例（避免占费用）
- 删 drill 命名空间下所有 `app=emergency-mount` pod（清理 job 应该已经做了，手动确认）
- 把演练时间 / `ok`/`failed` 数 / 耗时 / 发现的问题写到 `docs/sre/drill-history/<YYYY-QN>.md`
- 任何**新发现的占位符 / 字段缺失**都加进本 runbook 的场景 1 Step 6 表格

---

## 已知限制

下列场景**目前不在恢复能力范围内**（设计上的取舍，见 spec `docs/superpowers/specs/2026-05-21-dbay-data-recovery-design.md` "非目标" 节）：

| 级别 | 场景 | 原因 | 临时缓解 |
|------|------|------|---------|
| **L6** | OBS region 整挂 | 未开 Cross-Region Replication | 接受云提供商级故障；事故复盘后讨论是否开 CRR |
| **L7** | OBS 对象被勒索 / 恶意删 | 未开 Versioning / Object Lock | 加强 OBS bucket policy 和审计；break-glass 凭据严格隔离 |
| — | 持续 WAL 重放热备副本（秒级 RTO） | Phase 3 设计 | 短期靠 PITR + emergency-mount 凑合 |
| — | 自动定时演练（dry-run rebuild） | Phase 2 设计 | 走 [场景 5](#场景-5季度灾难恢复演练) 手工 |

### Phase 1 恢复后的元数据缺口（场景 1 Step 6 详表）

Manifest 仅承载下列字段，**不是 RDS 的完整快照**：

| Manifest 含 | Manifest 不含 |
|-------------|--------------|
| `tenant_id`, `owner_email`, `created_at`, `updated_at` | `tenants.name`, `tenants.api_key` |
| 每个 database：`db_id`, `name`, `neon_tenant_id`, `timeline_id`, `created_at`, `deleted_at` | `compute_size`, `suspend_timeout`, `storage_limit_gb`, `environment_variables` |
| 每个 branch：`branch_id`, `parent`, `lsn` | `branches.name` (原名), `branches.is_default` |
| — | `users`, `user_tenant_membership`, `operation_logs`, 计费 / 审计辅助表 |

**rebuild 后必做的检查清单**：

- [ ] 每个 `database_instances` 至少有一个 `is_default=TRUE` 的 branch
- [ ] 所有 `tenants.api_key` 形如 `REBUILT-*` 的全部轮换
- [ ] `branches.name` 形如 `rebuilt@*` 的，跟用户确认/重命名
- [ ] `compute_size` / `suspend_timeout` / `storage_limit_gb` 按计费等级回填（如有）
- [ ] `users` 表是否需要从备份/审计日志/SSO 反建
- [ ] 通报客户成功团队：哪些字段需要用户配合补齐

---

## 附录 A：常用一行命令

```bash
# OBS 健康自检
dbay-rescue list-tenants | head -5

# 单个邮箱有哪些租户
dbay-rescue owner-lookup --email alice@example.com

# PITR 到 5 分钟前（API 健康时用 CLI，比 rescue 快）
dbay db pitr <db_id> --time '5min ago'

# RDS 挂时绕过 lakeon-api 做 PITR
dbay-rescue pitr --db <db_id> --time '5min ago' --tenant <tenant_id>

# 紧急直连（默认 1h 只读）
dbay-rescue emergency-mount --tenant <tn> --owner <email>

# RDS 整库重建
dbay-rescue rebuild-metadata --to "$NEW_RDS_DSN"

# 自定义凭据路径
dbay-rescue --creds /path/to/creds.yaml <subcommand>
```

## 附录 B：相关文档

- **设计文档**：`docs/superpowers/specs/2026-05-21-dbay-data-recovery-design.md`
- **实施计划与任务跟踪**：`docs/superpowers/plans/2026-05-21-dbay-data-recovery-plan.md`
- **PITR API 与 schema 演进**：`lakeon-api/src/main/java/com/lakeon/controller/RecoveryController.java`，`lakeon-api/src/main/resources/db/migration/V40__add_recovered_from_pitr.sql`
- **rebuild 字段映射源**：`dbay-rescue/internal/rds/client.go` 顶部注释（权威）
- **Console UI 路径**：`lakeon-console/src/views/databases/DatabaseDetailView.vue`（Restore 按钮）
- **运维通用 ops 文档**：`docs/ops-guide.md`
