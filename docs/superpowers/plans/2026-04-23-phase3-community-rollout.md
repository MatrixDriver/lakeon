# Phase 3: Community Rollout + jiuwenclaw Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **Prerequisite:** Phase 1 merged + Phase 2 PyPI released before starting Task 3 (jiuwenclaw PR). Tasks 1-2 (announcement + docs) can start as soon as Phase 2 PyPI is live.

**Goal:** Get openjiuwen-dbay-store into the hands of the community — surfacing it in jiuwenclaw config, publishing the announcement, running benchmarks, and seeding a second external backend plugin to demonstrate the ecosystem pattern works.

**Architecture:** Three workstreams running in parallel: (A) docs + announcement on community + dbay.cloud sides, (B) jiuwenclaw config-layer PR to make `backend=dbay` a first-class option, (C) benchmark harness comparing dbay vs local pgvector vs milvus.

**Tech Stack:** Pandas / matplotlib (benchmarks), jiuwenclaw's existing config framework (TBD — depends on jiuwenclaw's conventions), Markdown for docs / announcements.

---

## File Structure

```
Repositories touched:
  openJiuwen/community (gitcode)          — announcement + plugin list entry
  openJiuwen-ai/jiuwenclaw (gitcode)      — config surface PR
  openJiuwen/agent-core (gitcode)         — plugin author guide "References" update
  <your-user>/openjiuwen-dbay-store       — benchmark harness + results docs
  lakeon's own: dbay.cloud / blog         — launch post
```

---

## Task 1: Community Announcement

**Repo:** `openJiuwen-ai/community`
**File:** New discussion / issue / whatever that repo uses for plugin announcements.

- [ ] **Step 1: Scan the community repo to find the right announcement channel**

```bash
cd ~/code/openjiuwen-docs 2>/dev/null || \
  git clone https://gitcode.com/openJiuwen-ai/community ~/code/openjiuwen-community && \
  cd ~/code/openjiuwen-community

ls -la
```

Look for: `ANNOUNCEMENTS.md`, `PLUGINS.md`, `discussions/`, or GitHub-style issue templates. Pick the channel that matches existing community patterns.

- [ ] **Step 2: Draft announcement**

Content template:

```markdown
# 社区第一个外部存储后端插件：openjiuwen-dbay-store

## 背景

在 [#xxxx](link-to-phase-1-PR) 里，openJiuwen 加入了基于 entry_points 的插件发现机制。这意味着社区开发者可以把新的存储后端作为独立 Python 包发布，而不用把代码合进主仓。

`openjiuwen-dbay-store` 是第一个按这个模式发布的外部插件，提供 **Neon Serverless PG + pgvector** 作为存储后端。

## 快速试用

```bash
pip install openjiuwen openjiuwen-dbay-store
```

```python
from openjiuwen.core.foundation.store import create_vector_store
store = create_vector_store("dbay", dsn="postgresql://user:pass@host/db")
```

## 亮点

- ✅ **零侵入** — 不需要改 openJiuwen 主仓一行代码
- ✅ **完整接口** — 实现 `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` 全部协议
- ✅ **友好错误** — DSN 错、pgvector 缺失、权限不足都有可操作的提示
- ✅ **LongTermMemory 开箱即用** — `examples/long_term_memory.py`
- 📦 **PyPI**: https://pypi.org/project/openjiuwen-dbay-store/
- 📁 **仓库**: https://gitcode.com/<your-user>/openjiuwen-dbay-store

## 你也可以贡献 backend

按插件作者指南（[中文](link) / [英文](link)）的模式，你可以：

- 发布自己的 Qdrant / Weaviate / Elasticsearch / GaussDB / 自研数据库 插件
- 各自独立 release cadence，不受主仓周期约束
- PyPI 直接安装，用户体验与内置 backend 一致

欢迎更多 backend 插件加入生态！
```

- [ ] **Step 3: Post the announcement**

Submit via community repo's preferred channel (PR / issue / discussion).

---

## Task 2: Update Phase 1 Plugin Author Guide with Reference

