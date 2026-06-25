# Lakebase CDF and Lakeon-managed Iceberg Roadmap

## Core Clarification

Lakeon will not provide automatic query path selection in the first implementation.

Client behavior is explicit:

- Iceberg clients read Lakeon-managed Iceberg tables through the Lakeon Iceberg REST Catalog and ultimately scan Parquet data files in OBS.
- PostgreSQL clients read Lakebase tables. They do not automatically read Iceberg Parquet files.
- PG clients get low-latency benefits when they query the original Lakebase source table, or in a future phase, a Lakebase serving projection maintained by Lakeon.
- A transparent SQL layer over Iceberg for PG or SQL-over-HTTP clients requires a Lakeon-managed query engine. That is a separate future product phase.

## Phase 0: Design Lock

Goal: fix the product boundary before implementation.

Deliverables:

- Lakeon-managed lakehouse table definition.
- Initial backfill semantics.
- CDF offset and Iceberg snapshot atomicity.
- Tenant Lakebase `_lakeon_iceberg` schema design.
- Rule that direct OBS paths are not a supported user interface.

Success criteria:

- Team agrees that the first product is CDF plus Iceberg REST Catalog, not a full SQL query engine.
- Team agrees that PG and Iceberg clients use different paths.

## Phase 1: CDF And Initial Backfill MVP

Goal: users can turn on CDF for a Lakebase table and get a readable managed lakehouse table.

Deliverables:

- API to enable CDF for a Lakebase table.
- Consistent initial backfill snapshot.
- Logical decoding from the captured backfill LSN.
- OBS Parquet data files.
- `_lakeon_iceberg` tables for catalog state, snapshots, data files, and CDF offsets.
- Basic Lakeon Iceberg REST Catalog load endpoint.

Client behavior:

- Iceberg clients can load the table from Lakeon Catalog after backfill commits.
- Analytical reads go to Parquet through the Iceberg engine.
- PG clients still query the original Lakebase source table for low-latency point lookups.

Success criteria:

- Open CDF on `public.orders`.
- Backfill creates a complete initial snapshot.
- Inserts, updates, and deletes after the backfill LSN appear in later snapshots.
- dbay-agent Python jobs can read the table through Iceberg/PyIceberg-compatible configuration.

## Phase 2: Lakebase-backed Metadata And Planning Optimization

Goal: reduce Iceberg metadata latency without changing standard clients.

Deliverables:

- Hot table metadata JSON stored in tenant Lakebase.
- Lakebase planning index for data files, partitions, stats, and bounds.
- REST server-side scan planning for clients that support the Iceberg REST planning API.
- Writer keep-warm and small-file compaction.

Client behavior:

- Standard Iceberg clients still read Parquet.
- Clients that support REST server-side scan planning can avoid client-side OBS manifest planning.
- Clients that do not support REST planning still follow standard Iceberg behavior and may read materialized metadata/manifests when export is enabled.

Success criteria:

- Catalog load does not fetch metadata JSON from OBS in the hot path.
- Supported clients can receive planned file tasks from Lakeon.
- Unsupported clients remain compatible through the normal Iceberg path.

## Phase 3: Lazy Iceberg Export

Goal: keep Lakeon-managed tables fast by default while preserving compatibility, recovery, and audit.

Deliverables:

- `managed_only` table state.
- `iceberg_exported` table state.
- On-demand Iceberg metadata and manifest materialization to OBS.
- Export status, errors, and retry APIs.

Client behavior:

- Default managed tables require Lakeon Catalog.
- External engines that need standard OBS metadata use exported snapshots.

Success criteria:

- Normal CDF commits do not synchronously write manifest files.
- A user or policy can materialize a standard Iceberg snapshot when needed.
- Exported metadata can be loaded by a standard Iceberg client.

## Phase 4: Branch And Governance

Goal: make Lakebase branch the user-facing branch model for managed lakehouse tables.

Deliverables:

- Branch-scoped catalog pointer.
- Branch-scoped planning index visibility.
- Branch-aware CDF offsets.
- Branch-aware GC reachability.
- Permission and audit events at Catalog level.

Client behavior:

- Users choose Lakebase database and branch.
- Iceberg branch/tag metadata remains an internal compatibility layer unless advanced users explicitly request it.

Success criteria:

- The same Lakeon-managed table can have independent snapshots under different Lakebase branches.
- GC never deletes files reachable from any active branch, snapshot, export, or query lease.

## Phase 5: PG Serving Projection

Goal: let PG clients read selected derived/current-state outputs at Lakebase latency.

Deliverables:

- `_lakeon_serving` schema.
- Per-table serving projection policy.
- Ordered, idempotent CDF apply into serving tables.
- Tombstone or hard-delete policy.
- Projection lag and failure status.

Client behavior:

- PG clients query serving tables with normal PostgreSQL drivers.
- Iceberg clients continue to use Parquet for analytical reads.
- This is not automatic query routing. Users or APIs choose the serving table.

Success criteria:

- A declared current-state table is queryable by primary key through PG.
- Projection lag is visible and bounded by the configured SLA.
- Failed projection apply pauses safely without advancing the committed offset.

## Phase 6: Lakeon Query Engine

Goal: provide a Lakeon-owned SQL path over managed Iceberg data when product demand justifies it.

Deliverables:

- Embedded or integrated query engine for Parquet over OBS.
- Lakeon SQL API or PG-compatible gateway decision.
- Cost controls, concurrency limits, result caching, and spill policy.
- Query planner integration with `_lakeon_iceberg` planning index.

Client behavior:

- PG or SQL-over-HTTP clients can query managed lakehouse tables through Lakeon.
- Lakeon chooses whether a query uses Lakebase serving tables, Lakebase source tables, or Parquet execution.

Success criteria:

- Users can submit SQL against managed lakehouse tables without configuring an external Iceberg engine.
- The engine can route simple point lookups to serving/source tables and analytical scans to Parquet.
- This phase is explicitly outside the first CDF/Catalog implementation.

## Recommended Execution Order

1. Implement Phase 1 first.
2. Add Phase 2 only after MVP read/write correctness is proven.
3. Add Phase 3 for compatibility and audit requirements.
4. Add Phase 4 when branch semantics become a product differentiator.
5. Add Phase 5 only for concrete PG serving use cases.
6. Add Phase 6 only if Lakeon needs to own query execution, not just catalog and data management.
