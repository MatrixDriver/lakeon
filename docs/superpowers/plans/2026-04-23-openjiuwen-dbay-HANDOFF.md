# openJiuwen × dbay 项目 Handoff

> **给接手的 Claude Code 会话读**
> 最后更新：2026-04-23
> 用户：Jacky Li (jackylk on gitcode / GitHub, Dianesteen1993@yahoo.com)

## 三句话背景

1. **目标**：让 openJiuwen 社区能通过 `pip install openjiuwen-dbay-store` 接入 dbay (Neon Serverless PG + pgvector) 作为存储后端，插件本身独立发包，不进 openJiuwen 主仓。
2. **分三阶段**：Phase 1 = 给 openJiuwen 主仓加插件发现机制；Phase 2 = 独立仓 + PyPI；Phase 3 = 社区推广。
3. **当前状态**：Phase 1 MR 已开等 review；Phase 2 代码/测试/文档全部 ready 但未发 PyPI（依赖 Phase 1 合入）；Phase 3 已 draft 公告/博客文案，等启动。

## 当前代码位置

| 仓库 | 路径 | 分支 | commit | 状态 |
|---|---|---|---|---|
| openJiuwen fork | `~/code/agent-core/.worktrees/plugin-registry/` | `feat/plugin-registry` | 5 commits on top of `origin/develop` | 已 push 到 `gitcode.com/jackylk/agent-core` → MR #1155 |
| openjiuwen-dbay-store | `~/code/openjiuwen-dbay-store/` | `main` | `4de3a76` 最新 | 已 push 到 `gitcode.com/jackylk/openjiuwen-dbay-store`（public） |
| Phase 1 老本地 | `~/code/agent-core/` | `feat/dbay-store` | `8e31a34` | **废弃**（in-tree 方案，被 Phase 1+2 替代，合入后应删） |

## Phase 1 状态（MR #1155）

**URL**：https://gitcode.com/openJiuwen/agent-core/merge_requests/1155
**标题**：`feat(store): pluggable vector-store backends via entry_points`
**Base**：`openJiuwen/agent-core:develop`
**Head**：`gcw_0TpbO03A/agent-core:feat/plugin-registry`

**5 commits**（from oldest to newest）：
```
test(store): add regression tests for built-in vector-store factory dispatch
test(store): add failing tests for vector-store plugin framework
feat(store): pluggable vector-store backends via entry_points + register API
docs(store): declare Base*Store ABCs as stable public plugin APIs
docs: plugin author guide for vector-store backends (zh + en)
```

**什么 merge 后改变了**：
- `create_vector_store()` 变成三级解析：built-in → explicit register → entry_points(`openjiuwen.vector_stores`)
- 新增公共 API `register_vector_store(name, factory)` 与常量 `VECTOR_STORE_ENTRY_POINT_GROUP`
- `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` docstring 标注 stable public plugin API
- 中英双份插件作者指南上线

**CLA**：用户已签个人 CLA。

**MR 正文**：用了 openJiuwen 社区标准模板（`/kind feature` + 5 项 Self-checklist），正文 4K 字结构化。

**已知 reviewer 可能反馈**：
- 设计章节 `[ ]` 未勾（首次贡献者，请 Maintainer 评审）
- 接口章节 `[ ]` 未勾（新增公共 API，请接口评审组织评审）
- 其他 `[x]` 已勾：测试、验证、文档

## Phase 2 状态（openjiuwen-dbay-store）

**URL**：https://gitcode.com/jackylk/openjiuwen-dbay-store
**主分支**：`main`，4 commits 已 push
**PyPI**：**未发布**（pyproject `openjiuwen>=0.1.9` 是占位，等 Phase 1 合入后换真实版本号）
**文档**：全中文优先，英文版 `.en.md` 并存
**测试**：36 单测 + 19 E2E + 1 SKIP（integration，等 Phase 1）
**Benchmark**：本地 pgvector baseline 已跑——p50 4.45ms / p99 9.43ms / insert 81.7 QPS

