# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T091309Z`

## Test Environment

```json
{
  "api_base_url": "https://api.dbay.cloud:8443/api/v1",
  "profile": "public-comparison-stress",
  "compute_size": "1cu",
  "datasets": [
    "S"
  ],
  "benchmark_status": "failed",
  "error": {
    "type": "DbayApiError",
    "message": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
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
      "min": 2393.2511449966114,
      "max": 2393.2511449966114,
      "p50": 2393.2511449966114,
      "p95": 2393.2511449966114,
      "p99": 2393.2511449966114,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 29,
    "success_count": 29,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 137.26555500761606,
      "max": 180.10904200491495,
      "p50": 138.99980499991216,
      "p95": 178.84175818762742,
      "p99": 179.85412244102918,
      "stddev": 18.049431076768485
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 139.09960200544447,
      "max": 139.09960200544447,
      "p50": 139.09960200544447,
      "p95": 139.09960200544447,
      "p99": 139.09960200544447,
      "stddev": 0.0
    }
  },
  "branch_create_without_compute/create/S": {
    "sample_count": 40,
    "success_count": 40,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 218.10213301796466,
      "max": 1194.498522003414,
      "p50": 280.44395649340004,
      "p95": 915.3794173820643,
      "p99": 1093.2320112083105,
      "stddev": 217.43097262337827
    }
  },
  "branch_create_with_compute/create/S": {
    "sample_count": 5,
    "success_count": 5,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 223.45208798651583,
      "max": 506.9544549915008,
      "p50": 327.20629099640064,
      "p95": 471.514904993819,
      "p99": 499.8665449919645,
      "stddev": 94.10631212338055
    }
  },
  "branch_create_concurrent/create/S": {
    "sample_count": 40,
    "success_count": 40,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 270.5760140088387,
      "max": 1188.0029849999119,
      "p50": 485.48240450327285,
      "p95": 828.4964157966897,
      "p99": 1051.021187190199,
      "stddev": 203.01710175201006
    }
  },
  "branch_depth/create/S": {
    "sample_count": 4,
    "success_count": 4,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 307.66662198584527,
      "max": 147565.44209900312,
      "p50": 1602.6858065015404,
      "p95": 125827.99276295112,
      "p99": 143217.9522317927,
      "stddev": 63397.17024393192
    }
  },
  "version_create/create/S": {
    "sample_count": 40,
    "success_count": 40,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 179.68569899676368,
      "max": 380.83875001757406,
      "p50": 192.0848755107727,
      "p95": 298.1799115310423,
      "p99": 352.19820613274345,
      "stddev": 45.40393919476018
    }
  },
  "version_read/list/S": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 139.88697799504735,
      "max": 139.88697799504735,
      "p50": 139.88697799504735,
      "p95": 139.88697799504735,
      "p99": 139.88697799504735,
      "stddev": 0.0
    }
  },
  "version_read/get/S": {
    "sample_count": 401,
    "success_count": 318,
    "error_rate": 0.20698254364089774,
    "api_latency_ms": {
      "min": 130.52885798970237,
      "max": 376.77988299401477,
      "p50": 137.37313699675724,
      "p95": 150.8276220120024,
      "p99": 369.2506859952118,
      "stddev": 34.584591715897545
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
    "message": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
  },
  "cleanup": {
    "bench_id": "bench_20260529T091309Z",
    "database_id": "db_b1b7d514",
    "database_name": "bench-branch-version-20260529T091309Z-db",
    "cleanup_status": "failed",
    "failures": [
      {
        "type": "branch",
        "id": "br_033ee6ff",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      }
    ]
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
