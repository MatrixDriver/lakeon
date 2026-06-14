# LakebaseFS 特性与 lakeFS 对比

本文记录 LakebaseFS（简称 LBFS）当前已经实现的能力、目录类型与后台处理任务，并分析它和 lakeFS 的定位差异。本文基于当前 `main` 分支实现（`333bddde`）以及本地 `~/code/lakefs` 代码与 README。

## 1. LBFS 的定位

LakebaseFS 是 DBay/Lakeon 面向 DataAgent 和用户数据目录的通用文件接入层。它的核心目标不是做一个独立的数据湖版本控制系统，而是让任意本地目录以 `mount` 或 `sync` 模式接入 DBay，并根据目录类型触发不同的后台异步处理，最终成为 DataAgent 可以读取、分析和生成报告的数据上下文。

典型场景：

- DataAgent / opencode / Codex / Claude Code / OpenClaw 的 home 目录接入 LBFS，pod 不再强依赖本地 EVS 盘，便于灵活启动、迁移和恢复上下文。
- 用户把本地 CSV、Excel、Parquet、JSONL 等数据目录同步到 LBFS，在 console 里交给 DataAgent 做自然语言分析、报告和 dashboard。
- 用户把 Iceberg 表、Lance 表等目录接入 LBFS，后续由表类型 worker 派生表元数据、schema、统计信息和检索入口。
- 外部客户把任意目录作为一个可服务的文件空间接入 LBFS，供 agent、工具链或业务系统读写。

## 2. 客户端能力

LBFS 客户端位于 `dbay-fuse`，目前提供以下入口：

| 命令 | 用途 | 当前行为 |
| --- | --- | --- |
| `mount` | 把 LBFS 挂成本地 POSIX 视图 | 本地 passthrough 到 state 目录，写入进入 outbox，后台异步上传；默认启动不 pull 远端内容，可用 `--pull-on-startup` 显式打开 |
| `sync` | 把已有本地目录作为源目录接入 LBFS | 原目录仍是唯一完整本地副本，`~/.dbay/sync/<folder>/` 只保存 metadata、队列和临时文件；watch 模式负责发现变化，上传仍依赖 outbox/uplink drain |
| `import` | 一次性导入目录 | 使用和 `sync` 相同的 planner，执行后退出 |
| `pull` | 从远端拉取目录内容 | 用于显式同步远端内容到本地 ledger/state |
| `inspect` | 本地目录类型建议 | 根据目录结构和文件扩展名推荐 `directory_kind` |

近期性能验证结果（本机 + 生产 API，作为实现观测值，不是 SLA）：

| 指标 | 结果 |
| --- | --- |
| `mount` 默认初始化 | p50 约 765ms，p95 约 857ms；默认不做 startup pull |
| `sync` 初始化返回 | p50 约 268ms，p95 约 331ms |
| `mount` 本地 4KB 写入 | p50 约 0.95ms，p95 约 1.35ms；flush/release 已异步化，`fsync` 仍保持同步语义 |
| `mount` 云端可见延迟 | 稳态 p50 约 165ms，p95 约 583ms |
| `sync` 本地写入 | p50 约 0.84ms，p95 约 2.36ms |
| `sync + drain` 云端可见延迟 | p50 约 1.18s，p95 约 1.37s |

## 3. 目录类型、存储策略与异步任务

LBFS 的目录接入由三个 profile 维度描述：

- `directory_kind`：目录是什么。
- `storage_policy`：字节应该如何存储。
- `processing_profile`：后台应该派生什么。

当前所有目录类型都可以用 `mount` 和 `sync` 接入。需要注意的是：当前真实物理存储仍是每租户 Lakebase/Postgres/Neon 数据库里的 `files` 表，文件内容存入 `BYTEA`。`storage_policy` 目前主要是 profile 元数据和后续路由意图，OBS 对象层、`object-first`/`object-only` 的真实分层，以及 `table-native` 的物理表原生映射还没有落地。

