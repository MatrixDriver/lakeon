# LakebaseFS Mount 模式韧性增强设计（ETag 冲突检测 + 下行拉取）

日期：2026-05-19
作者：@jacky
状态：Draft，待 review

---

## 1. 背景

`dbay-fuse` 当前的 mount 模式（passthrough + outbox + 异步 uplink）已在生产可用，但 user-guide §9 列出的几个客户端缺陷一直挂着：

- §9.2 跨设备同改同一文件 → last-write-wins，无感知
- §9.3 session.jsonl 并发 append 走全量 put（**实际已通过 `append_state` 解决，但文档未更新**）
- §12 Phase 5 "Cache-miss 下行拉取"未实装

本设计聚焦剩余两件实事：**跨设备 ETag 冲突检测** 与 **下行拉取**。文档同步修正过时描述。

不在范围：sync 模式（独立 spec `2026-04-23-lakebasefs-sync-vs-mount.md`）、3-way merge、占位文件、macOS File Provider。

---

## 2. 现状盘点

| 能力 | 服务端 | 客户端（mount 默认路径） | 客户端（inmem 实验路径） |
|---|---|---|---|
| `/files/put` 接受 `if_match` | ✅ `LakebaseFSService.java:127` | ❌ uplink 不传 | ✅ `put_strict` 传 |
| `/files/append` 接受 `if_match` | ❌ 当前 append 实现忽略 if_match | ❌ uplink 不传 | n/a |
| `/files/batch` 接受 per-op `if_match` | ❌ controller 第 168 行没读 | ❌ | n/a |
| 启动时下行同步 | n/a | ❌ state_dir 空开始 | ❌ lazy GET on access |
| Etag 本地持久化（ledger） | n/a | ❌ | 部分（inmem 内存中保存） |

**结论**：服务端只需做两处小扩展（append 的 if_match、batch 的 per-op if_match）；客户端要新增 etag ledger 与 pull 子命令。

---

## 3. 任务 A — ETag 冲突检测

### 3.1 数据模型：etag ledger

位置：`~/.dbay/sync-ledger/<agent>/etags.db`

```sql
CREATE TABLE etag_ledger (
  path        TEXT PRIMARY KEY,    -- 虚拟路径，如 "/CLAUDE.md"
  etag        TEXT NOT NULL,       -- 上次成功同步时服务端返回的 etag
  size        BIGINT NOT NULL,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_etag_ledger_updated ON etag_ledger(updated_at);
```

写时机：
- 成功 PUT / APPEND（uplink）→ 用响应里的 new etag upsert
- 成功 GET（pull 或 inmem 缓存）→ upsert
- 删除（DELETE op）→ 删行

读时机：
- uplink 即将发出 PUT / APPEND / batch-op 前

崩溃恢复：SQLite 的 WAL + fsync 保证已写入 ledger 的 etag 持久；崩溃后下一次 uplink 会重新读 ledger。如果 ledger 文件被删除或损坏，等同于"零状态"——uplink 不带 if_match，行为退化到现状（无冲突检测），不会比当前更差。

### 3.2 服务端改动

**A1**：`LakebaseFSService.append(...)` 增加 `String ifMatch` 参数，进入事务后比对 `existing.etag`，不匹配抛 `BadRequestException("precondition_failed")`。

**A2**：`LakebaseFSController.appendFile` 从 body 读 `if_match` 字段透传给 service。

**A3**：`LakebaseFSController.batch` 的每个 op item 支持可选 `if_match`（put / append / delete 三类），controller 解析后透传给对应 service 方法。**返回值结构扩展**：每个 op 的结果带 `etag`（新版本）、`status`（`ok` 或 `precondition_failed`）。

