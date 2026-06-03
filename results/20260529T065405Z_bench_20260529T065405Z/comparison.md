# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T065405Z`

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
    "type": "DbayApiError",
    "message": "DBay API error 0: {'error': {'message': 'The read operation timed out'}}"
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
      "min": 2232.4195439869072,
      "max": 2232.4195439869072,
      "p50": 2232.4195439869072,
      "p95": 2232.4195439869072,
      "p99": 2232.4195439869072,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 30,
    "success_count": 30,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 135.69673500023782,
      "max": 220.8375540212728,
      "p50": 137.93217297643423,
      "p95": 183.79532305116294,
      "p99": 211.81227870809383,
      "stddev": 21.475785211273585
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 136.57479599351063,
      "max": 136.57479599351063,
      "p50": 136.57479599351063,
      "p95": 136.57479599351063,
      "p99": 136.57479599351063,
      "stddev": 0.0
    }
  },
  "branch_create_without_compute/create/S": {
    "sample_count": 20,
    "success_count": 20,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 231.08478198992088,
      "max": 567.6432930049486,
      "p50": 252.3982929997146,
      "p95": 399.3675975681982,
      "p99": 533.9881539175983,
      "stddev": 79.54903983843649
    }
  },
  "branch_create_with_compute/create/S": {
    "sample_count": 5,
    "success_count": 5,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 236.19049598346464,
      "max": 479.3033199966885,
      "p50": 339.0824999951292,
      "p95": 477.40652839420363,
      "p99": 478.92396167619154,
      "stddev": 104.64894902663127
    }
  },
  "branch_create_concurrent/create/S": {
    "sample_count": 20,
    "success_count": 20,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 231.4619609969668,
      "max": 614.146007981617,
      "p50": 367.3238564952044,
      "p95": 595.0999472392141,
      "p99": 610.3367958331364,
      "stddev": 112.17041880956704
    }
  },
  "branch_depth/create/S": {
    "sample_count": 3,
    "success_count": 3,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 392.1590239915531,
      "max": 3320.7444149884395,
      "p50": 437.6374499988742,
      "p95": 3032.4337184894825,
      "p99": 3263.082275688648,
      "stddev": 1369.9548431797075
    }
  },
  "branch/create/S": {
    "sample_count": 1,
    "success_count": 0,
    "error_rate": 1.0,
    "api_latency_ms": {
      "min": 60002.784084994346,
      "max": 60002.784084994346,
      "p50": 60002.784084994346,
      "p95": 60002.784084994346,
      "p99": 60002.784084994346,
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
    "type": "DbayApiError",
    "message": "DBay API error 0: {'error': {'message': 'The read operation timed out'}}"
  },
  "cleanup": {
    "bench_id": "bench_20260529T065405Z",
    "database_id": "db_2f03ee73",
    "database_name": "bench-branch-version-20260529T065405Z-db",
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
