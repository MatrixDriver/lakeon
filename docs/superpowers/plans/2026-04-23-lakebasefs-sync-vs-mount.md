# LakebaseFS 同步模式设计方案（vs Mount）

日期：2026-04-23
作者：@jacky
状态：Draft，等讨论

---

## 1. 背景与动机

LakebaseFS 当前只提供一种客户端接入方式——**FUSE mount**（`dbay-fuse/src/main.rs`，使用 Rust `fuser 0.14` crate）。用户执行 `dbay-fuse mount --agent=<name>` 后，在 `~/.dbay/mnt/<agent>/` 得到一个挂载点，所有 agent 读写都经 FUSE 转发到 `/api/v1/lbfs/files/*`。

这种方式对**大语料库 / 只读访问 / 强一致性**场景非常合适，但在用户侧有几个现实摩擦：

- **macOS 安装门槛高**：需要 macFUSE（kext），系统扩展必须手动批准，SIP 阻挡，升级系统后经常失效
- **离线不可用**：断网即挂载丢失，agent 看到空目录
- **系统工具兼容性差**：VS Code 文件索引、Spotlight、Time Machine 对 FUSE 路径支持有 bug
- **跨设备不透明**：同一用户换一台机器要重新 mount，不像 Dropbox 那样"文件就在那儿"

参考 Google Drive / Dropbox / OneDrive 的"本地目录同步"模式，我们想**增加一种不依赖 FUSE 的接入方式**：用户指定一个本地目录，dbay daemon 双向同步到 LakebaseFS 后端。

**目标**：两种模式长期共存，用户按场景选，不做抛弃式迁移。

---

## 2. 两种方式的工作原理（对照）

### 2.1 Mount（当前方案）

```
agent 进程 ─read/write─▶ ~/.dbay/mnt/foo/x.txt
                            │ FUSE VFS 调用
                            ▼
                       dbay-fuse (fuser crate)
                            │
                            ▼
              HTTP ─▶ /api/v1/lbfs/files/put
                      (LakebaseFSController.java:86)
                            │
                            ▼
                   PostgreSQL files 表
                   data BYTEA + etag=SHA256
```

- **每次读写都同步 RTT 到后端**（按需 seek，不缓存全量）
- 状态存 `~/.dbay/state/<agent>/` 和 `~/.dbay/outbox/<agent>/`（outbox 用于写失败后重放）
- 强一致性靠后端 etag + `if_match` 乐观锁

### 2.2 Sync（新方案）

```
agent 进程 ─read/write─▶ ~/dbay-workspace/foo/x.txt   (普通 POSIX 文件)
                            │
                            ▼ (FSEvents / inotify / ReadDirectoryChangesW)
                       dbay-sync daemon
                            │
                 ┌──────────┴──────────┐
                 ▼                     ▼
         local SQLite 元数据       批量上传队列
         (etag/mtime/state)       (debounce + coalesce)
                                       │
                                       ▼
                         HTTP ─▶ /api/v1/lbfs/files/batch
                                  (已有端点, if_match 支持)
                                       │
                 ◀──── 反向同步 ◀──────┤
                                       │
                         SSE/poll ─▶ /api/v1/lbfs/events
                                      (新端点，消费 lbfs_events 表)
```

- 文件在本地是**普通文件**，所有工具（IDE、git、grep、rsync）直接可用
- daemon 监听文件系统事件 → debounce 合并 → 批量 PUT
- 反向：订阅 `lbfs_events` CDC 流 → 拉文件 → 写本地
- 离线可工作，上线后增量同步

**关键观察**：后端 API (`LakebaseFSController`) 完全不用改——PUT/GET/batch/list 已经全了，etag + if_match 语义也齐。改动集中在：新增 events 订阅端点 + 新 daemon。

---

## 3. 使用场景差异（本节是决策重点）

### 3.1 场景对照矩阵