**A4**：batch 整体语义微调——目前 batch 在事务中执行，**一个 412 是否回滚全部？** 决策：**保持单 op 失败不影响其他 op 的语义**（每个 op 独立短事务）。理由：uplink 的 batch 可能跨多个文件，一个文件冲突不应阻塞其他。需要确认 controller.batch 当前实现是否已是 per-op tx；如果是大事务，要拆。

### 3.3 客户端改动

**A5**：新增 `dbay-fuse/src/etag_ledger.rs`
- 用 `rusqlite`（已在 Cargo.lock？需确认）
- 公开 API：`get(path) -> Option<EtagEntry>`、`upsert(path, etag, size)`、`forget(path)`、`open(agent) -> Ledger`

**A6**：`uplink_worker.rs::send_batch` 改动
- 构造 op json 时，查 ledger，若有则加 `if_match` 字段
- batch 调用拿响应后，按 op 处理：
  - `status == "ok"`：upsert ledger（path, new_etag, size）
  - `status == "precondition_failed"`：触发冲突处理（§3.4），**不 ACK 该条 outbox entry**，让下一轮重试
  - 整 batch 失败（网络/HTTP 5xx）：保持现状（exp backoff 重试）

**A7**：`dbay_api.rs::lbfs_batch` 签名扩展返回 `Vec<BatchOpResult>`（含 status + etag），caller 才能 per-op 处理

### 3.4 冲突处理（客户端）

发生 412 时：
1. `lbfs_get(path)` 拉云端最新版本 + 新 etag
2. 把云端版本写到 state_dir，路径为 `<path>.conflict-from-<hostname>-<yyyy-MM-ddTHH-mm-ss>`
3. 在 `~/.dbay/conflicts/<agent>.log` 追加一行：`{ts, path, base_etag, server_etag, hostname, action: "saved_remote_as_<conflict_path>"}`
4. 把本地 pending 的 PUT（即 outbox entry）重新发出去 **不带 if_match**，作为新基线
5. 成功后 ledger 更新到新 etag
6. tracing::warn! 输出，让 daemon 日志能看到

**冲突策略选定**：远端版本另存为副本、本地版本作为新基线推上去。理由：
- 永不丢数据（两个版本都保留）
- 本地用户的最新意图被尊重
- 远端的"丢失版本"以文件形式可见，用户能 diff、merge

**替代方案对比**：
- "本地另存副本、远端覆盖本地"——会让用户已经在编辑器里看到的内容突然变成旧版，体验更差
- "暂停 uplink、阻塞等用户介入"——简单但破坏 daemon 异步语义

### 3.5 测试

**Rust 单测**（`dbay-fuse/tests/test_etag_ledger.rs`）
- ledger 基础 CRUD
- 路径含特殊字符

**E2E**（`tests/e2e/test_lbfs_etag_conflict.py`）
- 两个模拟客户端 X、Y 共用同一个 agent base
- X 写 v1（ledger=etag_v1）
- Y 直接调 API 写 v2（ledger=etag_v2 模拟另一台机器）
- X 在不更新 ledger 的情况下尝试 PUT v3 → 服务端返回 412
- 断言：云端是 v3 + X 的本地有 `*.conflict-from-X-*` 内容是 v2 + conflict log 有记录

---

## 4. 任务 B — 下行拉取（Phase 5）

### 4.1 子命令设计

```
dbay-fuse pull --agent <name> [--prefix <path>] [--include-large] [--dry-run]
```

参数：
- `--prefix`：只拉某个子树，默认 `/`
- `--include-large`：包含 >100MB 的文件，默认跳过（打 warn 不算错）
- `--dry-run`：列出会做的操作但不真执行

退出码：0 = 成功；2 = 部分文件失败（continue-on-error）；1 = 致命错误（连不上 API）

### 4.2 流程

