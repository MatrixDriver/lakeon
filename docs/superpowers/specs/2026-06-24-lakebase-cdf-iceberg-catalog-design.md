# Lakebase CDF and Lakeon-managed Iceberg Catalog Design

## Decision

Lakeon will build a Lakebase-native CDF and lakehouse table feature inside the Lakebase Core boundary.

The product model is **Lakeon-managed lakehouse tables**:

- Applications continue to write ordinary Lakebase/Postgres tables.
- Lakeon captures changes with logical decoding and writes them into managed lakehouse tables.
- Lakeon exposes those tables through a standard Iceberg REST Catalog API.
- The hot catalog, branch state, commit state, CDF offsets, and scan-planning index live in the user's own Lakebase database.
- OBS stores Parquet data/delete files by default.
- Standard Iceberg metadata and manifest files are materialized lazily only when a user needs export, external ecosystem compatibility, audit, or recovery.

This is not a bare OBS Iceberg-table product. Direct OBS metadata paths are not a supported interface. Users bind to Lakeon REST Catalog to get Lakebase transactions, permissions, branch isolation, low-latency catalog load, server-side scan planning where the client supports it, and managed GC.

## Product Goals

1. **Lakebase CDF**: turn OLTP changes into near-real-time lakehouse data.
2. **Initial Backfill**: when a user enables CDF for a Lakebase table, first create a consistent initial lakehouse snapshot so the target table represents the full source table, then continue from the matching LSN.
3. **Lakebase-backed Catalog**: use the user's Lakebase transactions for Iceberg REST Catalog commit requirements and branch-scoped table visibility.
4. **Lakebase-backed Planning**: avoid the normal client-side OBS manifest hot path by storing a relational planning index in Lakebase and using REST server-side scan planning.
5. **Lazy Iceberg Export**: generate standard Iceberg metadata/manifest only when requested by product policy.
6. **Query Path Guidance**: document which client gets which benefit. Iceberg clients read Parquet through the Lakeon Catalog. PostgreSQL clients benefit only when they query the source Lakebase table or a future serving projection.
7. **Repo Boundary**: keep the feature in `lakeon` as Lakebase Core. `dbay-agent` consumes it only through stable HTTP APIs and Iceberg REST Catalog.

## Non-Goals

- Do not fork PyIceberg, Spark, DuckDB, or Iceberg table format.
- Do not expose Lakeon RDS metadata to `dbay-agent`.
- Do not support direct user writes to OBS lakehouse paths.
- Do not promise sub-second analytical visibility in the first release.
- Do not make Iceberg refs the primary user-facing branch model in the first release.
- Do not build an automatic query router in the first release.
- Do not build a Lakeon SQL query engine for Iceberg in the first release.
- Do not promise PostgreSQL clients can transparently query Iceberg Parquet files. PG clients use Lakebase tables; Iceberg clients use Parquet.

## System Architecture

```text
Application
  -> Lakebase/Postgres source table
     -> initial backfill snapshot at captured LSN
     -> logical decoding slot from the backfill LSN
        -> Lakebase CDF worker
           -> OBS Parquet data/delete files
           -> user Lakebase _lakeon_iceberg catalog + planning index
              -> Lakeon Iceberg REST Catalog
                 -> dbay-agent / PyIceberg / Spark / DuckDB
```

Lakeon API owns:

- CDF stream lifecycle.
- Logical decoding worker orchestration.
- Iceberg REST Catalog endpoints.
- Catalog commit validation and compare-and-swap.
- Lakebase planning index updates.
- Server-side scan planning.
- Lazy Iceberg manifest materialization.
- Managed GC.
- Future Lakebase serving table projection.

## Metadata Model

Lakeon uses three metadata tiers.

### User Lakebase Authoritative State

Stored in each user's Lakebase database under `_lakeon_iceberg`.

```text
_lakeon_iceberg.tables
  table_id
  database_id
  branch_id
  namespace
  table_name
  table_location
  current_metadata_location
  current_metadata_json
  current_metadata_hash
  current_snapshot_id
  metadata_version
  export_status
  last_commit_lsn
  created_at
  updated_at

_lakeon_iceberg.snapshots
  table_id
  branch_id
  snapshot_id
  parent_snapshot_id
  sequence_number
  operation
  committed_at
  summary_json
  manifest_materialized

_lakeon_iceberg.data_files
  table_id
  branch_id
  snapshot_id
  file_path
  content_type
  partition_json
  record_count
  file_size_bytes
  lower_bounds_json
  upper_bounds_json
  null_counts_json
  value_counts_json
  added_at

_lakeon_iceberg.delete_files
  same table/snapshot/file shape, with equality/position delete metadata

_lakeon_iceberg.cdf_streams
  stream_id
  source_schema
  source_table
  target_namespace
  target_table
  mode
  status
  backfill_status
  backfill_lsn
  slot_name
  publication_name
  created_at
  updated_at

_lakeon_iceberg.cdf_offsets
  stream_id
  branch_id
  last_commit_lsn
  last_txid
  last_snapshot_id
  applied_at
```

