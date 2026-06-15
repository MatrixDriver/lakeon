# E2E Suites

Lakebase Core tests remain in this repo:

```bash
xargs python3 -m pytest -v -s < tests/e2e/lakebase_core.txt
```

DBay Agent migration tests move to the `dbay-agent` repo:

```bash
cat tests/e2e/dbay_agent_migration.txt
```

Do not mark failing tests as skipped to hide failures. During migration, move each test with the module it validates.

