# ZhiXing 竞品分析与能力差距

> 本文档是 [11-zhixing-strategy.md](./11-zhixing-strategy.md) 的详细分析附件，覆盖 ZhiXing 现有能力盘点、与各 Agent 系统的关系定位、竞品差距深度分析、以及三层模型架构的详细论证。

---

## 1. ZhiXing 现有能力盘点

### 1.1 存储架构

单一 PostgreSQL 数据库（ParadeDB），包含：
- **pgvector**：向量相似度搜索（HALFVEC float16 量化）
- **pg_search**：BM25 全文检索（Tantivy 解析器）
- **图节点/边**：实体关系三元组
- **KV 存储**：应用层键值对

与竞品对比：

| 框架 | 存储组件 | 问题 |
|------|---------|------|
| **ZhiXing** | PostgreSQL only | 统一查询、强一致性、ACID |
| Mem0 | PostgreSQL + Qdrant + Neo4j | 3 个数据库，最终一致性 |
| MemOS | PostgreSQL + Redis + Qdrant + Neo4j | 4 个数据库，难以同步 |
| graphiti | PostgreSQL + Neo4j + 向量 DB | 无法交叉优化 |

### 1.2 记忆类型

| 类型 | 说明 | 存储 |
|------|------|------|
| **Fact** | 持久事实 | 向量 + 图双写，双时间线 |
| **Episode** | 时间事件 | 向量，含情感元数据 |
| **Trait** | 用户特征（自动反思生成） | 向量，含置信度和证据链 |
| **Procedural** | 过程性记忆 | 向量 |
| **Document** | 上传文档 | 分块 + 向量 |

### 1.3 检索机制

```
recall() 编排：向量 Top100 + BM25 Top100 → RRF 融合 → 图谱 boost → 时间衰减 → Zettelkasten 扩展
```

### 1.4 独特能力

- **9 步反思引擎**：趋势 → 行为 → 偏好 → 核心特征（MemOS/OpenViking/HydraDB 都没有）
- **单一 PostgreSQL 架构**：竞品需要 3-4 个数据库
- **Agent-Extract Mode**：客户端提取减少 80% 服务端 LLM 调用

### 1.5 集成方式

REST API · MCP Protocol（13 工具） · OpenClaw 插件 · Python SDK

---

## 2. 替换还是叠加？ZhiXing 与各系统的关系定位

### 2.1 与 Claude Code 的关系：替换价值不大

| ZhiXing 的强项 | Claude Code 需要的 |
|--------------|------------------------|
| 语义检索（从海量记忆中找相关的） | 全量加载（记忆量小，不需要检索） |
| 自动提取（从对话中抽取事实） | 精确写入（用户/AI 主动决定记什么） |
| 遗忘曲线（时间衰减） | 不需要遗忘（项目规范不会衰减） |
| 情感标注 | 完全不需要 |
| 图谱关联 | 扁平列表就够了 |

**结论：对编码助手，替换关系价值不大，甚至可能是降级。**

唯一的增量价值：**跨项目的用户画像同步**——这是 Claude Code 原生完全做不到的。

### 2.2 与 OpenClaw 的关系：叠加而非替换

之前假设 OpenClaw 原生没有长期记忆——这是错的。OpenClaw 原生已经有：
- ✅ Markdown 长期记忆（MEMORY.md）
- ✅ 日记系统（daily notes）
- ✅ 向量 + BM25 混合检索（SQLite）
- ✅ 时间衰减
- ✅ MMR 多样性重排

**逐项对比——哪些是增量价值，哪些反而是劣势：**

| 能力 | OpenClaw 原生 | ZhiXing 增量 | 是否刚需？ |
|------|-------------|-------------|----------|
| 向量检索 | ✅ sqlite-vec | ✅ pgvector（更强） | ❌ SQLite 够用 |
| BM25 全文 | ✅ FTS5 | ✅ pg_search | ❌ 差别不大 |
| 图谱关联 | ❌ | ✅ 图节点+边 | ⚠️ 看场景 |
| 自动提取 | ❌ 靠 AI 自律写日记 | ✅ LLM 自动分类提取 | ✅ **关键差异** |
| 记忆分类 | ❌ 纯文本 | ✅ fact/episode/trait | ⚠️ 有用非必需 |
| 反思/Trait | ❌ | ✅ 9步反思引擎 | ⚠️ 长期使用才显现 |
| 情感标注 | ❌ | ✅ valence/arousal | ❌ 多数场景不需要 |
| 双时间线 | ❌ | ✅ valid_from/until | ⚠️ 事实变化时有用 |
| 跨设备/跨Agent | ❌ 本地文件 | ✅ 云端 API | ✅ **关键差异** |
| 人可编辑 | ✅ Markdown | ❌ 需 API/UI | ❌ **反而是劣势** |
| 离线可用 | ✅ | ❌ 需要网络 | ❌ **反而是劣势** |

