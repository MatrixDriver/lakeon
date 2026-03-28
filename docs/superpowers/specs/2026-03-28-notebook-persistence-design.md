# Notebook Persistence + Management — Design Spec

## Goal

Persist notebooks to OBS so users can save, manage, and restore their work across sessions and devices.

## Data Model

### DB: `notebooks` table

| Field | Type | Description |
|-------|------|-------------|
| id | VARCHAR(64) | `nb_` + 12 hex |
| tenant_id | VARCHAR(64) | Owner |
| name | VARCHAR(256) | User-editable name |
| image | VARCHAR(32) | python-data / ray |
| dataset_ids | TEXT | Comma-separated |
| obs_path | VARCHAR(512) | `notebooks/{tenantId}/{id}/notebook.json` |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | Last save time |

### OBS: file structure

```
notebooks/{tenantId}/{notebookId}/
  notebook.json                        ← current version (overwritten on every save)
  versions/
    2026-03-28T12-00-00.json           ← manual save snapshots (Ctrl+S)
    2026-03-28T12-15-30.json
    ...
```

### `notebook.json` format

```json
{
  "cells": [
    {
      "id": "cell_abc",
      "code": "print('hello')",
      "cellType": "code",
      "outputs": [{"type": "stdout", "text": "hello\n"}],
      "execCount": 1,
      "durationMs": 5
    }
  ],
  "image": "python-data",
  "datasetIds": ["ds_xxx"]
}
```

## Routes

- `/datalake/notebook` → list page (all notebooks for tenant)
- `/datalake/notebook/:id` → editor page (existing page, refactored)

## API

All under `/api/v1/datalake/notebooks`, tenant-scoped via ApiKeyFilter.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/` | Create notebook (body: `{name, image}`) → create DB row + empty OBS file |
| GET | `/` | List notebooks (returns DB metadata only) |
| GET | `/:id` | Get notebook — DB metadata + read `notebook.json` from OBS |
| PUT | `/:id` | Save — upload cells JSON to OBS, update `updated_at` |
| PUT | `/:id?version=true` | Manual save — also creates timestamped snapshot in `versions/` |
| PATCH | `/:id` | Rename (body: `{name}`) |
| DELETE | `/:id` | Delete DB row + all OBS files under the notebook prefix |
| GET | `/:id/versions` | List versions — OBS ListObjects on `versions/` prefix |
| GET | `/:id/versions/:ts` | Get specific version content |
| POST | `/:id/versions/:ts/restore` | Restore — copy version content to `notebook.json` |

## Frontend

### List Page (`/datalake/notebook`)

- Table: name, image tag, last modified, actions (open / rename / delete)
- "New Notebook" button → dialog (name input + image select) → POST → redirect to editor
- Click row → navigate to `/datalake/notebook/:id`

### Editor Page (`/datalake/notebook/:id`)

Existing `DatalakeNotebook.vue` refactored:

- **onMounted**: `GET /notebooks/:id` → load cells + config from OBS, replace localStorage logic
- **Auto-save**: debounce 3s after any cell edit → `PUT /notebooks/:id` (no version snapshot)
- **Ctrl+S**: immediate `PUT /notebooks/:id?version=true` (creates version snapshot)
- **Save indicator**: toolbar shows "Saving..." → "Saved ✓" (auto-fade after 2s)
- **Back button**: link to `/datalake/notebook` list page
- **Version history**: toolbar button "History" → side panel listing versions by timestamp, click to preview, "Restore" button

### Version management

- Auto-save (debounce) does NOT create versions — too frequent
- Manual Ctrl+S creates a version snapshot in `versions/`
- Max 50 versions per notebook, oldest auto-cleaned on save
- Version preview is read-only (shows cells but can't edit)
- "Restore" copies version content to current `notebook.json` and reloads editor

### Migration from localStorage

No migration. Users create new notebooks; localStorage data stays until browser clears it. The old localStorage-based flow is removed.

## Not in scope

- GitHub sync (Phase 5 roadmap — OAuth + repo select + .ipynb export)
- Sharing notebooks between tenants
- Collaborative editing
