# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T063201Z`

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
    "type": "ProgrammingError",
    "message": "incomplete placeholder: '%'; if you want to use '%' as an operator you can double it up, i.e. use '%%'"
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
      "min": 2320.6450619909447,
      "max": 2320.6450619909447,
      "p50": 2320.6450619909447,
      "p95": 2320.6450619909447,
      "p99": 2320.6450619909447,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 30,
    "success_count": 30,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 136.49542100029066,
      "max": 235.5137319827918,
      "p50": 145.31972150143702,
      "p95": 216.02649849664877,
      "p99": 233.99392724299105,
      "stddev": 28.459926813508353
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 144.34580400120467,
      "max": 144.34580400120467,
      "p50": 144.34580400120467,
      "p95": 144.34580400120467,
      "p99": 144.34580400120467,
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
    "type": "ProgrammingError",
    "message": "incomplete placeholder: '%'; if you want to use '%' as an operator you can double it up, i.e. use '%%'"
  },
  "cleanup": {
    "bench_id": "bench_20260529T063201Z",
    "database_id": "db_0c97b0a4",
    "database_name": "bench-branch-version-20260529T063201Z-db",
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