**ZhiXing 对 OpenClaw 的真正增量价值集中在三点：**
1. **自动记忆提取**（最大价值）：解决日记堆积、提炼靠自律的问题
2. **跨 Agent/跨设备共享**：统一用户画像
3. **Trait 自动演化**：原生 Markdown 做不到的用户画像归纳

**核心结论：不要试图替换 Markdown，而是做 Markdown 的"智能后台"。** 文本给人看，结构化记忆给机器用，两者双写不矛盾。

### 2.3 与 OpenJiuWen 的关系：填补空白

OpenJiuWen 有 Agent 框架但缺好的长期记忆系统，且无外部记忆插件机制。ZhiXing 的定位是**填补空白**——成为其"官方推荐记忆引擎"。

| 维度 | OpenClaw | OpenJiuWen |
|------|----------|-----------|
| 集成现状 | ✅ 已有插件 | ❌ 无接口 |
| ZhiXing 价值 | 增量（原生已有基础记忆） | **填补空白**（缺长期记忆） |
| 用户类型 | C 端/个人助理 | B 端/企业 Agent |
| 生态规模 | 独立开源社区 | 华为云+小艺+鸿蒙 |

### 2.4 替换 vs 叠加总结

| Agent 系统 | 替换？ | 叠加？ | 正确策略 |
|-----------|-------|-------|---------|
| Claude Code | ❌ 替换是降级 | ⚠️ 增量价值小 | 仅做跨项目画像同步 |
| OpenClaw | ❌ 不要替换 Markdown | ✅ 做智能后台 | 自动提取+反思+跨Agent共享 |
| OpenJiuWen | N/A（无记忆系统可替换） | ✅ 填补空白 | 贡献 MemoryBackend 接口 |

---

## 3. 与竞品的差距深度分析

### 3.1 与 MemOS 对比：省 token 机制澄清

MemOS 72% 省 token 的实际来源是**精准记忆召回替代 MEMORY.md 全量注入**（不是对话历史压缩）：
```
原生 OpenClaw：注入完整 MEMORY.md（几千~几万 token）
MemOS：只注入与当前 query 相关的记忆条目（固定预算，可控）
```

MemOS 作为 `kind: "memory"` 插件，**无权压缩对话历史**——对话历史压缩是 ContextEngine 的职责。

ZhiXing 作为同类 memory 插件，可以通过 L0/L1/L2 分层加载达到类似甚至更好的记忆侧省 token 效果。对话历史压缩则可以依赖 OpenClaw 原生 memory-core compaction，两者共存叠加。

### 3.2 与 OpenViking 对比：缺分层加载

OpenViking 83% 省 token 的主要来源是 L0/L1/L2 分层加载。

ZhiXing `recall()` 返回完整记忆内容，没有摘要层。

**建议**：
- `ingest()` 时自动生成 L0（一句话）和 L1（核心信息）摘要
- `recall()` 支持 `detail_level` 参数
- Agent 先扫 L0 列表，按需加载 L1/L2

### 3.3 与 HydraDB 对比：滑动窗口实现差异 & 时序图深度

#### 能力对比

| 维度 | ZhiXing | HydraDB |
|------|---------|---------|
| 图谱 | PostgreSQL 图节点+边 | 专用时序状态多重图 |
| 时序 | `valid_from/valid_until` | Git 式 append-only + 决策理由（C_meta） |
| 检索 | 向量+BM25+图 boost（RRF） | 三路向量+图遍历+三层重排序+查询扩展 |
| 滑动窗口 | ⚠️ 已实现但默认禁用（LOCOMO 未提升） | ✅ 核心特性 |
| Trait 反思 | ✅ 9步引擎（独有） | ❌ |

#### 滑动窗口：ZhiXing vs HydraDB 深度对比

ZhiXing 已于 2026-03-14 实现了滑动窗口功能（`extraction_mode="window"`），但默认禁用。**两者名字相同但做的事情完全不同。**