**关键文件布局**：
```
src/openjiuwen_dbay_store/
  __init__.py      # 中文 docstring
  _version.py      # 0.1.0
  db.py            # DbayDbStore + ping() + DSN redact
  errors.py        # 4 个中文异常类
  kv.py            # DbayKVStore
  vector.py        # DbayVectorStore + 中文 _ensure_pgvector 错误
tests/unit/        # 5 文件 / 36 测试
tests/e2e/         # 1 文件 / 19 测试 + conftest
examples/          # quickstart.py + long_term_memory.py
benchmarks/        # run.py + RESULTS.md + results/*.json
.github/workflows/ # e2e.yml (gitcode 不执行但保留)
scripts/test.sh    # 本地 docker 跑 pgvector 的 canonical CI
README.md / CONTRIBUTING.md / CHANGELOG.md  # 中文主版
*.en.md            # 英文辅版
```

## Phase 3 状态

**已做的**：
- Benchmark harness + 本地 baseline 已 commit 到 Phase 2 repo
- 社区公告草稿：`2026-04-23-phase3-community-announcement.md`
- 博客草稿：`2026-04-23-phase3-blog-post.md`
- jiuwenclaw 调研：`2026-04-23-phase3-jiuwenclaw-investigation.md`

**已决策**：
- **放弃 jiuwenclaw 集成目标**（jiuwenclaw 用独立 SQLite+FTS memory 架构，不经过 openjiuwen `LongTermMemory`；bridge 包工作量 1-2 周且价值不确定，不做）
- Phase 3 scope 限定 openjiuwen agent-core 直接用户

## Phase 1 merge 后的收尾 checklist

### 必做（按顺序）

- [ ] **1. 版本对齐**
  - 确认 Phase 1 合入后 openjiuwen 发的新 release tag（可能是 0.1.11 / 0.2.0）
  - 改 `~/code/openjiuwen-dbay-store/pyproject.toml` 的 `openjiuwen>=0.1.9` → `openjiuwen>=<新版本>`
  - commit `chore: pin openjiuwen>=<新版本> for entry_points support`

- [ ] **2. 真实环境验证**（关键——当前 integration test 是 SKIP 状态）
  ```bash
  cd ~/code/openjiuwen-dbay-store
  rm -rf .venv && uv venv .venv --python 3.11 && source .venv/bin/activate
  uv pip install openjiuwen==<新版本> -e ".[test]"
  python -m pytest tests/unit/ -v    # 必须 37 passed, 0 skipped（integration 不再 SKIP）
  DBAY_E2E_DSN="postgresql://..." python -m pytest tests/e2e/ -v   # 19 passed
  ```

