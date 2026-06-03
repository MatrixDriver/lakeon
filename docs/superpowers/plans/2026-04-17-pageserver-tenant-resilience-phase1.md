# Pageserver Tenant 韧性 Phase 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 L2 tenant reconcile 定时任务 + L3 SRE 控制台 tenant 健康监控 + 清理 Error Pod

**Architecture:** lakeon-api 新增 `TenantReconcileService`，每 60s 扫全量 `database_instances`，对比 pageserver attached tenant 列表，缺失的自动 re-attach。新增 admin API `/pageserver/tenant-health` 返回健康状态。lakeon-admin InfraMonitor 的 Neon 数据层 tab 新增 Tenant 挂靠状态卡片 + 一键修复按钮。

**Tech Stack:** Spring Boot `@Scheduled` + NeonApiClient + Vue 3 + lakeon-admin

**设计文档:** `docs/superpowers/specs/2026-04-17-pageserver-tenant-resilience-design.md`

---

### Task 1: NeonApiClient 新增 listTenants 方法

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java`

NeonApiClient 当前没有 listTenants 方法。reconcile 和 SRE 页面都需要它来获取 pageserver 上 attached 的全量 tenant 列表。

- [ ] **Step 1: 在 NeonApiClient 中添加 listTenants 方法**

在 `getStatus()` 方法（行 313）之前添加：

```java
/**
 * List all tenants currently attached on pageserver.
 * GET /v1/tenant
 */