| 维度 | ZhiXing 的实现 | HydraDB 的实现 |
|------|---------------|---------------|
| **定位** | 替代逐条提取的**批量提取模式** | 存储前的 **chunk 增强预处理** |
| **输入** | 多条原始消息（缓冲到 500 字符） | 已切分的 segment + 前后 h 个邻居 |
| **处理** | 一次 LLM 调用提取 facts/episodes/triples | LLM 对每个 segment 做实体消解+偏好映射 |
| **输出** | 结构化记忆条目（JSON） | **增强后的自包含 chunk**（原文+消解上下文） |
| **存储物** | 提取出的记忆（不保留原文 chunk） | **增强 chunk 本身**被向量化为 v_inferred |
| **上下文窗口** | 仅前向 `previous_summary`（100 字摘要） | **双向** lookback + lookahead |

图解差异：
```
ZhiXing（批量提取模式）：
  [msg1] [msg2] [msg3] [msg4] [msg5]  ← 缓冲 500 字符
       └──────────┬──────────┘
                  ↓ 一次 LLM 调用
        提取 facts/episodes/triples → 存入 DB
        原始对话向量不受影响

HydraDB（chunk 增强模式）：
  [seg1] [seg2] [seg3] [seg4] [seg5]  ← 文档切片
              ↑   ↑   ↑
         W_i = [seg1..seg5]  ← 双向上下文窗口
                  ↓ LLM 增强每个 segment
        seg3' = "Alice（海洋生物学家）搬到了办公室"
                  ↓ 三路向量化
        v_content  = embed(原始 seg3)
        v_inferred = embed(增强后 seg3')  ← 关键差异！
        v_sparse   = BM25(seg3)
```

#### ZhiXing 滑动窗口的 LOCOMO 测试结果

| 模式 | 总分 | 单跳 | 时序 | 开放域 | 多跳 |
|------|------|------|------|--------|------|
| per_message（基线） | **0.824** | 0.829 | 0.766 | 0.896 | 0.829 |
| window 500 字符 | 0.802 (**-2.7%**) | 0.830 | 0.751 | 0.896 | 0.802 |
| window 1500 字符 | 0.781 (**-5.2%**) | 0.848 | 0.733 | 0.842 | 0.770 |

**时序和多跳反而变差了。** 原因分析：

1. **合并提取稀释信息密度**：多条消息一起提取时，LLM attention 被分散，时序信息容易丢失
2. **没有存储增强 chunk**：HydraDB 的关键是 v_inferred（增强后的向量）直接参与检索，ZhiXing 只存提取出的记忆条目
3. **只有前向摘要**：ZhiXing 的 `previous_summary` 只有 100 字，信息损失大；HydraDB 有真正的双向窗口

#### HydraDB 的 90.79% 不全是滑动窗口的功劳

论文**没有消融实验**。90.79% 是整个系统的协同效果：

| 组件 | 贡献的维度 | 论文自己的归因 |
|------|----------|-------------|
| 滑动窗口 + v_inferred | 单 session 信息提取 100% | "prevents information fragmentation" |
| Git 式时序图 | 时序推理 90.97% | "**directly stems from** the Git-Style Versioned Graph" |
| v_inferred | 偏好提取 96.67% | "Latent Context Injection captures semantic intent" |
| 三层重排序 + 查询扩展 | 多 session 推理 | 未单独归因 |
| append-only ledger | 知识更新 97.4% | "prevents destructive overwrites" |

**结论：滑动窗口主要帮助信息提取和偏好理解，时序推理的提升来自时序图。**

#### "Git 式时序图" ZhiXing 能做到吗？

**基本已经有了。** ZhiXing 的 `valid_from/valid_until` + `memory_history` 表是同一个思路。差距在于：

| 维度 | ZhiXing 现状 | HydraDB | 改进建议 |
|------|-------------|---------|---------|
| 旧事实处理 | 设 `expired_at`（软删除） | 保留旧边，追加新边 | 停止软删除，改为保留+追加 |
| 决策理由 | ❌ 不记录 | ✅ C_meta 字段 | 在 graph edge 加 `change_reason` |
| 版本关联 | 新旧记忆无显式链接 | 同一 E(u,v) 下自然形成链 | 加 `superseded_by` 字段 |

**这不需要换存储引擎**，PostgreSQL 完全能做——核心就是"只 INSERT 不 UPDATE"的数据建模约束。

#### 对 ZhiXing 滑动窗口的改进建议

**方案 A（推荐）：从"批量提取"转为"chunk 增强"**

