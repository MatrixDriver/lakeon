# DBay 生产就绪 & 增长策略

> 2026-03-14 整理，用于规划下一阶段工作重点

---

## 一、生产级大规模服务差距分析

### 1. 高可用 & 容灾（最关键）

| 现状 | 生产要求 |
|------|----------|
| Pageserver 单副本 | **SPOF**，宕机所有数据库不可用 |
| API 单副本 + hostNetwork | 发布时全量中断 |
| Safekeeper CCE 上仅 1 副本 | 设计是 3 副本 quorum，当前无冗余 |
| 单节点 4C/8G | 无法承载大量 compute pod |
| 单 AZ 部署 | 机房级故障无法恢复 |

需要：多节点集群、多 AZ、Pageserver 主备或分片、API 多副本 + Ingress（去掉 hostNetwork）

### 2. 弹性 & 性能

- **冷启动 15-20s 太慢** — 竞品 Neon Cloud 已做到 ~500ms（通过预热 VM pool）
- **没有连接池** — 需要 PgBouncer/Supavisor，否则高并发连接数会压垮 compute
- ~~没有 auto-scaling~~ — ✅ 已通过弹性节点池解决 (2026-03-15)
- **没有读副本** — 大规模读场景没有分流能力（方案已设计，见第六章）
- **Pageserver 单点瓶颈** — 所有租户共享一个 pageserver，IO 会成为天花板（方案已设计，见第六章）

### 3. 安全加固

- **租户注册无认证** — 任何人可以无限创建租户，容易被滥用
- **没有 Rate Limiting** — API 无限流，容易被 DDoS
- **没有 WAF** — SQL 注入、恶意请求无防护
- **密码不可重置** — 用户丢了密码只能删库重建
- **没有网络隔离** — 租户间 compute pod 在同一个 flat namespace，理论上可互通
- **没有 IP 白名单** — 数据库连接无源 IP 限制

### 4. 计费 & 商业化

- **没有真正的计费系统** — 当前只有成本预估，没有用量计量 → 出账 → 扣费链路
- **没有支付集成** — 无法收费
- **没有套餐/订阅** — Free / Pro / Enterprise 分级
- **没有资源配额强制执行** — quota 只是数字，超限不阻止
- **没有用量告警** — 用户不知道自己快超限了

### 5. 运维成熟度

- **没有 CI/CD** — 手动脚本部署，容易出错（已有教训）
- **没有蓝绿/金丝雀发布** — 发布即全量切换
- **没有自动化备份/恢复** — 虽然 Neon 天然有 PITR 能力，但没有暴露给用户
- **没有 SLA 监控** — 不知道可用性是几个 9
- **证书续期手动** — acme.sh 需要定期手动更新

### 6. 合规

- **没有数据加密说明** — 传输加密有了(TLS)，静态加密依赖 OBS 默认
- **没有审计日志导出** — 合规审计需要
- **没有服务条款 / 隐私政策** — 商用必备
- **没有等保测评** — 国内云服务需要

---

## 二、快速获客策略

### 高 ROI 动作（1-2 周可落地）

**1. 慷慨的免费层**
- 1 个免费数据库、512MB 存储、auto-suspend 后 0 成本
- "注册即用，不需要信用卡"
- 这是 Neon/Supabase/PlanetScale 获客的核心策略

**2. 中国本土化优势（最大差异化）**
- **数据在中国** — 合规、低延迟，Neon/Supabase/Vercel Postgres 都做不到
- 强调"国内数据库，国内存储，符合数据出境合规"
- 对接微信登录/手机号注册（降低注册门槛）

**3. 一键体验 Demo**
- 在落地页加"30 秒体验"按钮 — 自动创建租户+数据库，直接展示 SQL 编辑器
- 不要求注册，先用后注册

**4. 开发者内容营销**
- "5 分钟从零部署一个 Next.js + DBay 全栈应用" 教程
- "如何用数据库分支做安全的数据库迁移" 博文
- 发到掘金、思否、V2EX、知乎

**5. 框架集成模板**
- Prisma / Drizzle ORM 连接示例
- Spring Boot / Go / Python 快速接入 SDK
- Vercel / Railway / 1Panel 一键部署模板

### 中期动作（1-2 月）

**6. Query Console 增强**
- 在 Web 控制台内嵌 SQL 编辑器（已有基础）
- 加 Schema 可视化、Query History、AI SQL 助手
- 让用户不需要离开平台

**7. Database Branching 作为杀手功能营销**
- "给你的数据库创建 Git 分支" — 对开发者很有吸引力
- CI/CD 集成：PR 自动创建数据库分支，合并后自动删除
- 对标 Neon Branching，但在中国能用

**8. 对接国内云生态**
- 华为云 Marketplace 上架
- 与国内 PaaS 平台合作（Sealos、1Panel 等）
- 阿里云/腾讯云 Marketplace（如果未来多云）

---

## 三、竞品对比定位

> **"中国开发者的 Neon" — Serverless PostgreSQL，数据在中国，按需付费，秒级分支**

| 特性 | Neon | Supabase | DBay |
|------|------|----------|------|
| 数据在中国 | - | - | + |
| Serverless 自动休眠 | + | - | + |
| 数据库分支 | + | - | + |
| 免费层 | + | + | + |
| 中文支持 | - | - | + |
| 合规（数据不出境）| - | - | + |

---

## 四、建议优先级

如果资源有限，先做这五项（投入产出比最高）：

1. **免费层** — 获客核心
2. **一键体验** — 降低试用门槛
3. **冷启动优化** — 用户体验底线
4. **Rate Limiting** — 安全底线
5. **CI/CD** — 运维底线

---

## 五、弹性方案验证结论 (2026-03-14)

