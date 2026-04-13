# Agent 记忆文件接口方案讨论

> 日期: 2026-04-13
> 状态: 讨论中
> 参与者: Jacky, Claude

## 背景

越来越多的 AI Agent（Claude Code, OpenClaw 等）使用 markdown 文件记录记忆，例如 Claude Code 的 `~/.claude/projects/*/memory/`。这些记忆目前是本地的、孤立的，换设备就丢失，跨 Agent 不互通。

DBay 已有 memory_ingest/recall API（embedding + 向量检索 + 端到端加密），我们讨论是否应该提供文件接口，让这些 Agent 的 markdown 记忆可以存入 DBay。

## 行业参考

### Oracle — 数据库路线

- 来源: [Oracle Blog - Comparing File Systems and Databases for Effective AI Agent Memory Management](https://blogs.oracle.com/developers/comparing-file-systems-and-databases-for-effective-ai-agent-memory-management)
- 方案: Oracle AI Database 做统一存储（SQL + 向量索引），Agent 通过 LangChain + OracleVS SDK 调数据库
- 核心观点: 不要用多个独立存储（polyglot persistence），用一个融合数据库搞定
- **不是 FUSE**, 是纯数据库 API
- **需要 Agent 适配**: Agent 必须集成 OracleVS SDK 才能使用

### OpenViking（火山引擎）— 文件 API 抽象路线

- 来源: [OpenViking Storage Concepts](https://github.com/volcengine/OpenViking/blob/main/docs/zh/concepts/05-storage.md)
- 方案: AGFS 提供 POSIX 风格的 API（`read(uri)`, `write(uri)`, `mkdir(uri)`），通过 VikingFS URI（如 `viking://resources/docs/auth`）路由到不同后端（localfs / S3 / memory）
- 分层 markdown 结构: `.abstract.md`, `.overview.md`, `.relations.json`，L0-L2 抽象层级
- **不是 FUSE**, 是应用层 API 抽象，不是内核级挂载
- **需要 Agent 适配**: Agent 必须调 VikingFS SDK

### 共同问题

Oracle 和 OpenViking 都要求 **Agent 来适配自己**。如果 Claude Code、OpenClaw 等 Agent 不修改代码，就无法使用这两个方案。

## 三条可选路线

### 路线 1: FUSE — 虚拟文件系统

```
~/.claude/projects/xxx/memory/  →  FUSE mount  →  DBay API
```

Agent 读写文件时，实际在读写 DBay。对 Agent **完全透明，零侵入**。

### 路线 2: Sync — 后台同步

```
本地文件 ←→ dbay-cli memory sync ←→ DBay API
```

文件还是本地的，通过 CLI 或 hook 定期同步到 DBay。Agent 无感知。

### 路线 3: Native API — Agent 直接调 DBay

```
Agent → MCP tool (memory_ingest/recall) → DBay API
```

当前已有的方式。Claude Code 通过 MCP 调用 `memory_ingest`。

### 对比

| 维度 | FUSE | Sync | Native API |
|---|---|---|---|
| Agent 侵入性 | 零（透明挂载） | 零（旁路同步） | 需要集成 |
| 实时性 | 实时 | 有延迟窗口 | 实时 |
| 离线可用 | ❌ 断网即挂 | ✅ 本地优先 | ❌ 依赖网络 |
| 语义丰富度 | 低（只有字节流） | 中（可解析 frontmatter） | 高（tag/metadata/关联） |
| 实现复杂度 | 高 | 低 | 低（已有） |
| 平台兼容性 | macOS 困难 | 全平台 | 全平台 |

## FUSE 方案深入分析

### 能发挥的 DBay 优势

**1. 跨设备同步**
- 当前记忆困在本地 `~/.claude/` 目录，换台机器就没了
- FUSE 挂载 DBay 后，任何设备上的 Agent 都能读到同一份记忆

**2. 跨 Agent 共享**
- Claude Code 写的记忆，OpenClaw 也能读到（挂同一个 DBay 账户）
- 不同 Agent 在不同项目积累的经验可以互通

**3. 透明加密**
- 本地 markdown 是明文的，DBay 已有端到端加密
- FUSE 层透明加解密，Agent 读到明文，存的是密文

**4. 向量检索（有限）**
- 写入时自动做 embedding，DBay 侧可以 `memory_recall` 语义检索
- 但 Agent 本身不会用语义检索，它还是按路径读文件。此优势仅在 DBay 侧有用

### FUSE 的劣势

**1. 语义降级 — 最核心的问题**

```
Agent 写文件: write("feedback_testing.md", content)
FUSE 能知道的:   路径、内容、时间
FUSE 不知道的:   这是新增还是更新？哪些段落变了？为什么变？
```

DBay `memory_ingest` 本来可以带 tag、场景、关联关系，FUSE 只能拿到字节流，丰富的语义接口退化成文件 IO。

**2. 延迟叠加**

| 操作 | 本地文件 | FUSE → DBay |
|---|---|---|
| read 一个 .md | <1ms | 50-200ms（网络往返） |
| Agent 启动加载 MEMORY.md + N 个记忆文件 | <5ms | N × 100ms，串行可能秒级 |

Agent 每次对话开始都要读记忆文件，这个延迟用户能感知到。

**3. 离线不可用**
- 断网时 FUSE read/write 失败，Agent 行为不可预测
- 做本地缓存就引入一致性问题，复杂度飙升

**4. 平台门槛**
- macOS: macFUSE 要内核扩展，Apple Silicon 上要关 SIP 或用收费的签名版
- Linux: 原生支持，没问题
- Windows: WinFsp 可以但生态差

**5. 运维负担**
- 多一个 daemon 进程要保活
- 崩溃/卡死时 mount 点变成僵尸，Agent 文件操作全卡住

### FUSE 适用场景判断

- **适合**: 用户有多台设备、用多个 Agent，核心诉求是"记忆不丢、到处能用"
- **不适合**: 追求语义丰富的记忆管理（tag、关联、检索）— 这些能力在 FUSE 层会被磨平

## 待讨论问题

1. **核心诉求是什么？** 跨设备同步 vs 语义丰富的记忆管理 vs 两者都要
2. **是否可以组合？** 例如 Sync 方案兜底 + Native API 做增强，而不是 all-in FUSE
3. **谁是目标用户？** 如果是开发者自用，Sync 够了；如果是产品化给企业用，FUSE 的"零侵入"有吸引力
4. **macOS 门槛是否可接受？** 大部分开发者在 macOS 上，FUSE 的平台问题是否会成为推广障碍