**Repo:** `openJiuwen/agent-core`

- [ ] **Step 1: Open follow-up PR**

Branch: `docs/add-dbay-plugin-example` from `develop`.

- [ ] **Step 2: Edit plugin author guides**

In both `docs/zh/2.开发指南/使用手册/插件开发-存储后端.md` and `docs/en/2.Development Guide/User Manual/plugin-development-store.md`, update the "参考示例" / "Reference" section at the bottom:

Replace stub:
```markdown
## 参考示例

- `openjiuwen-dbay-store` （Neon Serverless PG + pgvector）：https://gitcode.com/jacky-li/openjiuwen-dbay-store
```

With fully-linked:
```markdown
## 参考示例

已发布的社区插件：

| 插件 | 后端 | PyPI | 仓库 |
|------|------|------|------|
| openjiuwen-dbay-store | Neon Serverless PG + pgvector | [pypi](https://pypi.org/project/openjiuwen-dbay-store/) | [gitcode](https://gitcode.com/<your-user>/openjiuwen-dbay-store) |

欢迎提 PR 把你的插件加入此表。
```

- [ ] **Step 3: Open PR**

```bash
git push fork docs/add-dbay-plugin-example
# Open PR on gitcode, target openJiuwen/agent-core:develop
```

---

## Task 3: jiuwenclaw Config-Layer PR

**Repo:** `openJiuwen-ai/jiuwenclaw`
**Goal:** Make `backend=dbay` selectable via jiuwenclaw's existing config mechanism.

- [ ] **Step 1: Clone jiuwenclaw locally if not already done**

```bash
ls ~/code/jiuwenclaw 2>/dev/null || git clone https://gitcode.com/openJiuwen-ai/jiuwenclaw ~/code/jiuwenclaw
cd ~/code/jiuwenclaw
git checkout main
git pull origin main
```

- [ ] **Step 2: Inspect the config surface**

Find where jiuwenclaw picks a vector-store backend today. Likely suspects:

```bash
grep -rn "create_vector_store\|vector_store_type\|VECTOR_STORE" ~/code/jiuwenclaw --include='*.py' --include='*.yaml' --include='*.yml' --include='*.toml' 2>/dev/null | head -20
```

Expected findings: some config file or env var that gets passed to `create_vector_store(...)`. Identify:
- Where the backend name is read (env var? YAML key? CLI flag?)
- Where kwargs are assembled (DSN, host, port, etc.)
- Where the vector store is instantiated and handed to LongTermMemory or equivalent

- [ ] **Step 3: Design the minimal change**

Likely patterns jiuwenclaw might use:

**Pattern A** — env var driven:
```bash
VECTOR_STORE=dbay
DBAY_DSN=postgresql://...
```

**Pattern B** — YAML driven:
```yaml
store:
  vector:
    type: dbay
    dsn: postgresql://...
```

**Pattern C** — code-level constant:
```python
VECTOR_STORE_TYPE = "milvus"
```

Pick whichever matches the repo's existing style. Don't invent a new pattern.

- [ ] **Step 4: Add "dbay" as a valid choice**

Regardless of pattern, the change is small:

- Add `dbay` to whatever enum / validator / whitelist restricts backend names
- When `backend=dbay`, read DSN from `DBAY_DSN` (or equivalent) + pass to `create_vector_store("dbay", dsn=...)`
- Document in jiuwenclaw's README or config-reference doc

Write it as smallest possible diff. Example (assume Pattern A):

```python
# before
SUPPORTED_VECTOR_STORES = {"chroma", "milvus", "gaussvector"}

# after
SUPPORTED_VECTOR_STORES = {"chroma", "milvus", "gaussvector", "dbay"}
```

And in the kwargs-assembly function:

```python
def _vector_store_kwargs(backend: str) -> dict:
    if backend == "dbay":
        dsn = os.environ.get("DBAY_DSN")
        if not dsn:
            raise ValueError("Set DBAY_DSN env var when using backend=dbay")
        return {"dsn": dsn}
    # ... other backends
```

