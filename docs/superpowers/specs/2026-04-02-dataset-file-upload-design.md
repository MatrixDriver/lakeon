# Dataset File Upload Design

## Goal

Allow users to create datasets by uploading files (JSONL, CSV, Parquet) directly to OBS, without needing a database. Supports uploading directories with subdirectory structure. Follows the same pattern as knowledge base document upload.

## Data Model Changes

**DatasetSourceType enum**: Add `FILE_UPLOAD` alongside existing `DB_EXPORT`, `JOB_OUTPUT`, `PIPELINE_OUTPUT`.

**DatasetEntity new fields**:
- `file_count` (Integer): Number of files in the dataset (for FILE_UPLOAD)
- `format` (String): File format hint — `jsonl`, `csv`, `parquet` (nullable, for FILE_UPLOAD)

**OBS path structure**: `datasets/{tenantId}/{datasetId}/{original/path/file.jsonl}`
- Preserves the original directory structure from the user's upload
- Example: uploading directory `train_data/` with `a.jsonl` and `sub/b.jsonl` produces:
  ```
  datasets/tn_xxx/ds_xxx/train_data/a.jsonl
  datasets/tn_xxx/ds_xxx/train_data/sub/b.jsonl
  ```

## API Endpoints

### POST /api/v1/datasets/upload-urls

Batch-generate presigned PUT URLs for uploading files. Creates the dataset record in DRAFT status.

**Request**:
```json
{
  "name": "my-training-data",
  "description": "News articles for text pipeline",
  "files": [
    {"path": "train_data/a.jsonl", "size": 102400},
    {"path": "train_data/sub/b.jsonl", "size": 51200}
  ]
}
```

**Response** (201):
```json
{
  "dataset_id": "ds_abc123",
  "uploads": [
    {
      "path": "train_data/a.jsonl",
      "obs_key": "datasets/tn_xxx/ds_abc123/train_data/a.jsonl",
      "upload_url": "https://obs...presigned-put-url",
      "expires_in": 900
    },
    {
      "path": "train_data/sub/b.jsonl",
      "obs_key": "datasets/tn_xxx/ds_abc123/train_data/sub/b.jsonl",
      "upload_url": "https://obs...presigned-put-url",
      "expires_in": 900
    }
  ]
}
```

**Validation**:
- `files` array must not be empty, max 200 files per request
- Each `path` must not contain `..` or start with `/`
- `name` is required

### POST /api/v1/datasets/{id}/finalize

Called after all files are uploaded. Verifies files exist in OBS, computes totals, transitions status.

**Response** (200):
```json
{
  "id": "ds_abc123",
  "status": "READY",
  "source_type": "FILE_UPLOAD",
  "file_count": 2,
  "file_size": 153600,
  "obs_path": "datasets/tn_xxx/ds_abc123/"
}
```

**Logic**:
1. List OBS objects under `datasets/{tenantId}/{datasetId}/` prefix
2. Count files, sum sizes
3. If no files found, return 400 error
4. Update dataset: `file_count`, `file_size`, `status=READY`, `obs_path`

### Existing endpoints — no changes needed

- `GET /datasets` — list works as-is (FILE_UPLOAD datasets appear alongside DB_EXPORT)
- `GET /datasets/{id}` — works as-is, returns download_url for the OBS prefix
- `DELETE /datasets/{id}` — works as-is, deletes OBS objects under the prefix

## CLI

### DbayClient new methods

```python
def get_dataset_upload_urls(self, name: str, files: list[dict], description: str = None) -> dict:
    """Get presigned upload URLs and create DRAFT dataset."""
    return self._request("POST", "/datasets/upload-urls", json={
        "name": name, "description": description, "files": files
    })

def finalize_dataset(self, dataset_id: str) -> dict:
    """Finalize dataset after upload."""
    return self._request("POST", f"/datasets/{dataset_id}/finalize")

def upload_dataset(self, name: str, path: str, description: str = None) -> dict:
    """Upload a file or directory as a dataset. High-level convenience method."""
    # 1. Scan path for files (recursively if directory)
    # 2. Call get_dataset_upload_urls
    # 3. Concurrent upload via httpx (presigned PUT)
    # 4. Call finalize_dataset
    # Returns finalized dataset
```

### CLI command (Typer)

```
dbay dataset upload --name "my-data" ./train_data/
dbay dataset upload --name "my-data" ./data.jsonl
```

- Displays progress bar during upload
- Supports single file or directory (recursive)

## Console UI

### DatalakeDatasetNew.vue changes

Add a tab/toggle at the top of the create page:

- **Tab 1: "From database"** (existing DB_EXPORT flow, unchanged)
- **Tab 2: "Upload files"** (new FILE_UPLOAD flow)

Upload tab contents:
- Dataset name input
- Description input (optional)
- Drag-and-drop zone / file picker (accepts files and directories via `webkitdirectory`)
- File list with name, size, remove button
- "Upload & Create" button
- Upload progress bar (uploaded count / total count, bytes transferred)

Flow:
1. User selects files/directory
2. Click "Upload & Create"
3. POST `/datasets/upload-urls` with file list
4. Upload each file to its presigned URL (parallel, max 4 concurrent)
5. POST `/datasets/{id}/finalize`
6. Navigate to dataset detail page

### DatalakeDatasetDetail.vue changes

For FILE_UPLOAD datasets:
- Show `file_count` and `file_size` instead of `row_count`
- Show file list (from OBS listing) in the overview tab
- Download button generates presigned URL for individual files

## E2E Test

```python
def test_upload_dataset_and_run_pipeline(e2e_client):
    """Upload JSONL fixtures → create pipeline → trigger run."""
    # 1. Get upload URLs for text fixture files
    # 2. Upload via presigned URLs
    # 3. Finalize dataset
    # 4. Create text pipeline
    # 5. Trigger run with dataset
    # 6. Poll for completion
```

No database creation needed.

## Implementation Order

1. API: DatasetSourceType + entity fields + upload-urls + finalize endpoints
2. CLI: client methods + upload command
3. Console: dataset create page upload tab
4. E2E test: upload fixtures and run pipeline