```
1. lbfs_list(prefix, recursive=true) → Vec<LakebaseFSEntry>
2. for each entry:
     local = state_dir.join(entry.path[1:])
     ledger = etag_ledger.get(entry.path)
     match (local.exists(), ledger):
       (false, _) →
         lbfs_get(entry.path) → bytes
         write local, set mtime to entry.mtime
         ledger.upsert(entry.path, entry.etag, entry.size)
       (true, Some(l)) if l.etag == entry.etag →
         skip (already synced)
       (true, Some(l)) if l.etag != entry.etag →
         CONFLICT: lbfs_get → write to <local>.conflict-pull-<ts>
         本地原文件保留
         conflict log 追加一行
       (true, None) →
         本地有、ledger 没记录 → 视为"未上传的本地新文件"
         skip（uplink 之后会把它推上去）
3. 进度：每处理 50 条打一次 [N/M] 进度日志
4. 总结：synced=, skipped=, conflicts=, errors=
```

**为什么 "ledger 缺失 + 本地存在" 不下载远端覆盖**：避免 takeover 之前的本地真实新文件被远端旧版本覆盖。

### 4.3 mount 启动时默认跑 pull

`main.rs::Cmd::Mount` 加 `--skip-pull` 参数，默认 false。

mount 流程：
1. 解析配置
2. **如果未指定 `--skip-pull`：先跑一次 pull**（synchronous，daemon 不开始服务直到 pull 完）
3. 然后 spawn passthrough FS + uplink worker

理由：mount 是用户与系统的入口，启动时让 state_dir 反映远端状态最符合直觉。

失败处理：pull 失败时**不阻断 mount 启动**——把 pull 错误降级为 `tracing::warn!` 日志一行，继续 mount 流程。理由：用户可能在网络不可用情况下挂载，仍需可读本地缓存；启动期失败不应让人无法本地使用。

### 4.4 takeover 流程整合

`takeover.rs` 当前：备份原始目录 → rsync 进 state_dir → 写 rescan.trigger → 加 symlink。

改动：**在 backup 之前先跑 pull**。
- 这样 state_dir 里先有了远端文件
- rsync 把本地真实文件叠上去，本地新文件保留
- 写 rescan.trigger，让 uplink 把本地新文件推上去
- pull 期间发现的冲突文件 `.conflict-pull-*` 会被 rsync 一并推上云

**风险**：pull 拉的数据写到 state_dir，然后 rsync 又写——可能导致重复 mtime 变更触发 uplink。但 server 端的 `WHERE etag IS DISTINCT FROM` 幂等会吸收，可接受。

### 4.5 大文件策略

默认 `--include-large=false` 时，>100MB 的 entry：
- list 阶段不下载、不写本地
- ledger 也不更新（保持空）
- 输出一行 warn：`skipped large file path=/X size=200MB; use --include-large to fetch`

后果：之后用户在本地不指定 `--include-large` 重跑 pull 会一直跳过；如果 agent 尝试读该路径，FUSE 会看到 ENOENT，不会触发 lazy GET（passthrough 模式没有 lazy GET，inmem 模式才有）。这是已知 trade-off，避免笔记本一启动 mount 就拉几个 GB。

### 4.6 测试

**Rust 单测**（`dbay-fuse/tests/test_pull.rs`）
- 远端有、本地无 → 文件下载 + ledger upsert
- 双方都有且 ledger 一致 → skip
- 双方都有但 ledger 冲突 → `.conflict-pull-*` 产出
- 大文件跳过 + log 包含 "skipped large"

**E2E**（`tests/e2e/test_lbfs_pull.py`）
- 测试租户先通过 API 写 3 个文件
- 启动 dbay-fuse pull
- 断言 state_dir 出现这 3 个文件、内容一致、ledger 有 3 条
- 通过 API 改其中一个的内容
- 再 pull
- 断言本地内容更新

**E2E 跨流程**（`tests/e2e/test_lbfs_mount_resume.py`）
- 模拟"换机器"：用 agent X 上传若干文件 → 新建一个空 state_dir → mount（含默认 pull）→ 断言 agent 能在 mount point 看到全部文件