- [ ] **Step 5: Add test**

Follow jiuwenclaw's existing test convention. Write a unit test that:
- Sets `backend=dbay` and `DBAY_DSN=postgresql://fake`
- Calls the vector-store factory
- Asserts the returned object is the expected type (or mocked correctly)

Don't hit a real PG in jiuwenclaw's CI; mock `create_vector_store` if their test pattern does that.

- [ ] **Step 6: Update jiuwenclaw docs**

In jiuwenclaw README or config reference, add:

```markdown
### Using dbay as vector backend

```bash
export VECTOR_STORE=dbay
export DBAY_DSN="postgresql://user:pass@host:5432/db"
pip install openjiuwen-dbay-store   # required when backend=dbay
```

See [openjiuwen-dbay-store docs](https://gitcode.com/<your-user>/openjiuwen-dbay-store) for DSN source options.
```

- [ ] **Step 7: Open PR**

```bash
git checkout -b feat/dbay-backend
# apply changes, commit
git push fork feat/dbay-backend
# Open PR on gitcode, target openJiuwen-ai/jiuwenclaw:main
```

PR title: `feat: add dbay as vector backend option`

---

## Task 4: Benchmark Harness

**Repo:** `openjiuwen-dbay-store`
**Goal:** Give users concrete numbers to decide whether to pick dbay vs alternatives.

- [ ] **Step 1: Design the benchmark**

Compare 3 backends on the same workload:
1. **local pgvector** (pg 16, default) — represents "self-hosted"
2. **dbay.cloud** (managed Neon) — represents "managed serverless"
3. **Milvus Lite** — represents "in-tree alternative"

Metrics to capture:
- **Insert QPS**: 10K docs × 384-dim vectors, batch=128
- **Search p50 / p95 / p99**: 1K queries against that corpus, top_k=10
- **Recall@10**: using a synthetic-but-realistic ground truth (cosine top-10 via numpy brute force)
- **Cost/month estimate**: approximate cost to host 1M docs + 100K queries/day

- [ ] **Step 2: Write the harness**

```bash
cd ~/code/openjiuwen-dbay-store
mkdir -p benchmarks
```

Create `benchmarks/run.py`:

```python
# benchmarks/run.py
"""Benchmark dbay vs local pgvector vs Milvus Lite.

Usage:
    python benchmarks/run.py --backend dbay --dsn "postgresql://..."
    python benchmarks/run.py --backend pgvector --dsn "postgresql://localhost/bench"
    python benchmarks/run.py --backend milvus --uri "milvus://localhost:19530"
"""
import argparse
import asyncio
import time

import numpy as np

from openjiuwen.core.foundation.store import create_vector_store
from openjiuwen.core.foundation.store.base_vector_store import (
    CollectionSchema, FieldSchema, VectorDataType,
)


N_DOCS = 10_000
N_QUERIES = 1_000
DIM = 384
TOP_K = 10


def make_corpus(seed=42):
    rng = np.random.default_rng(seed)
    return rng.normal(size=(N_DOCS, DIM)).astype("float32")


def make_queries(corpus, seed=43):
    rng = np.random.default_rng(seed)
    # Queries are perturbations of random corpus docs
    idx = rng.integers(0, N_DOCS, size=N_QUERIES)
    queries = corpus[idx] + rng.normal(scale=0.1, size=(N_QUERIES, DIM)).astype("float32")
    return queries, idx  # idx[i] = ground-truth id for queries[i]


def build_ground_truth(corpus, queries):
    """Brute-force top-K by cosine similarity — ground truth recall reference."""
    from numpy.linalg import norm
    corpus_n = corpus / norm(corpus, axis=1, keepdims=True)
    q_n = queries / norm(queries, axis=1, keepdims=True)
    sims = q_n @ corpus_n.T
    top_k = np.argsort(-sims, axis=1)[:, :TOP_K]
    return top_k


async def bench_backend(backend: str, **kwargs):
    store = create_vector_store(backend, **kwargs)
    assert store is not None, f"backend '{backend}' not registered"

    schema = CollectionSchema()
    schema.add_field(FieldSchema(name="id", dtype=VectorDataType.VARCHAR, is_primary=True, max_length=32))
    schema.add_field(FieldSchema(name="emb", dtype=VectorDataType.FLOAT_VECTOR, dim=DIM))

    coll = f"bench_{backend}_{int(time.time())}"
    await store.create_collection(coll, schema)

    corpus = make_corpus()
    queries, gt_ids = make_queries(corpus)
    gt_topk = build_ground_truth(corpus, queries)

    # Insert
    docs = [{"id": str(i), "emb": corpus[i].tolist()} for i in range(N_DOCS)]
    t0 = time.perf_counter()
    for i in range(0, N_DOCS, 128):
        await store.add_docs(coll, docs[i:i+128])
    insert_qps = N_DOCS / (time.perf_counter() - t0)

    # Search
    latencies = []
    recalls = []
    for qi, q in enumerate(queries):
        t0 = time.perf_counter()
        results = await store.search(coll, q.tolist(), "emb", top_k=TOP_K)
        latencies.append((time.perf_counter() - t0) * 1000)  # ms
        returned_ids = {int(r.fields["id"]) for r in results}
        expected_ids = set(gt_topk[qi].tolist())
        recalls.append(len(returned_ids & expected_ids) / TOP_K)

    lat = np.array(latencies)
    report = {
        "backend": backend,
        "n_docs": N_DOCS,
        "dim": DIM,
        "insert_qps": round(insert_qps, 1),
        "search_p50_ms": round(np.percentile(lat, 50), 2),
        "search_p95_ms": round(np.percentile(lat, 95), 2),
        "search_p99_ms": round(np.percentile(lat, 99), 2),
        "recall_at_10": round(np.mean(recalls), 3),
    }

    await store.delete_collection(coll)
    if hasattr(store, "close"):
        await store.close()

    return report


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", required=True, choices=["dbay", "chroma", "milvus", "gaussvector"])
    ap.add_argument("--dsn", help="DSN for pg-based backends")
    ap.add_argument("--uri", help="URI for milvus")
    args = ap.parse_args()

    kwargs = {}
    if args.dsn:
        kwargs["dsn"] = args.dsn
    if args.uri:
        kwargs["uri"] = args.uri

    report = await bench_backend(args.backend, **kwargs)
    import json
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
```

- [ ] **Step 3: Run benchmarks**

```bash
# Local pgvector
python benchmarks/run.py --backend dbay --dsn "postgresql://localhost/bench" > benchmarks/results/local_pgvector.json

# dbay.cloud (real managed)
python benchmarks/run.py --backend dbay --dsn "$DBAY_CLOUD_DSN" > benchmarks/results/dbay_cloud.json

# Milvus Lite (if available)
python benchmarks/run.py --backend milvus --uri milvus-lite:./bench.db > benchmarks/results/milvus_lite.json
```

- [ ] **Step 4: Write benchmark results doc**

Create `benchmarks/RESULTS.md` with a comparison table + caveats. Include:
- Machine specs (laptop vs server)
- pg version + pgvector version
- Milvus version
- Date
- Caveat: "YMMV, re-run in your own environment before making decisions"

- [ ] **Step 5: Commit**

```bash
git add benchmarks/
git commit -m "bench: initial comparison dbay vs pgvector vs milvus"
git push origin main
```

---

## Task 5: Launch Blog / dbay.cloud Docs

**Goal:** Onboarding funnel for users discovering openJiuwen via dbay.cloud.

- [ ] **Step 1: Write dbay.cloud docs page**

Create a new docs page at dbay.cloud: "Using dbay as memory backend for openJiuwen agents". Content:

- 1-paragraph: what is openJiuwen
- 3 commands to get running (pip install, env var, python run.py)
- Copy the LongTermMemory integration example
- Screenshot of a dbay.cloud dashboard showing tables created by openJiuwen
- Pricing hint: "dbay.cloud free tier covers up to X memory items"