| 维度 | Mount 更合适 | Sync 更合适 |
|---|---|---|
| **数据规模** | 100GB+ 语料库、历史快照、公共数据集 | GB 级工作目录、当前项目文件 |
| **访问模式** | 随机 seek、部分读取大文件（读一个 50GB 视频的某 10 秒） | 全文件读写、顺序访问 |
| **读写比** | 读多写少（共享语料库、日志归档） | 读写均衡（项目工作区） |
| **本地磁盘** | 省磁盘（不落地，按需读） | 充裕（本地有全量副本） |
| **离线需求** | 不需要（必须在线） | 要能离线编辑，回到网络再同步 |
| **跨设备** | 单设备 / 云端开发环境 | 笔记本 ↔ 工作站 ↔ 手机 看到同一份 |
| **系统工具集成** | 可容忍 Spotlight/IDE 索引异常 | 必须原生（git、VS Code 索引、`rg`、Time Machine） |
| **一致性要求** | 强一致（任何时刻最新版本） | 最终一致（秒级延迟可接受） |
| **并发写** | 多 agent 高频并发写同一文件（后端串行化） | 以单 agent / 单用户为主，偶发冲突 |
| **安装门槛** | 接受 macFUSE / kext 审批 | 只能装用户态程序，拒绝 kext |
| **典型用户** | SRE、数据分析师、研究员 | 开发者、写作者、普通用户 |

### 3.2 典型用例拆解

**Case A：agent 跑全库代码审查（mount 赢）**
- 数据：整个 monorepo 历史快照 ~80GB
- 访问：只读，按 blame 随机 seek 特定行
- 结论：sync 要先拉 80GB，mount 直接按需读

**Case B：开发者用 Claude Code 在本地写项目（sync 赢）**
- 数据：当前项目目录 ~200MB
- 访问：VS Code 索引 + git + agent 编辑
- 离线：地铁里也要能写
- 结论：mount 的 FUSE 路径会让 VS Code 索引挂、离线不可用、git 也会疑惑

**Case C：多 agent 协同处理同一数据集（mount 赢）**
- 场景：5 个 agent 分片处理同一 parquet 文件
- 冲突：后端靠 etag + if_match 原子序列化
- 结论：sync 模式需要额外的冲突协调（3-way merge 或 LWW），复杂度高

**Case D：跨设备记忆库 / 配置同步（sync 赢）**
- 场景：`~/.dbay/memory/` 在笔记本和工作站之间透明同步
- 结论：这是 Dropbox 的经典场景，mount 反而别扭

**Case E：agent 产出 artifact 回收（sync 赢，但只要单向）**
- 场景：agent 在 sandbox 里生成报告、截图、数据文件
- 结论：单向"上传同步"（只监听、只推送、不拉远端），最简化的 sync 子集，可以作为 Phase 1

### 3.3 不适合任何一种的场景

- **超大单文件流式处理**（>50GB 单文件顺序扫描）：两种都不理想，mount 会 RTT 爆炸，sync 首次同步太慢。应该走对象存储直读（OBS SDK），不经 LakebaseFS。
- **极低延迟写**（<10ms 每次写）：两种都不行，应该用内存 KV 或本地 SQLite，LakebaseFS 不是设计目标。

### 3.4 默认策略建议

- **新用户默认 sync**（零 kext，Dropbox 体验，99% 场景够用）
- **高级用户 / SRE / 大数据场景自行切 mount**（文档里明确引导）
- 两种模式**可共存于同一 agent**：比如 `~/dbay-workspace/` 用 sync，`~/dbay-readonly-corpus/` 用 mount

---

## 4. Sync 模式技术设计

### 4.1 组件划分

新增组件：
- **`dbay-sync` 守护进程**：Rust（和 `dbay-fuse` 同语言，可共享 HTTP client 代码）
- **本地 SQLite 元数据库**：记录每个文件的同步状态
- **事件订阅端点**：`GET /api/v1/lbfs/events?since=<id>&agent=<name>`（新加，SSE）

不改：
- LakebaseFS 后端文件操作 API（复用 `/files/put`、`/files/batch`、`/files/head`、`/list`、`/rename`、`/delete`）
- PostgreSQL files 表结构
- etag + if_match 语义

### 4.2 本地元数据 schema

SQLite 路径：`~/.dbay/sync/<agent>/state.db`

