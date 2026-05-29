# DBay Branch and Version Benchmark

This harness measures DBay.cloud branch and DBay self-managed version operations using temporary databases only.

## Required environment

```bash
export DBAY_API_TOKEN="..."
export DBAY_API_BASE_URL="https://api.dbay.cloud:8443/api/v1"
```

## Dry run

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --dry-run
```

## First production run

The first production run uses datasets `S,M`. Dataset `L` requires `--allow-large-dataset`.

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --datasets S,M
```

## Cleanup an interrupted run

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --cleanup-only <bench_id>
```

## Safety

The runner only creates and deletes database names beginning with `bench-branch-version-`.
It deletes versions first, then non-main branches, then the temporary database instance.
Artifacts redact tokens, passwords, and full connection strings.
