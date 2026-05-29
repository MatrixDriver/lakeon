# DBay Branch and Version Benchmark Design

Date: 2026-05-29
Status: approved for planning
Target environment: DBay.cloud production

## Goal

Measure the real DBay.cloud performance of database branch and DBay version operations, then compare DBay measured results with public claims from comparable database services.

This benchmark is a public-comparison benchmark, not a stress test. It must create only temporary DBay.cloud resources, collect reproducible raw samples and summaries, verify data correctness, and delete all temporary versions, branches, and database instances before completion.

## Scope

In scope:

- Temporary database instance creation and deletion on DBay.cloud.
- Branch API operations under `/api/v1/databases/{dbId}/branches`.
- DBay self-managed version API operations under `/api/v1/databases/{dbId}/branches/{branchId}/versions`.
- Data loading, branch isolation checks, checksum checks, version metadata checks, and cleanup verification.
- Comparison against official public vendor materials.

Out of scope:

- Testing existing user or production application databases.
- Long-running saturation or destructive production pressure tests.
- Neon PITR or snapshot APIs as direct DBay version operations. They can be mentioned only as conceptual comparisons.
- Claims that DBay is faster or slower than a vendor unless the benchmark uses a directly comparable metric and explains the difference in semantics.

## Source Context

Relevant local DBay APIs:

- `BranchController`: `/api/v1/databases/{dbId}/branches`
  - `POST /`
  - `GET /`
  - `GET /tree`
  - `GET /{branchId}`
  - `POST /{branchId}/promote`
  - `POST /{branchId}/restore`
  - `DELETE /{branchId}`
- `VersionController`: `/api/v1/databases/{dbId}/branches/{branchId}/versions`
  - `POST /`
  - `GET /`
  - `GET /{versionId}`
  - `DELETE /{versionId}`
  - `POST /squash`

Version creation in `VersionService` includes:

- optional compute checkpoint,
- current timeline LSN lookup,
- snapshot timeline creation from the branch timeline at that LSN,
- metadata persistence.

## Benchmark Profile

The selected profile is `public-comparison`.

Datasets:

| Name | Target rows | Approximate size | Purpose |
| --- | ---: | ---: | --- |
| S | 10k | 10-50 MB | fast baseline |
| M | 100k | 100-300 MB | primary public-comparison dataset |
| L | 1M | 1-3 GB | validates whether branch creation remains close to O(1) at larger data size |

Each dataset must include:

- an OLTP-style table,
- a wide-row JSONB table,
- an append-only event table.

Each dataset must have row counts and checksums recorded before branch and version scenarios run.

## Metrics

Every operation sample records these fields where applicable:

- `bench_id`
- `dataset`
- `scenario`
- `operation`
- `resource_type`
- `resource_id`
- `concurrency`
- `depth`
- `attempt`
- `started_at`
- `ended_at`
- `api_latency_ms`
- `visible_latency_ms`
- `ready_latency_ms`
- `connect_latency_ms`
- `cleanup_latency_ms`
- `http_status`
- `success`
- `error_code`
- `error_message`

Latency definitions:

- `api_latency_ms`: time from sending the API request until HTTP response is fully received.
- `visible_latency_ms`: time until the resource appears in DBay list/get APIs.
- `ready_latency_ms`: time until the resource reaches the expected usable status.
- `connect_latency_ms`: time until a Postgres connection can be established and a trivial query succeeds.
- `cleanup_latency_ms`: time until delete returns and follow-up checks show the resource is absent.

Summary statistics:

- sample count,
- success count,
- error rate,
- p50,
- p95,
- p99,
- min,
- max,
- standard deviation.

## Workload Matrix

| Scenario | Samples | Concurrency | Primary metrics |
| --- | ---: | ---: | --- |
| branch create, `start_compute=false` | 100 per dataset | 1 | API accepted, visible, delete |
| branch create, `start_compute=true` | 50 per dataset | 1 | API accepted, ready, first connect |
| branch create concurrent | 100 total | 5 and 10 | p50/p95/p99, error rate, limit behavior |
| branch depth | depth 1, 5, 10 | 1 | create latency, read latency, write latency, checksum |
| version create | 100 per dataset | 1 | checkpoint plus LSN lookup plus snapshot timeline creation latency |
| version list/get | 1000 reads | 10 | control-plane read latency |
| version squash | 20 groups, 10 versions per group | 1 | squash latency, remaining version correctness |
| cleanup | all benchmark resources | 1 and 5 | versions, non-main branches, database instance deletion |

The benchmark runner must enforce hard caps on total runtime, total temporary branches, total temporary versions, and concurrent operations. Default maximum branch-create concurrency is 10.

## Correctness Checks

The benchmark is invalid unless it records:

