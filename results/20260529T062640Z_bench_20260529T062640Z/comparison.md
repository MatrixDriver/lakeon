# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T062640Z`

## Test Environment

```json
{
  "api_base_url": "https://api.dbay.cloud:8443/api/v1",
  "profile": "public-comparison",
  "compute_size": "1cu",
  "datasets": [
    "S",
    "M"
  ],
  "benchmark_status": "failed",
  "error": {
    "type": "RuntimeError",
    "message": "DBay database has no branches"
  }
}
```

## DBay Measured Results

```json
{
  "database/create/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 2149.011981993681,
      "max": 2149.011981993681,
      "p50": 2149.011981993681,
      "p95": 2149.011981993681,
      "p99": 2149.011981993681,
      "stddev": 0.0
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 147.5344590144232,
      "max": 147.5344590144232,
      "p50": 147.5344590144232,
      "p95": 147.5344590144232,
      "p99": 147.5344590144232,
      "stddev": 0.0
    }
  }
}
```

## Correctness and Cleanup

```json
{
  "benchmark_status": "failed",
  "error": {
    "type": "RuntimeError",
    "message": "DBay database has no branches"
  },
  "cleanup": {
    "bench_id": "bench_20260529T062640Z",
    "database_id": "db_f4cfa3ec",
    "database_name": "bench-branch-version-20260529T062640Z-db",
    "cleanup_status": "clean",
    "failures": []
  }
}
```

## Vendor Public Claims

| Vendor | Area | Source | Comparison note |
| --- | --- | --- | --- |
| Neon | branching | https://neon.com/docs/introduction/point-in-time-restore | Partially comparable. DBay uses Neon-style timelines but includes DBay control-plane and compute lifecycle paths. |
| Neon | database versioning with snapshots | https://neon.com/docs/ai/ai-database-versioning | Conceptually comparable to DBay version create, but DBay uses its self-managed version API. |
| Xata | instant branching | https://xata.io/documentation/core-concepts | Partially comparable. Both position around copy-on-write branching; implementation differs. |
| Supabase | branching | https://supabase.com/docs/guides/deployment/branching | Not fully comparable. Supabase branches are complete preview environments. |
| PlanetScale | branching | https://planetscale.com/docs/concepts/branching | Not fully comparable. PlanetScale branching is primarily schema/deploy workflow. |

## Interpretation

Measured DBay results are production observations from this run. Vendor entries are public claims or product documentation and are not measured in this harness.

## Raw Artifacts

- [raw_samples.csv](raw_samples.csv)
- [summary.json](summary.json)
- [correctness.json](correctness.json)
- [cleanup_status.json](cleanup_status.json)
