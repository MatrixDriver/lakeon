# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T065313Z`

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
    "message": "DBay database entered ERROR before default branch was available: \u521b\u5efa\u8fc7\u7a0b\u88ab\u670d\u52a1\u91cd\u542f\u4e2d\u65ad\uff0c\u8bf7\u5220\u9664\u540e\u91cd\u65b0\u521b\u5efa"
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
      "min": 2144.841836998239,
      "max": 2144.841836998239,
      "p50": 2144.841836998239,
      "p95": 2144.841836998239,
      "p99": 2144.841836998239,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 20,
    "success_count": 20,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 141.25496800988913,
      "max": 203.20690600783564,
      "p50": 143.22693001304287,
      "p95": 184.86114154948154,
      "p99": 199.5377531161648,
      "stddev": 20.268048866547094
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
    "message": "DBay database entered ERROR before default branch was available: \u521b\u5efa\u8fc7\u7a0b\u88ab\u670d\u52a1\u91cd\u542f\u4e2d\u65ad\uff0c\u8bf7\u5220\u9664\u540e\u91cd\u65b0\u521b\u5efa"
  },
  "cleanup": {
    "bench_id": "bench_20260529T065313Z",
    "database_id": "db_676cd342",
    "database_name": "bench-branch-version-20260529T065313Z-db",
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