- base dataset row counts and checksums,
- branch fork checksum matches the parent at fork time,
- parent-only writes are absent from child branches,
- child-only writes are absent from parent branches,
- version create returns a version with non-empty `id`, `branch_id`, `lsn`, `snapshot_timeline_id`, and `created_at`,
- version list/get can retrieve created versions,
- squash removes only middle versions in the selected LSN range,
- all temporary resources are removed during cleanup.

## Harness Design

Create a standalone harness under:

```text
benchmarks/dbay_branch_version/
  README.md
  config.example.yaml
  run_benchmark.py
  dbay_client.py
  pg_workload.py
  metrics.py
  cleanup.py
  compare_claims.md
  results/
```

Execution phases:

1. `preflight`
   - Verify `DBAY_API_BASE_URL`, API token, API reachability, Postgres client capability, local clock, and network baseline.
   - Abort before resource creation if preflight fails.
2. `create temporary database`
   - Create a database named `bench-branch-version-<timestamp>-<suffix>`.
   - Wait until it is running and connectable.
3. `load dataset`
   - Load selected S/M/L datasets.
   - Record counts and checksums.
4. `run branch scenarios`
   - Run sequential, concurrent, connect-ready, and depth scenarios.
5. `run version scenarios`
   - Run create/list/get/delete/squash scenarios through DBay self-managed version APIs.
6. `cleanup always`
   - Delete versions.
   - Delete all non-main benchmark branches.
   - Delete the temporary database instance.
   - Record cleanup status and residual resources.
7. `report`
   - Generate raw, summary, correctness, and comparison artifacts.

Example command:

```bash
export DBAY_API_BASE_URL="https://api.dbay.cloud:8443/api/v1"
export DBAY_API_TOKEN="..."
uv run python benchmarks/dbay_branch_version/run_benchmark.py \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --dataset S,M,L \
  --profile public-comparison
```

## Safety Rules

The harness must enforce these rules in code:

- Only create databases whose names start with `bench-branch-version-`.
- Only delete databases whose names start with `bench-branch-version-`.
- Never run write tests against existing non-benchmark databases.
- Use `try/finally` around all benchmark phases.
- Provide a standalone cleanup command that can be rerun by `bench_id`.
- Redact tokens, passwords, and full connection strings from all artifacts.
- If cleanup fails, produce `cleanup_failed.json` and make the final report clearly state that cleanup is not clean.

## Output Artifacts

Each run writes a timestamped result directory containing:

- `raw_samples.csv`
- `summary.json`
- `correctness.json`
- `cleanup_status.json`
- `comparison.md`
- `run_config.json`

`comparison.md` sections:

1. Test environment
2. DBay measured results
3. Correctness and cleanup
4. Vendor public claims
5. Interpretation
6. Raw artifacts

## External Comparison Sources

Use only official public vendor sources unless explicitly noted otherwise.

Initial comparison set:

- Neon branching docs: `https://neon.com/docs/introduction/point-in-time-restore`
- Neon database versioning with snapshots: `https://neon.com/docs/ai/ai-database-versioning`
- Xata instant branching docs: `https://xata.io/documentation/core-concepts`
- Supabase branching docs: `https://supabase.com/docs/guides/deployment/branching`
- PlanetScale branching docs: `https://planetscale.com/docs/concepts/branching`

Comparison semantics:

| Vendor area | Directly comparable? | Notes |
| --- | --- | --- |
| Neon branch create | Partially | DBay uses Neon-style timelines but adds DBay control plane and compute lifecycle paths. |
| Xata instant branching | Partially | Similar copy-on-write positioning, different implementation. |
| Supabase branching | Not fully | Supabase branches are complete preview environments and may wait for multiple services to be healthy. |
| PlanetScale branching | Not fully | Primarily schema/deploy workflow, not equivalent to Postgres data+schema copy-on-write branching. |
| DBay version create vs Neon snapshots | Conceptually comparable | DBay version creation uses self-managed version APIs and snapshot timeline creation; report checkpoint and LSN lookup overhead separately where possible. |

## Acceptance Criteria

- The benchmark can run against DBay.cloud production using only temporary resources.
- Every benchmark run emits raw samples and summary statistics.
- Correctness checks pass for all completed scenarios.
- Cleanup deletes all benchmark versions, all benchmark non-main branches, and the temporary database instance.
- Public comparison separates vendor claims from DBay measured results.
- No artifact contains secrets or full connection strings.
- The first production run uses datasets `S,M`; dataset `L` requires an explicit flag.

## Open Implementation Notes

- The implementation plan should inspect current DBay CLI/API client conventions before choosing whether the harness calls REST directly or reuses an existing client.
- The runner should support a `--dry-run` mode that performs preflight and prints the resource plan without creating cloud resources.
- The runner should support `--cleanup-only <bench_id>` for interrupted runs.
- The first real production run should start with datasets `S,M` before enabling `L`.
