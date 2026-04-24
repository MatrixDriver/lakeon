---
name: domain_glossary
description: dbay 领域速查 — 对象模型 + 症状映射 + 标准诊断路径。LLM 看到人类语义(数据库名/tenant 名/症状描述)时优先参考此文件。
version: v0.1
personality: sre
---

# dbay 领域速查

## 对象模型

```
Tenant (租户, 人类可读名如 "perf-team")
  └── Database (数据库, 人类可读名如 "tcph-bench", 内部 db_id 是 UUID)
        └── Compute Pod (一个 k8s pod, compute_host 字段记录)
              └── Pageserver (attach to tenant_id)
```

**关键**: log 里只有 UUID (tenant_id, db_id),**没有人类可读名**。用户问"tcph-bench",你得先 `find_database(name="tcph-bench")` 拿到 db_id + tenant_id 才能去 log 里追。

## 用户问句 → 标准工具路径

| 用户问 | 别这么做 ❌ | 该这么做 ✅ |
|---|---|---|
| "为什么 X 唤醒失败" | 直接 `log_search(keyword="X")` | `database_status(name_or_id="X")` 一次拿 状态+cold_start+events |
| "X 租户健康吗" | 瞎翻 log | `find_tenant(name="X")` 拿到 id → 每个 db 调 `database_status` |
| "X 数据库 / tenant 是什么" | 搜 log | `find_database(name="X")` / `find_tenant(name="X")` |
| "为什么这么多 tenant 都挂了" | 看单个 tenant | `multi_tenant_blast_radius(window="15m")` |
| "X 任务卡住了" | log_search 漫游 | `stuck_task_query(threshold_minutes=10)` |
| "为什么 X tenant 部署失败" | 猜 | `pod_create_failures(since="30m")` + 按 category 读 |

## 症状 → 工具映射

见 `symptom_map.md`(自动加载的延展 reference)。

## 标准 5 步诊断(遇到"为什么 X 慢/挂/失败")

1. **标识**: X 是 database 名?tenant 名?内部 UUID?先 `find_database` / `find_tenant` 确定。
2. **快照**: `database_status(name_or_id=X)` — 拿状态 + cold_start_p95 + 最近 1h events。
3. **近因**: 如果 status 异常,`log_search(tenant_id=..., since="30m")` 或 `log_errors(component=...)`。
4. **横向**: 检查是否多 tenant 同时有事 — `multi_tenant_blast_radius(window="15m")`。
5. **结论**: 综合给根因 + 建议 action。如果置信度低,**说**"证据不够,建议再查 Y"。

## 关键原则

- **"我不知道"是合法答案**: 如果工具没返回相关信息,就说"日志/数据里没有,建议查 Y";不要硬编故事。
- **每次 log_search 都指明 since**: 没有 since 默认 1h,太长会捞到无关数据。
- **数字要精确**: "cold_start_p95 = 2100ms" 比"挺慢的"强。
