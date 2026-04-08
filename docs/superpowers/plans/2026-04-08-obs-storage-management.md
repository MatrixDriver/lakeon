# OBS 存储管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "存储" Tab to the SRE InfraMonitor page showing OBS storage usage per tenant (databases, knowledge bases, memory bases) with orphan detection and cleanup.

**Architecture:** Backend adds 3 endpoints to AdminController that aggregate data from NeonApiClient (logical_size), DocumentEntity (obsSize), MemoryBaseEntity (via database), and S3 ListObjectsV2 (orphan scan). Frontend adds StoragePanel.vue as a lazy-loaded component in InfraMonitor's new 5th tab.

**Tech Stack:** Java 17, Spring Boot, AWS S3 SDK v2, Vue 3 Composition API, TypeScript

---

### Task 1: Backend — GET /admin/storage/summary

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/service/AdminService.java`

- [ ] **Step 1: Add storageSummary endpoint to AdminController**

In `AdminController.java`, add after the existing `/infra/*` endpoints:

```java
@GetMapping("/storage/summary")
public Map<String, Object> getStorageSummary() {
    return adminService.getStorageSummary();
}
```

- [ ] **Step 2: Add getStorageSummary to AdminService**

Add these imports to `AdminService.java`:

```java
import com.lakeon.knowledge.KnowledgeBaseEntity;
import com.lakeon.knowledge.KnowledgeBaseRepository;
import com.lakeon.knowledge.DocumentEntity;
import com.lakeon.knowledge.DocumentRepository;
import com.lakeon.memory.MemoryBaseEntity;
import com.lakeon.memory.MemoryBaseRepository;
import com.lakeon.neon.dto.NeonTimeline;
```

Inject the new repositories in the constructor (add parameters and fields):

```java
private final KnowledgeBaseRepository knowledgeBaseRepository;
private final DocumentRepository documentRepository;
private final MemoryBaseRepository memoryBaseRepository;
```

Add the method:

```java
public Map<String, Object> getStorageSummary() {
    Map<String, Object> result = new LinkedHashMap<>();
    List<Map<String, Object>> tenantSummaries = new ArrayList<>();

    // Load all tenants
    var allTenants = tenantRepository.findAll();
    Set<String> knownTenantIds = allTenants.stream()
            .map(t -> t.getId()).collect(java.util.stream.Collectors.toSet());

    long totalDbBytes = 0;
    long totalKbDocBytes = 0;
    long totalMemoryBytes = 0;

    for (var tenant : allTenants) {
        String tid = tenant.getId();
        Map<String, Object> ts = new LinkedHashMap<>();
        ts.put("tenantId", tid);
        ts.put("tenantName", tenant.getName());
        ts.put("status", "ACTIVE");

        List<Map<String, Object>> items = new ArrayList<>();
        long tenantDbBytes = 0;
        long tenantKbDocBytes = 0;
        long tenantMemoryBytes = 0;

        // 1) Databases (non-KB, non-memory)
        List<DatabaseEntity> dbs = databaseRepository.findAllByTenantId(tid);
        Set<String> kbDbIds = new HashSet<>();
        Set<String> memDbIds = new HashSet<>();

        // Identify KB and memory database IDs
        List<KnowledgeBaseEntity> kbs = knowledgeBaseRepository.findAllByTenantIdOrderByCreatedAtDesc(tid);
        for (var kb : kbs) {
            if (kb.getDatabaseId() != null) kbDbIds.add(kb.getDatabaseId());
        }
        List<MemoryBaseEntity> mbs = memoryBaseRepository.findByTenantIdOrderByCreatedAtDesc(tid);
        for (var mb : mbs) {
            if (mb.getDatabaseId() != null) memDbIds.add(mb.getDatabaseId());
        }

        // Standalone databases
        for (var db : dbs) {
            if (kbDbIds.contains(db.getId()) || memDbIds.contains(db.getId())) continue;
            if (db.getStatus() == DatabaseStatus.DELETED) continue;
            long dbSize = getTimelineLogicalSize(db);
            tenantDbBytes += dbSize;
            items.add(Map.of(
                "type", "database",
                "id", db.getId(),
                "name", db.getName(),
                "status", db.getStatus().name(),
                "dbBytes", dbSize,
                "kbDocBytes", 0L,
                "memoryBytes", 0L
            ));
        }

        // 2) Knowledge bases
        for (var kb : kbs) {
            long kbDbSize = 0;
            if (kb.getDatabaseId() != null) {
                var kbDb = dbs.stream().filter(d -> d.getId().equals(kb.getDatabaseId())).findFirst();
                if (kbDb.isPresent()) kbDbSize = getTimelineLogicalSize(kbDb.get());
            }
            long docSize = documentRepository.sumObsSizeByKbId(tid, kb.getId());
            tenantDbBytes += kbDbSize;
            tenantKbDocBytes += docSize;
            items.add(Map.of(
                "type", "knowledge_base",
                "id", kb.getId(),
                "name", kb.getName(),
                "status", kb.getStatus().name(),
                "dbBytes", kbDbSize,
                "kbDocBytes", docSize,
                "memoryBytes", 0L
            ));
        }

        // 3) Memory bases
        for (var mb : mbs) {
            long mbDbSize = 0;
            if (mb.getDatabaseId() != null) {
                var mbDb = dbs.stream().filter(d -> d.getId().equals(mb.getDatabaseId())).findFirst();
                if (mbDb.isPresent()) mbDbSize = getTimelineLogicalSize(mbDb.get());
            }
            tenantDbBytes += mbDbSize;
            items.add(Map.of(
                "type", "memory_base",
                "id", mb.getId(),
                "name", mb.getName(),
                "status", "ACTIVE",
                "dbBytes", mbDbSize,
                "kbDocBytes", 0L,
                "memoryBytes", mbDbSize
            ));
        }

        ts.put("dbBytes", tenantDbBytes);
        ts.put("kbDocBytes", tenantKbDocBytes);
        ts.put("memoryBytes", tenantMemoryBytes);
        ts.put("totalBytes", tenantDbBytes + tenantKbDocBytes);
        ts.put("items", items);
        tenantSummaries.add(ts);

        totalDbBytes += tenantDbBytes;
        totalKbDocBytes += tenantKbDocBytes;
    }

    result.put("totalDbBytes", totalDbBytes);
    result.put("totalKbDocBytes", totalKbDocBytes);
    result.put("totalObsBytes", totalDbBytes + totalKbDocBytes);
    result.put("orphanBytes", 0L);
    result.put("orphans", List.of());
    result.put("tenants", tenantSummaries);
    result.put("lastScanTime", null);
    return result;
}

private long getTimelineLogicalSize(DatabaseEntity db) {
    if (db.getNeonTenantId() == null || db.getNeonTimelineId() == null) return 0;
    try {
        NeonTimeline tl = neonApiClient.getTimeline(db.getNeonTenantId(), db.getNeonTimelineId());
        return tl.getCurrentLogicalSize() != null ? tl.getCurrentLogicalSize() : 0;
    } catch (Exception e) {
        log.warn("Failed to get timeline size for {}: {}", db.getId(), e.getMessage());
        return 0;
    }
}
```

- [ ] **Step 3: Add sumObsSizeByKbId query to DocumentRepository**

In `DocumentRepository.java`, add:

```java
@Query("SELECT COALESCE(SUM(d.obsSize), 0) FROM DocumentEntity d WHERE d.tenantId = :tenantId AND d.kbId = :kbId")
long sumObsSizeByKbId(@Param("tenantId") String tenantId, @Param("kbId") String kbId);
```

- [ ] **Step 4: Verify AdminService has neonApiClient injected**

Check `AdminService` constructor already has `NeonApiClient neonApiClient` — it should from existing code. If not, add the field and constructor parameter.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/AdminController.java \
        lakeon-api/src/main/java/com/lakeon/service/AdminService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java
git commit -m "feat(api): add GET /admin/storage/summary endpoint"
```

---

### Task 2: Backend — POST /admin/storage/scan (OBS orphan detection)

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/service/AdminService.java`

- [ ] **Step 1: Add scan endpoint to AdminController**

```java
@PostMapping("/storage/scan")
public Map<String, Object> scanObsStorage() {
    return adminService.scanObsStorage();
}
```

- [ ] **Step 2: Add scanObsStorage to AdminService**

Add S3 imports:

```java
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.regions.Region;
```

Add the method:

```java
public Map<String, Object> scanObsStorage() {
    Map<String, Object> summary = getStorageSummary();

    // Scan OBS for orphan prefixes
    Set<String> knownTenantIds = tenantRepository.findAll().stream()
            .map(t -> t.getId()).collect(java.util.stream.Collectors.toSet());

    String bucket = props.getObs().getBucket();
    String[] topPrefixes = {"datasets/", "knowledge/", "tenant-", "datalake-logs/", "datasources/"};

    Map<String, Long> orphanMap = new LinkedHashMap<>();

    try (S3Client s3 = buildS3Client()) {
        for (String topPrefix : topPrefixes) {
            // List "directories" under each top prefix
            ListObjectsV2Request req = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(topPrefix)
                    .delimiter("/")
                    .build();
            ListObjectsV2Response resp = s3.listObjectsV2(req);
            for (CommonPrefix cp : resp.commonPrefixes()) {
                // Extract tenant ID from prefix like "datasets/tn_xxx/"
                String sub = cp.prefix().substring(topPrefix.length());
                String tenantId = sub.endsWith("/") ? sub.substring(0, sub.length() - 1) : sub;
                // Handle "tenant-xxx/" format
                if (topPrefix.equals("tenant-")) tenantId = sub.replace("/", "");

                if (!knownTenantIds.contains(tenantId)) {
                    // Orphan — count bytes
                    long bytes = countPrefixBytes(s3, bucket, cp.prefix());
                    orphanMap.merge(tenantId, bytes, Long::sum);
                }
            }
        }
    }

    long orphanTotal = orphanMap.values().stream().mapToLong(Long::longValue).sum();
    List<Map<String, Object>> orphans = orphanMap.entrySet().stream()
            .map(e -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("tenantId", e.getKey());
                o.put("bytes", e.getValue());
                return o;
            })
            .toList();

    summary.put("orphanBytes", orphanTotal);
    summary.put("orphans", orphans);
    summary.put("totalObsBytes", (long) summary.get("totalDbBytes") + (long) summary.get("totalKbDocBytes") + orphanTotal);
    summary.put("lastScanTime", java.time.Instant.now().toString());
    return summary;
}

private long countPrefixBytes(S3Client s3, String bucket, String prefix) {
    long total = 0;
    String token = null;
    do {
        ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefix).maxKeys(1000);
        if (token != null) req.continuationToken(token);
        ListObjectsV2Response resp = s3.listObjectsV2(req.build());
        for (S3Object obj : resp.contents()) {
            total += obj.size();
        }
        token = resp.isTruncated() ? resp.nextContinuationToken() : null;
    } while (token != null);
    return total;
}

private S3Client buildS3Client() {
    return S3Client.builder()
            .endpointOverride(java.net.URI.create(props.getObs().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
            .region(Region.of("cn-north-4"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
            .build();
}
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/AdminController.java \
        lakeon-api/src/main/java/com/lakeon/service/AdminService.java
git commit -m "feat(api): add POST /admin/storage/scan with orphan detection"
```

---

### Task 3: Backend — POST /admin/storage/cleanup

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/service/AdminService.java`

- [ ] **Step 1: Add cleanup endpoint to AdminController**

```java
@PostMapping("/storage/cleanup")
public Map<String, Object> cleanupOrphanStorage(@RequestBody Map<String, Object> body) {
    String tenantId = (String) body.get("tenantId");
    boolean dryRun = body.get("dryRun") != null ? (Boolean) body.get("dryRun") : true;
    return adminService.cleanupOrphanStorage(tenantId, dryRun);
}
```

- [ ] **Step 2: Add cleanupOrphanStorage to AdminService**

```java
public Map<String, Object> cleanupOrphanStorage(String tenantId, boolean dryRun) {
    // Safety: verify tenant does not exist
    if (tenantRepository.findById(tenantId).isPresent()) {
        throw new IllegalArgumentException("Tenant " + tenantId + " still exists, cannot cleanup");
    }

    String bucket = props.getObs().getBucket();
    String[] prefixes = {
        "datasets/" + tenantId + "/",
        "knowledge/" + tenantId + "/",
        "tenant-" + tenantId + "/",
        "datalake-logs/" + tenantId + "/",
        "datasources/" + tenantId + "/"
    };

    int totalDeleted = 0;
    long totalBytes = 0;

    try (S3Client s3 = buildS3Client()) {
        for (String prefix : prefixes) {
            String token = null;
            do {
                ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(prefix).maxKeys(1000);
                if (token != null) req.continuationToken(token);
                ListObjectsV2Response resp = s3.listObjectsV2(req.build());

                List<ObjectIdentifier> keys = resp.contents().stream()
                        .map(o -> {
                            totalBytes += o.size();
                            return ObjectIdentifier.builder().key(o.key()).build();
                        })
                        .toList();

                // Note: totalBytes is accumulated via side effect above — 
                // need to track separately for dryRun
                if (!dryRun && !keys.isEmpty()) {
                    s3.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(keys).build())
                            .build());
                }
                totalDeleted += keys.size();
                token = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (token != null);
        }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tenantId", tenantId);
    result.put("objectsDeleted", totalDeleted);
    result.put("bytesFreed", totalBytes);
    result.put("dryRun", dryRun);
    return result;
}
```

Note: The `totalBytes` tracking in the lambda has a compilation issue because it captures a non-effectively-final variable. Fix by using an `AtomicLong`:

Replace the inner loop with:

```java
                java.util.concurrent.atomic.AtomicLong batchBytes = new java.util.concurrent.atomic.AtomicLong();
                List<ObjectIdentifier> keys = resp.contents().stream()
                        .peek(o -> batchBytes.addAndGet(o.size()))
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                totalBytes += batchBytes.get();
```

And make `totalBytes` a local `long[]` or `AtomicLong` at method scope:

```java
    java.util.concurrent.atomic.AtomicLong totalBytes = new java.util.concurrent.atomic.AtomicLong();
    int totalDeleted = 0;
```

Return uses `totalBytes.get()`.

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/AdminController.java \
        lakeon-api/src/main/java/com/lakeon/service/AdminService.java
git commit -m "feat(api): add POST /admin/storage/cleanup for orphan removal"
```

---

### Task 4: Frontend — admin API bindings

**Files:**
- Modify: `lakeon-admin/src/api/admin.ts`

- [ ] **Step 1: Add storage API methods**

Add to the `adminApi` object:

```typescript
  // Storage management
  storageSummary: () => client.get('/storage/summary'),
  storageScan: () => client.post('/storage/scan'),
  storageCleanup: (tenantId: string, dryRun: boolean) =>
    client.post('/storage/cleanup', { tenantId, dryRun }),
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-admin/src/api/admin.ts
git commit -m "feat(admin): add storage API bindings"
```

---

### Task 5: Frontend — StoragePanel.vue

**Files:**
- Create: `lakeon-admin/src/views/system/StoragePanel.vue`

- [ ] **Step 1: Create StoragePanel.vue**

```vue
<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { adminApi } from '../../api/admin'

interface StorageItem {
  type: string
  id: string
  name: string
  status: string
  dbBytes: number
  kbDocBytes: number
  memoryBytes: number
}

interface TenantStorage {
  tenantId: string
  tenantName: string
  status: string
  dbBytes: number
  kbDocBytes: number
  memoryBytes: number
  totalBytes: number
  items: StorageItem[]
}

interface OrphanEntry {
  tenantId: string
  bytes: number
}

interface StorageData {
  totalObsBytes: number
  totalDbBytes: number
  totalKbDocBytes: number
  orphanBytes: number
  tenants: TenantStorage[]
  orphans: OrphanEntry[]
  lastScanTime: string | null
}

const data = ref<StorageData | null>(null)
const loading = ref(false)
const scanning = ref(false)
const cleaningUp = ref<string | null>(null)
const expandedTenants = ref<Set<string>>(new Set())

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i]
}

function toggleExpand(tenantId: string) {
  const s = new Set(expandedTenants.value)
  if (s.has(tenantId)) s.delete(tenantId); else s.add(tenantId)
  expandedTenants.value = s
}

async function loadSummary() {
  loading.value = true
  try {
    const resp = await adminApi.storageSummary()
    data.value = resp.data
  } catch (e) {
    console.error('Failed to load storage summary', e)
  } finally {
    loading.value = false
  }
}

async function runScan() {
  scanning.value = true
  try {
    const resp = await adminApi.storageScan()
    data.value = resp.data
  } catch (e) {
    console.error('OBS scan failed', e)
  } finally {
    scanning.value = false
  }
}

async function cleanupOrphan(tenantId: string) {
  // Dry run first
  cleaningUp.value = tenantId
  try {
    const dry = await adminApi.storageCleanup(tenantId, true)
    const count = dry.data.objectsDeleted
    const size = formatBytes(dry.data.bytesFreed)
    if (!confirm(`将删除 ${count} 个对象，释放 ${size}。确认清理？`)) return
    await adminApi.storageCleanup(tenantId, false)
    await loadSummary()
  } catch (e) {
    console.error('Cleanup failed', e)
  } finally {
    cleaningUp.value = null
  }
}

function itemIcon(type: string): string {
  if (type === 'database') return '├ '
  if (type === 'knowledge_base') return '├ '
  if (type === 'memory_base') return '├ '
  return ''
}

function itemLabel(type: string): string {
  if (type === 'database') return '数据库'
  if (type === 'knowledge_base') return '知识库'
  if (type === 'memory_base') return '记忆库'
  return type
}

onMounted(loadSummary)
</script>

<template>
  <div class="storage-panel">
    <!-- Summary cards -->
    <div v-if="data" class="summary-cards">
      <div class="summary-card">
        <div class="card-label">OBS 总用量</div>
        <div class="card-value primary">{{ formatBytes(data.totalObsBytes) }}</div>
      </div>
      <div class="summary-card">
        <div class="card-label">数据库存储</div>
        <div class="card-value blue">{{ formatBytes(data.totalDbBytes) }}</div>
      </div>
      <div class="summary-card">
        <div class="card-label">知识库文档</div>
        <div class="card-value green">{{ formatBytes(data.totalKbDocBytes) }}</div>
      </div>
      <div v-if="data.orphanBytes > 0" class="summary-card">
        <div class="card-label">孤儿对象</div>
        <div class="card-value red">{{ formatBytes(data.orphanBytes) }}</div>
      </div>
    </div>

    <!-- Toolbar -->
    <div class="storage-toolbar">
      <span class="toolbar-title">租户存储明细</span>
      <div class="toolbar-actions">
        <button class="btn-scan" @click="runScan" :disabled="scanning">
          {{ scanning ? '扫描中...' : '刷新 OBS 扫描' }}
        </button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="loading-msg">加载中...</div>

    <!-- Table -->
    <div v-else-if="data" class="storage-table-wrap">
      <table class="storage-table">
        <thead>
          <tr>
            <th style="width:32px"></th>
            <th>租户</th>
            <th class="num">数据库</th>
            <th class="num">知识库</th>
            <th class="num">记忆库</th>
            <th class="num">总量</th>
            <th class="num">状态</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="t in data.tenants" :key="t.tenantId">
            <tr class="tenant-row" @click="toggleExpand(t.tenantId)">
              <td class="expand-col">{{ expandedTenants.has(t.tenantId) ? '▼' : '▶' }}</td>
              <td class="name-col">{{ t.tenantName || t.tenantId }}</td>
              <td class="num blue">{{ formatBytes(t.dbBytes) }}</td>
              <td class="num green">{{ formatBytes(t.kbDocBytes) }}</td>
              <td class="num purple">{{ t.memoryBytes ? formatBytes(t.memoryBytes) : '—' }}</td>
              <td class="num bold">{{ formatBytes(t.totalBytes) }}</td>
              <td class="num"><span class="badge active">活跃</span></td>
            </tr>
            <template v-if="expandedTenants.has(t.tenantId)">
              <tr v-for="item in t.items" :key="item.id" class="item-row">
                <td></td>
                <td class="item-name">{{ itemIcon(item.type) }}{{ item.name }} <span class="item-type">({{ itemLabel(item.type) }})</span></td>
                <td class="num sub blue">{{ item.dbBytes ? formatBytes(item.dbBytes) : '—' }}</td>
                <td class="num sub green">{{ item.kbDocBytes ? formatBytes(item.kbDocBytes) : '—' }}</td>
                <td class="num sub purple">{{ item.memoryBytes ? formatBytes(item.memoryBytes) : '—' }}</td>
                <td class="num sub">{{ formatBytes(item.dbBytes + item.kbDocBytes + item.memoryBytes) }}</td>
                <td class="num sub"><span class="badge-sm">{{ item.status }}</span></td>
              </tr>
            </template>
          </template>

          <!-- Orphans -->
          <tr v-for="o in data.orphans" :key="o.tenantId" class="orphan-row">
            <td>—</td>
            <td class="name-col orphan-name">{{ o.tenantId }} <span class="deleted-tag">已删除</span></td>
            <td class="num red" colspan="3">{{ formatBytes(o.bytes) }}</td>
            <td class="num bold red">{{ formatBytes(o.bytes) }}</td>
            <td class="num">
              <button class="btn-cleanup" @click.stop="cleanupOrphan(o.tenantId)"
                      :disabled="cleaningUp === o.tenantId">
                {{ cleaningUp === o.tenantId ? '清理中...' : '清理' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="data.lastScanTime" class="scan-time">
        最后 OBS 扫描：{{ data.lastScanTime }}
      </div>
      <div v-else class="scan-time">
        数据来源：数据库 logical_size + 文档 obsSize 汇总（点击「刷新 OBS 扫描」获取精确数据和孤儿检测）
      </div>
    </div>
  </div>
</template>

<style scoped>
.storage-panel { padding: 0; }

.summary-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 24px;
}
.summary-card {
  background: var(--card-bg, #1e1e38);
  border-radius: 8px;
  padding: 14px;
}
.card-label { color: var(--text-muted, #888); font-size: 11px; margin-bottom: 4px; }
.card-value { font-size: 22px; font-weight: 700; }
.card-value.primary { color: var(--accent, #c9a96e); }
.card-value.blue { color: #7eb8da; }
.card-value.green { color: #a8d5a2; }
.card-value.red { color: #e07070; }

.storage-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.toolbar-title { font-size: 14px; font-weight: 600; }
.toolbar-actions { display: flex; gap: 8px; }

.btn-scan {
  background: var(--card-bg, #2a2a4a);
  color: var(--text-muted, #aaa);
  border: 1px solid var(--border, #444);
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 12px;
  cursor: pointer;
}
.btn-scan:disabled { opacity: 0.5; cursor: not-allowed; }

.storage-table-wrap {
  border: 1px solid var(--border, #333);
  border-radius: 8px;
  overflow: hidden;
}
.storage-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.storage-table thead tr { background: var(--card-bg, #1e1e38); }
.storage-table th {
  text-align: left;
  padding: 10px 14px;
  color: var(--text-muted, #888);
  font-weight: 500;
}
.storage-table th.num, .storage-table td.num { text-align: right; }

.tenant-row { cursor: pointer; border-top: 1px solid var(--border-subtle, #2a2a4a); }
.tenant-row:hover { background: var(--hover-bg, #1a1a32); }
.tenant-row td { padding: 10px 14px; }
.expand-col { color: var(--accent, #c9a96e); width: 32px; }
.name-col { font-weight: 600; }

.item-row { background: var(--bg-deep, #141428); }
.item-row td { padding: 6px 14px; }
.item-name { padding-left: 36px !important; color: var(--text-muted, #888); font-size: 11px; }
.item-type { color: var(--text-muted, #666); }
.sub { font-size: 11px; color: var(--text-muted, #999); }

.blue { color: #7eb8da; }
.green { color: #a8d5a2; }
.purple { color: #d4a8d5; }
.red { color: #e07070; }
.bold { font-weight: 600; }

.badge { background: #1a3a1a; color: #6d6; padding: 2px 8px; border-radius: 10px; font-size: 11px; }
.badge.active { background: #1a3a1a; color: #6d6; }
.badge-sm { color: var(--text-muted, #888); font-size: 11px; }

.orphan-row { background: #1a1416; border-top: 1px solid var(--border-subtle, #2a2a4a); }
.orphan-row td { padding: 10px 14px; }
.orphan-name { color: #e07070; font-weight: 600; }
.deleted-tag { font-size: 10px; color: var(--text-muted, #888); font-weight: 400; }

.btn-cleanup {
  background: #5a2020;
  color: #e07070;
  border: 1px solid #733;
  border-radius: 4px;
  padding: 3px 10px;
  font-size: 11px;
  cursor: pointer;
}
.btn-cleanup:disabled { opacity: 0.5; cursor: not-allowed; }

.loading-msg { text-align: center; padding: 40px; color: var(--text-muted, #888); }
.scan-time { margin-top: 12px; color: var(--text-muted, #666); font-size: 11px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-admin/src/views/system/StoragePanel.vue
git commit -m "feat(admin): add StoragePanel component for storage tab"
```

---

### Task 6: Frontend — Wire StoragePanel into InfraMonitor

**Files:**
- Modify: `lakeon-admin/src/views/system/InfraMonitor.vue`

- [ ] **Step 1: Add storage tab button**

In `InfraMonitor.vue`, find the tab buttons (around line 8-13):

```vue
<div class="infra-tabs">
  <button class="infra-tab" :class="{ active: activeTab === 'control' }" @click="activeTab = 'control'">管控面</button>
  <button class="infra-tab" :class="{ active: activeTab === 'neon' }" @click="activeTab = 'neon'">Neon 数据层</button>
  <button class="infra-tab" :class="{ active: activeTab === 'cce' }" @click="activeTab = 'cce'">CCE 弹性节点池</button>
  <button class="infra-tab" :class="{ active: activeTab === 'cci' }" @click="activeTab = 'cci'">CCI Pod</button>
</div>
```

Add the storage tab button:

```vue
<div class="infra-tabs">
  <button class="infra-tab" :class="{ active: activeTab === 'control' }" @click="activeTab = 'control'">管控面</button>
  <button class="infra-tab" :class="{ active: activeTab === 'neon' }" @click="activeTab = 'neon'">Neon 数据层</button>
  <button class="infra-tab" :class="{ active: activeTab === 'cce' }" @click="activeTab = 'cce'">CCE 弹性节点池</button>
  <button class="infra-tab" :class="{ active: activeTab === 'cci' }" @click="activeTab = 'cci'">CCI Pod</button>
  <button class="infra-tab" :class="{ active: activeTab === 'storage' }" @click="activeTab = 'storage'">存储</button>
</div>
```

- [ ] **Step 2: Add lazy-loaded StoragePanel import and v-if block**

At the top of `<script setup>`, add:

```typescript
import { defineAsyncComponent } from 'vue'
const StoragePanel = defineAsyncComponent(() => import('./StoragePanel.vue'))
```

Then find the last tab content block (the `<div v-if="activeTab === 'cci'">` section) and add after it:

```vue
    <!-- Storage Tab -->
    <div v-if="activeTab === 'storage'" style="padding: 20px;">
      <StoragePanel />
    </div>
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-admin/src/views/system/InfraMonitor.vue
git commit -m "feat(admin): wire StoragePanel into InfraMonitor as 5th tab"
```

---

### Task 7: Build, deploy, and verify

**Files:** (no code changes)

- [ ] **Step 1: Type-check frontend**

```bash
cd lakeon-admin && npx vue-tsc -b --noEmit
```

Expected: no errors.

- [ ] **Step 2: Build and push API**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
```

- [ ] **Step 3: Build and push admin**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-admin.sh
```

- [ ] **Step 4: Restart API deployment**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-api -n lakeon --timeout=120s
```

- [ ] **Step 5: Verify API endpoint**

```bash
curl -s https://api.dbay.cloud:8443/api/v1/admin/storage/summary \
  -H "Authorization: Bearer lakeon-sre-2026" | python3 -m json.tool | head -20
```

Expected: JSON with `totalObsBytes`, `totalDbBytes`, `tenants` array.

- [ ] **Step 6: Verify in browser**

Open the SRE admin console, navigate to 基础设施 → 存储 tab. Verify:
- Summary cards display
- Tenant table loads with data
- Clicking a tenant row expands to show items
- "刷新 OBS 扫描" button works

- [ ] **Step 7: Commit version bump if needed**

```bash
git add -A
git commit -m "chore: deploy obs storage management v0.9.215"
```
