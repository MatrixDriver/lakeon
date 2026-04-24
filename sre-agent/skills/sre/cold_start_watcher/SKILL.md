---
name: cold-start-watcher
description: "[ACTIVE, 每 2 分钟 tick] 监控 dbay.cloud compute 冷启动 > 5s，自动开诊断 session。真正执行体在 main.py croniter 里，不是此 SKILL.md。"
version: v0.1
personality: sre
---

# cold-start-watcher

**当前运行状态：active**（由同容器内的 `main.py` croniter 循环每 2 分钟调用一次，**不是** hermes 的 cron 系统）

## 你在干什么

每 2 分钟扫一次 dbay-logs 表里最近 3 分钟的 `lakeon-api` 日志，找匹配模式
`compute started in {ms}ms for tenant={t} db={d}`。

对 `ms > 5000` 的每一条：
1. 去重：10 分钟内同一 (tenant, db) 已开过 session 就跳过
2. 新开一个 `type=incident` session，tags 包含 `severity:medium` / `component:compute` / `skill:cold-start-watcher`
3. SkillLedger 记录这次触发
4. 调用 LLM（你自己）做根因诊断，产生分支假设 + 证据收集
5. 通过飞书 REST API 推一张 card 给 Jacky

## 数据落盘位置

- Session 目录: `/data/hermes/data/sessions/YYYY/MM/DD/sess_<ts>_<hash>/`
- Skill 触发台账: `/data/hermes/data/skills-ledger/cold-start-watcher/invocations.jsonl`

## 当 Jacky 问你"最近有冷启动告警吗 / 你在监控吗"时

你**正在**监控（别否认）。如何查证：

- 列 `ls /data/hermes/data/sessions/$(date +%Y)/$(date +%m)/$(date +%d)/` 看今天开过的 incident
- 读 `skills-ledger/cold-start-watcher/invocations.jsonl` 看触发次数
- 读 `skills-ledger/cold-start-watcher/stats.json` 看触发成功率

如果目录/文件不存在或为空，意思是**最近 2 分钟窗口内没有 compute 冷启动 > 5 秒**——这是好事，不是监控没启动。

## 不做什么

- 不改生产（只读诊断）
- 不执行修复建议——只报告
- 不用 hermes 的 cron 触发器（hermes skill 是 prompt-only，真正的调度在 main.py）
