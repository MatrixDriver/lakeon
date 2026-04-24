---
name: daily_briefing
description: Morning 9:00 / Evening 22:00 / Weekly Monday 9:00 — summarise SRE commit log + ledger to DM.
version: v0.1
triggers:
  cron_morning: "0 1 * * *"     # 9:00 Asia/Shanghai
  cron_evening: "0 14 * * *"    # 22:00 Asia/Shanghai
  cron_weekly:  "0 1 * * 1"     # Monday 9:00 Asia/Shanghai
personality: sre
---

# daily_briefing

3 briefings, all use `BriefingRunner`:

- **morning (9:00)**: 昨夜动态 + 未关闭 incidents + 今日预期 (upcoming skill stats thresholds)
- **evening (22:00)**: 今日总览 + 未解决 incidents + 明日 follow-up
- **weekly (周一 9:00)**: 本周 pattern 聚类 + skill 准确率 + 趋势

Each briefing:
1. Query commit log (`list_sessions(type="incident", since=N)`) + `SkillLedger.stats(*)`
2. Pack into LLM prompt (morning/evening/weekly variant)
3. LLM writes markdown brief (≤ 300 字)
4. Open `type=briefing` session, tag `[kind:morning|evening|weekly]`, conclude with brief
5. Return text for main.py to DM Jacky

Briefings do NOT open incidents — they're pure read + summary. But they DO get
archived as `type=briefing` sessions so you can look back at "what did yesterday's
morning brief say".
