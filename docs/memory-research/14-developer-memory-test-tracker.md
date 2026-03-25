# Developer Memory — Pain Point Test Tracker

> 每一项测试直接对应 `13-claude-code-developer-memory.md` 里的具体痛点场景。
> 跑测试通过 = 痛点有代码层保障；不通过 = 需要修复。

## 如何运行

```bash
cd sdk

# 全部 developer memory 测试
pytest tests/test_dev_pain_points.py tests/test_dev_extraction.py tests/test_dev_reflection.py -v

# 只跑痛点场景
pytest tests/test_dev_pain_points.py -v

# 快速冒烟（看有没有明显 broken）
pytest tests/test_dev_pain_points.py -v -x
```

---

## PP-1：跨 session 遗忘

> 痛点：每次新 session 开 Claude Code，要重新解释「我们用 asyncpg 不用 SQLAlchemy」

| 测试 | 文件 | 状态 |
|------|------|------|
| `test_decision_survives_new_session` | test_dev_pain_points.py | ✅ |
| `test_rejection_survives_new_session` | test_dev_pain_points.py | ✅ |
| `test_multiple_decisions_all_survive` | test_dev_pain_points.py | ✅ |

**通过标准**：Session 1 存入的 decision/rejection，用独立的 NeuroMemory 实例（= 新 session）依然能 recall 到。

---

## PP-2：/compact 后记忆清空

> 痛点：context window 满触发 /compact，Claude 立刻忘记上次说「不用 MongoDB 了」

| 测试 | 文件 | 状态 |
|------|------|------|
| `test_decisions_survive_simulated_compact` | test_dev_pain_points.py | ✅ |
| `test_dev_context_expires_but_decisions_persist` | test_dev_pain_points.py | ✅ |

**通过标准**：新建 NeuroMemory 实例（模拟 compact 后的空白状态）仍能找到所有 decision/rejection/convention；同时 TTL 过期的 dev_context 不再出现。

---

## PP-3：反复建议已排除方案

> 痛点：两个月前说过「不用 GraphQL」，新 session 里 Claude 又建议 GraphQL

| 测试 | 文件 | 状态 |
|------|------|------|
| `test_rejection_surfaces_on_similar_query` | test_dev_pain_points.py | ✅ |
| `test_multiple_rejections_all_retrievable` | test_dev_pain_points.py | ✅ |
| `test_rejection_memory_type_is_correct` | test_dev_pain_points.py | ✅ |

**通过标准**：rejection 记忆在语义相关查询时浮出，且 `memory_type = 'rejection'`（不会被当成普通 fact 淹没）。

---

## PP-4：重复解释偏好

> 痛点：每个新项目都要教 Claude「我们用 FastAPI、用 asyncpg、不用 ORM」

| 测试 | 文件 | 状态 |
|------|------|------|
| `test_reflection_produces_stack_trait` | test_dev_pain_points.py | ✅ |
| `test_stack_trait_has_dimension` | test_dev_pain_points.py | ✅ |
| `test_personal_mode_does_not_produce_developer_traits` | test_dev_pain_points.py | ✅ |

**通过标准**：多次同类 decision 触发 Trait 反思后，产生带 `trait_dimension` 的开发者 Trait；personal mode 不受影响。

---

## 基础设施：提取层

| 测试 | 文件 | 验证内容 | 状态 |
|------|------|---------|------|
| `test_stores_decision_and_returns_uuid` | test_dev_extraction.py | store_single_memory 基本功能 | ✅ |
| `test_content_hash_dedup` | test_dev_extraction.py | 相同内容不重复存储 | ✅ |
| `test_dev_context_has_expiry` | test_dev_extraction.py | dev_context 7天过期 | ✅ |
| `test_all_developer_memory_types_accepted` | test_dev_extraction.py | 6种类型全部通过 CHECK 约束 | ✅ |
| `test_metadata_stored_correctly` | test_dev_extraction.py | project/rationale 字段写入 | ✅ |
| `test_extracts_dev_memories_from_messages` | test_dev_extraction.py | LLM驱动的批量提取 | ✅ |
| `test_extracted_memories_have_correct_types` | test_dev_extraction.py | 提取出的类型合法 | ✅ |
| `test_broken_llm_returns_gracefully` | test_dev_extraction.py | LLM返回垃圾时不崩溃 | ✅ |
| `test_developer_mode_does_not_extract_personal_types` | test_dev_extraction.py | developer模式不产生fact/episodic | ✅ |

---

## 基础设施：反思层

| 测试 | 文件 | 验证内容 | 状态 |
|------|------|---------|------|
| `test_developer_mode_scans_dev_types` | test_dev_reflection.py | developer模式扫描dev类型 | ✅ |
| `test_personal_mode_scans_personal_types` | test_dev_reflection.py | personal模式扫描fact/episodic | ✅ |
| `test_developer_reflection_calls_dev_llm` | test_dev_reflection.py | 路由到developer LLM路径 | ✅ |
| `test_developer_reflection_produces_trait_with_dimension` | test_dev_reflection.py | Trait有trait_dimension | ✅ |
| `test_five_valid_dimensions_stored` | test_dev_reflection.py | 五个维度都能存 | ✅ |
| `test_personal_reflection_produces_no_dimension` | test_dev_reflection.py | personal Trait无dimension | ✅ |
| `test_developer_memories_not_scanned_in_personal_mode` | test_dev_reflection.py | 模式隔离 | ✅ |

---

## 未测试项（已知空白）

这些功能已实现但尚无测试覆盖：

| 功能 | 对应文档 §| 优先级 |
|------|----------|--------|
| `zhixing_project_context` MCP 工具 | 8.4 | P1 |
| `zhixing_developer_profile` MCP 工具 | 8.4 | P1 |
| `zhixing_record_decision` MCP 工具 | 8.4 | P1 |
| `zhixing_record_rejection` MCP 工具 | 8.4 | P1 |
| `zhixing_recall` MCP 工具 | 8.4 | P1 |
| `zhixing_update_context` MCP 工具 | 8.4 | P1 |
| L0/L1/L2 分层加载（< 300 token 预算） | 6.2 P0 | P0 — ✅ 已实现，见 test_dev_layered_loading.py |
| /compact 后自动恢复 hook | 6.2 P0 | ✅ 已实现，见 15-post-compact-hook.md |
| session 内实时 auto-capture | 8.5 | P1 |
| 跨项目开发者画像 Space 共享 | 6.2 P2 | P2 |

---

## 状态图例

- ⬜ 未运行
- ✅ 通过
- ❌ 失败（需修复）
- ⏭️ 跳过（依赖未就绪）

> 更新方式：跑完测试后把 ⬜ 改成 ✅ / ❌