| 目录类型 | 适用目录 | 默认存储策略 | 当前实际存储 | 默认处理 profile | mount/sync | 当前异步处理 |
| --- | --- | --- | --- | --- | --- | --- |
| `codex-home` | Codex home、`AGENTS.md`、skills 等 | `auto` | Lakebase/Postgres `BYTEA` | `agent-home` | 支持 | 由 forwarder 读取 `lbfs_events`，白名单过滤后调用 memory-svc `/lbfs/derive`，沉淀 agent home 里的可记忆内容 |
| `claude-home` | Claude Code home、`CLAUDE.md`、projects 等 | `auto` | Lakebase/Postgres `BYTEA` | `agent-home` | 支持 | 同上；当前自动 memory base 名称仍是 `lbfs-claude`，属于 MVP 遗留命名 |
| `openclaw-home` | OpenClaw home | `auto` | Lakebase/Postgres `BYTEA` | `agent-home` | 支持 | 同 agent-home 派生链路 |
| `opencode-home` | opencode home 或 workspace | `auto` | Lakebase/Postgres `BYTEA` | `agent-home` | 支持 | 同 agent-home 派生链路，是 DataAgent/opencode 场景的核心类型 |
| `data-dir` | CSV、TSV、Excel、Parquet、ORC、Arrow、JSONL 等普通数据目录 | `object-first` | Lakebase/Postgres `BYTEA` | `dataset` | 支持 | 记录 `lbfs_auto_job`；当前 worker 识别数据文件类型并返回 metadata observed/queued 状态，尚未真正持久化 schema、统计信息和 profile |
| `iceberg-table` | Iceberg 表目录，含 `metadata/*.metadata.json` 或 avro | `table-native` | Lakebase/Postgres `BYTEA` | `iceberg` | 支持 | 记录 `lbfs_auto_job`；当前 worker 接收 Iceberg event，尚未解析 snapshot、manifest、schema 和 partition spec |
| `lance-table` | Lance 表目录，含 `_versions`、`_fragments`、`_indices` 或 `.lance` | `table-native` | Lakebase/Postgres `BYTEA` | `lance` | 支持 | 记录 `lbfs_auto_job`；当前 worker 接收 Lance event，尚未解析 fragments、schema、index 和向量检索元数据 |
| `files` | 任意普通文件目录 | `auto` | Lakebase/Postgres `BYTEA` | `none` | 支持 | 默认跳过 profile-specific processing；只作为文件空间接入 |

## 4. LBFS 自动任务触发机制

写入 LBFS 后，租户库中的 `lbfs_events` 会产生 pending 事件。`LakebaseFSEventForwarder` 每 30 秒扫描 READY 租户，并通过租户级 leader lock 避免多个 API 副本重复处理同一批事件。

处理路径分两类：

- `agent-home`：经过路径白名单过滤后调用 memory-svc `/lbfs/derive`。这条链路用于把 agent home 里的说明、记忆、项目上下文等沉淀进记忆库；它不走 `lbfs_auto_job` 表。
- `dataset`、`iceberg`、`lance`、`none`：由 `LakebaseFSProcessingRouter` 分发到对应 worker，并通过 `LBFSAutoJobRecorder` 记录 `lbfs_auto_job`。当前 job key 由 tenant、folder、source path、source etag 和 profile 去重，能够避免同一个文件同一个 etag 被无限重复记录。

当前自动任务已经具备“按目录类型路由、重试、记录状态”的骨架，但 dataset/table 的深度处理还没有完成。下一步应把这些 worker 从“事件接收”推进到“真实派生产物”：

- dataset：读取内容，生成 schema、列类型、样本、行数、质量检查、profile summary，并在 console/DataAgent 可查询。
- Iceberg：解析 metadata、snapshot、manifest、partition spec、schema evolution，并生成 DataAgent 可引用的数据源描述。
- Lance：解析 schema、fragment、index、向量列和版本信息，并生成检索/分析入口。
- agent-home：把 `lbfs-claude` 这类 MVP 遗留命名改成通用 `lbfs-agent-home` 或按 folder/kind 命名，并把派生状态暴露给 console。

## 5. lakeFS 的定位

本地 `~/code/lakefs/README.md` 把 lakeFS 定位为 “Data Version Control (Git for Data)”。它把对象存储变成 Git-like repository，用 branch、commit、merge、diff、rollback、hook 等能力管理数据湖。

lakeFS 的核心特征：

- 底层面向对象存储：AWS S3、Azure Blob Storage、Google Cloud Storage。
- 对外提供 S3 API 兼容能力，能接入 Spark、Hive、Athena、DuckDB、Presto 等数据生态。
- 重点解决数据湖上的可重复、原子化、版本化操作。
- 支持数据 pipeline 的 dev/test 分支、Write-Audit-Publish、回滚、时间旅行、审计和治理。
- 本地挂载不是它的开源核心 POSIX agent-home 场景；代码里 Web UI 的 mount modal 使用 `everest mount "lakefs://repo/ref/path" <local-dir>`，属于 lakeFS Cloud/Enterprise 方向。

## 6. LBFS 与 lakeFS 的差异

