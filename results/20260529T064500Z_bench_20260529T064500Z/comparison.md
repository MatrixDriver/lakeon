# DBay Branch and Version Benchmark Report

Bench ID: `bench_20260529T064500Z`

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
      "min": 2113.9848250022624,
      "max": 2113.9848250022624,
      "p50": 2113.9848250022624,
      "p95": 2113.9848250022624,
      "p99": 2113.9848250022624,
      "stddev": 0.0
    }
  },
  "database/get/": {
    "sample_count": 29,
    "success_count": 29,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 135.51538900355808,
      "max": 411.41286402125843,
      "p50": 138.22593601071276,
      "p95": 211.76276460173528,
      "p99": 361.74246533424576,
      "stddev": 52.375588454326625
    }
  },
  "branch/list/": {
    "sample_count": 1,
    "success_count": 1,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 136.1933549924288,
      "max": 136.1933549924288,
      "p50": 136.1933549924288,
      "p95": 136.1933549924288,
      "p99": 136.1933549924288,
      "stddev": 0.0
    }
  },
  "branch_create_without_compute/create/S": {
    "sample_count": 100,
    "success_count": 100,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 218.38094701524824,
      "max": 1895.7627979980316,
      "p50": 239.3507965025492,
      "p95": 422.06285055144656,
      "p99": 1128.262281465581,
      "stddev": 204.27434129572865
    }
  },
  "branch_create_with_compute/create/S": {
    "sample_count": 50,
    "success_count": 50,
    "error_rate": 0.0,
    "api_latency_ms": {
      "min": 226.35724800056778,
      "max": 763.7859120150097,
      "p50": 249.48755849618465,
      "p95": 563.6810529642388,
      "p99": 686.9876808460682,
      "stddev": 107.66115962444928
    }
  },
  "branch_create_concurrent/create/S": {
    "sample_count": 144,
    "success_count": 142,
    "error_rate": 0.013888888888888888,
    "api_latency_ms": {
      "min": 135.4429300117772,
      "max": 1007.0231760037132,
      "p50": 469.2959569947561,
      "p95": 869.8452652053675,
      "p99": 1005.2035704936134,
      "stddev": 193.82933194405302
    }
  },
  "branch_create_concurrent/create/": {
    "sample_count": 57,
    "success_count": 0,
    "error_rate": 1.0,
    "api_latency_ms": {
      "min": 132.8679289727006,
      "max": 139.12942400202155,
      "p50": 135.0890019966755,
      "p95": 138.8872382172849,
      "p99": 139.05738840112463,
      "stddev": 1.777998062132818
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
    "bench_id": "bench_20260529T064500Z",
    "database_id": "db_34e8639a",
    "database_name": "bench-branch-version-20260529T064500Z-db",
    "cleanup_status": "failed",
    "failures": [
      {
        "type": "branch",
        "id": "br_650187a3",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_606118b5",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5571c1bb",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_c5376113",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_d1277773",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_691ac764",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_259892c4",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_3905953a",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_297e16b3",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_4089354f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_48802cf1",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_e6fd8561",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_97e77621",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_29796d50",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_6c9cbbea",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_11c4c093",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_6adf144a",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_c61949e8",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_0f54a127",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_1c3aa549",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_6d3103e9",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_04ddddb2",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_bb38d5bd",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_a27e0c6b",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_6a910f21",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_75433feb",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_e2ba35ba",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_202e1cc9",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_d9c6e964",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_93693e72",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_13d8e44d",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_3071166f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b7a438ad",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_38d1e55f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_eb92d8b0",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_58fd20ab",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_3d05218e",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_cfff6934",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5d9ac28e",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_d5b80918",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_3cf39aac",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_bab89db9",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b3c8ddb2",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_a8fd7847",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_7160bb0f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f751ee9a",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f3ffcfa1",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_0a5b6c40",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_8ffa799f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_3f08a5bc",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_4d8d7262",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_68486ef3",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_d803aa6d",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_02f0ac47",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_7fa0c93b",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_09421ef0",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_45e871e1",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_19d46c85",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_584fbf36",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_328d9f54",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_788ad1af",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_42ea5c93",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_7dac7597",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b6265d86",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_7607449d",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5a9c31aa",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f5a39405",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5a25c5b2",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_6c969840",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f38f6e0c",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f86df658",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_a3cba0f2",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_96a1ae2f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_aedb4c61",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_ddda21ec",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_2955adaa",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b8a31894",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_bbb5fb78",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_46a1504f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_fc81289b",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5ebf95f9",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_d68525a3",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_2bff56ce",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_7373744d",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_4f98684f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b73d52f9",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_fe846b5f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_7574761f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_049adb10",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f1970d84",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b35a4ca1",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b2a9bec5",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_addf37c9",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_9426d78d",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_05578269",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_31313e8a",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_118de2f9",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_df3c4f3e",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_6be52b56",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b616a43f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_46f6f637",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f043df11",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_c647684d",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5d4efb1a",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_6d45b86a",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_96b29148",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_28e52d89",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_97a11044",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5191130f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f0e45566",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_655e2159",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_3973464e",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_01ee5fae",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5d29141f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_61978f44",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_59ff3ce0",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f80a2015",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_925f0faa",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_4e0e5389",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_68e0f09b",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_fa488391",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5955f8a7",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_84ae8fe2",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_38523bce",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_cbb57ebe",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_c25e7d71",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_0c6d1c0c",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_3f9d396b",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b395c112",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_9a4c78cb",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_db86410b",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_888cf53c",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_24b4f237",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f0d000d6",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_8b29b4c1",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_fee7a6ed",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_7442922e",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_c3dd1bf3",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_2fb0320f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_c39023d9",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_84b56ee2",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_1d3df73e",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_10afa1a8",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_e05249de",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_296e1607",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_18e26218",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_87d2dd68",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b7a7c1fd",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_a007475e",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_98395e72",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f6f9b331",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_a0b536c2",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_3a5b8d5e",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_8c43a755",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_68930f70",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_56effad0",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_71b93b34",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_bb7dc022",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_a26b8406",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_336e8c99",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_56cfaa35",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5529e357",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_8ce45dec",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_62f57d05",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_eeafa882",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_68fac41d",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_329470af",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_dc572a42",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_95cba9ab",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_827d0935",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_677fa112",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5124936d",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f82ecb5b",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_5f29ef26",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_2085cfe3",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_000b5124",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_57e9387a",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_d1346b68",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_7fa52b09",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_b3235323",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_d8da14b5",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_83bd2e7f",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_30c58dbe",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_bd49b010",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_f9f1879c",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_fd36df37",
        "error": "DBay API error 429: {'error': {'code': 'RATE_LIMITED', 'message': 'Too many requests. Try again later.'}}"
      },
      {
        "type": "branch",
        "id": "br_24a51b39",
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