The current visible version is authoritative in Lakebase. A commit is visible when the Lakebase catalog transaction commits.

### OBS Data Files

Stored under a Lakeon-managed warehouse prefix:

```text
obs://dbay-mainstore/iceberg/<tenant>/<database>/<branch>/<namespace>/<table>/
  data/*.parquet
  deletes/*.parquet
  export/metadata/*.metadata.json
  export/metadata/*.manifest.avro
  export/metadata/*.manifest-list.avro
```

The default hot path writes only data/delete files. `export/metadata` is populated by lazy materialization.

### Lakeon API Cache

Non-authoritative short TTL cache:

- table identifier -> table id / branch id
- current metadata hash
- permissions
- recent planning results
- export status

## Commit Semantics

## Initial Backfill Semantics

Opening CDF on a Lakebase table has two phases:

1. **Backfill phase**: Lakeon captures a stable source-table snapshot and records the LSN that represents the high-water mark for the backfill.
2. **Incremental phase**: Lakeon starts logical decoding from the recorded LSN and applies only changes after the backfill boundary.

The target table is readable by Iceberg clients only after the first backfill snapshot is committed to `_lakeon_iceberg.tables`, `_lakeon_iceberg.snapshots`, and `_lakeon_iceberg.data_files`.

Backfill requirements:

- A source table must have a primary key or explicit replica identity for current-state and serving modes.
- Append-only changelog mode can support tables without primary key, but deletes/updates are represented as change events rather than current-state replacement.
- Backfill and logical slot setup must be ordered so no committed source change is missed between snapshot extraction and incremental decoding.
- If backfill fails, the stream remains `BACKFILL_FAILED` and is not exposed as ready in the REST Catalog.
- Retrying backfill is idempotent: an existing initial snapshot for the same stream and branch is reused unless the user explicitly resets the stream.

Lakeon REST Catalog accepts standard Iceberg update requirements. The backend maps those requirements to a Lakebase transaction.

Commit sequence:

1. Validate tenant, database, branch, namespace, and table permissions.
2. Validate request requirements against `_lakeon_iceberg.tables.current_metadata_hash` and current metadata JSON.
3. Write OBS Parquet data/delete files before commit.
4. Insert new snapshot, data file rows, delete file rows, and CDF offset rows.
5. Update `_lakeon_iceberg.tables` with compare-and-swap on `current_metadata_hash` or `current_metadata_location`.
6. Mark export status `NOT_MATERIALIZED`.
7. On CAS failure, return standard Iceberg commit conflict and enqueue orphan-file cleanup.

The CDF offset and catalog pointer must commit in the same Lakebase transaction so a restart can never expose a snapshot without the matching offset.

## Server-side Scan Planning

Lakeon implements Iceberg REST server-side scan planning endpoints. Clients that support the REST planning API can avoid reading OBS manifest files.

Planning path:

```text
client scan(filter, projection, snapshot)
  -> POST planTableScan
  -> Lakeon validates table and branch
  -> Lakeon queries _lakeon_iceberg.data_files/delete_files with partition and column stats pruning
  -> Lakeon returns FileScanTask list or plan-task pages
  -> client reads only OBS Parquet files
```

Clients without server-side planning support can still load metadata. In `managed_only` mode they must use Lakeon-supported paths; direct OBS manifest fallback is not guaranteed until export is materialized.

## Lazy Iceberg Manifest Materialization

Each table has an export mode:

```text
managed_only      default; Lakeon Catalog required; manifest not generated on every commit
iceberg_exported  standard Iceberg metadata/manifest available for external ecosystem use
```

Materialization triggers:

- User requests export.
- Admin enables periodic export for a table.
- A recovery job requires standard metadata files.
- A partner integration requires bare Iceberg compatibility.

Materialization reads `_lakeon_iceberg.tables`, `snapshots`, `data_files`, and `delete_files`, then writes standard Iceberg metadata and manifest files to OBS. Export does not change the current authoritative catalog pointer unless it records a new `current_metadata_location` for compatibility.