public List<Map<String, Object>> listTenants() {
    try {
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/tenant"))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new NeonApiException("Failed to list tenants: HTTP " + response.statusCode(), response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    } catch (NeonApiException e) {
        throw e;
    } catch (Exception e) {
        throw new NeonApiException("Failed to list tenants: " + e.getMessage(), e);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java
git commit -m "feat(api): add NeonApiClient.listTenants for pageserver tenant enumeration"
```

---

### Task 2: DatabaseRepository 新增查询方法

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/repository/DatabaseRepository.java`

reconcile 需要查询所有未删除且有 neonTenantId 的 DB。

- [ ] **Step 1: 添加 repository 方法**

在 `DatabaseRepository.java` 接口末尾（`findByIdAndTenantIdForUpdate` 之后）添加：

```java
@Query("SELECT d FROM DatabaseEntity d WHERE d.deletedAt IS NULL AND d.neonTenantId IS NOT NULL")
List<DatabaseEntity> findAllActiveWithNeonTenant();
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/repository/DatabaseRepository.java
git commit -m "feat(api): add DatabaseRepository.findAllActiveWithNeonTenant"
```

---

### Task 3: TenantReconcileService 定时任务

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/TenantReconcileService.java`

核心逻辑：每 60s 获取 pageserver tenant 列表，与控制面对比，缺失的自动 PUT location_config re-attach。

- [ ] **Step 1: 创建 TenantReconcileService**

```java
package com.lakeon.service;

import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.exception.NeonApiException;
import com.lakeon.repository.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Component
public class TenantReconcileService {
    private static final Logger log = LoggerFactory.getLogger(TenantReconcileService.class);

    private final DatabaseRepository databaseRepository;
    private final NeonApiClient neonApiClient;
    private final ReentrantLock lock = new ReentrantLock();

    // Expose last reconcile result for SRE API
    private final AtomicReference<ReconcileResult> lastResult = new AtomicReference<>();

    public record ReconcileResult(
        Instant timestamp,
        int expectedCount,
        int attachedCount,
        int missingCount,
        int reattachedCount,
        int failedCount,
        java.util.List<String> missingDatabases,
        java.util.List<String> reattachedDatabases,
        java.util.List<String> failedDatabases
    ) {}

    public TenantReconcileService(DatabaseRepository databaseRepository, NeonApiClient neonApiClient) {
        this.databaseRepository = databaseRepository;
        this.neonApiClient = neonApiClient;
    }

    @Scheduled(fixedDelayString = "${lakeon.tenant.reconcile-interval-ms:60000}", initialDelay = 30000)
    public void reconcile() {
        if (!lock.tryLock()) {
            log.debug("Tenant reconcile already running, skipping");
            return;
        }
        try {
            doReconcile(false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Run reconcile. If repair=true, also re-attach missing tenants.
     * Called by scheduled task (repair=false triggers re-attach too) and SRE manual trigger.
     */
    public ReconcileResult doReconcile(boolean manualTrigger) {
        var dbs = databaseRepository.findAllActiveWithNeonTenant();
        Set<String> expected = dbs.stream()
            .map(db -> db.getNeonTenantId())
            .collect(Collectors.toSet());

        Set<String> attached;
        try {
            var tenants = neonApiClient.listTenants();
            attached = tenants.stream()
                .map(t -> (String) t.get("id"))
                .collect(Collectors.toSet());
        } catch (NeonApiException e) {
            log.warn("Tenant reconcile: failed to list pageserver tenants: {}", e.getMessage());
            return null;
        }

        var missing = expected.stream()
            .filter(id -> !attached.contains(id))
            .collect(Collectors.toSet());

        var reattached = new java.util.ArrayList<String>();
        var failed = new java.util.ArrayList<String>();

        if (!missing.isEmpty()) {
            log.warn("Tenant reconcile: {} missing from pageserver out of {} expected",
                missing.size(), expected.size());

            for (String tenantId : missing) {
                String dbName = dbs.stream()
                    .filter(db -> tenantId.equals(db.getNeonTenantId()))
                    .map(db -> db.getName())
                    .findFirst().orElse("?");
                try {
                    neonApiClient.createTenant(tenantId);
                    reattached.add(dbName + " (" + tenantId.substring(0, 8) + ")");
                    log.info("Tenant reconcile: re-attached {} ({})", dbName, tenantId);
                } catch (Exception e) {
                    failed.add(dbName + " (" + tenantId.substring(0, 8) + ")");
                    log.error("Tenant reconcile: failed to re-attach {} ({}): {}",
                        dbName, tenantId, e.getMessage());
                }
            }
        } else if (manualTrigger) {
            log.info("Tenant reconcile: all {} tenants attached", expected.size());
        }

        var missingNames = missing.stream().map(tid ->
            dbs.stream().filter(db -> tid.equals(db.getNeonTenantId()))
                .map(db -> db.getName()).findFirst().orElse(tid.substring(0, 8))
        ).collect(Collectors.toList());

        var result = new ReconcileResult(
            Instant.now(),
            expected.size(),
            attached.size(),
            missing.size(),
            reattached.size(),
            failed.size(),
            missingNames,
            reattached,
            failed
        );
        lastResult.set(result);
        return result;
    }

    public ReconcileResult getLastResult() {
        return lastResult.get();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/TenantReconcileService.java
git commit -m "feat(api): add TenantReconcileService — L2 tenant reconcile every 60s"
```

---

### Task 4: Admin API 端点 — tenant 健康 + 手动 reconcile

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`

SRE 控制台需要两个端点：(1) 查看 tenant 健康状态，(2) 手动触发 reconcile。

- [ ] **Step 1: 在 AdminController 注入 TenantReconcileService**

在 AdminController 构造函数的参数中添加 `TenantReconcileService tenantReconcileService`，并保存为字段。

- [ ] **Step 2: 添加 tenant-health 和 reconcile 端点**

在 `getPageserverMetrics()` 方法（行 631-634）之后添加：

```java
@GetMapping("/pageserver/tenant-health")
public Map<String, Object> getTenantHealth() {
    var result = tenantReconcileService.getLastResult();
    var dbs = databaseRepository.findAllActiveWithNeonTenant();
    Set<String> expected = dbs.stream()
        .map(db -> db.getNeonTenantId())
        .collect(java.util.stream.Collectors.toSet());

    // Live check against pageserver
    Set<String> attached = Set.of();
    boolean pageserverReachable = true;
    try {
        var tenants = neonApiClient.listTenants();
        attached = tenants.stream()
            .map(t -> (String) t.get("id"))
            .collect(java.util.stream.Collectors.toSet());
    } catch (Exception e) {
        pageserverReachable = false;
    }

    int missingCount = 0;
    var missingList = new java.util.ArrayList<Map<String, String>>();
    if (pageserverReachable) {
        for (var db : dbs) {
            if (!attached.contains(db.getNeonTenantId())) {
                missingCount++;
                missingList.add(Map.of(
                    "db_name", db.getName(),
                    "db_id", db.getId(),
                    "neon_tenant_id", db.getNeonTenantId(),
                    "status", db.getStatus().name()
                ));
            }
        }
    }

    String health = !pageserverReachable ? "UNREACHABLE"
        : missingCount == 0 ? "HEALTHY"
        : "DEGRADED";

    var response = new java.util.LinkedHashMap<String, Object>();
    response.put("health", health);
    response.put("expected_tenants", expected.size());
    response.put("attached_tenants", attached.size());
    response.put("missing_count", missingCount);
    response.put("missing", missingList);
    response.put("pageserver_reachable", pageserverReachable);
    if (result != null) {
        response.put("last_reconcile", Map.of(
            "timestamp", result.timestamp().toString(),
            "reattached", result.reattachedCount(),
            "failed", result.failedCount()
        ));
    }
    return response;
}

@PostMapping("/pageserver/tenant-reconcile")
public Map<String, Object> triggerReconcile() {
    var result = tenantReconcileService.doReconcile(true);
    if (result == null) {
        return Map.of("success", false, "error", "Pageserver unreachable");
    }
    return Map.of(
        "success", true,
        "expected", result.expectedCount(),
        "attached", result.attachedCount(),
        "missing", result.missingCount(),
        "reattached", result.reattachedCount(),
        "failed", result.failedCount(),
        "reattached_databases", result.reattachedDatabases(),
        "failed_databases", result.failedDatabases()
    );
}
```

注意：AdminController 中需要注入 `DatabaseRepository` 和 `NeonApiClient`（如果还没注入的话）。检查构造函数，缺什么加什么。

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/AdminController.java
git commit -m "feat(api): add /pageserver/tenant-health and /pageserver/tenant-reconcile admin endpoints"
```

---

### Task 5: lakeon-admin API 客户端新增方法

**Files:**
- Modify: `lakeon-admin/src/api/admin.ts`

- [ ] **Step 1: 在 adminApi 对象中添加方法**

在 `pageserverMetrics` 行之后添加：

```typescript
tenantHealth: () => client.get('/pageserver/tenant-health'),
triggerReconcile: () => client.post('/pageserver/tenant-reconcile'),
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-admin/src/api/admin.ts
git commit -m "feat(admin): add tenantHealth and triggerReconcile API methods"
```

---

### Task 6: InfraMonitor Neon 数据层 tab 新增 Tenant 健康卡片

**Files:**
- Modify: `lakeon-admin/src/views/system/InfraMonitor.vue`

在现有 Neon 数据层 tab 中，在 Neon Pressure Overview stats-row 之后、Neon 组件 Pod 表格之前，插入 tenant 挂靠健康卡片。

- [ ] **Step 1: 添加数据状态**

在 `<script setup>` 中（约行 656，`psMetrics` 附近）添加：

```typescript
interface TenantHealthData {
  health: 'HEALTHY' | 'DEGRADED' | 'UNREACHABLE'
  expected_tenants: number
  attached_tenants: number
  missing_count: number
  missing: { db_name: string; db_id: string; neon_tenant_id: string; status: string }[]
  pageserver_reachable: boolean
  last_reconcile?: { timestamp: string; reattached: number; failed: number }
}

const tenantHealth = ref<TenantHealthData | null>(null)
const tenantHealthLoading = ref(false)
const reconcileLoading = ref(false)

async function loadTenantHealth() {
  tenantHealthLoading.value = true
  try {
    const res = await adminApi.tenantHealth()
    tenantHealth.value = res.data
  } catch (e) { console.error('Failed to load tenant health', e) }
  finally { tenantHealthLoading.value = false }
}

async function triggerReconcile() {
  if (!confirm('确定手动触发 tenant reconcile？\n将检查并修复 pageserver 上缺失的 tenant 挂靠。')) return
  reconcileLoading.value = true
  try {
    const res = await adminApi.triggerReconcile()
    const d = res.data
    if (d.success) {
      const msg = d.reattached > 0
        ? `修复完成：重新挂靠 ${d.reattached} 个 tenant` + (d.failed > 0 ? `，失败 ${d.failed} 个` : '')
        : `检查完成：所有 ${d.expected} 个 tenant 均已挂靠`
      alert(msg)
    } else {
      alert('Reconcile 失败: ' + d.error)
    }
    await loadTenantHealth()
  } catch (e) {
    alert('请求失败')
    console.error(e)
  } finally {
    reconcileLoading.value = false
  }
}
```

- [ ] **Step 2: 在 onMounted 和 pollData 中加载 tenantHealth**

在现有的 `onMounted` 中，找到 `loadComputeSummary()` 等加载函数调用的位置，添加 `loadTenantHealth()`。在 `pollData()` 中也添加 `loadTenantHealth()` 到 `Promise.allSettled` 调用中。

- [ ] **Step 3: 在 Neon 数据层 tab 中添加 UI**

在 `<div v-if="activeTab === 'neon'">` 里，Neon Pressure Overview `</div>`（行 323）之后、`<div class="section-card">` Neon 组件 Pod（行 325）之前，插入：

```html
<!-- Tenant 挂靠健康 -->
<div class="section-card" style="margin-bottom: 16px;">
  <div class="section-header">
    <h3>Tenant 挂靠状态</h3>
    <button class="action-btn" :disabled="reconcileLoading" @click="triggerReconcile">
      {{ reconcileLoading ? '修复中...' : '检查并修复' }}
    </button>
  </div>
  <div v-if="tenantHealthLoading" class="empty-text">加载中...</div>
  <template v-else-if="tenantHealth">
    <div class="stats-row" style="margin-bottom: 12px;">
      <div class="stat-card">
        <div class="stat-value" :style="{ color: tenantHealth.health === 'HEALTHY' ? 'var(--cs-normal)' : tenantHealth.health === 'DEGRADED' ? 'var(--cs-severe)' : '#94a3b8' }">
          {{ tenantHealth.health === 'HEALTHY' ? '正常' : tenantHealth.health === 'DEGRADED' ? '异常' : '不可达' }}
        </div>
        <div class="stat-label">健康状态</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ tenantHealth.expected_tenants }}</div>
        <div class="stat-label">控制面 DB 数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ tenantHealth.attached_tenants }}</div>
        <div class="stat-label">已挂靠</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" :style="{ color: tenantHealth.missing_count > 0 ? 'var(--cs-severe)' : 'var(--cs-normal)' }">
          {{ tenantHealth.missing_count }}
        </div>
        <div class="stat-label">缺失</div>
      </div>
    </div>
    <div v-if="tenantHealth.missing.length > 0">
      <div class="table-wrapper">
        <table class="data-table">
          <thead><tr><th>数据库</th><th>Neon Tenant ID</th><th>DB 状态</th></tr></thead>
          <tbody>
            <tr v-for="m in tenantHealth.missing" :key="m.db_id">
              <td>{{ m.db_name }}</td>
              <td class="pod-name">{{ m.neon_tenant_id.substring(0, 16) }}...</td>
              <td><span class="phase-badge" :class="dbStatusBadge(m.status.toLowerCase())">{{ m.status }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-if="tenantHealth.last_reconcile" style="margin-top: 8px; font-size: 12px; color: #94a3b8;">
      上次 reconcile: {{ new Date(tenantHealth.last_reconcile.timestamp).toLocaleString() }}
      <template v-if="tenantHealth.last_reconcile.reattached > 0">
        &mdash; 修复 {{ tenantHealth.last_reconcile.reattached }} 个
      </template>
    </div>
  </template>
</div>
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-admin/src/views/system/InfraMonitor.vue
git commit -m "feat(admin): add tenant health card to Neon tab in InfraMonitor"
```

---

### Task 7: 清理 Error Pod + 构建部署

**Files:** 无代码改动，运维操作。

- [ ] **Step 1: 删除 23 天 Error 的 pageserver Pod**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl delete pod pageserver-8df9d695d-nqlmq -n lakeon
```

预期：Pod 被删除，ReplicaSet 不会重建（因为 h2xjw 已经占了 1 个 replica 名额）。

- [ ] **Step 2: 构建并推送 lakeon-api**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
```

- [ ] **Step 3: 重启 lakeon-api**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
```

- [ ] **Step 4: 构建并推送 lakeon-admin**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-admin.sh
```

- [ ] **Step 5: 验证 reconcile 日志**

等 API 启动 30s 后（initialDelay），查看日志中是否出现 reconcile 扫描：

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl logs -n lakeon -l app=lakeon-api --tail=50 | grep -i "reconcile"
```

预期：看到 `Tenant reconcile: all N tenants attached`（因为之前已手动修复）。

- [ ] **Step 6: 在 SRE 控制台验证**

打开 lakeon-admin → 基础设施 → Neon 数据层 tab，确认 Tenant 挂靠状态卡片显示 "正常"，缺失数为 0，点击"检查并修复"按钮能正常触发。

- [ ] **Step 7: Commit（如果有遗漏的代码变更）**

```bash
git add -A
git commit -m "ops: cleanup Error pageserver pod, deploy L2 reconcile + L3 SRE monitoring"
```