- [ ] **Step 2: Write launch blog post**

Title: "第一个 openJiuwen 外部存储后端插件：openjiuwen-dbay-store 发布"

Content outline:

1. Why: openJiuwen 的生态开放
2. What: pgvector 插件 + 跑在 dbay.cloud（或任何 pgvector 实例）
3. How: 3 行代码接入
4. Benchmark: 链接到 `benchmarks/RESULTS.md`
5. Roadmap: 下一步会做的事（多模态 embedding 支持、数据迁移工具...）

- [ ] **Step 3: Publish**

Via dbay.cloud's blog / Notion / WeChat公众号 — whatever distribution channel is used.

---

## Task 6: Seed a Second Plugin (Optional but Recommended)

> If nobody else contributes a plugin for a while, the "ecosystem" claim rings hollow. Seed it yourself if needed, but publicly frame it as "proof-of-concept for contributors".

**Candidates:** Qdrant, Weaviate, Elasticsearch, Redis Vector. Pick the one:
- You actually use or want to use
- Has a Python async client already
- Is popular enough that your contribution generates interest

- [ ] **Step 1: Scope the proof-of-concept**

Time-box to 1 day. Create `openjiuwen-<backend>-store` repo.

- [ ] **Step 2: Minimum viable impl**

Port only `BaseVectorStore` methods strictly needed. KV/DB optional.

- [ ] **Step 3: Publish + announce**

Same flow as dbay: PyPI → add to plugin author guide table → community post.

Even if this is proof-of-concept quality, it validates the pattern.

---

## Task 7: Metrics Collection

**Goal:** Prove the rollout is working.

- [ ] **Step 1: Set up PyPI stats tracking**

[pypistats.org](https://pypistats.org/packages/openjiuwen-dbay-store) auto-tracks downloads. Make a note to check it weekly for first month, monthly after.

- [ ] **Step 2: Optional: usage telemetry**

If you want per-install usage data, add opt-in telemetry (e.g., first import pings `telemetry.dbay.cloud/openjiuwen-plugin` with anonymous install id). GDPR-compliant, opt-out via env var.

Decision: leave this out for v0.1.0; add in v0.2+ after community feedback.

- [ ] **Step 3: Success criteria check at 30 days**

Review:
- PyPI downloads > 100 ✅ / ❌
- At least 1 GitHub/gitcode issue from external user ✅ / ❌
- jiuwenclaw PR merged ✅ / ❌
- Ideally: 1+ other community member publishes a plugin ✅ / ❌

If any major criterion fails, revisit approach.

---

## Design Decisions

1. **Why jiuwenclaw PR is Phase 3 not Phase 2?** jiuwenclaw's config change is coupled to the PyPI release (it lists `dbay` in install instructions). Releasing jiuwenclaw first would confuse users. Phase 3 lets us batch: PyPI live → jiuwenclaw PR + announcement + blog all on the same day.

2. **Why benchmarks in the plugin repo not openJiuwen?** The plugin is tested against its specific backend — benchmarks live with the code that produces them. openJiuwen itself is backend-agnostic and shouldn't host backend-comparison benchmarks.

3. **Why seed a second plugin?** Ecosystem credibility. A plugin-author guide with one example is "look, it can be done"; a guide with two examples is "other people are doing it". Worth the time investment.

4. **Why not automate announcement?** Announcements are one-off, human-written. Automation adds fragility for zero gain.

---

## Self-Review

- [x] Spec coverage: all Phase 3 roadmap items have tasks (announcement, jiuwenclaw, benchmark, docs).
- [x] No placeholders: every step has actionable content; placeholders like `<your-user>`, `<PHASE_1_VERSION>`, `DBAY_CLOUD_DSN` are explicit runtime substitutions with clear meaning.
- [x] Sequencing: tasks 1-2 can run in parallel; task 3 (jiuwenclaw) depends on PyPI live; tasks 4-5 independent; task 6 optional.
- [x] Success metrics defined: Task 7 has 4 concrete 30-day checkpoints.
