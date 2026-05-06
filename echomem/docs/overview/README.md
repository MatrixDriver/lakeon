# echomem · 概览

> **本地优先的 Agent 记忆中枢** — 让 Claude Code、openclaw、hermes 共享同一份"会反思"的本地大脑。

---

## 一句话定位

echomem 是云端 dbay 的**本地 first 同源近亲**：schema 兼容、API 概念一致，但本地完全自给（SQLite + FS + Ollama 小模型）。云端是可选挂载，不是默认依赖。

## 解决什么问题

| # | 现状 | 问题 |
|---|------|------|
| 1 | 用户在 CC、openclaw、hermes 之间切换工作 | 记忆 / skill / 上下文不互通；同一件事每家 Agent 重新讲一遍 |
| 2 | dbay 云端有完整能力，但强依赖云 | 客户/同事 demo 不便；离线场景受阻 |
| 3 | 想让本地小模型做衍生物提取 | 现有 dbay pipeline 与云端 PG schema 紧耦合，剥本地版有工程量 |
| 4 | "用记忆预测 LLM 输出长度"假设未验证 | 做对了对 MaaS 调度有价值；做法侵入 Agent 风险高 |

## 目标 / 非目标

### MVP 目标

1. 三家 Agent 通过统一 API 共享同一份记忆
2. 用户能在 Dashboard 看到记忆/衍生物/会话，并理解"为什么有这条衍生物"
3. 本地 AI 跑通 ingest pipeline，产出 4 种衍生物：时间流 / 树 / 图 / 程序性
4. 一条命令完成安装、注册 MCP、起 Dashboard、拉本地模型
5. Context API：URL / PDF / repo 文件能灌到 echomem 给 Agent 引用

### 非目标（不做或留后期）

- Benchmark Runner（locomo / longmemeval）—— Phase 6+
- 多用户认证 / 团队协作 —— echomem 是单机单用户工具
- 单独的 "Skill 共享" 模块 —— 已并入"程序性记忆"衍生物
- 因果链衍生物 —— 待证明价值

## 设计原则

| 原则 | 含义 |
|---|---|
| **Local-first** | 本地是主战场。备份就是 `cp ~/.echomem`。云端 dbay 是可选挂载 |
| **同源不重写** | schema 与 API 概念尽量与云端 dbay 兼容，StorageDriver 抽象切换底层 |
| **存储抽象层** | 所有上层只见 `StorageDriver` 接口；切换 SQLite ↔ PG ↔ Cloud 不动业务代码 |
| **小模型友好** | 衍生物 pipeline 对 gemma 4B 量级模型留降级路径 |
| **零云依赖默认** | demo 必须在没外网时跑通；云挂载是用户主动行为 |
| **Hook 不入侵** | Insight Track 等可选能力通过 Agent 框架 hook 接入 |

## 与同类项目的关系

| 项目 | 角色 |
|---|---|
| **dbay (云端)** | 长期持久化、跨设备、团队共享。echomem 是它的本地近亲 — schema 同源，API 概念一致，CloudDriver 可双向挂载。|
| **mem0 / Letta** | 偏服务化记忆框架，存储抽象通常绑定一种后端。echomem 强调"本地优先 + 同源云端 + 多 Agent 共享"。 |
| **本地向量库（chromadb / sqlite-vec）** | 是 echomem 的底层组件之一，但 echomem 还做衍生物提取、Skill 索引、上下文 API 这些组件级服务。 |

## 怎么用（开发者视角）

```bash
# 安装
cd lakeon/echomem
pip install -e ".[dev]"

# 起 daemon
echomem init                                     # 创建 ~/.echomem/
echomem start                                    # 监听 127.0.0.1:8473

# 写入与查询
echomem mem ingest "today I met jacky" --agent cli
echomem mem recall "jacky"

# 查衍生物
curl http://127.0.0.1:8473/derivatives/timeline?agent=cc
curl 'http://127.0.0.1:8473/derivatives/graph?seed=ent:jacky&hops=2'
```

接入 Claude Code：在 `~/.claude/settings.json` 的 `mcpServers` 加：

```json
{
  "mcpServers": {
    "echomem": {
      "command": "echomem-mcp-shim",
      "args": []
    }
  }
}
```

## 接下来读什么

- [架构图](/architecture/) — 分层结构、数据流、技术选型表
- [路线图](/roadmap/) — 五阶段交付时间线与状态
- [Phase 1 计划](/docs-viewer/#/plans/phase1-backbone) — Backbone 已交付
- [Phase 2 计划](/docs-viewer/#/plans/phase2-derivatives) — Derivatives 已交付
- [Phase 3 计划](/docs-viewer/#/plans/phase3-context-api) — Context API 进行中
