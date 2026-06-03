# Phase 1 PR Draft

> 合入上游前请 review 这份草稿。执行 push + open PR 之前的最后一道检查。

## 仓库/分支

| 项 | 值 |
|---|---|
| 目标仓库 | `gitcode.com/openJiuwen/agent-core` |
| 目标分支 | `develop` |
| 本地分支 | `feat/plugin-registry` (5 commits on top of `origin/develop @ 7c422149`) |
| 工作目录 | `~/code/agent-core/.worktrees/plugin-registry/` |

## 5 个 commits（rebased clean）

```
6eb9980c docs: plugin author guide for vector-store backends (zh + en)
a3a29422 docs(store): declare Base*Store ABCs as stable public plugin APIs
1ffcf916 feat(store): pluggable vector-store backends via entry_points + register API
44b36dd9 test(store): add failing tests for vector-store plugin framework
f0fe8314 test(store): add regression tests for built-in vector-store factory dispatch
```

Diff: 7 files, 572 insertions, 12 deletions

## PR 标题

```
feat(store): pluggable vector-store backends via entry_points
```

## PR 正文

```markdown
## Motivation

Current `create_vector_store()` requires in-tree code for every new backend.
This blocks third-party ecosystem growth — a Qdrant / Weaviate / Dbay author
cannot ship their backend as an independent package.

## What this PR does

1. Adds 3-level resolution in the factory: built-in → explicit registration
   → entry_points(`openjiuwen.vector_stores`).
2. New public API `register_vector_store(name, factory)` for programmatic
   in-process registration (useful for private backends).
3. Exports `VECTOR_STORE_ENTRY_POINT_GROUP` constant so plugin authors can
   reference the group name.
4. Declares `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` as stable
   public plugin APIs (docstring only, no code change).
5. Adds plugin author guide in zh + en.
6. 10 unit tests — 4 regression (built-in dispatch unchanged) + 6 framework.

## What this PR does NOT do

- Does not introduce any new backend (no dbay, no qdrant).
- Does not change any existing backend behavior — built-in resolution is
  literally unchanged (same chroma/milvus/gaussvector branches, just moved
  into `_resolve_builtin`).
- Does not add factories for KV / DB stores (YAGNI; users instantiate
  directly — documented in the plugin guide).

## Plugin ecosystem

The first external plugin using this mechanism will be
[openjiuwen-dbay-store](https://gitcode.com/jacky-li/openjiuwen-dbay-store)
(Neon Serverless PG + pgvector). Its `pyproject.toml` will use:

    [project.entry-points."openjiuwen.vector_stores"]
    dbay = "openjiuwen_dbay_store.vector:DbayVectorStore"

After this PR merges, any community member can publish their own backend
as an independent PyPI package using the same pattern.

## Compatibility

- **No public API removed.** Existing `create_vector_store("chroma" | "milvus" | "gaussvector", **kwargs)` behaves byte-for-byte identical.
- **Resolution order preserves built-in precedence.** Plugins that claim
  built-in names (e.g., re-register `chroma`) are silently ignored — the
  built-in still wins.
- **Broken plugins log WARNING and are silently skipped.** A third-party
  wheel that fails to import (missing dep, broken metadata, exception in
  `.load()`, exception in constructor) cannot crash the factory for the
  whole application.

## Testing

- 10 new unit tests in
  `tests/unit_tests/core/foundation/store/test_vector_store_plugin.py`:
  - `TestBuiltinRegression` (4) — locks in that `chroma/milvus/gaussvector`
    dispatch is unchanged and `unknown` returns `None`.
  - `TestExplicitRegistration` (2) — `register_vector_store()` registers a
    custom backend; built-in names cannot be overridden.
  - `TestEntryPointsDiscovery` (3) — entry_points discovery finds plugins;
    broken plugin load returns `None` instead of raising; built-in wins
    over entry_points.
  - `TestEntryPointsGroupName` (1) — group-name constant
    (`openjiuwen.vector_stores`) is exported and stable.

- Full `tests/unit_tests/core/foundation/store/` passes locally (plugin
  tests + in_memory_kv_store = 49 PASSED). Existing
  `test_knowledge_retrieval_comp.py` — which patches `create_vector_store`
  — works unchanged against the refactored factory (validated by
  inspection; full test run requires jsonschema_path which is not
  installed in the minimal dev venv but is part of upstream CI).

## Notes for Reviewers

- `_resolve_builtin` / `_resolve_entry_point` are private (underscore-prefixed)
  and not exported via `__all__`. They're split out for testability and readability.
- The test file uses `sys.modules` pre-injection (in an autouse
  module-scoped fixture with teardown) to stub optional backend modules
  (`chroma_vector_store`, `milvus_vector_store`, `gauss_vector_store`) so
  `unittest.mock.patch()` can resolve its target paths without requiring
  `chromadb` / `pymilvus` / `psycopg2` to be installed for the test run.
  In upstream CI where the real modules are already loaded, the guard
  `if mod_path not in sys.modules` makes this a no-op.
- PEP 562 lazy `__getattr__` mechanism is preserved exactly — the existing
  `BaseDbStore` / `DbBasedKVStore` / `DefaultDbStore` lazy-loading is
  untouched.
```

## Push & open PR 命令

```bash
cd /Users/jacky/code/agent-core/.worktrees/plugin-registry

# 1. Ensure you have signed the CLA on gitcode (community repo).
#    Without CLA, the PR cannot be merged.

# 2. Add your fork as a remote (replace <your-user>)
git remote add fork https://gitcode.com/<your-user>/agent-core.git

# 3. Push the feature branch
git push fork feat/plugin-registry

# 4. On gitcode web UI: open PR with
#    - base: openJiuwen/agent-core:develop
#    - head: <your-user>/agent-core:feat/plugin-registry
#    - title and body from above
```

## 最后的 sanity check

```bash
cd /Users/jacky/code/agent-core/.worktrees/plugin-registry
. .venv/bin/activate
PYTHONPATH=. python -m pytest \
  tests/unit_tests/core/foundation/store/test_vector_store_plugin.py \
  tests/unit_tests/core/foundation/store/test_in_memory_kv_store.py \
  -v
# Expect: 49 passed
```

```bash
git log --oneline origin/develop..HEAD
# Expect: exactly 5 commits
```