### CCE Autopilot 实测 — 不可行

在 AP 集群 `dbay-cceap` (ee92a460) 上实测：

| 测试项 | 结果 |
|--------|------|
| `ulimit -c` | `10485760` (~5GB)，不是 unlimited |
| `ulimit -c unlimited` | `Operation not permitted` |
| privileged 模式 | `Gatekeeper 拦截: Privileged container is not allowed` |
| DaemonSet 修改 containerd | AP 不支持 DaemonSet |
| 小镜像冷启动 (busybox 2MB) | **~19s** (调度 13s + 拉取 0.9s) |
| 大镜像冷启动 (compute-node 450MB) | **~34s** (调度 13s + 拉取 18s) |
| 镜像缓存 | **无效** — 每次分配新 VM，镜像重新拉取 |

**结论**: compute_ctl 的 setrlimit(CORE, INFINITY) 在 AP 上必定失败，且冷启动 34s 不可接受。

### CCI 直接 API — 同样阻塞

setrlimit 问题与 AP 相同，CCI Kata VM 的 core limit 也不是 unlimited。

### 最终方案：CCE Standard + 弹性节点池

**零代码改动即可使用**（已完成 nodeSelector 配置化支持）：

架构：
```
固定节点 (1-2 台, 常驻)           弹性节点池 (0~N 台, 自动伸缩)
├─ lakeon-api                     ├─ compute-db_001
├─ pageserver                     ├─ compute-db_002
├─ safekeeper                     ├─ ...
├─ proxy                          └─ (空闲时缩到 0)
└─ storage-broker
```

配置步骤：
1. CCE 控制台创建节点池 `compute-pool`，标签 `lakeon/role=compute`，弹性伸缩 min=0 max=N
2. 安装 autoscaler 插件
3. Helm values 设置 `api.computeNodeSelector: { "lakeon/role": "compute" }`
4. DaemonSet `lakeon-node-init` 自动在新节点修复 containerd ulimit

启动时间保持不变：冷启动 ~3s，热启动 ~100ms。

---

## 六、读副本 & Pageserver 水平扩展方案

### 6.1 读副本（优先级：高，改动量：2-3 天）

Neon 的 compute 配置原生支持 `mode: Replica`，当前 Lakeon 硬编码为 `Primary`。

**架构：**
```
写请求 → Proxy → Primary Compute (读写) → Pageserver
读请求 → Proxy → Replica Compute (只读) → Pageserver (同一个 timeline)
```

Replica compute 连接同一个 pageserver 和 timeline，通过 WAL 流复制实时同步，不需要额外存储。

**实现要点：**

| 改动 | 内容 |
|------|------|
| API | `POST /api/v1/databases/{id}/replicas` 创建读副本 |
| ComputePodManager | `generateComputeConfig()` 的 `mode` 从硬编码 `"Primary"` 改为参数传入 |
| Compute Pod | Replica pod 连同一个 pageserver + safekeeper，PG 以 hot_standby 运行 |
| Proxy | Neon Proxy 原生支持按 endpoint 路由，replica 有独立 endpoint |
| 数据模型 | `database_instances` 加 `replica_of` 字段，或新建 `replicas` 表 |

**关键代码改动点：**
- `ComputePodManager.java:508` — `spec.put("mode", "Primary")` 改为参数化
- `NeonApiClient.java` — 无需改动，replica 不需要额外 Neon 资源
- `DatabaseService.java` — 新增 `createReplica()` / `deleteReplica()` 方法

**用户价值：** "一键读写分离" 是差异化卖点，适合读多写少的 OLTP 场景。

### 6.2 Pageserver 租户分片（优先级：中，改动量：1-2 周）

Neon 的 `location_config` API 支持将不同租户分配到不同 pageserver 实例。

**架构：**
```
当前 (单 pageserver):
  所有租户 → Pageserver → OBS

分片后 (多 pageserver):
  租户 A,B,C → Pageserver-0 → OBS
  租户 D,E,F → Pageserver-1 → OBS
  租户 G,H,I → Pageserver-2 → OBS
```

**实现要点：**

| 改动 | 内容 |
|------|------|
| Helm Chart | Pageserver 从 Deployment 改为 StatefulSet (多实例) |
| NeonApiClient | `createTenant()` 时指定目标 pageserver URL |
| 租户分配 | 轮询/按负载，记录到 `tenants.pageserver_id` 字段 |
| Compute Config | `pageserver_connstring` 从全局值改为按租户查找 |
| 健康检查 | 每个 pageserver 实例独立监控 |

**Helm 变化：**
```yaml
# pageserver 改为 StatefulSet，每个实例有独立 DNS:
# pageserver-0.pageserver-headless.lakeon.svc.cluster.local
# pageserver-1.pageserver-headless.lakeon.svc.cluster.local
```

**关键代码改动点：**
- `NeonApiClient.java` — `createTenant()` 接受 pageserver URL 参数
- `ComputePodManager.java:500` — `pageserver_connstring` 从全局改为按租户
- `DatabaseService.java` — 创建数据库时选择目标 pageserver
- `TenantEntity` 或 `DatabaseEntity` — 新增 `pageserverId` 字段
- 在线迁移：通过 `location_config` API 的 `Secondary` → 追赶 → 切换

**触发时机：** 单 pageserver 可支撑约 50-100 个活跃租户。超过此规模时实施。

### 6.3 优先级总结

| 方案 | 用户价值 | 改动量 | 触发条件 |
|------|---------|--------|---------|
| **读副本** | 高 — 读写分离是常见需求 | 小 (2-3天) | 产品功能，可随时做 |
| **Pageserver 分片** | 中 — 扩展内部瓶颈 | 大 (1-2周) | 活跃租户 >50 时 |