```sql
-- 每个已知文件的同步状态
CREATE TABLE sync_files (
  local_path       TEXT PRIMARY KEY,     -- 绝对路径
  remote_path      TEXT NOT NULL,        -- LakebaseFS 路径
  last_known_etag  TEXT,                 -- 最近一次与远端同步时的 etag
  last_known_mtime_ns BIGINT,            -- 远端 mtime
  local_mtime_ns   BIGINT,               -- 最近一次我们自己写入时的本地 mtime
  local_size       BIGINT,
  state            TEXT NOT NULL,        -- 'synced' | 'pending_up' | 'pending_down' | 'conflict'
  conflict_remote_etag TEXT,             -- 冲突时保存远端版本的 etag
  last_sync_at     TIMESTAMP
);

-- 待处理操作队列（崩溃恢复用）
CREATE TABLE sync_queue (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  op          TEXT NOT NULL,             -- 'put' | 'delete' | 'rename' | 'mkdir'
  local_path  TEXT NOT NULL,
  payload     BLOB,                      -- op 相关附加数据
  enqueued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  attempts    INT DEFAULT 0,
  last_error  TEXT
);

-- 远端事件游标（断点续传用）
CREATE TABLE event_cursor (
  agent         TEXT PRIMARY KEY,
  last_event_id BIGINT NOT NULL,
  updated_at    TIMESTAMP
);

-- 忽略规则（类似 .gitignore）
CREATE TABLE ignore_patterns (
  pattern TEXT PRIMARY KEY
);
```

### 4.3 上行流程（本地 → 云端）

1. FSEvents/inotify 捕获 `x.txt` 变化
2. **Debounce 300ms**（避免编辑器临时写盘风暴）
3. 读文件，计算 `new_etag = SHA256(content)`
4. 查 `sync_files`，若 `new_etag == last_known_etag` → skip（内容没变，可能只是 touch）
5. 入 `sync_queue`，`op=put`
6. worker 消费队列：`POST /files/put` 带 `if_match=<last_known_etag>`
7. 成功 → 更新 `sync_files.last_known_etag = new_etag, state='synced'`
8. 412/BadRequest → 并发冲突，走冲突处理（§4.5）

### 4.4 下行流程（云端 → 本地）

1. daemon 订阅 `GET /api/v1/lbfs/events?since=<cursor>&agent=<name>`（SSE）
2. 收到事件 `{path, etag, event_type}`
3. 过滤"自己造成的事件"：若 `etag == sync_files[path].last_known_etag` → 忽略（回声）
4. 检查本地 `sync_files[path].state`：
   - `synced` 且本地 mtime 未变 → 下载 `GET /files?path=<p>&if-none-match=<etag>`，写本地，更新 state
   - `pending_up` → 冲突（本地有未上传的改动，远端也变了）→ 走冲突处理
5. 更新 `event_cursor.last_event_id`

**后端改动**：需要新增 `GET /api/v1/lbfs/events` 端点，消费现有 `lbfs_events` 表（`LakebaseFSDatabaseManager.java:60-72` 已有 CDC 触发器，直接读即可）。SSE 或 long-poll 均可，推荐 SSE。

### 4.5 冲突处理

**冲突定义**：远端 etag 变了（from A→B），本地也基于 A 改到了 C。

策略（从简到繁）：

1. **Phase 1 - 二进制冲突副本**（Dropbox 风格）
   - 远端保留 B（不回滚）
   - 本地 C 另存为 `x.txt (conflict from <hostname> 2026-04-23).txt`
   - 提示用户介入（daemon 状态 UI / 通知）
   - 无数据丢失，但需要人工 merge
2. **Phase 2 - 文本 3-way merge**
   - 识别文本文件（mime + 文件扩展名白名单）
   - 取 base=A、mine=C、theirs=B，调用 `diff3`（有成熟 Rust crate `diffy`）
   - 无冲突 hunk → 合并后 PUT
   - 有冲突 hunk → 退回 Phase 1 模式
