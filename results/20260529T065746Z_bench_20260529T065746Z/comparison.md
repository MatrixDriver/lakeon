# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T065746Z`

## Test Environment

```json
{
  "api_base_url": "https://api.dbay.cloud:8443/api/v1",
  "profile": "public-comparison",
  "compute_size": "1cu",
  "datasets": [
    "S"
  ],
  "benchmark_status": "completed",
  "error": null
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
      "min": 2326.8525050079916,
      "max": 2326.8525050079916,
      "p50": 2326.8525050079916,
      "p95": 2326.8525050079916,
      "p99": 2326.8525050079916,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 30,
    "success_count": 30,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 133.8475730153732,
      "max": 185.45186199480668,
      "p50": 141.73134550219402,
      "p95": 182.89238229190232,
      "p99": 185.2718337639817,
      "stddev": 17.677807838965194
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 135.10000499081798,
      "max": 135.10000499081798,
      "p50": 135.10000499081798,
      "p95": 135.10000499081798,
      "p99": 135.10000499081798,
      "stddev": 0.0
    }
  },
  "branch_create_without_compute/create/S": {
    "sample_count": 10,
    "success_count": 10,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 212.80394299537875,
      "max": 779.969429015182,
      "p50": 307.5365154945757,
      "p95": 747.8788130043539,
      "p99": 773.5513058130164,
      "stddev": 188.98968096927527
    }
  },
  "branch_create_with_compute/create/S": {
    "sample_count": 3,
    "success_count": 3,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 221.25127402250655,
      "max": 475.542033003876,
      "p50": 229.4842499832157,
      "p95": 450.9362547018099,
      "p99": 470.6208773434628,
      "stddev": 117.98116826262894
    }
  },
  "branch_create_concurrent/create/S": {
    "sample_count": 10,
    "success_count": 10,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 230.0383999827318,
      "max": 959.8959029826801,
      "p50": 475.4359025100712,
      "p95": 940.8097348423325,
      "p99": 956.0786693546106,
      "stddev": 246.5922111295902
    }
  },
  "branch_depth/create/S": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 350.38755199639127,
      "max": 350.38755199639127,
      "p50": 350.38755199639127,
      "p95": 350.38755199639127,
      "p99": 350.38755199639127,
      "stddev": 0.0
    }
  },
  "version_create/create/S": {
    "sample_count": 10,
    "success_count": 10,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 203.52805897709914,
      "max": 479.50558201409876,
      "p50": 317.24186599603854,
      "p95": 473.5493045998737,
      "p99": 478.31432653125376,
      "stddev": 103.36423011477622
    }
  },
  "version_read/list/S": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 142.34338101232424,
      "max": 142.34338101232424,
      "p50": 142.34338101232424,
      "p95": 142.34338101232424,
      "p99": 142.34338101232424,
      "stddev": 0.0
    }
  },
  "version_read/get/S": {
    "sample_count": 100,
    "success_count": 100,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 135.28609700733796,
      "max": 152.04947197344154,
      "p50": 140.22492850199342,
      "p95": 143.4750309897936,
      "p99": 150.68065147730522,
      "stddev": 3.0173694287953445
    }
  },
  "version_squash/create/S": {
    "sample_count": 30,
    "success_count": 30,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 187.50374898081645,
      "max": 654.3293230060954,
      "p50": 202.85777997924015,
      "p95": 338.9167789588099,
      "p99": 568.1074802967489,
      "stddev": 87.88673032744606
    }
  },
  "version_squash/squash/S": {
    "sample_count": 3,
    "success_count": 3,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 323.0896020249929,
      "max": 613.9521099976264,
      "p50": 468.88251599739306,
      "p95": 599.4451505976031,
      "p99": 611.0507181176217,
      "stddev": 118.74424402989742
    }
  }
}
```

## Correctness and Cleanup

```json
{
  "benchmark_status": "completed",
  "error": null,
  "cleanup": {
    "bench_id": "bench_20260529T065746Z",
    "database_id": "db_85504369",
    "database_name": "bench-branch-version-20260529T065746Z-db",
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
