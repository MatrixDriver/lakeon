# Dataset File Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to create datasets by uploading files/directories to OBS, without needing a database.

**Architecture:** Add `FILE_UPLOAD` source type to the existing dataset system. Use presigned PUT URLs (same pattern as knowledge base upload) so clients upload directly to OBS. API creates DRAFT record + presigned URLs, client uploads files, then calls finalize to mark READY. CLI wraps this into a single `upload_dataset()` method. Console adds an "Upload files" tab on the dataset creation page.

**Tech Stack:** Java 17 / Spring Boot (API), Python httpx (CLI), Vue 3 + TypeScript (Console), OBS S3 presigned URLs

---

### Task 1: Add FILE_UPLOAD source type and entity fields

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetSourceType.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java`

- [ ] **Step 1: Add FILE_UPLOAD to DatasetSourceType**

```java
// DatasetSourceType.java
public enum DatasetSourceType {
    DB_EXPORT, JOB_OUTPUT, PIPELINE_OUTPUT, FILE_UPLOAD
}
```

- [ ] **Step 2: Add file_count field to DatasetEntity**

Add after the `fileSize` field:

```java
@Column(name = "file_count")
private Integer fileCount;
```

Add getter/setter:

```java
public Integer getFileCount() { return fileCount; }
public void setFileCount(Integer fileCount) { this.fileCount = fileCount; }
```

- [ ] **Step 3: Compile and verify**

Run: `cd lakeon-api && mvn compile -q`
Expected: no errors

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/dataset/DatasetSourceType.java \
        lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java
git commit -m "feat(api): add FILE_UPLOAD source type and file_count field to dataset"
```

---