---

## 5. 文件改动清单

### 服务端
| 文件 | 改动 |
|---|---|
| `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSService.java` | `append(...)` 增加 `ifMatch` 参数与比对 |
| `lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSController.java` | `appendFile` 解析 `if_match`；`batch` 支持 per-op if_match + per-op 结果含 etag/status |

### 客户端
| 文件 | 改动 |
|---|---|
| `dbay-fuse/Cargo.toml` | 加 `rusqlite = { version = "0.31", features = ["bundled"] }` 若未加 |
| `dbay-fuse/src/etag_ledger.rs` | **新建**：ledger CRUD |
| `dbay-fuse/src/dbay_api.rs` | `lbfs_batch` 返回 `Vec<BatchOpResult>`；`lbfs_get` 已存在；扩展 `lbfs_append` 支持 if_match 选项 |
| `dbay-fuse/src/uplink_worker.rs` | send_batch 注入 if_match + per-op 处理 412 + 冲突分支 |
| `dbay-fuse/src/pull.rs` | **新建**：pull 主逻辑 |
| `dbay-fuse/src/main.rs` | 加 `Cmd::Pull`；`Cmd::Mount` 加 `--skip-pull`、启动时调 pull |
| `dbay-fuse/src/takeover.rs` | rsync 前先 pull |

### 文档
| 文件 | 改动 |
|---|---|
| `docs/lbfs-user-guide.md` | §9.2 标 ✅；§9.3 标 ✅；§12 Phase 5 标 ✅；新增 §6.4 冲突文件如何处理 |

### 测试
| 文件 | 改动 |
|---|---|
| `dbay-fuse/tests/test_etag_ledger.rs` | **新建** |
| `dbay-fuse/tests/test_pull.rs` | **新建** |
| `tests/e2e/test_lbfs_etag_conflict.py` | **新建** |
| `tests/e2e/test_lbfs_pull.py` | **新建** |
| `tests/e2e/test_lbfs_mount_resume.py` | **新建**（pull + mount 综合） |

---

## 6. 错误处理与边界

| 场景 | 行为 |
|---|---|
| ledger 文件被删 | uplink 退化到无 if_match，等同当前；首次成功 PUT 后重新 populate |
| ledger SQLite 损坏 | uplink 启动时检测 `PRAGMA integrity_check` 失败 → 重命名为 `.broken-<ts>`、新建空 ledger + 提示 |
| pull 期间网络中断 | 已处理的 entry 保留 ledger；下次 pull 续传从 list 重新开始（list 幂等） |
| batch 部分 op 412 | per-op 处理（成功的 ACK、412 的走冲突分支、其他 op 不受影响） |
| 客户端 ≥ 服务端版本，但服务端 if_match 字段未生效 | 服务端无声忽略 if_match → 等同当前 LWW 行为；E2E 部署验证时检查 412 真的会触发 |
| 冲突文件再次冲突 | 副本路径里有时间戳，不会覆盖；可能产生 `X.conflict-from-host-T1.conflict-from-host-T2`（罕见但可接受） |
| 大文件冲突（>100MB） | pull 仍按"跳过"处理；uplink 触发的冲突会照常下载远端版本到副本（无大小限制） |

---

## 7. Rollout 计划

### 7.1 阶段拆分（每阶段独立可合并）

**Phase 1 — 服务端 + ledger（无行为变化）**
- 服务端 A1/A2/A3/A4
- 客户端 etag_ledger.rs 实现 + uplink 写入 ledger（**暂不传 if_match**）
- 部署后所有客户端开始 populate ledger，但行为不变
- 目的：让 ledger 在切换到 strict 模式前先有数据，避免首次开启时 100% 走"无 ledger"分支

**Phase 2 — uplink 开启 if_match**
- send_batch 注入 if_match
- 冲突分支 + conflict log
- 跑 E2E test_lbfs_etag_conflict