3. **Phase 3 - 可配置策略**
   - 按路径模式配置：`*.md` → 3-way merge、`*.jpg` → LWW（后写覆盖）、`secret.env` → 永远保留本地

**关键**：**绝不自动丢数据**。LWW 只在用户显式配置允许的路径上用。

### 4.6 与现有 etag 语义的复用

当前后端已经支持：
- PUT 带 `if_match=<etag>` → 412 if 不匹配（`LakebaseFSController.java:86-87`）
- HEAD 返回 `etag + mtime_ns + size`
- idempotency：相同 etag 的 PUT 不更新 mtime（bb2d052b commit + `LakebaseFSService.java:324-327`）

这三个特性**直接可用**，sync daemon 的上行冲突检测、下行回声过滤、重试幂等都靠它们。这是非常重要的利好——说明后端不需要为 sync 模式专门改造。

### 4.7 Ignore 规则

默认忽略（硬编码）：
- `.git/`、`.svn/`、`.hg/`（版本控制内部状态）
- `node_modules/`、`__pycache__/`、`.venv/`、`target/`、`dist/`（构建产物）
- `.DS_Store`、`Thumbs.db`
- 任何 `.dbay-ignore` 文件（类似 `.gitignore` 语法）

用户可通过 `~/.dbay/sync/<agent>/.dbaysyncignore` 追加。**强烈建议默认忽略 `.git/`**——否则 git index 会被远端覆盖、冲突地狱。

### 4.8 大规模事件处理

npm install、`rm -rf node_modules` 这类操作会瞬间产生 10万+ FS events。daemon 必须：
- 事件先进入有界 channel（ring buffer，溢出时降级到"重新扫描整目录 mtime"）
- Debounce 窗口内合并同一 path 的多次事件
- 批量上传走 `/api/v1/lbfs/files/batch`（已存在的批量端点，一次最多 100 op）

---

## 5. 与 Google Drive / Dropbox 的对比

| 特性 | Google Drive File Stream | Dropbox | 我们的 sync daemon |
|---|---|---|---|
| 事件监听 | macOS File Provider | FSEvents/inotify + 后期迁 File Provider | FSEvents/inotify（Phase 1） |
| kext 依赖 | 无（系统 API） | 无 | 无 |
| 本地存全量 | 按需（占位文件） | 可选（Smart Sync 占位文件） | 全量（Phase 1）、占位文件（Phase 4 考虑） |
| 冲突处理 | 冲突副本 | 冲突副本 + 某些类型智能合并 | 冲突副本（Phase 1）→ 3-way merge（Phase 2） |
| 跨平台 | macOS/Windows | macOS/Windows/Linux | macOS/Linux（Phase 1），Windows（Phase 3） |

**Phase 4 可选**：接入 macOS 原生 **File Provider 框架**（需要 Developer ID 签名 + entitlement），让 `~/dbay-workspace/` 在 Finder 里显示云端图标、支持"按需下载"。这一步对工程投入大，短期不做。

---

## 6. 红蓝对抗审查

### 蓝军挑战

**B1: "Dropbox 最后还是做了类 FUSE（Smart Sync），说明纯同步模式走不通。"**
- 红军：Dropbox 的驱动是"家庭照片 10TB 本地塞不下"，是 consumer 大数据量场景。LakebaseFS 是 agent 工作目录，GB 级，需求不一样。Phase 1 不做占位文件也 OK。

**B2: "git 仓库同步必翻车，.git/index 天天冲突。"**
- 红军：默认忽略 `.git/` 是标配。VS Code 的远程开发、Dropbox 的建议文档也都这样要求。如果用户真要同步 git 历史，走 `git push` 到 LakebaseFS 暴露的 git 远端更干净。

**B3: "多 agent 并发写同一文件，sync 必然数据丢。"**
- 红军：先承认——**sync 模式本来就不适合多 agent 高频并发写**（见 §3.1 矩阵）。这种场景明确引导用户选 mount。但 Phase 2 的 3-way merge 可以处理文本类低频冲突。

**B4: "10 万文件 npm install 会把 daemon 打爆。"**
- 红军：§4.8 已经列了方案：有界 channel + debounce + 溢出降级到全量扫描。另外 `node_modules/` 默认忽略，这个场景直接绕开。