在 `ingest()` 时不只提取 facts/episodes，还生成并存储增强后的对话向量：
```python
# 现在的 ingest：
await nm.ingest(user_id, role="user", content="她讨厌那个框架")
# → 存储原始文本的 embedding

# 改进后：
# 1. 滑动窗口增强：enriched = "Alice 讨厌 React 框架，因为它难以调试"
# 2. 同时存储原始 embedding 和增强 embedding
# → 检索时可以匹配到 "Alice" 和 "React"
```

**方案 B：改进窗口上下文**

将 `previous_summary`（100 字）改为保留上一窗口最后 2-3 条原始消息作为 overlap：
```python
# 现在：previous_summary = "关键信息摘要（100字）"
# 改进：previous_context = 上一窗口最后 2 条原始消息 + 摘要
```

**方案 C：改进提取 prompt**

窗口模式的额外价值应聚焦在跨消息的关系推断和精确时序保留，而非简单的代词消解（per_message 模式已经在做）。

### 3.4 差距总结

| 机制 | MemOS | OpenViking | HydraDB | ZhiXing 现状 | 需要补充？ |
|------|-------|-----------|---------|-------------|----------|
| 按相关性召回 | ✅ | ✅ | ✅ | ✅ recall() 已支持 | 无差距 |
| 精准召回替代全量注入 | ✅ | ✅ | ✅ | ✅ recall() 已支持 | 无差距 |
| 去重/合并 | ✅ | ❌ | ❌ | ✅ content_hash | 无差距 |
| 分层加载 L0/L1/L2 | ❌ | ✅ | ❌ | ❌ | **需补充** |
| 多因子排序 | ✅ | ✅ | ✅ | ✅ 三因子评分 | 无差距 |
| 图谱增强检索 | ❌ | ❌ | ✅ 图遍历 | ✅ graph boost | ZhiXing 有基础 |
| 滑动窗口/chunk增强 | ❌ | ❌ | ✅ v_inferred | ⚠️ 已实现但方向偏差 | **需改为 chunk 增强模式** |
| 三路向量存储 | ❌ | ❌ | ✅ | ❌ | 可借鉴 |
| 查询扩展 | ❌ | ❌ | ✅ 多查询并行 | ❌ | 可借鉴 |
| Git式时序图 | ❌ | ❌ | ✅ append-only | ⚠️ 有 valid_from/until，缺决策理由 | **小改动即可** |
| Skill 记忆 | ✅ | ❌ | ❌ | ⚠️ procedural 有基础 | 可考虑 |
| Trait 反思引擎 | ❌ | ❌ | ❌ | ✅ 9步引擎 | **ZhiXing 独有优势** |

---

## 4. 定位辨析：记忆 vs 上下文管理

竞品在"记忆"和"上下文管理"之间的光谱定位：

| 系统 | 自我定位 | 插件类型 | 实际做的事 |
|------|---------|---------|----------|
| **OpenViking** | "Context Database" | `kind: "memory"` | 记忆+资源+技能的分层加载 |
| **MemOS** | "Memory Operating System" | `kind: "memory"` | 记忆存取+精准召回 |
| **Mem0** | "Memory layer for AI" | `kind: "memory"` | 存取事实，不碰对话历史 |
| **Supermemory** | "Universal Memory API" | `kind: "memory"` | 信息整合+存取 |

**关键事实：所有主流第三方记忆系统都是 `kind: "memory"` 插件，没有一个替换 ContextEngine。**

这意味着 ContextEngine 替换路线（占满上下文管理插槽）虽然技术上可行，但：

```
替换 ContextEngine 的代价：
├── 用户失去 OpenClaw 原生的全部上下文管理功能（compaction、history 管理等）
├── ZhiXing 需要自行实现大量非记忆功能才能达到 OpenClaw 原生的基线水平
├── 用户需要信任 ZhiXing 而非 OpenClaw 社区来管理核心对话体验
└── 结论：当前阶段不现实，风险远大于收益
```

**推荐定位：做最好的 `kind: "memory"` 插件**

```
ZhiXing 作为 memory 插件能做的（已经足够有竞争力）：
├── ✅ L0/L1/L2 分层加载（记忆侧省 token -80%+）
├── ✅ 精准召回替代全量注入
├── ✅ Trait 反思引擎（独有优势，竞品都没有）
├── ✅ 图谱增强检索
└── ✅ 与 OpenClaw 原生 memory-core compaction 共存
    → 记忆侧（ZhiXing）+ 对话历史侧（OpenClaw 原生）叠加 ≈ -90% token

不需要替换 ContextEngine 也能获得大部分收益。
```

