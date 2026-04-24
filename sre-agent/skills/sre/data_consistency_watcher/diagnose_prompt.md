Found {count} violations of invariant rule `{rule}`:

{violations_json}

Rule description: {description}

Write a short (≤ 200 字) root-cause hypothesis. Consider:
- Event ordering / @AfterCommit listener missed
- Transaction commit race before downstream consumer
- REQUIRES_NEW tx scope missing
- Listener not wrapped in @Transactional
- Retry/rollback left orphans

Output markdown with these sections:
## 根因假设 (confidence 0-1)
## 建议调查
## 建议修复动作