| 维度 | LBFS | lakeFS |
| --- | --- | --- |
| 核心抽象 | 用户声明的目录、文件空间、DataAgent 上下文 | 数据湖 repository、branch、commit、merge |
| 主要目标 | 把 agent home 和用户文件目录接入 DBay，触发异步派生，服务自然语言分析 | 管理对象存储中的数据版本、发布流程、回滚和治理 |
| 底层存储现状 | 当前存 Lakebase/Postgres/Neon `BYTEA`；OBS/table-native 是计划能力 | 对象存储为核心，S3/Azure/GCS |
| 本地体验 | `mount` 提供 POSIX 视图，`sync` 接入已有目录 | 主要通过 API/S3 gateway/数据工具；本地 mount 是 Everest/Cloud/Enterprise 方向 |
| 版本控制 | 目前没有 branch/commit/merge/time travel | 核心能力就是 Git-like data version control |
| 异步处理 | 按目录类型触发 agent memory、dataset/table metadata 派生 | hooks/actions 用于数据质量门禁、CI/CD、catalog export 等 |
| Agent 适配 | 面向 Codex、Claude Code、OpenClaw、opencode home 和 DataAgent | 不直接管理 agent home，也不做 agent memory 派生 |
| 数据生态 | 目标是让 DataAgent 在 console 中理解和分析接入目录 | 强项是 Spark、Hive、Athena、DuckDB、Presto 等数据湖生态 |
| 最小接入单位 | 本地任意目录或 LBFS folder | 对象存储 repository/ref/path |
| 用户心智 | “把我的目录接给 DBay/DataAgent” | “像 Git 管代码一样管理数据湖版本” |

## 7. 适用场景判断

优先用 LBFS 的场景：

- DataAgent 的 home 目录需要持久化、迁移和恢复，但不想给每个 pod 绑定 EVS 盘。
- 用户从 Mac、本地服务器或工具链把 CSV、Excel、JSONL、Parquet 等文件接入 DBay，让 console 里的 DataAgent 做自然语言分析。
- agent 的 instructions、memory、workspace 文件需要成为可派生、可检索、可审计的上下文。
- 需要对不同目录类型触发不同后台任务，例如 agent-home 进记忆派生、data-dir 做 dataset profile、Iceberg/Lance 做表元数据。
- 需要一个面向外部客户的通用 FS 接入服务，支持任意目录的 mount/sync。

优先用 lakeFS 的场景：

- 数据已经主要在 S3、Azure Blob、GCS 等对象存储中，需要数据湖级版本管理。
- 需要 branch、commit、merge、diff、rollback、time travel。
- ETL/ELT pipeline 需要 dev/test/prod 分支隔离和 Write-Audit-Publish。
- 需要和 Spark、Hive、Athena、DuckDB、Presto 等现有数据处理框架深度兼容。
- 需要在发布到生产数据前跑数据质量 gate、hook、审计流程。

同时使用两者的场景：

- lakeFS 管对象存储上的生产数据湖版本和治理；LBFS 管 DataAgent 的 home、用户临时文件和 console 侧自然语言分析入口。
- DataAgent 通过 LBFS 获得工作目录、记忆和用户上传文件，同时把 lakeFS repo/branch/path 注册为一个外部数据源。
- Iceberg/Lance 等大表数据继续放在对象存储或 lakeFS 管理的数据湖里；LBFS 只保存引用、缓存、profile 和 DataAgent 可理解的派生产物。

## 8. 对 DBay/DataAgent 的产品建议

LBFS 不应该短期内重复 lakeFS 的完整版本控制系统。更合理的路线是：

1. 把 LBFS 做成 DataAgent 的默认文件与上下文层：稳定 mount/sync、状态可见、失败可恢复。
2. 把 `agent-home`、`dataset`、`iceberg`、`lance` 的自动任务补成真实派生产物，并在 console 上展示 job、profile、schema、样本、报告入口。
3. 落地对象层存储：小文件可继续 inline，大文件进入 OBS，对 Iceberg/Lance 走 table-native 或外部引用。
4. 把 lakeFS 作为可接入的数据湖版本源，而不是竞争性重写。LBFS 可以新增一种 external data source/profile，引用 lakeFS 的 repo/ref/path。
5. 当 DBay 自身需要 workspace/version 语义时，只在 DataAgent 工作区层做轻量版本快照；不要一开始复制 lakeFS 的 branch/merge/object-store GC 全套能力。

结论：LBFS 和 lakeFS 不是同一层产品。lakeFS 是数据湖对象存储的版本控制与治理层；LBFS 是 DBay/DataAgent 的目录接入、文件上下文和异步派生层。对 DataAgent 来说，LBFS 是入口和工作空间；lakeFS 更适合作为大规模数据湖数据源和版本化治理后端。
