# 检索优化：Q-value 与 MemRL

本文档覆盖记忆**检索**环节的优化：**Q-value 效用评分**（给每条记忆打分，衡量"好不好用"而非"像不像"）和 **MemRL**（用强化学习机制让检索越用越准，不需要重训模型）。

---

## 1. Q-value 解释：记忆的效用评分

### 1.1 什么是 Q-value

Q-value（Quality value）源自强化学习，是一个衡量"这条记忆在帮助 Agent 完成任务时到底有多大用"的分数。不同于语义相似度只看"像不像"，Q-value 衡量的是"好不好用"。

### 1.2 在 ZhiXing 中的工作方式

每条记忆携带一个 `utility_score`（float 类型），初始值为 0.5。当 Agent 检索到某条记忆并用于执行任务后，根据任务结果（成功或失败）通过指数移动平均公式更新该分数：

```
Q_new = Q_old + α × (reward - Q_old)
```

其中：
- `Q_old` 是当前效用分数
- `α` 是学习率（控制更新速度，典型值 0.1-0.3）
- `reward ∈ [-1, 1]` 是任务反馈信号（成功为正，失败为负）

任务成功 → Q 值上升 → 下次更容易被检索。任务失败 → Q 值下降 → 下次被降权。

### 1.3 为什么需要 Q-value

**语义相似 ≠ 实际有用。** 一条记忆可能和当前查询在向量空间中非常接近，但历史上每次被用到都导致任务失败。纯相似度检索会反复召回这条"高相似度的坏记忆"，而 Q-value 会逐步将它降权。

### 1.4 两阶段检索

Q-value 通过两阶段检索机制发挥作用：

1. **相似度召回**（缩小候选集）：用余弦相似度从全量记忆中筛选 top-k1 候选
2. **效用加权排序**（精选结果）：对候选池按 `(1-λ)×similarity + λ×Q_value` 重排序，取 top-k2

参数 λ 控制"相似度 vs 历史效用"的权重。系统初期 λ=0（纯相似度），随 Q-value 积累足够信号后逐渐增大。

### 1.5 核心洞察

Q-value 本质上是**"通过检索实现个性化"**——不需要训练模型、不需要 GPU、不需要微调，只需要给每条记忆维护一个浮点数。Jeff Dean 投资 Supermemory 时明确表态："personalization through retrieval, not fine-tuning"。MemRL 论文证明，仅更新 Q-value（不重训模型）就能在复杂任务上获得 +56% 的提升。

### 1.6 类比

类似于 Google PageRank 根据链接权威性给网页打分（而非仅靠关键词匹配），Q-value 根据任务效用给记忆打分（而非仅靠语义相似度）。PageRank 让搜索引擎从"匹配关键词"进化到"理解质量"，Q-value 让记忆系统从"匹配语义"进化到"理解有用性"。

---

## 2. MemRL：记忆系统的 RL 增强

### 2.1 核心问题：语义相似 ≠ 实际有用

传统记忆检索靠余弦相似度排序。但"相似的记忆"不一定是"能帮 Agent 完成任务的记忆"。例如：
- 一条关于 Python 调试的记忆和当前 Python 问题语义相似度很高
- 但这条记忆编码了一个在特定场景下会失败的策略
- Agent 检索到它并执行，任务失败
- 下次遇到类似问题，还是会检索到这条"高相似度"的坏记忆

### 2.2 MemRL 的解法

给每条记忆一个 **Q-value（效用分数）**，通过任务结果反馈来更新：

**记忆数据结构**：
```
每条记忆 = (z_i, e_i, Q_i)
  z_i = intent embedding（任务/查询的向量表示）
  e_i = experience（解决方案轨迹或经验文本）
  Q_i = learned utility score（学习得到的效用分数）
```

**两阶段检索**：
```
Phase A — 相似度召回（缩小候选集）：
  query → 余弦相似度 > 阈值 δ → 候选池（top-k1）

Phase B — 效用加权选择（精选最终结果）：
  候选池 → 按 (1-λ)×similarity + λ×Q_value 重排序 → top-k2
  λ 控制"相似度 vs 历史效用"的权重
  初期 λ=0（纯相似度），随 Q-value 积累信号逐渐增大
```

**Q-value 更新（任务完成后）**：
```
Q_new = Q_old + α × (reward - Q_old)    # 指数移动平均
reward ∈ [-1, 1]（任务成功/失败信号）

成功：Q 值上升 → 下次更容易被检索
失败：Q 值下降 → 下次被降权
新经验：LLM 总结成功轨迹 → 写入为新记忆（初始 Q = 0.5）
```

**关键特点：不需要重训模型。** LLM backbone 冻结，只更新记忆条目的 Q-value（一个浮点数的指数移动平均）。

### 2.3 实测效果

