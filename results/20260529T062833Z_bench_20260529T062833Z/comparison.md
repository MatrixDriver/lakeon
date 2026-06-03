# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T062833Z`

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
    "type": "OperationalError",
    "message": "connection failed: connection to server at \"198.18.0.97\", port 4432 failed: fe_sendauth: no password supplied"
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
      "min": 2007.5458550127223,
      "max": 2007.5458550127223,
      "p50": 2007.5458550127223,
      "p95": 2007.5458550127223,
      "p99": 2007.5458550127223,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 29,
    "success_count": 29,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 137.52323100925423,
      "max": 211.7521559994202,
      "p50": 146.4539720036555,
      "p95": 194.31622638367114,
      "p99": 207.790575993713,
      "stddev": 21.596446559886523
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 141.11544398474507,
      "max": 141.11544398474507,
      "p50": 141.11544398474507,
      "p95": 141.11544398474507,
      "p99": 141.11544398474507,
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
    "type": "OperationalError",
    "message": "connection failed: connection to server at \"198.18.0.97\", port 4432 failed: fe_sendauth: no password supplied"
  },
  "cleanup": {
    "bench_id": "bench_20260529T062833Z",
    "database_id": "db_ae081de5",
    "database_name": "bench-branch-version-20260529T062833Z-db",
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