## Branch Model

Users see only Lakebase branches.

```text
database: app_prod
branch: main / experiment / replay
table: public.orders_cdf
```

Lakebase branch scopes:

- catalog pointer
- current metadata JSON
- planning index
- CDF offset
- serving table state

Iceberg refs remain a standard metadata capability for future compatibility, but they are not the primary product abstraction.

## Query Path Selection

Lakeon does not provide automatic query path selection in the first release. Query path selection is a product and API contract, not a transparent runtime router.

### Analytical Path

Use Lakeon REST Catalog + OBS Parquet. Standard Iceberg clients and engines use this path:

- scans
- aggregates
- batch jobs
- dbay-agent dataset analysis
- historical replay

An Iceberg client should be expected to read Parquet data files. Lakeon can reduce metadata latency through REST Catalog load and, for clients that implement Iceberg REST scan planning, server-side planning. It cannot make every existing Iceberg client avoid Parquet or object storage reads without introducing a new query execution layer.

### Serving Path

Use Lakebase source table now, or a future Lakebase serving projection:

- primary key point lookup
- API serving
- online feature lookup
- model result serving
- user profile lookup

PostgreSQL clients get low-latency benefits only on this path. If the source table is already the desired serving shape, no copy is needed. If the lakehouse table represents an analytical result or a cross-table current-state projection, a future Lakeon feature can maintain a separate serving table with ordered, idempotent CDF apply.

### Future Lakeon Query Engine

If Lakeon needs to let PostgreSQL or SQL-over-HTTP clients query managed Iceberg tables directly, Lakeon must include or embed a query engine that can plan and execute over Parquet files. That is a large future phase. It should not be bundled into the initial CDF/Catalog work.

## dbay-agent Contract

`dbay-agent` calls Lakeon only through:

- REST API for CDF stream management and catalog configuration.
- Iceberg REST Catalog for table load, commit if allowed, and scan planning.
- Standard temporary OBS credentials or signed access returned by Lakeon.

Forbidden:

- direct SQL reads from Lakeon metadata RDS
- shared JPA entities
- shared Kubernetes namespaces
- direct writes to OBS managed paths

## Performance Targets

MVP:

- Initial backfill: completes for small tables before the target is marked readable; large tables report progress and become readable when the first snapshot commits.
- Incremental CDF visibility after backfill: 10-30 seconds.
- Catalog load: no OBS metadata read on hot path.
- Commit: one Lakebase transaction after OBS data-file writes.

Optimized:

- CDF visibility: 3-10 seconds under steady workload.
- Scan planning: no client-side OBS manifest read for supported clients.
- Future serving projection: 1-10 seconds behind source, depending on apply loop.

## Failure Handling

- OBS data write succeeds but catalog commit fails: enqueue orphan cleanup.
- Catalog commit succeeds but worker crashes before acknowledging offset: restart reads committed offset from `_lakeon_iceberg.cdf_offsets`.
- Planning index is suspected stale: reject scan for that snapshot and trigger index repair from committed snapshot rows.
- Export materialization fails: keep `managed_only` table usable; mark export error separately.
- Future serving projection apply fails: pause projection, preserve CDF offset before failing batch, expose status.

## Security And Access

- Lakeon REST Catalog authenticates with existing tenant API key/service token flow.
- Lakeon validates tenant, database, branch, namespace, and table ownership.
- User-visible OBS paths are not a supported API.
- OBS credentials are short-lived and scoped to exact file prefixes.
- System tables under `_lakeon_iceberg` are managed by Lakeon service roles.

## Testing Strategy

- Unit tests for schema DDL rendering, commit requirement validation, CAS conflict handling, and scan pruning.
- API tests for Iceberg REST Catalog load/commit/plan endpoints.
- Worker tests for idempotent CDF apply and offset advancement.
- Export tests that materialized metadata can be loaded by an Iceberg client.
- E2E tests covering source table backfill -> source insert/update/delete -> CDF -> Lakeon-managed table -> Iceberg client read path -> lazy export.

## Rollout Strategy

1. Ship disabled-by-default backend schema and REST Catalog skeleton.
2. Enable one internal test tenant with one append-only CDF stream and initial backfill.
3. Add server-side planning index and verify it only with clients that support Iceberg REST scan planning.
4. Add lazy export and verify external Iceberg compatibility.
5. Expose status in Console/Admin after backend invariants are stable.
6. Future: add serving projection, automatic query routing, or a Lakeon-managed query engine as separate product phases.
