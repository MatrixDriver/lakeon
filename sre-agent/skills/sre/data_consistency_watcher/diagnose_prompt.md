Found {count} violation(s) of invariant rule `{rule}`:

{violations_json}

Rule description: {description}

Context:
- Severity (server-classified): {severity}
- Self-healable by an automated reconciler? {self_healable}
- Max violation age: {max_age_seconds} seconds

When `self_healable=yes`, the system has a periodic L3 reconciler that should
have fixed transient drift on its own. Reaching this prompt means the violation
**survived past the reconciler's window** — so the hypothesis should focus on
why the reconciler failed or why the row is stuck, NOT on the original cause
of the drift (which the reconciler is designed to absorb).

Common root-cause classes — pick the one that fits the data; do NOT assume
event-listener/transactional bugs unless you can point at a specific listener
in the code path. Examples:
- Reconciler service crashed / not running (check service health)
- Pod genuinely missing in the cluster (was deleted, never recreated)
- Worker stall (queue not draining; thread pool / DB connection exhausted)
- Listener / @TransactionalEventListener wired wrong (only if there IS one)
- Tx commit ordering race
- Manual operator action left orphans

Write a short (≤ 200 字) hypothesis. Be honest about confidence — if the data
doesn't pin one cause, say so and list what evidence would resolve it. Do not
invent code paths (don't reference @AfterCommit, REQUIRES_NEW, etc unless they
appear in the actual code).

Output markdown with these sections:
## 根因假设 (confidence 0-1)
## 建议调查
## 建议修复动作