### Task 2: Add upload-urls and finalize API endpoints

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetController.java`

- [ ] **Step 1: Add presigner builder to DatasetService**

Add imports at top of DatasetService.java:

```java
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.S3Client;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
```

Add presigner builder method (follow KnowledgeService pattern):

```java
private S3Presigner buildPresigner() {
    return S3Presigner.builder()
            .endpointOverride(URI.create(props.getObs().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
            .region(Region.of(props.getObs().getRegion()))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
            .build();
}

private S3Client buildS3Client() {
    return S3Client.builder()
            .endpointOverride(URI.create(props.getObs().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
            .region(Region.of(props.getObs().getRegion()))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
            .build();
}
```

- [ ] **Step 2: Add generateUploadUrls method to DatasetService**

```java
@Transactional
public Map<String, Object> generateUploadUrls(String tenantId, String name, String description,
                                               List<Map<String, Object>> files) {
    if (name == null || name.isBlank()) {
        throw new BadRequestException("Dataset name is required");
    }
    if (files == null || files.isEmpty()) {
        throw new BadRequestException("At least one file is required");
    }
    if (files.size() > 200) {
        throw new BadRequestException("Maximum 200 files per upload");
    }

    // Create dataset entity
    DatasetEntity entity = new DatasetEntity();
    entity.setTenantId(tenantId);
    entity.setName(name);
    entity.setDescription(description);
    entity.setSourceType(DatasetSourceType.FILE_UPLOAD);
    entity.setStatus(DatasetStatus.DRAFT);
    datasetRepository.save(entity);

    String bucket = props.getObs().getBucket();
    String prefix = "datasets/" + tenantId + "/" + entity.getId() + "/";
    int expireSeconds = 900;

    List<Map<String, Object>> uploads = new java.util.ArrayList<>();
    try (S3Presigner presigner = buildPresigner()) {
        for (Map<String, Object> file : files) {
            String path = (String) file.get("path");
            if (path == null || path.contains("..") || path.startsWith("/")) {
                throw new BadRequestException("Invalid file path: " + path);
            }
            String obsKey = prefix + path;

            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(obsKey)
                    .build();
            PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expireSeconds))
                    .putObjectRequest(putReq)
                    .build();
            String uploadUrl = presigner.presignPutObject(presignReq).url().toString();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", path);
            item.put("obs_key", obsKey);
            item.put("upload_url", uploadUrl);
            item.put("expires_in", expireSeconds);
            uploads.add(item);
        }
    }

    entity.setObsPath(prefix);
    datasetRepository.save(entity);

    log.info("Generated {} upload URLs for dataset {} tenant {}", files.size(), entity.getId(), tenantId);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dataset_id", entity.getId());
    result.put("uploads", uploads);
    return result;
}
```

- [ ] **Step 3: Add finalizeUpload method to DatasetService**

```java
@Transactional
public DatasetEntity finalizeUpload(String tenantId, String datasetId) {
    DatasetEntity dataset = datasetRepository.findByIdAndTenantId(datasetId, tenantId)
            .orElseThrow(() -> new NotFoundException("Dataset not found: " + datasetId));

    if (dataset.getSourceType() != DatasetSourceType.FILE_UPLOAD) {
        throw new BadRequestException("Dataset is not a file upload dataset");
    }
    if (dataset.getStatus() != DatasetStatus.DRAFT) {
        throw new BadRequestException("Dataset is not in DRAFT status");
    }

    String bucket = props.getObs().getBucket();
    String prefix = dataset.getObsPath();

    // List OBS objects to count files and total size
    int fileCount = 0;
    long totalSize = 0;
    try (S3Client s3 = buildS3Client()) {
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix);
            if (continuationToken != null) {
                reqBuilder.continuationToken(continuationToken);
            }
            var resp = s3.listObjectsV2(reqBuilder.build());
            for (S3Object obj : resp.contents()) {
                fileCount++;
                totalSize += obj.size();
            }
            continuationToken = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (continuationToken != null);
    }

    if (fileCount == 0) {
        throw new BadRequestException("No files found in OBS. Upload files before finalizing.");
    }

    dataset.setFileCount(fileCount);
    dataset.setFileSize(totalSize);
    dataset.setStatus(DatasetStatus.READY);
    datasetRepository.save(dataset);

    log.info("Finalized dataset {} with {} files ({} bytes) for tenant {}",
            datasetId, fileCount, totalSize, tenantId);
    return dataset;
}
```

- [ ] **Step 4: Add upload-urls and finalize endpoints to DatasetController**

Add before the existing `@GetMapping`:

```java
@PostMapping("/upload-urls")
@ResponseStatus(HttpStatus.CREATED)
public Map<String, Object> getUploadUrls(HttpServletRequest req, @RequestBody Map<String, Object> body) {
    String tenantId = (String) req.getAttribute("tenantId");
    String name = (String) body.get("name");
    String description = (String) body.get("description");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
    return datasetService.generateUploadUrls(tenantId, name, description, files);
}

@PostMapping("/{id}/finalize")
public Map<String, Object> finalize(HttpServletRequest req, @PathVariable String id) {
    String tenantId = (String) req.getAttribute("tenantId");
    DatasetEntity ds = datasetService.finalizeUpload(tenantId, id);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", ds.getId());
    m.put("name", ds.getName());
    m.put("status", ds.getStatus().name());
    m.put("source_type", ds.getSourceType().name());
    m.put("file_count", ds.getFileCount());
    m.put("file_size", ds.getFileSize());
    m.put("obs_path", ds.getObsPath());
    return m;
}
```

- [ ] **Step 5: Add file_count to existing response mapper**

In the `toMap` helper method (or wherever `m.put("file_size", ...)` is), add after it:

```java
m.put("file_count", ds.getFileCount());
```

- [ ] **Step 6: Compile and verify**

Run: `cd lakeon-api && mvn compile -q`
Expected: no errors

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/dataset/DatasetService.java \
        lakeon-api/src/main/java/com/lakeon/dataset/DatasetController.java
git commit -m "feat(api): add dataset upload-urls and finalize endpoints"
```

---

### Task 3: CLI upload_dataset method

**Files:**
- Modify: `dbay-cli/dbay_cli/client.py`

- [ ] **Step 1: Add upload URL and finalize methods to DbayClient**

Add after the existing `delete_dataset` method:

```python
def get_dataset_upload_urls(self, name: str, files: list[dict],
                            description: str | None = None) -> dict:
    """Get presigned upload URLs and create DRAFT dataset."""
    body: dict = {"name": name, "files": files}
    if description:
        body["description"] = description
    return self._request("POST", "/datasets/upload-urls", json=body)

def finalize_dataset(self, dataset_id: str) -> dict:
    """Finalize dataset after all files are uploaded."""
    return self._request("POST", f"/datasets/{dataset_id}/finalize")

def upload_dataset(self, name: str, path: str,
                   description: str | None = None) -> dict:
    """Upload a file or directory as a dataset.

    Scans path recursively, gets presigned URLs, uploads files
    concurrently, then finalizes the dataset.
    """
    import os
    import concurrent.futures

    # 1. Scan files
    path = os.path.abspath(path)
    if os.path.isfile(path):
        base_dir = os.path.dirname(path)
        file_list = [path]
    else:
        base_dir = path
        file_list = []
        for root, _dirs, fnames in os.walk(path):
            for fname in fnames:
                file_list.append(os.path.join(root, fname))

    if not file_list:
        raise ValueError(f"No files found at {path}")

    files_meta = []
    for fp in file_list:
        rel = os.path.relpath(fp, os.path.dirname(base_dir) if os.path.isfile(path) else os.path.dirname(path))
        files_meta.append({"path": rel, "size": os.path.getsize(fp)})

    # 2. Get presigned URLs
    resp = self.get_dataset_upload_urls(name, files_meta, description)
    dataset_id = resp["dataset_id"]
    uploads = resp["uploads"]

    # 3. Upload files concurrently
    def _upload_one(upload_info: dict, local_path: str):
        import httpx
        with open(local_path, "rb") as f:
            r = httpx.put(upload_info["upload_url"], content=f.read(), timeout=300)
            r.raise_for_status()

    path_to_local = {}
    for fp in file_list:
        rel = os.path.relpath(fp, os.path.dirname(base_dir) if os.path.isfile(path) else os.path.dirname(path))
        path_to_local[rel] = fp

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futures = []
        for u in uploads:
            local = path_to_local[u["path"]]
            futures.append(pool.submit(_upload_one, u, local))
        for fut in concurrent.futures.as_completed(futures):
            fut.result()  # raise on error

    # 4. Finalize
    return self.finalize_dataset(dataset_id)
```

- [ ] **Step 2: Verify syntax**

Run: `python3 -c "import ast; ast.parse(open('dbay-cli/dbay_cli/client.py').read()); print('OK')"`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add dbay-cli/dbay_cli/client.py
git commit -m "feat(cli): add upload_dataset method to DbayClient"
```

---

### Task 4: Console dataset upload tab

**Files:**
- Modify: `lakeon-console/src/views/datalake/DatalakeDatasetNew.vue`
- Modify: `lakeon-console/src/api/datalake.ts`

- [ ] **Step 1: Add API functions to datalake.ts**

Add to `lakeon-console/src/api/datalake.ts`:

```typescript
export function getDatasetUploadUrls(name: string, files: { path: string; size: number }[], description?: string) {
  return api.post('/datasets/upload-urls', { name, description, files })
}

export function finalizeDataset(datasetId: string) {
  return api.post(`/datasets/${datasetId}/finalize`)
}
```

- [ ] **Step 2: Add upload tab to DatalakeDatasetNew.vue**

Add a `createMode` ref and tab toggle at the top of the form. Add file upload state and handlers. The upload tab includes:
- Dataset name input (shared with DB export tab)
- Description input
- File drop zone / directory picker using `<input type="file" webkitdirectory multiple>`
- File list display with name, size, remove button
- Upload progress (file count done / total)
- "Upload & Create" button

Key reactive state to add:

```typescript
const createMode = ref<'db' | 'upload'>('db')
const uploadFiles = ref<File[]>([])
const uploadProgress = ref({ done: 0, total: 0, uploading: false })
```

Key upload handler:

```typescript
async function handleUpload() {
  if (!datasetName.value.trim() || uploadFiles.value.length === 0) return
  uploadProgress.value = { done: 0, total: uploadFiles.value.length, uploading: true }

  const files = uploadFiles.value.map(f => ({
    path: f.webkitRelativePath || f.name,
    size: f.size,
  }))

  const { data } = await getDatasetUploadUrls(datasetName.value, files, description.value)
  const datasetId = data.dataset_id
  const uploads = data.uploads

  // Upload files concurrently (max 4)
  const queue = [...uploads]
  const workers = Array.from({ length: Math.min(4, queue.length) }, async () => {
    while (queue.length > 0) {
      const item = queue.shift()!
      const file = uploadFiles.value.find(f =>
        (f.webkitRelativePath || f.name) === item.path
      )!
      await fetch(item.upload_url, { method: 'PUT', body: file })
      uploadProgress.value.done++
    }
  })
  await Promise.all(workers)

  await finalizeDataset(datasetId)
  uploadProgress.value.uploading = false
  router.push(`/datalake/datasets/${datasetId}`)
}
```

- [ ] **Step 3: Add file_count display to DatalakeDatasetDetail.vue**

In the info grid section of `DatalakeDatasetDetail.vue`, add file_count display for FILE_UPLOAD datasets:

```html
<div v-if="dataset.source_type === 'FILE_UPLOAD'" class="info-item">
  <span class="label">Files</span>
  <span>{{ dataset.file_count }}</span>
</div>
```

- [ ] **Step 4: Type check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: no errors

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/api/datalake.ts \
        lakeon-console/src/views/datalake/DatalakeDatasetNew.vue \
        lakeon-console/src/views/datalake/DatalakeDatasetDetail.vue
git commit -m "feat(console): add file upload tab to dataset creation page"
```

---

### Task 5: E2E test — upload dataset and run text pipeline

**Files:**
- Modify: `tests/e2e/test_pipeline_text.py`

- [ ] **Step 1: Replace database-based fixtures with upload-based fixtures**

Replace the `pipeline_db`, `news_dataset`, `review_dataset`, `chinese_dataset` fixtures with upload-based versions. Remove `_insert_text_records` helper.

New fixture pattern:

```python
@pytest.fixture(scope="module")
def news_dataset(e2e_client):
    """Upload news articles JSONL as dataset."""
    records_path = os.path.join(TEXT_FIXTURES_DIR, "news_articles.jsonl")
    ds = e2e_client.upload_dataset(
        name=f"e2e-news-{int(time.time())}",
        path=records_path,
        description="E2E news articles",
    )
    assert ds["status"] == "READY"
    assert ds["file_count"] == 1

    yield ds

    try:
        e2e_client.delete_dataset(ds["id"])
    except Exception:
        pass
```

Same pattern for `review_dataset` (movie_reviews.jsonl) and `chinese_dataset` (chinese_abstracts.jsonl).

- [ ] **Step 2: Remove database fixture and helpers**

Remove `pipeline_db` fixture, `_insert_text_records` helper, and `run_psql` import since they're no longer needed.

- [ ] **Step 3: Verify tests collect**

Run: `python3 -m pytest tests/e2e/test_pipeline_text.py --collect-only`
Expected: 5 tests collected

- [ ] **Step 4: Commit**

```bash
git add tests/e2e/test_pipeline_text.py
git commit -m "feat(e2e): use file upload for text pipeline dataset fixtures"
```

---

### Task 6: Build, deploy, and run E2E tests

**Files:** (no code changes, deployment only)

- [ ] **Step 1: Build and push API image**

```bash
IMAGE_TAG=0.9.182 SITE=hwstaff bash deploy/cce/build-and-push-api.sh
```

- [ ] **Step 2: Deploy API**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl set image deployment/lakeon-api \
  lakeon-api=swr.cn-north-4.myhuaweicloud.com/flex/lakeon-api:0.9.182 -n lakeon
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-api -n lakeon --timeout=120s
```

- [ ] **Step 3: Run E2E upload test**

```bash
PIPELINE_SKIP_COMPLETION=1 python3 -m pytest tests/e2e/test_pipeline_text.py -v --timeout=300
```

Expected: Tests create datasets via upload (no database), trigger pipeline runs. With `PIPELINE_SKIP_COMPLETION=1`, tests skip waiting for orchestrator completion.

- [ ] **Step 4: Run full E2E (if orchestrator is working)**

```bash
python3 -m pytest tests/e2e/test_pipeline_text.py::TestTextPipelineNews -v --timeout=600
```