**Phase 3 — pull 子命令**
- pull.rs + main.rs Cmd::Pull
- E2E test_lbfs_pull

**Phase 4 — mount/takeover 集成 pull**
- main.rs Mount 默认 pull
- takeover.rs 调 pull
- E2E test_lbfs_mount_resume

**Phase 5 — 文档**
- user-guide 更新

### 7.2 部署顺序

服务端（A1-A4）先合先发，客户端再合。原因：服务端忽略 if_match 字段对旧客户端无影响；新客户端等服务端发完版后再用 if_match。

### 7.3 回滚

- 客户端：`--skip-pull` 标志 + 删除 ledger 文件 → 立刻回到当前行为
- 服务端：A1-A4 都是新增字段处理，不破坏旧请求（旧客户端不带 if_match 走原路径）

---

## 8. 决策记录

| 决策 | 选择 | 替代 | 理由 |
|---|---|---|---|
| 冲突方向 | 本地→新基线、远端→副本 | 远端→覆盖本地、本地→副本 | 不打断用户编辑流；本地最新意图被尊重 |
| Pull 触发 | mount 启动默认 + 显式命令 | 仅显式 | 开箱即用；可 `--skip-pull` 关 |
| ledger 存储 | SQLite | JSON / sled / 文件树 | rusqlite 成熟、原子写、查询快 |
| 大文件 | 默认跳过、可选拉 | 全部拉 / 占位文件 | 笔记本场景体验优先；占位文件留 Phase 6 |
| Batch 部分失败 | per-op tx，独立成败 | 整 batch tx | 一个文件冲突不应阻塞其他文件 |

---

## 9. 已知风险与遗留

- **ledger 与服务端不一致的窗口**：成功 PUT 但 ledger 写入前 daemon crash → 下次 PUT 会带旧 etag 触发 412 → 走冲突分支（本地版本和服务端版本相同，"冲突副本"内容相同）→ 浪费一次 GET，无数据损失。可接受。
- **跨多客户端 pull 同步窗口**：两台机器在 1 秒内都做 pull，两边都看到对方上次的版本；不影响正确性，只影响"看到对方最新"的时延，由 mount 启动 + 之后的 uplink 自然收敛。
- **冲突文件污染**：每次冲突生成一个文件，长期可能堆积；后续可加 `dbay-fuse conflicts list/clean` 子命令（不在本 spec 范围）。
- **append + if_match 在服务端是 best-effort**：append 现在的实现是 read-modify-write 全量重算 etag，加 if_match 后等于"乐观锁的 append"，并发 append 多客户端竞争时只有一个胜出，其他需要重试。Phase 2 客户端会自动重试（412 → 走冲突分支后重发）。文档要说清。

---

## 10. 验收标准

- [ ] 服务端 `/files/append`、`/batch` per-op 都支持 `if_match`
- [ ] Rust 单测 `test_etag_ledger`、`test_pull` 全绿
- [ ] E2E `test_lbfs_etag_conflict`、`test_lbfs_pull`、`test_lbfs_mount_resume` 全绿
- [ ] `dbay-fuse mount --agent claude` 启动时自动从远端拉取本机缺失的文件
- [ ] 两台机器并发改同一文件，没有静默丢数据，冲突日志和冲突文件都产出
- [ ] user-guide §9 / §12 更新完毕，冲突文件处理流程有文档

---

## 附：关键代码锚点

- `LakebaseFSService.append`：`lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSService.java:157`
- `LakebaseFSController.batch`：`lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSController.java:138`
- `uplink_worker::send_batch`：`dbay-fuse/src/uplink_worker.rs:138`
- `state_scan::walk`（上行 baseline，pull 是它的镜像）：`dbay-fuse/src/state_scan.rs:38`
- `dbay_api::lbfs_put_strict`（已存在的参考实现）：`dbay-fuse/src/dbay_api.rs:428`