- [ ] **3. PyPI 发布**
  - PyPI token 在 `~/.dbay/tokens.json` 的 `PYPI_API_KEY`
  ```bash
  cd ~/code/openjiuwen-dbay-store
  source .venv/bin/activate
  uv pip install build twine
  python -m build
  twine check dist/*
  TWINE_USERNAME=__token__ TWINE_PASSWORD=$(jq -r .PYPI_API_KEY ~/.dbay/tokens.json) twine upload dist/*
  git tag v0.1.0
  GITCODE_TOKEN=$(cat ~/.claude/projects/-Users-jacky-code-lakeon/memory/user_gitcode_credentials.md | grep Token | cut -d'`' -f2)
  git remote set-url origin "https://jackylk:${GITCODE_TOKEN}@gitcode.com/gcw_0TpbO03A/openjiuwen-dbay-store.git"
  git push origin main --tags
  git remote set-url origin "https://gitcode.com/gcw_0TpbO03A/openjiuwen-dbay-store.git"
  ```
  - 验证：在 /tmp 新建 venv，`pip install openjiuwen openjiuwen-dbay-store`，跑 examples/quickstart.py

- [ ] **4. Phase 1 follow-up PR（补参考示例）**
  - 分支从 `develop` 起 `docs/add-dbay-plugin-example`
  - 改 `docs/zh/2.开发指南/高阶用法/插件开发-存储后端.md` 和 `docs/en/.../Store Plugin Development.md`
  - 把底部 "参考示例" 一句话替换成表格：
    ```markdown
    | 插件 | 后端 | PyPI | 仓库 |
    |---|---|---|---|
    | openjiuwen-dbay-store | Neon Serverless PG + pgvector | [pypi](https://pypi.org/project/openjiuwen-dbay-store/) | [gitcode](https://gitcode.com/jackylk/openjiuwen-dbay-store) |
    ```
  - 开新 MR 对 develop（用社区模板，`/kind docs`）

- [ ] **5. 社区公告发布**
  - 取 `~/code/lakeon/docs/superpowers/plans/2026-04-23-phase3-community-announcement.md`
  - 替换占位 `<PHASE_1_VERSION>` 为真实版本号
  - 发到 `openJiuwen-ai/community`（具体入口：用户自选 issue/discussion）

- [ ] **6. 博客发布**
  - 取 `~/code/lakeon/docs/superpowers/plans/2026-04-23-phase3-blog-post.md`
  - 替换占位
  - 发到 dbay.cloud 博客 / WeChat 公众号（用户自选）
  - 风格约束：**不出现商业性内容**（按 feedback_no_commercial）

### 清理类（顺手做）

- [ ] **7. 更新 CHANGELOG**：把 `[0.1.0] —— 未发布` 改成 `[0.1.0] — 2026-XX-XX`
- [ ] **8. 删本地废弃分支**：
  ```bash
  git -C ~/code/agent-core worktree remove .worktrees/plugin-registry
  git -C ~/code/agent-core branch -D feat/plugin-registry feat/dbay-store
  ```
- [ ] **9. gitcode repo topics**（API 没暴露，需用户 web UI 手设）：`openjiuwen`, `pgvector`, `ai-agent`, `plugin`, `memory`, `vector-database`, `neon`, `dbay`

### 响应类（等外部信号）

- [ ] **10. Phase 1 review 反馈**：在 `feat/plugin-registry` 分支新增 commit（不 force-push），保留 TDD 历史
- [ ] **11. 用户反馈**：每周看 pypistats + gitcode issues

### 可选（有余力再做）

- [ ] **12. 第二个 backend 插件**（Qdrant/Weaviate/Redis Vector 任选）证明模式可复制
- [ ] **13. Benchmark 扩展**：对比 chroma / milvus / dbay.cloud；调 `ef_search` 召回曲线

## 关键凭据

- **gitcode token**（可 fork/push，**缺 all_projects** 不能建新仓）：保存在 dbay 记忆 + `~/.claude/projects/-Users-jacky-code-lakeon/memory/user_gitcode_credentials.md`
- **PyPI token**：`~/.dbay/tokens.json` 的 `PYPI_API_KEY`
- **DBay API key**：`~/.claude/projects/-Users-jacky-code-lakeon/memory/user_api_credentials.md`

## 相关 plan 文档

- 总路线图：`2026-04-23-openjiuwen-dbay-roadmap.md`
- Phase 1 plan：`2026-04-23-phase1-plugin-framework.md`
- Phase 1 PR draft（已用）：`2026-04-23-phase1-PR-draft.md`
- Phase 2 plan：`2026-04-23-phase2-dbay-store-repo.md`
- Phase 3 plan：`2026-04-23-phase3-community-rollout.md`
- Phase 3 jiuwenclaw 调研：`2026-04-23-phase3-jiuwenclaw-investigation.md`
- Phase 3 公告 draft：`2026-04-23-phase3-community-announcement.md`
- Phase 3 博客 draft：`2026-04-23-phase3-blog-post.md`
- **本 handoff**：`2026-04-23-openjiuwen-dbay-HANDOFF.md`

## 给接手 CC 的一句话

打开这份文档，看"Phase 1 merge 后的收尾 checklist"，按 1→6 顺序执行就能结束这个项目。每一步都是 5-15 分钟粒度。中间如果 PR review 有反馈，先处理第 10 项。