**B5: "macOS File Provider 需要 App Store 审核 / Developer ID，你们没打算上架。"**
- 红军：Phase 1-3 都用 FSEvents，不走 File Provider。Phase 4 如果真做，只需 Developer ID 签名（99 美元/年），不需要 App Store。Dropbox、rclone-mount 都走的这条路。

**B6: "后端 `lbfs_events` 表会不会被 sync 订阅打爆？"**
- 红军：现有 CDC 已在生产用（Phase 2 记忆派生），吞吐量已经验证过。SSE 订阅端点做 per-agent 过滤 + LISTEN/NOTIFY（Postgres 原生），不会全表扫。需要压测验证，但架构上没瓶颈。

**B7: "sync daemon 挂了用户不知道，静默丢数据。"**
- 红军：daemon 必须有 status UI（状态栏图标 / `dbay-sync status` CLI）+ 出错时系统通知。队列持久化在 SQLite，重启后自动恢复。这是 Must Have，不是 Nice to Have。

### 蓝军遗留担忧

- **跨设备冲突频率**：如果用户同时在两台机器编辑同一文件，冲突副本会污染目录。→ 需要实际用一段时间看频率，Phase 2 文本 merge 能消化多少。
- **大文件首同步时间**：2GB 单文件首次上传 ~30min（3MB/s 住宅带宽），体验差。→ 引导文档里明确说明"首次同步可能较慢"，并提供 `--exclude-large` 开关。

---

## 7. 分阶段落地计划

### Phase 1（MVP，约 2 周）
- Rust `dbay-sync` daemon（复用 `dbay-fuse` 的 HTTP client）
- 单向上行：监听本地 → 批量 PUT
- 冲突策略：冲突副本（Dropbox 风格）
- CLI：`dbay-sync start --agent=<name> --dir=<path>` + `dbay-sync status`
- 后端：**新增 `GET /api/v1/lbfs/events` SSE 端点**（小改动）
- **不支持**：下行同步、跨设备、File Provider

**Milestone**：一个开发者的 artifact 目录（写作 / 代码产物）能实时同步到云端，和 mount 并存可选。

### Phase 2（双向同步，约 3 周）
- 下行同步：SSE 订阅 events → 本地写
- 冲突处理完善：状态标记 + 用户介入 UI（lakeon-console 加一个 sync status 面板）
- 跨设备测试：笔记本 ↔ 工作站

### Phase 3（质量与规模，约 2 周）
- 3-way merge for text files
- Windows 支持（`ReadDirectoryChangesW`）
- 大规模事件测试（10万文件 repo）
- Ignore 规则完善

### Phase 4（可选，macOS 原生集成）
- macOS File Provider extension（签名、entitlement、按需下载）
- 仅在用户反馈强烈需要时做

**不在本方案范围**：
- 端到端加密（记忆库加密走现有 `project_memory_encryption` 方案，sync 之后单独考虑）
- P2P 同步（短期不做，所有流量经中心后端）

---

## 8. 决策请求

1. 是否同意**两种模式长期共存**的定位？（vs "用 sync 取代 mount"）
2. 是否同意**新用户默认 sync** 的建议？
3. Phase 1 MVP 的范围（单向上行 + 冲突副本 + macOS/Linux）是否合适？
4. 后端新增 `GET /api/v1/lbfs/events` SSE 端点，由谁负责？
5. 本方案是否需要加入管理者报告 / 经 challenge skill 再审一轮？

---

## 附：关键代码锚点

- 后端 Controller：`lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSController.java`
- 后端 Service（etag + if_match + 幂等 upsert）：`lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSService.java:324`
- 数据库 schema + CDC 触发器：`lakeon-api/src/main/java/com/lakeon/lbfs/LakebaseFSDatabaseManager.java:45`
- Rust FUSE 客户端（可复用 HTTP client）：`dbay-fuse/src/main.rs`、`dbay-fuse/src/uplink_worker.rs`
- 幂等性回归测试：`tests/e2e/test_lbfs_idempotent.py`
