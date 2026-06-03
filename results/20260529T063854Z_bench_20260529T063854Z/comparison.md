# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T063854Z`

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
    "type": "ConnectionTimeout",
    "message": "connection timeout expired"
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
      "min": 2205.899309978122,
      "max": 2205.899309978122,
      "p50": 2205.899309978122,
      "p95": 2205.899309978122,
      "p99": 2205.899309978122,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 29,
    "success_count": 29,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 139.67001999844797,
      "max": 217.9914930020459,
      "p50": 148.75696299714036,
      "p95": 195.13338260003366,
      "p99": 211.74474116298367,
      "stddev": 22.289505118740554
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 147.6159410085529,
      "max": 147.6159410085529,
      "p50": 147.6159410085529,
      "p95": 147.6159410085529,
      "p99": 147.6159410085529,
      "stddev": 0.0
    }
  },
  "branch_create_without_compute/create/S": {
    "sample_count": 100,
    "success_count": 100,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 229.89836600027047,
      "max": 778.7264560174663,
      "p50": 259.85355500597507,
      "p95": 509.96325837331807,
      "p99": 689.9871743723638,
      "stddev": 105.19025406197187
    }
  },
  "branch_create_with_compute/create/S": {
    "sample_count": 3,
    "success_count": 3,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 379.46841298253275,
      "max": 502.3665560001973,
      "p50": 493.40882801334374,
      "p95": 501.47078320151195,
      "p99": 502.18740144046023,
      "stddev": 55.94303911149293
    }
  }
}
```

## Correctness and Cleanup

```json
{
  "benchmark_status": "failed",
  "error": {
    "type": "ConnectionTimeout",
    "message": "connection timeout expired"
  },
  "cleanup": {
    "bench_id": "bench_20260529T063854Z",
    "database_id": "db_73c503ed",
    "database_name": "bench-branch-version-20260529T063854Z-db",
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