**差异化叙事：**
- vs OpenViking："它管文件和资源，ZhiXing 理解你是谁（Trait 反思）"
- vs MemOS："它只存取记忆，ZhiXing 还能从对话中反思出用户画像"
- vs Mem0/Supermemory："它们只存取，ZhiXing 分层加载更省 token"

---

## 5. 三层模型架构：详细论证

ZhiXing 的训练目标不是给用户训通用 Agent 大模型，而是训练**专精于记忆管理的小模型**。关键证据：Dria 的 mem-agent（4B 参数）在记忆管理任务上击败了所有 70B+ 通用大模型，仅次于 Qwen3-235B。专精 + 独有训练数据 = 在窄任务上超越通用智能。

**为什么 ZhiXing 的记忆模型能比 DeepSeek/GPT-5 更好？**

1. **通用大模型优化所有任务的平均表现**——编码、数学、写作……它们在"记忆管理"上分配的参数和训练信号极少
2. **ZhiXing 拥有通用模型没有的训练数据**——Q-value 反馈信号（哪些记忆真的有用）、用户纠正模式、任务成功/失败与检索决策的关联
3. **类比**：AlphaFold 碾压 GPT-5 做蛋白质折叠，国际象棋引擎碾压 GPT-5 下棋。专精+独有数据=窄任务上超越通用智能

**三层模型架构：**

| 层 | 什么 | 谁用 | 训练策略 | 成本 |
|---|------|------|---------|------|
| **第一层：Q-value** | 每条记忆的效用评分 | 所有用户 | 不需要训练，运行时更新浮点数 | 零 |
| **第二层：平台 base model** | 记忆提取+管理+排序的专精小模型 | 所有用户 | 聚合全平台匿名数据训练 | ZhiXing 内部消化 |
| **第三层：per-tenant LoRA** | 在 base model 上微调的租户专用 adapter | 企业付费客户 | 用租户自身数据 LoRA 微调 | $50-300/次，按需 |

**三个模型功能，三种训练策略：**

| 模型功能 | 做什么 | 训练策略 | 原因 |
|---------|--------|---------|------|
| **记忆提取模型** | "这段对话中提取什么 fact/episode/trait？" | 统一训练，所有租户共享 | 从对话中识别事实/偏好/情感跨领域通用 |
| **记忆管理模型** | "ADD/UPDATE/DELETE/NOOP 决策" | 统一 base + 可选 per-tenant LoRA | 基础决策通用，但"何时遗忘"高度依赖场景 |
| **检索排序模型** | "这些候选记忆，按有用度排序" | 统一 base + Q-value per-user | Q-value 已是 per-user 运行时个性化 |

**为什么记忆管理需要 per-tenant LoRA：**

- 情感陪伴 Agent：永远不要忘记情感里程碑 → DELETE 阈值极高
- 业务客服 Agent：过期业务信息必须立即作废 → DELETE 阈值极低
- 编码助手 Agent：项目规范不能衰减 → 按类型差异化衰减

统一模型学到的是"平均策略"，对所有场景都不够好。per-tenant LoRA 让每个企业有自己的记忆管理策略。

**经济可行性：**

- 平台 base model：训练 $1,000-3,000/次（8B LoRA），ZhiXing 内部消化
- Per-tenant LoRA：$50-300/次/租户，Together AI 按 $0.48/1M tokens 计费
- 推理：Multi-LoRA 部署（Together AI/Fireworks/vLLM），按 base model per-token 计费
- 对月付 ¥500+ 的企业客户完全可行

**这个架构的 lock-in 效应：**

- 第一层 Q-value：用户迁走 = 失去所有积累的效用评分
- 第二层平台模型：ZhiXing 独有的记忆管理模型，竞品没有同等质量的训练数据
- 第三层 per-tenant LoRA：迁走 = 失去定制的记忆策略，需从零训练

---

## 6. 垂直整合定位

ZhiXing 处于 Agent 和存储的"中间层"，两个方向都能深度整合：

| 方向 | 创造的价值 | 用户感知 | 壁垒 |
|------|----------|---------|------|
| **向上（Agent）** | 省 token、统一画像、Trait 可视化 | 强 | 中 |
| **向下（dbay.cloud）** | 零成本休眠、记忆分支、降部署门槛 | 弱 | 强 |

详细分析见 [09-vertical-integration.md](./09-vertical-integration.md)。
