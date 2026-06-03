# openJiuwen × dbay 插件生态路线图

> 规划人：Jacky Li
> 目标：让 openJiuwen 社区用户能通过 `pip install openjiuwen-dbay-store` 零改代码使用 dbay (Neon Serverless PG + pgvector) 作为存储后端。
> 约束：dbay 相关实现不进 openJiuwen 主仓；上游只做"开门"这一件事。

---

## 背景

当前 `openjiuwen/core/foundation/store/__init__.py:create_vector_store()` 用 if/elif 硬编码内置 backend（chroma / milvus / gaussvector）。要新增 backend 必须 in-tree，不支持第三方独立发布。

本路线图分 3 阶段，每阶段产出独立可交付物：

1. **Phase 1** — openJiuwen 上游 PR，给 `create_vector_store()` 加入口点发现机制
2. **Phase 2** — 建 `openjiuwen-dbay-store` 独立仓库，PyPI 发首版
3. **Phase 3** — 社区曝光 + jiuwenclaw 集成 + 文档推广

---

## Phase 1：插件框架上游 PR

**目标仓库**：`gitcode.com/openJiuwen/agent-core`
**分支**：`feat/plugin-registry`（从 `develop` 开始）
**详细计划**：[2026-04-23-phase1-plugin-framework.md](./2026-04-23-phase1-plugin-framework.md)

**交付物**：
- `create_vector_store()` 支持 3 级解析：built-in → 显式注册 → entry_points
- 新增 `register_vector_store(name, factory)` 公共 API
- `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` 的 "stable public plugin API" docstring
- 插件作者指南（docs/zh + docs/en）
- 6 个单测：3 个保障 built-in 回归 + 3 个验证新机制
- CLA 已签

**验收**：
- 现有 built-in backend 行为完全不变（所有已有测试 PASS）
- 本地 site-packages 放一个假插件 wheel，`create_vector_store("fake")` 能发现它
- PR 对 develop 开，CI 绿灯

**不做**：
- 不引入 dbay 实现
- 不改 KV / DB 工厂（它们没有工厂；插件直接 `from X import Y`）
- 不动现有 backend（chroma / milvus / gauss 的代码完全不碰）

---

## Phase 2：`openjiuwen-dbay-store` 独立仓库

**目标仓库**：`gitcode.com/jacky-li/openjiuwen-dbay-store`（新建）
**依赖**：Phase 1 PR merged + 新版 openjiuwen 发到 PyPI
**详细计划**：[2026-04-23-phase2-dbay-store-repo.md](./2026-04-23-phase2-dbay-store-repo.md)

**交付物**：
- 独立 Python 包 `openjiuwen-dbay-store`（约 1300 行，完整复制自 `feat/dbay-store`）
- `pyproject.toml` 声明 entry_points：`dbay = "openjiuwen_dbay_store.vector:DbayVectorStore"`
- 完整 README / Quickstart（中英）
- 错误信息改造（DSN 错误 / pgvector 缺失 / 权限不足 → 友好指引）
- E2E 测试 + CI（gitcode Actions 起 `postgres:16 + pgvector` 镜像跑）
- LICENSE（独立 MIT，不带华为 copyright header）
- 发 PyPI `0.1.0`

**验收**：
- 空 venv `pip install openjiuwen openjiuwen-dbay-store` → `create_vector_store("dbay", dsn=...)` 返回可用实例
- 跑 LongTermMemory 示例（agent_evolving / 单 agent demo）能正常读写
- PyPI 页面有 badge / quickstart / link to openJiuwen

**不做**：
- 不提供数据迁移工具（Milvus → Dbay），放 Phase 3+
- 不包含 L2 记忆 SaaS 适配（LongTermMemory 替换为 dbay.cloud API），那是 L2 独立项目

---

## Phase 3：社区曝光 + jiuwenclaw 集成

**目标仓库**：`gitcode.com/openJiuwen-ai/jiuwenclaw` + openJiuwen community 仓
**依赖**：Phase 2 PyPI 发布完成
**详细计划**：[2026-04-23-phase3-community-rollout.md](./2026-04-23-phase3-community-rollout.md)

**交付物**：
- jiuwenclaw `jiuwenbox` / 配置层识别 `DBAY_DSN` 环境变量，让 backend=dbay 可通过配置选择
- jiuwenclaw 的 PR（不是上游 agent-core）
- openJiuwen community 仓发布公告（"第一个外部 backend 插件案例"）
- dbay.cloud 侧发博客：如何用 openJiuwen + dbay 快速搭起带长期记忆的 agent
- benchmark 对比（dbay vs 本地 pgvector vs milvus，单节点 QPS + 召回率 + 单条成本）
- 可观测性：连接池 / 查询耗时 / 错误率 hook 打到 openJiuwen 现有 logger

**验收**：
- jiuwenclaw 新用户跟教程走，15 分钟内跑起来，能看到 "dbay" 选项
- 公告发布后两周内有至少 1 个社区开发者按此模式新贡献另一个 backend 插件（例如 Qdrant）

**不做**：
- 不追求 dbay 成为默认 backend（保持社区中立）

---

## 依赖图

```
Phase 1 (上游 PR)
    │
    ├─> openJiuwen 新版上 PyPI (等上游 release)
    │
    └─> Phase 2 (独立仓库 + PyPI 发首版)
            │
            └─> Phase 3 (jiuwenclaw 集成 + 公告 + benchmark)
```

**关键依赖**：Phase 2 的 entry_points 要落到 `openjiuwen.vector_stores` 这个 group name；这个名字在 Phase 1 的 PR 里敲定，一旦上游合入就不能变。Phase 1 PR 正文里必须给出"我打算用这个 group name 发插件"的示例，确保社区对命名达成一致。

---

## 时序建议

| 周 | 动作 |
|---|---|
| W1 | Phase 1：本地完成代码 + 测试 + 文档；签 CLA；提交 PR |
| W2 | Phase 1：跟 maintainer review 交互；并行起草 Phase 2 代码骨架 |
| W3 | 假设 Phase 1 merge：立刻做 Phase 2；跑完整 E2E；发 PyPI |
| W4 | Phase 3：jiuwenclaw PR；写公告；做 benchmark |

**风险缓解**：若 Phase 1 review 超过 2 周未合入，Phase 2 可以先做独立仓库骨架，但 entry_points 机制暂用 "explicit register" 形态（用户在 app init 手动 `openjiuwen.register_vector_store("dbay", DbayVectorStore)`），这是纯 Python 用法，不需要上游改动。待 entry_points merge 后升级。

---

## 成功指标

- **技术**：空 venv 三条命令（`uv venv / pip install openjiuwen openjiuwen-dbay-store / python run.py`）跑起来 LongTermMemory + dbay
- **社区**：有第二个社区贡献者按 plugin 模式独立发了另一个 backend
- **业务**：dbay.cloud 的 openJiuwen 来源日活 > 0（通过 PyPI downloads / UA tracking）

---

## 相关文档

- Phase 1 详细计划：`2026-04-23-phase1-plugin-framework.md`
- Phase 2 详细计划：`2026-04-23-phase2-dbay-store-repo.md`
- Phase 3 详细计划：`2026-04-23-phase3-community-rollout.md`
- 当前 dbay 实现（将在 Phase 2 搬迁）：`~/code/agent-core/`, branch `feat/dbay-store`, commit `8e31a34`
