# Connector Platform Phase 1: PostgreSQL Connector for DBay Import and Sync

## Context

DBay already has two separate ideas that should become one product concept:

- OBS connections live under the datalake area and are managed by `/api/v1/obs-connections`.
- PostgreSQL import and sync already let users connect an external PostgreSQL database, choose tables, and import or continuously sync them into a DBay database.

The PostgreSQL flow is the higher-value first connector scenario because it already maps to a working database import capability. The current weakness is that source connection details are entered per import task, so users cannot manage, retest, reuse, audit, or later extend these external sources as first-class assets.

## Goals

- Introduce a generic `Connector` product concept for external data access.
- Make external PostgreSQL reusable for existing database import and sync.
- Preserve the existing import and sync execution path, including table discovery, full import, selective import, and logical replication sync.
- Keep OBS visible under the connector model without breaking existing OBS connection APIs or datalake flows.
- Give the console one clear place for user-managed external sources.

## Non-Goals

- Do not rewrite `ImportJobPodManager`, import pods, or the database import execution model.
- Do not implement knowledge-base ingestion from OBS in this phase.
- Do not add MySQL, S3, GitHub, SaaS apps, or arbitrary connector plugins in this phase.
- Do not migrate all OBS storage internals into the connector table in Phase 1.
- Do not remove the existing direct PostgreSQL import payload immediately; keep it for backward compatibility.

## Product Model

`Connector` is a tenant-scoped external data source definition. It answers: where can DBay read from, what type of source is it, what credentials are needed, when was it last tested, and which DBay features use it.

Phase 1 connector types:

- `POSTGRESQL`: first-class implementation. Used by database import and sync.
- `OBS`: shown in the connector center by adapting the existing `obs_connections` model. Existing OBS APIs remain available.

The console should use Chinese labels for the main navigation and screens:

- First-level area: `数据`
- Menu item: `连接器`
- Page title: `连接器`
- PostgreSQL type label: `PostgreSQL`
- OBS type label: `对象存储 OBS`

## Console UX

### Connector List

Replace or supplement the current `OBS 连接` menu entry with a generic `连接器` page under the data/data-source area.

The list shows:

- Name
- Type
- Target summary, such as `host:port/database` for PostgreSQL or bucket/endpoint summary for OBS
- Status: untested, connected, failed
- Last tested time
- Usage count or usage hints, such as `数据库导入`
- Actions: test, edit, delete, use for import

The page should make existing OBS connections visible as connector rows. If an OBS connector is edited, it can route to the existing OBS connection form or use the same fields inside the new connector page.

### PostgreSQL Connector Form

Fields:

- Name
- Host
- Port
- Database
- Username
- Password
- SSL mode, optional with a conservative default matching current import behavior
- Description, optional

Actions:

- `测试连接`
- `保存`
- `保存并用于导入`

The test result should show:

- PostgreSQL version when available
- Whether basic login works
- Whether table discovery works
- For sync readiness, whether logical replication prerequisites appear satisfied, including `wal_level=logical` and replication permission where the current import checks can detect them

### Import Wizard

The existing database import wizard remains the main entry for importing into a chosen DBay database.

Step 1 changes from only manual connection fields to:

- Default path: select an existing `PostgreSQL` connector.
- Secondary path: create a new connector inline, then continue.
- Compatibility path: `临时连接`, which keeps the current host, port, database, user, and password fields without saving a connector.

Step 2 table selection and mode selection remain conceptually unchanged:

- Full import
- Selective import
- Sync

Step 3 confirmation should show connector name plus the resolved source summary. If the user used a temporary connection, it should show the host and database as today.

## Backend Architecture

Add a new connector package, for example `com.lakeon.connector`, with focused services and DTOs instead of embedding connector logic into import or OBS controllers.

Core backend components:

- `ConnectorEntity`
- `ConnectorRepository`
- `ConnectorController`
- `ConnectorService`
- `ConnectorSecretCrypto`
- `PostgresConnectorAdapter`
- `ObsConnectorProjection` or equivalent adapter over existing OBS connections

The `ConnectorService` owns generic lifecycle operations. Type-specific adapters own validation and metadata extraction.

### Data Model

Add a `connectors` table:

- `id`
- `tenant_id`
- `type`
- `name`
- `status`
- `config_json`
- `encrypted_secret_json`
- `last_tested_at`
- `last_error`
- `created_at`
- `updated_at`

For PostgreSQL:

- `config_json` stores non-secret fields: host, port, database, ssl mode, optional description.
- `encrypted_secret_json` stores secret fields: username if treated as sensitive, password.

Passwords must not be returned by any API response. If the current import task snapshot still stores source password using the existing behavior, Phase 1 should not expand that exposure into connector responses. A later hardening pass can encrypt import task snapshots as well.

### API

Add generic connector endpoints:

- `GET /api/v1/connectors`
- `POST /api/v1/connectors`
- `GET /api/v1/connectors/{id}`
- `PATCH /api/v1/connectors/{id}`
- `DELETE /api/v1/connectors/{id}`
- `POST /api/v1/connectors/{id}/test`
- `GET /api/v1/connectors/{id}/postgres/tables`

The table endpoint is PostgreSQL-specific in Phase 1 because table discovery is not a generic connector operation yet.

Keep existing OBS endpoints:

- `/api/v1/obs-connections`

The connector list can include OBS rows by projecting existing OBS connections. This avoids a risky migration while still giving users a unified entry point.

### Import API Compatibility

Extend the existing import request DTO:

- Add optional `connector_id`.
- Keep `source_host`, `source_port`, `source_dbname`, `source_user`, and `source_password`.

Behavior:

- If `connector_id` is present, `ImportService` resolves the PostgreSQL connector into a connection snapshot and fills the existing source fields internally.
- If manual source fields are present without `connector_id`, current behavior remains unchanged.
- If both are present, connector wins and manual source fields are ignored except for validation errors. The API should reject a non-PostgreSQL connector for import.

Add nullable `connector_id` to `ImportTaskEntity` and the import task table. Keep the current source snapshot fields on the task. This is important because an import job or retry should be stable even if the connector is edited later.

## Data Flow

1. User creates a PostgreSQL connector from the connector page or import wizard.
2. DBay tests the connector and stores last status, metadata, and error details.
3. User starts an import into a target DBay database and selects the connector.
4. `ImportService` resolves the connector to a source snapshot.
5. `ImportService` creates an import task with `connector_id` and the resolved source snapshot.
6. Existing table discovery, table task creation, compute wakeup, auto-suspend handling, and job pod launch continue through the current import implementation.
7. Existing sync status, pause, resume, cancel, retry, and stop endpoints continue to work for connector-backed tasks.

## Error Handling

Connector operations should return user-actionable errors:

- Authentication failure
- Host or network unreachable
- Database not found
- Permission denied
- Table discovery failed
- Sync prerequisites missing

Connector test failures update connector status and `last_error`. Import creation with a connector should fail before creating an import task if the connector cannot be resolved or tested enough to list tables.

Deleting a connector should be blocked if there are active import or sync tasks using it. Completed historical tasks can keep their connector id as lineage even if the connector is later deleted, but the UI should show it as deleted or unavailable.

## Security

- Store connector secrets encrypted at rest.
- Never include passwords in API responses or console state.
- Decrypt secrets only inside backend service calls that test, list tables, or create import task snapshots.
- Scope every connector query by tenant id.
- Do not allow an OBS connector id to be used for PostgreSQL import.
- Audit connector creation, update, delete, and test events if the existing audit framework has a nearby pattern; otherwise expose timestamps and last status in Phase 1.

## Migration and Compatibility

- Existing import tasks do not need backfill. They will have `connector_id = null`.
- Existing manual import creation remains supported.
- Existing OBS connections remain in `obs_connections`.
- The old OBS connection route can redirect to the new connector page filtered to OBS, or remain reachable while the menu points to `连接器`.
- Frontend copy should use one product term: `连接器`. Avoid mixing `数据源连接`, `OBS 连接`, and `外部连接` as separate concepts.

## Testing

Backend tests:

- Create/list/update/delete PostgreSQL connector.
- Test PostgreSQL connector with mocked success and failure.
- List PostgreSQL source tables through connector.
- Create import with `connector_id` and verify source snapshot plus connector lineage.
- Reject non-PostgreSQL connector for import.
- Preserve existing manual import request behavior.

Console tests:

- Connector page lists PostgreSQL and OBS connector rows.
- User creates and tests a PostgreSQL connector.
- Import wizard selects an existing PostgreSQL connector and creates an import task.
- Import wizard still supports temporary manual connection.

E2E smoke:

- Use a small external PostgreSQL source with two tables.
- Create connector.
- Test connector.
- Start full import into a DBay database.
- Verify import task appears with connector name and reaches the expected terminal status.

## Rollout

1. Add backend connector model, API, secret handling, and PostgreSQL adapter.
2. Add import DTO and service support for `connector_id`.
3. Add connector page in console and move OBS connection entry under it.
4. Update import wizard to select or create PostgreSQL connectors.
5. Run backend tests, console tests, and one smoke import.
6. Deploy API and console together because the console depends on new connector endpoints.

## Open Decisions Resolved

- Phase 1 prioritizes PostgreSQL import and sync, not OBS knowledge ingestion.
- OBS enters the connector center as an adapted existing connection type, not a full storage migration.
- Connector-backed imports keep task-level source snapshots for retry stability.
- Manual one-off PostgreSQL import stays available as `临时连接`.
