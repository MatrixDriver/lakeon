# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T063415Z`

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
    "message": "connection failed: connection to server at \"198.18.0.97\", port 4432 failed: ERROR:  Project name ('bench-branch-version-20260529T063415Z-db--bench_20260529T063415Z-s-branch_create_with_compute-0') must contain only alphanumeric characters and hyphen.\nconnection to server at \"198.18.0.97\", port 4432 failed: ERROR:  connection is insecure (try using `sslmode=require`)"
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
      "min": 2309.7558399895206,
      "max": 2309.7558399895206,
      "p50": 2309.7558399895206,
      "p95": 2309.7558399895206,
      "p99": 2309.7558399895206,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 29,
    "success_count": 29,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 140.31694800360128,
      "max": 463.8906079926528,
      "p50": 166.40158501104452,
      "p95": 364.5013152039605,
      "p99": 462.73930399445817,
      "stddev": 78.13223356634238
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 141.15449599921703,
      "max": 141.15449599921703,
      "p50": 141.15449599921703,
      "p95": 141.15449599921703,
      "p99": 141.15449599921703,
      "stddev": 0.0
    }
  },
  "branch_create_without_compute/create/S": {
    "sample_count": 100,
    "success_count": 100,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 231.2585290055722,
      "max": 657.8423469909467,
      "p50": 298.3364394895034,
      "p95": 503.1833287415793,
      "p99": 609.1746815780065,
      "stddev": 86.0153844475398
    }
  },
  "branch_create_with_compute/create/S": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 515.8707650261931,
      "max": 515.8707650261931,
      "p50": 515.8707650261931,
      "p95": 515.8707650261931,
      "p99": 515.8707650261931,
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
    "message": "connection failed: connection to server at \"198.18.0.97\", port 4432 failed: ERROR:  Project name ('bench-branch-version-20260529T063415Z-db--bench_20260529T063415Z-s-branch_create_with_compute-0') must contain only alphanumeric characters and hyphen.\nconnection to server at \"198.18.0.97\", port 4432 failed: ERROR:  connection is insecure (try using `sslmode=require`)"
  },
  "cleanup": {
    "bench_id": "bench_20260529T063415Z",
    "database_id": "db_82f210b7",
    "database_name": "bench-branch-version-20260529T063415Z-db",
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
