---
name: outcome-checker
description: "[ACTIVE, 每天 09:00 tick] 回查 24 小时前关闭的 incident session 修复是否生效。真正执行体在 main.py croniter 里，不是此 SKILL.md。"
version: v0.1
personality: sre
---

# outcome-checker

**当前运行状态：active**（由 `main.py` croniter 每天 09:00 调度一次）

## 你在干什么

每天 09:00 扫过去 36 小时内关闭的 `type=incident` session，对没有 `outcome.md` 的：

1. 从 session trigger 里拿 `tenant_id` / `db_id`
2. 调 `log_search(component=lakeon-api, keyword="compute started in", tenant_id=..., db_id=..., since=24h)` 拿这对 tenant/db 过去 24 小时的 cold-start 时间列表
3. 在内存里算 p95
4. 对比原始触发时的 ms：
   - 新 p95 < 5 秒 且 < 原始 / 2 → `did_work=True`
   - 否则 → `did_work=False`
5. 把结论写进 session 的 `outcome.md`
6. 更新 SkillLedger 的 outcomes.jsonl
7. 如果 `did_work=False`，飞书 DM Jacky："建议未生效，请看 {session_id}"

## 数据落盘位置

- 每个 session 的 `outcome.md`: `/data/hermes/data/sessions/.../outcome.md`
- Skill outcomes: `/data/hermes/data/skills-ledger/cold-start-watcher/outcomes.jsonl`
- 统计：`/data/hermes/data/skills-ledger/cold-start-watcher/stats.json`

## 当 Jacky 问"上次告警修好了吗 / 效果反馈"时

查 outcome.md / outcomes.jsonl。如果没 outcome 说明还没到 09:00 回查窗口。