| 基准 | MemRL | 基线（MemP） | 提升 |
|------|-------|------------|------|
| ALFWorld（家庭任务） | — | — | **+56%** 相对提升 |
| BigCodeBench | 0.627 | 0.602 | +4% |
| HLE Knowledge Frontier | 0.613 | 0.582 | +5% |
| Lifelong Agent Bench | 0.816 | 0.742 | +10% |

复杂多步任务收益最大，简单事实查找收益小。

### 2.4 研究浪潮：已形成趋势

| 论文 | 时间 | 核心思路 | 机构 |
|------|------|---------|------|
| **MemRL** | 2026.1 | Q-value 效用评分，两阶段检索 | 上海交大/NUS |
| **Memory-R1** | 2025.8 | RL 训练记忆管理 Agent（学习 ADD/UPDATE/DELETE/NOOP） | — |
| **Mem-alpha** (ICLR) | 2025.9 | RL 优化记忆**构建**（学习存什么、怎么结构化） | — |
| **MemSearcher** (CAS) | 2025.11 | 端到端 RL：推理+搜索+记忆管理一起训练 | 中科院 |
| **mem-agent** (Dria) | 2026 | 4B 专用小模型做记忆 CRUD，GSPO 训练 | Dria/HuggingFace |
| **MEM1** (MIT/NUS) | 2025 | 记忆与推理协同增强，长时间跨度任务 | MIT/NUS |

**制度信号**：
- **ICLR 2026** 接受了 "MemAgents: Memory for LLM-Based Agentic Systems" 工作坊
- **AWS Bedrock AgentCore** 添加 episodic memory 托管服务
- **ACM TOIS** 和多个 arXiv survey 将"RL-optimized memory"列为关键新兴前沿

### 2.5 开源项目

| 项目 | 地址 | 说明 |
|------|------|------|
| **MemTensor/MemRL** | github.com/MemTensor/MemRL | 官方代码。MIT。Python，支持 HLE/ALFWorld/BigCodeBench/LLB 基准。需要 OpenAI 兼容 LLM + embedding |
| **Tempera** | github.com/anvanster/tempera | MCP Server 实现 MemRL。Rust。JSON + LanceDB 向量搜索。Bellman 传播、时间信用分配、1%/天衰减。Apache 2.0 |
| **MemSearcher** | github.com/icip-cas/MemSearcher | RL 训练的推理+搜索+记忆管理 |
| **Agent-Memory-Paper-List** | github.com/Shichun-Liu/Agent-Memory-Paper-List | 100+ 篇 Agent 记忆论文精选列表 |

### 2.6 对 ZhiXing 的影响：MemRL-ready 记忆平台

MemRL 不替代 ZhiXing 的记忆基础设施，它**需要**这个基础设施。ZhiXing 只需小幅扩展即可成为"MemRL-ready"平台：

**需要新增的能力**：

| 能力 | 改动量 | 说明 |
|------|--------|------|
| `utility_score` 字段 | 加一列 float | 每条记忆的 Q-value，初始 0.5 |
| `recall(lambda=0.3)` 参数 | 修改排序逻辑 | `(1-λ)×similarity + λ×utility` 联合排序 |
| 隐式反馈（recall→ingest 闭环） | ingest 逻辑扩展 | 从下轮对话推断上轮记忆效用，自动更新 Q-value（主要来源） |
| `POST /feedback(memory_id, reward)` | 新 API | 显式反馈，高级用户/企业补充信号（次要来源） |
| 记忆使用日志 | 新表 | 记录哪些记忆被检索了、对应任务是否成功 |
| 批量导出 | 新 API | `(intent_embedding, experience, utility_score)` 格式，用于离线 RL |

**MemRL-ready 作为产品分层**：
```
基础版：存储 + 相似度检索（当前 ZhiXing）
专业版：+ Q-value 效用评分 + feedback API + 联合排序（MemRL-ready，检索即个性化）
企业版：+ 轨迹存储 + export() 训练数据导出 + ZhiXing 平台模型优先体验（数据飞轮）
```

---

## 参考链接

### MemRL 及相关研究
- [MemRL: Self-Evolving Agents via Runtime RL on Episodic Memory](https://arxiv.org/abs/2601.03192)
- [MemRL GitHub (MemTensor)](https://github.com/MemTensor/MemRL)
- [Tempera: MCP Server implementing MemRL](https://github.com/anvanster/tempera)
- [Memory-R1: Enhancing LLM Agents to Manage Memories via RL](https://arxiv.org/abs/2508.19828)
- [Mem-alpha: Learning Memory Construction via RL](https://openreview.net/forum?id=dm42omwep1)
- [MemSearcher](https://github.com/icip-cas/MemSearcher)
- [ICLR 2026 MemAgents Workshop](https://openreview.net/pdf?id=U51WxL382H)
- [Agent Memory Paper List (100+ papers)](https://github.com/Shichun-Liu/Agent-Memory-Paper-List)
