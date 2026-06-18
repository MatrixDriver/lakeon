# E2E Suites

Lakebase Core tests remain in this repo:

```bash
cargo build --release --manifest-path dbay-fuse/Cargo.toml
xargs python3 -m pytest -v -s < tests/e2e/lakebase_core.txt
```

DBay Agent tests live in the `dbay-agent` repo:

```bash
cd ../dbay-agent
python3 -m pytest tests/e2e -v -s
```

This repository should only keep Lakebase and LakebaseFS tests.
