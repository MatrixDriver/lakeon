An error signature has fired across {distinct_tenant_count} tenants in the last {window}:

- Component: `{component}`
- Error signature: `{error_signature}`
- Total occurrences: {total_occurrences}

Hypothesize the single fault domain causing this. Consider:
- Shared external dependency (upstream API, DNS, cert expiry)
- Shared config change (env var, feature flag, deploy)
- Resource exhaustion (connection pool, executor threads, disk)
- Single-point-of-failure infra (one pod, one DB, one service)

Output ≤ 150 字 markdown:
## 最可能根因
## 下一步验证 (1-2 条具体指令)
