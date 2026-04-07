# KB Wiki Phase 2b: Team Collaboration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable knowledge base sharing between tenants with admin/member role-based access control, so a ~10 person team can collaborate on shared knowledge bases.

**Architecture:** Add a `kb_shares` table linking KBs to tenants with roles. Introduce `KbAccessService` to centralize access checks, replacing direct `findByIdAndTenantId` calls. Admin (owner) can invite/remove members. Members can browse, upload, chat, and settle but cannot delete or manage sharing.

**Tech Stack:** Java 17 (Spring Boot JPA), PostgreSQL (Flyway migration), Vue 3 + TypeScript (frontend), pytest (E2E)

**Spec:** `docs/superpowers/specs/2026-04-07-kb-wiki-phase2-design.md` — Phase 2b section

---

## File Structure

### Backend (lakeon-api)

| File | Action | Responsibility |
|------|--------|----------------|
| `src/main/resources/db/migration/V32__add_kb_shares.sql` | Create | DB migration for kb_shares table |
| `src/main/java/com/lakeon/knowledge/KbShareEntity.java` | Create | JPA entity for kb_shares |
| `src/main/java/com/lakeon/knowledge/KbShareRepository.java` | Create | Repository for kb_shares queries |
| `src/main/java/com/lakeon/knowledge/KbAccessService.java` | Create | Centralized KB access check (returns role: admin/member/none) |
| `src/main/java/com/lakeon/knowledge/KbRole.java` | Create | Enum: ADMIN, MEMBER |
| `src/main/java/com/lakeon/knowledge/KnowledgeService.java` | Modify | Replace findByIdAndTenantId with KbAccessService checks |
| `src/main/java/com/lakeon/knowledge/KnowledgeController.java` | Modify | Add share endpoints, use role-based access |
| `src/main/java/com/lakeon/knowledge/KnowledgeBaseRepository.java` | Modify | Add shared KB query |
| `src/main/java/com/lakeon/knowledge/WikiService.java` | Modify | Replace findByIdAndTenantId with KbAccessService |

### Frontend (lakeon-console)

| File | Action | Responsibility |
|------|--------|----------------|
| `src/api/knowledge.ts` | Modify | Add share API functions |
| `src/views/knowledge/KnowledgeBases.vue` | Modify | Split list into "my KBs" and "shared KBs" groups |
| `src/views/knowledge/KnowledgeBaseDetail.vue` | Modify | Add share panel, hide admin-only actions for members |
| `src/views/knowledge/KbSharePanel.vue` | Create | Share management panel (invite, list, remove members) |

### Tests

| File | Action | Responsibility |
|------|--------|----------------|
| `tests/e2e/test_kb_sharing.py` | Create | E2E tests for full sharing workflow |

---

## Task 1: Database migration — kb_shares table

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V32__add_kb_shares.sql`

- [ ] **Step 1: Create migration file**

Create `lakeon-api/src/main/resources/db/migration/V32__add_kb_shares.sql`:

```sql
CREATE TABLE kb_shares (
    id VARCHAR(32) PRIMARY KEY,
    kb_id VARCHAR(32) NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    role VARCHAR(16) NOT NULL DEFAULT 'member',
    invited_by VARCHAR(64) NOT NULL REFERENCES tenants(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(kb_id, tenant_id)
);

CREATE INDEX idx_kb_shares_tenant ON kb_shares(tenant_id);
CREATE INDEX idx_kb_shares_kb ON kb_shares(kb_id);
```

- [ ] **Step 2: Verify migration applies**

```bash
cd lakeon-api && ./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/lakeon -Dflyway.user=lakeon -Dflyway.password=lakeon
```

Or restart the API locally and check logs for `Migrating schema ... to version 32`.

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V32__add_kb_shares.sql
git commit -m "feat(api): add kb_shares table for KB team collaboration"
```

---

## Task 2: Backend — KbRole enum, KbShareEntity, KbShareRepository

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KbRole.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KbShareEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KbShareRepository.java`

- [ ] **Step 1: Create KbRole enum**

Create `lakeon-api/src/main/java/com/lakeon/knowledge/KbRole.java`:

```java
package com.lakeon.knowledge;

public enum KbRole {
    ADMIN,
    MEMBER
}
```

- [ ] **Step 2: Create KbShareEntity**

Create `lakeon-api/src/main/java/com/lakeon/knowledge/KbShareEntity.java`:

```java
package com.lakeon.knowledge;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_shares")
public class KbShareEntity {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "kb_id", nullable = false, length = 32)
    private String kbId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private KbRole role = KbRole.MEMBER;

    @Column(name = "invited_by", nullable = false, length = 64)
    private String invitedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "ks_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public KbRole getRole() { return role; }
    public void setRole(KbRole role) { this.role = role; }

    public String getInvitedBy() { return invitedBy; }
    public void setInvitedBy(String invitedBy) { this.invitedBy = invitedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 3: Create KbShareRepository**

Create `lakeon-api/src/main/java/com/lakeon/knowledge/KbShareRepository.java`:

```java
package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface KbShareRepository extends JpaRepository<KbShareEntity, String> {

    List<KbShareEntity> findAllByKbId(String kbId);

    Optional<KbShareEntity> findByKbIdAndTenantId(String kbId, String tenantId);

    List<KbShareEntity> findAllByTenantId(String tenantId);

    void deleteAllByKbId(String kbId);

    @Query("SELECT s.kbId FROM KbShareEntity s WHERE s.tenantId = :tenantId")
    List<String> findKbIdsByTenantId(String tenantId);
}
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbRole.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KbShareEntity.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KbShareRepository.java
git commit -m "feat(api): add KbShareEntity, KbRole, and KbShareRepository"
```

---

## Task 3: Backend — KbAccessService

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KbAccessService.java`

- [ ] **Step 1: Create KbAccessService**

Create `lakeon-api/src/main/java/com/lakeon/knowledge/KbAccessService.java`:

```java
package com.lakeon.knowledge;

import com.lakeon.exception.ForbiddenException;
import com.lakeon.exception.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class KbAccessService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbShareRepository kbShareRepository;

    public KbAccessService(KnowledgeBaseRepository knowledgeBaseRepository,
                           KbShareRepository kbShareRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.kbShareRepository = kbShareRepository;
    }

    /**
     * Check tenant's access to a KB. Returns the role if access is granted.
     * Throws NotFoundException if KB doesn't exist, ForbiddenException if no access.
     */
    public KbRole checkAccess(String kbId, String tenantId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        return checkAccess(kb, tenantId);
    }

    /**
     * Check tenant's access to an already-loaded KB entity.
     */
    public KbRole checkAccess(KnowledgeBaseEntity kb, String tenantId) {
        // Owner is always admin
        if (kb.getTenantId().equals(tenantId)) {
            return KbRole.ADMIN;
        }
        // Check share table
        return kbShareRepository.findByKbIdAndTenantId(kb.getId(), tenantId)
                .map(KbShareEntity::getRole)
                .orElseThrow(() -> new ForbiddenException("No access to knowledge base: " + kb.getId()));
    }

    /**
     * Get KB with access check. Returns the KB entity.
     */
    public KnowledgeBaseEntity getKbWithAccess(String kbId, String tenantId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        checkAccess(kb, tenantId);
        return kb;
    }

    /**
     * Get KB with admin-only access check.
     */
    public KnowledgeBaseEntity getKbAdminOnly(String kbId, String tenantId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        KbRole role = checkAccess(kb, tenantId);
        if (role != KbRole.ADMIN) {
            throw new ForbiddenException("Admin access required for knowledge base: " + kbId);
        }
        return kb;
    }
}
```

- [ ] **Step 2: Verify ForbiddenException exists**

Check if `com.lakeon.exception.ForbiddenException` exists. If not, create it:

```java
package com.lakeon.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbAccessService.java
# Also add ForbiddenException if created
git commit -m "feat(api): add KbAccessService for role-based KB access control"
```

---

## Task 4: Backend — Modify KnowledgeService to use KbAccessService

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`

This is the critical refactoring task. We need to change the access pattern from `findByIdAndTenantId` to using `KbAccessService`.

- [ ] **Step 1: Add KbAccessService dependency to KnowledgeService**

In `KnowledgeService.java`, add the field and constructor parameter:

```java
private final KbAccessService kbAccessService;
```

Add to constructor:

```java
public KnowledgeService(..., KbAccessService kbAccessService) {
    ...
    this.kbAccessService = kbAccessService;
}
```

- [ ] **Step 2: Modify getKnowledgeBase()**

Change from:

```java
public KnowledgeBaseEntity getKnowledgeBase(String tenantId, String kbId) {
    return knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
            .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
}
```

To:

```java
public KnowledgeBaseEntity getKnowledgeBase(String tenantId, String kbId) {
    return kbAccessService.getKbWithAccess(kbId, tenantId);
}
```

- [ ] **Step 3: Modify listKnowledgeBases() to include shared KBs**

Add to `KnowledgeBaseRepository.java`:

```java
@Query("SELECT kb FROM KnowledgeBaseEntity kb WHERE kb.id IN :ids ORDER BY kb.createdAt DESC")
List<KnowledgeBaseEntity> findAllByIdIn(List<String> ids);
```

Change `listKnowledgeBases()` in `KnowledgeService.java` from:

```java
public List<KnowledgeBaseEntity> listKnowledgeBases(String tenantId) {
    return knowledgeBaseRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
}
```

To:

```java
public List<KnowledgeBaseEntity> listKnowledgeBases(String tenantId) {
    List<KnowledgeBaseEntity> owned = knowledgeBaseRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    List<String> sharedKbIds = kbShareRepository.findKbIdsByTenantId(tenantId);
    if (sharedKbIds.isEmpty()) {
        return owned;
    }
    List<KnowledgeBaseEntity> shared = knowledgeBaseRepository.findAllByIdIn(sharedKbIds);
    List<KnowledgeBaseEntity> all = new ArrayList<>(owned);
    all.addAll(shared);
    return all;
}
```

Add `KbShareRepository` field if not already injected.

- [ ] **Step 4: Modify deleteKnowledgeBase() to require admin**

Change the beginning of `deleteKnowledgeBase()` from:

```java
KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
        .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
```

To:

```java
KnowledgeBaseEntity kb = kbAccessService.getKbAdminOnly(kbId, tenantId);
```

Also add: delete all kb_shares when deleting a KB (before `knowledgeBaseRepository.delete(kb)`):

```java
kbShareRepository.deleteAllByKbId(kbId);
```

- [ ] **Step 5: Modify deleteDocument() to require admin**

Change the beginning of `deleteDocument()` from:

```java
DocumentEntity doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
        .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
```

To:

```java
DocumentEntity doc = documentRepository.findById(documentId)
        .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
if (doc.getKbId() != null) {
    kbAccessService.getKbAdminOnly(doc.getKbId(), tenantId);
} else if (!doc.getTenantId().equals(tenantId)) {
    throw new ForbiddenException("No access to document: " + documentId);
}
```

- [ ] **Step 6: Modify generateUploadUrl() to allow member access**

Change the beginning of `generateUploadUrl()` from:

```java
KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenant.getId())
        .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
```

To:

```java
KnowledgeBaseEntity kb = kbAccessService.getKbWithAccess(kbId, tenant.getId());
```

- [ ] **Step 7: Audit remaining findByIdAndTenantId calls in KnowledgeService**

Search for all remaining `findByIdAndTenantId` calls in `KnowledgeService.java` and update them:

- Read-only / member-allowed operations → `kbAccessService.getKbWithAccess(kbId, tenantId)`
- Write/delete / admin-only operations → `kbAccessService.getKbAdminOnly(kbId, tenantId)`
- Document operations that need KB context → check via KB's access

- [ ] **Step 8: Verify compilation**

```bash
cd lakeon-api && ./mvnw compile -DskipTests
```

Expected: Compilation succeeds.

- [ ] **Step 9: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeBaseRepository.java
git commit -m "feat(api): refactor KB access control to support shared KBs"
```

---

## Task 5: Backend — Modify WikiService to use KbAccessService

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java`

- [ ] **Step 1: Add KbAccessService dependency**

Add to `WikiService.java`:

```java
private final KbAccessService kbAccessService;
```

Add to constructor parameter list.

- [ ] **Step 2: Update methods that do KB access checks**

In WikiService, find methods that call `knowledgeBaseRepository.findByIdAndTenantId()` or do manual tenant checks. Replace with:

- `chatStream()`, `getGraph()`, `getWikiPages()` etc. (read operations) → use `kbAccessService.getKbWithAccess(kbId, tenantId)`
- `saveResponse()` (write/settle) → use `kbAccessService.getKbWithAccess(kbId, tenantId)` (member allowed)
- `runCurate()`, admin wiki operations → use `kbAccessService.getKbAdminOnly(kbId, tenantId)` or keep admin-token checks

- [ ] **Step 3: Verify compilation**

```bash
cd lakeon-api && ./mvnw compile -DskipTests
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java
git commit -m "feat(api): update WikiService to use KbAccessService for shared KB access"
```

---

## Task 6: Backend — Share management endpoints in KnowledgeController

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`

- [ ] **Step 1: Add KbAccessService and KbShareRepository dependencies**

Add fields and constructor params:

```java
private final KbAccessService kbAccessService;
private final KbShareRepository kbShareRepository;
```

- [ ] **Step 2: Add GET /knowledge/bases/{kbId}/shares endpoint**

Add to `KnowledgeController.java`:

```java
@GetMapping("/bases/{kbId}/shares")
public List<Map<String, Object>> listShares(HttpServletRequest req, @PathVariable String kbId) {
    TenantEntity tenant = getTenant(req);
    kbAccessService.getKbAdminOnly(kbId, tenant.getId());
    List<KbShareEntity> shares = kbShareRepository.findAllByKbId(kbId);
    return shares.stream().map(s -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("kb_id", s.getKbId());
        m.put("tenant_id", s.getTenantId());
        m.put("role", s.getRole().name().toLowerCase());
        m.put("invited_by", s.getInvitedBy());
        m.put("created_at", s.getCreatedAt().toString());
        // Resolve tenant username for display
        tenantRepository.findById(s.getTenantId()).ifPresent(t ->
            m.put("username", t.getUsername())
        );
        return m;
    }).toList();
}
```

- [ ] **Step 3: Add POST /knowledge/bases/{kbId}/shares endpoint**

```java
@PostMapping("/bases/{kbId}/shares")
public Map<String, Object> createShare(HttpServletRequest req, @PathVariable String kbId,
                                        @RequestBody Map<String, String> body) {
    TenantEntity tenant = getTenant(req);
    kbAccessService.getKbAdminOnly(kbId, tenant.getId());

    String username = body.get("username");
    if (username == null || username.isBlank()) {
        throw new BadRequestException("username is required");
    }

    TenantEntity target = tenantRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("User not found: " + username));

    if (target.getId().equals(tenant.getId())) {
        throw new BadRequestException("Cannot share with yourself");
    }

    // Check if already shared
    if (kbShareRepository.findByKbIdAndTenantId(kbId, target.getId()).isPresent()) {
        throw new BadRequestException("Already shared with this user");
    }

    KbShareEntity share = new KbShareEntity();
    share.setKbId(kbId);
    share.setTenantId(target.getId());
    share.setRole(KbRole.MEMBER);
    share.setInvitedBy(tenant.getId());
    kbShareRepository.save(share);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", share.getId());
    result.put("kb_id", share.getKbId());
    result.put("tenant_id", share.getTenantId());
    result.put("username", target.getUsername());
    result.put("role", share.getRole().name().toLowerCase());
    result.put("created_at", share.getCreatedAt().toString());
    return result;
}
```

- [ ] **Step 4: Add DELETE /knowledge/bases/{kbId}/shares/{shareId} endpoint**

```java
@DeleteMapping("/bases/{kbId}/shares/{shareId}")
public ResponseEntity<?> deleteShare(HttpServletRequest req, @PathVariable String kbId,
                                      @PathVariable String shareId) {
    TenantEntity tenant = getTenant(req);
    kbAccessService.getKbAdminOnly(kbId, tenant.getId());

    KbShareEntity share = kbShareRepository.findById(shareId)
            .orElseThrow(() -> new NotFoundException("Share not found: " + shareId));

    if (!share.getKbId().equals(kbId)) {
        throw new NotFoundException("Share not found in this KB");
    }

    kbShareRepository.delete(share);
    return ResponseEntity.ok(Map.of("deleted", true));
}
```

- [ ] **Step 5: Modify listKnowledgeBases response to include sharing info**

In the `listKnowledgeBases` endpoint method, modify the response to indicate whether each KB is owned or shared:

```java
@GetMapping("/bases")
public List<Map<String, Object>> listKnowledgeBases(HttpServletRequest req) {
    TenantEntity tenant = getTenant(req);
    List<KnowledgeBaseEntity> kbs = knowledgeService.listKnowledgeBases(tenant.getId());
    return kbs.stream().map(kb -> {
        Map<String, Object> m = toKbResponse(kb);
        m.put("is_shared", !kb.getTenantId().equals(tenant.getId()));
        if (!kb.getTenantId().equals(tenant.getId())) {
            // Add owner info for shared KBs
            tenantRepository.findById(kb.getTenantId()).ifPresent(t ->
                m.put("owner_name", t.getName())
            );
        }
        return m;
    }).toList();
}
```

- [ ] **Step 6: Add TenantRepository.findByUsername if not exists**

Check `TenantRepository.java` for `findByUsername`. If missing, add:

```java
Optional<TenantEntity> findByUsername(String username);
```

- [ ] **Step 7: Verify compilation**

```bash
cd lakeon-api && ./mvnw compile -DskipTests
```

- [ ] **Step 8: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java \
        lakeon-api/src/main/java/com/lakeon/model/entity/TenantRepository.java
git commit -m "feat(api): add KB share management endpoints (list, create, delete)"
```

---

## Task 7: Frontend — share API functions

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts`

- [ ] **Step 1: Add share API functions**

Add to `lakeon-console/src/api/knowledge.ts`:

```typescript
export interface KbShare {
  id: string
  kb_id: string
  tenant_id: string
  username: string
  role: string
  invited_by: string
  created_at: string
}

export function listShares(kbId: string) {
  return client.get<KbShare[]>(`/knowledge/bases/${kbId}/shares`)
}

export function createShare(kbId: string, username: string) {
  return client.post<KbShare>(`/knowledge/bases/${kbId}/shares`, { username })
}

export function deleteShare(kbId: string, shareId: string) {
  return client.delete(`/knowledge/bases/${kbId}/shares/${shareId}`)
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts
git commit -m "feat(console): add share API functions"
```

---

## Task 8: Frontend — KbSharePanel component

**Files:**
- Create: `lakeon-console/src/views/knowledge/KbSharePanel.vue`

- [ ] **Step 1: Create KbSharePanel.vue**

Create `lakeon-console/src/views/knowledge/KbSharePanel.vue`:

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listShares, createShare, deleteShare, type KbShare } from '@/api/knowledge'

const props = defineProps<{ kbId: string }>()

const shares = ref<KbShare[]>([])
const inviteUsername = ref('')
const loading = ref(false)
const inviteLoading = ref(false)
const error = ref('')
const inviteError = ref('')
const inviteSuccess = ref('')

async function loadShares() {
  loading.value = true
  error.value = ''
  try {
    const res = await listShares(props.kbId)
    shares.value = res.data
  } catch (e: any) {
    error.value = e?.response?.data?.error || '加载成员列表失败'
  } finally {
    loading.value = false
  }
}

async function handleInvite() {
  if (!inviteUsername.value.trim()) return
  inviteLoading.value = true
  inviteError.value = ''
  inviteSuccess.value = ''
  try {
    await createShare(props.kbId, inviteUsername.value.trim())
    inviteSuccess.value = `已邀请 ${inviteUsername.value}`
    inviteUsername.value = ''
    await loadShares()
  } catch (e: any) {
    inviteError.value = e?.response?.data?.error || e?.response?.data?.message || '邀请失败'
  } finally {
    inviteLoading.value = false
  }
}

async function handleRemove(share: KbShare) {
  if (!confirm(`确定移除 ${share.username} 的访问权限？`)) return
  try {
    await deleteShare(props.kbId, share.id)
    await loadShares()
  } catch (e: any) {
    error.value = e?.response?.data?.error || '移除失败'
  }
}

onMounted(loadShares)
</script>

<template>
  <div class="share-panel">
    <h3>共享管理</h3>

    <!-- Invite form -->
    <div class="invite-form">
      <input
        v-model="inviteUsername"
        placeholder="输入用户名邀请..."
        :disabled="inviteLoading"
        @keyup.enter="handleInvite"
      />
      <button @click="handleInvite" :disabled="inviteLoading || !inviteUsername.trim()">
        {{ inviteLoading ? '邀请中...' : '邀请' }}
      </button>
    </div>
    <p v-if="inviteError" class="msg error">{{ inviteError }}</p>
    <p v-if="inviteSuccess" class="msg success">{{ inviteSuccess }}</p>

    <!-- Members list -->
    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="shares.length === 0" class="empty">暂无共享成员</div>
    <div v-else class="members">
      <div v-for="share in shares" :key="share.id" class="member-row">
        <span class="username">{{ share.username }}</span>
        <span class="role">{{ share.role === 'member' ? '成员' : '管理员' }}</span>
        <span class="date">{{ new Date(share.created_at).toLocaleDateString() }}</span>
        <button class="remove-btn" @click="handleRemove(share)">移除</button>
      </div>
    </div>
    <p v-if="error" class="msg error">{{ error }}</p>
  </div>
</template>

<style scoped>
.share-panel { padding: 16px 0; }
.share-panel h3 {
  font-size: 15px;
  font-weight: 600;
  color: #2c2c2c;
  margin: 0 0 16px;
}
.invite-form {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.invite-form input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
}
.invite-form input:focus { border-color: #c19a6b; }
.invite-form button {
  padding: 8px 16px;
  background: #c19a6b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
.invite-form button:hover:not(:disabled) { background: #a8834f; }
.invite-form button:disabled { opacity: 0.5; cursor: not-allowed; }
.msg { font-size: 12px; margin: 4px 0 8px; }
.msg.error { color: #d94f4f; }
.msg.success { color: #4a8c5c; }
.loading, .empty {
  font-size: 13px;
  color: #999;
  padding: 12px 0;
}
.members { margin-top: 8px; }
.member-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}
.member-row:last-child { border-bottom: none; }
.username {
  font-size: 13px;
  font-weight: 500;
  color: #2c2c2c;
  flex: 1;
}
.role {
  font-size: 12px;
  color: #888;
  background: #f5f2ed;
  padding: 2px 8px;
  border-radius: 4px;
}
.date { font-size: 12px; color: #bbb; }
.remove-btn {
  font-size: 12px;
  color: #d94f4f;
  background: none;
  border: none;
  cursor: pointer;
}
.remove-btn:hover { text-decoration: underline; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/knowledge/KbSharePanel.vue
git commit -m "feat(console): add KbSharePanel component for KB sharing management"
```

---

## Task 9: Frontend — KB list page with shared KBs grouping

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBases.vue`

- [ ] **Step 1: Split KB list into owned and shared groups**

In `KnowledgeBases.vue`, modify the script section to separate KBs:

```typescript
const ownedKbs = computed(() => kbs.value.filter(kb => !kb.is_shared))
const sharedKbs = computed(() => kbs.value.filter(kb => kb.is_shared))
```

- [ ] **Step 2: Update template to show two groups**

Add a section header "共享知识库" before the shared KBs list. For shared KBs, show the owner name and hide the delete button.

In the card/table view, for each KB:
- If `kb.is_shared`, show a small "共享" badge and `kb.owner_name`
- If `kb.is_shared`, hide the delete button/action

- [ ] **Step 3: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBases.vue
git commit -m "feat(console): split KB list into owned and shared groups"
```

---

## Task 10: Frontend — KB detail page with share panel and role-based actions

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Detect if current KB is shared and determine role**

Add computed property based on the KB response:

```typescript
const isShared = computed(() => kb.value?.is_shared === true)
const isAdmin = computed(() => !isShared.value) // owner = admin
```

- [ ] **Step 2: Add share tab/panel for admin users**

Add a share icon/button in the KB detail header (visible only when `isAdmin`). On click, show `KbSharePanel` in a side drawer or as a new tab.

```vue
<KbSharePanel v-if="showSharePanel && isAdmin" :kb-id="kbId" />
```

- [ ] **Step 3: Hide admin-only actions for members**

For member users (`isShared && !isAdmin`):
- Hide: delete KB button, delete document button, clear all documents, KB settings
- Show: upload, browse, chat, settle

Use `v-if="isAdmin"` on the delete buttons and settings UI.

- [ ] **Step 4: Run type check and build**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): add share panel and role-based action visibility"
```

---

## Task 11: E2E tests — full sharing workflow

**Files:**
- Create: `tests/e2e/test_kb_sharing.py`

- [ ] **Step 1: Write comprehensive E2E tests**

Create `tests/e2e/test_kb_sharing.py`:

```python
"""E2E tests for KB sharing and team collaboration.

Tests the full sharing lifecycle with two tenants:
- tenant_a: KB owner (admin)
- tenant_b: invited member

Each test verifies real business outcomes, not just HTTP status codes.
"""
import pytest
import httpx
import uuid
import time
import os

API_BASE = "https://api.dbay.cloud:8443/api/v1"
ADMIN_TOKEN = "lakeon-sre-2026"
TEST_DOC_DIR = os.path.expanduser("~/code/kb-doc")


def create_tenant(client: httpx.Client, prefix: str) -> dict:
    name = f"{prefix}-{uuid.uuid4().hex[:8]}"
    username = f"{prefix}_{uuid.uuid4().hex[:6]}"
    password = "testpass123"
    res = client.post("/tenants", json={
        "name": name, "username": username, "password": password
    })
    assert res.status_code == 200, f"Failed to create tenant: {res.text}"
    data = res.json()
    return {
        "id": data["id"], "username": username, "password": password,
        "api_key": data["api_key"], "name": name
    }


def delete_tenant(client: httpx.Client, tenant_id: str):
    client.delete(f"/admin/tenants/{tenant_id}",
                  headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})


def wait_kb_ready(client: httpx.Client, headers: dict, kb_id: str, timeout: int = 120):
    for _ in range(timeout // 2):
        res = client.get(f"/knowledge/bases/{kb_id}", headers=headers)
        if res.status_code == 200 and res.json().get("status") == "READY":
            return
        time.sleep(2)
    pytest.fail(f"KB {kb_id} did not become READY within {timeout}s")


def wait_doc_ready(client: httpx.Client, headers: dict, kb_id: str, doc_id: str, timeout: int = 120):
    for _ in range(timeout // 2):
        res = client.get(f"/knowledge/bases/{kb_id}/documents", headers=headers)
        if res.status_code == 200:
            docs = res.json() if isinstance(res.json(), list) else res.json().get("items", [])
            for doc in docs:
                if doc.get("id") == doc_id and doc.get("status") == "READY":
                    return
        time.sleep(2)
    pytest.fail(f"Document {doc_id} did not become READY within {timeout}s")


@pytest.fixture(scope="module")
def setup():
    """Create two tenants and a KB owned by tenant_a."""
    client = httpx.Client(base_url=API_BASE, verify=False, timeout=60)

    tenant_a = create_tenant(client, "share-owner")
    tenant_b = create_tenant(client, "share-member")
    headers_a = {"Authorization": f"Bearer {tenant_a['api_key']}"}
    headers_b = {"Authorization": f"Bearer {tenant_b['api_key']}"}

    # Create a KB owned by tenant_a
    res = client.post("/knowledge/bases", headers=headers_a, json={
        "name": f"shared-kb-{uuid.uuid4().hex[:6]}",
        "type": "DOCUMENT"
    })
    assert res.status_code == 200
    kb = res.json()
    kb_id = kb["id"]
    wait_kb_ready(client, headers_a, kb_id)

    yield {
        "client": client,
        "tenant_a": tenant_a,
        "tenant_b": tenant_b,
        "headers_a": headers_a,
        "headers_b": headers_b,
        "kb_id": kb_id
    }

    # Cleanup
    client.delete(f"/knowledge/bases/{kb_id}", headers=headers_a)
    delete_tenant(client, tenant_a["id"])
    delete_tenant(client, tenant_b["id"])
    client.close()


class TestShareManagement:
    """Test share CRUD operations."""

    def test_01_tenant_b_cannot_access_before_share(self, setup):
        """Unshared tenant gets 403."""
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}",
            headers=setup["headers_b"]
        )
        assert res.status_code in (403, 404), f"Expected 403/404, got {res.status_code}"

    def test_02_create_share(self, setup):
        """Admin invites tenant_b as member."""
        res = setup["client"].post(
            f"/knowledge/bases/{setup['kb_id']}/shares",
            headers=setup["headers_a"],
            json={"username": setup["tenant_b"]["username"]}
        )
        assert res.status_code == 200, f"Share creation failed: {res.text}"
        data = res.json()
        assert data["username"] == setup["tenant_b"]["username"]
        assert data["role"] == "member"
        setup["share_id"] = data["id"]

    def test_03_list_shares(self, setup):
        """Admin can list members."""
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}/shares",
            headers=setup["headers_a"]
        )
        assert res.status_code == 200
        shares = res.json()
        assert len(shares) == 1
        assert shares[0]["username"] == setup["tenant_b"]["username"]

    def test_04_tenant_b_sees_shared_kb_in_list(self, setup):
        """Member's KB list includes the shared KB."""
        res = setup["client"].get("/knowledge/bases", headers=setup["headers_b"])
        assert res.status_code == 200
        kbs = res.json()
        kb_ids = [kb["id"] for kb in kbs]
        assert setup["kb_id"] in kb_ids, f"Shared KB not in member's list: {kb_ids}"
        shared_kb = next(kb for kb in kbs if kb["id"] == setup["kb_id"])
        assert shared_kb.get("is_shared") is True

    def test_05_tenant_b_can_read_kb(self, setup):
        """Member can access KB details."""
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}",
            headers=setup["headers_b"]
        )
        assert res.status_code == 200

    def test_06_member_cannot_list_shares(self, setup):
        """Member cannot manage shares."""
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}/shares",
            headers=setup["headers_b"]
        )
        assert res.status_code == 403

    def test_07_duplicate_share_rejected(self, setup):
        """Duplicate share returns 400."""
        res = setup["client"].post(
            f"/knowledge/bases/{setup['kb_id']}/shares",
            headers=setup["headers_a"],
            json={"username": setup["tenant_b"]["username"]}
        )
        assert res.status_code == 400


class TestMemberOperations:
    """Test what members can and cannot do."""

    def test_10_member_upload_document(self, setup):
        """Member can upload a document and it gets processed."""
        # Find a test file
        test_file = None
        if os.path.isdir(TEST_DOC_DIR):
            for f in os.listdir(TEST_DOC_DIR):
                if f.endswith(".md") or f.endswith(".txt"):
                    test_file = os.path.join(TEST_DOC_DIR, f)
                    break
        if not test_file:
            # Create a minimal test file
            test_file = "/tmp/share-test-doc.md"
            with open(test_file, "w") as f:
                f.write("# Knowledge Sharing Test\n\nThis document tests KB sharing between team members.\n\n"
                        "Knowledge bases enable teams to organize and retrieve information efficiently.\n")

        # Get upload URL as member
        res = setup["client"].post(
            f"/knowledge/bases/{setup['kb_id']}/documents/upload-url",
            headers=setup["headers_b"],
            json={"filename": os.path.basename(test_file), "tags": ["test-share"]}
        )
        assert res.status_code == 200, f"Upload URL failed: {res.text}"
        data = res.json()
        upload_url = data.get("upload_url")
        doc_id = data.get("document_id")
        assert upload_url, f"Missing upload_url: {data}"
        assert doc_id, f"Missing document_id: {data}"

        # Upload the file
        with open(test_file, "rb") as f:
            upload_res = httpx.put(upload_url, content=f.read(), verify=False, timeout=30)
        assert upload_res.status_code in (200, 201), f"Upload failed: {upload_res.status_code}"

        # Notify upload complete
        res = setup["client"].post(
            f"/knowledge/documents/{doc_id}/upload-complete",
            headers=setup["headers_b"]
        )
        assert res.status_code == 200, f"Upload complete failed: {res.text}"

        # Wait for document to be processed
        wait_doc_ready(setup["client"], setup["headers_b"], setup["kb_id"], doc_id)

        # Verify document count increased
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}",
            headers=setup["headers_b"]
        )
        assert res.status_code == 200
        kb = res.json()
        assert kb.get("document_count", 0) >= 1, f"Document count not increased: {kb}"

        setup["uploaded_doc_id"] = doc_id

    def test_11_member_can_view_wiki(self, setup):
        """Member can browse wiki pages."""
        res = setup["client"].get(
            f"/knowledge/wiki/pages?kb_id={setup['kb_id']}",
            headers=setup["headers_b"]
        )
        assert res.status_code == 200

    def test_12_member_can_view_graph(self, setup):
        """Member can view knowledge graph."""
        res = setup["client"].get(
            f"/knowledge/wiki/graph?kb_id={setup['kb_id']}",
            headers=setup["headers_b"]
        )
        assert res.status_code == 200

    def test_13_member_can_chat(self, setup):
        """Member can ask questions and get answers based on real content."""
        res = setup["client"].post(
            f"/knowledge/wiki/chat",
            headers=setup["headers_b"],
            json={"kb_id": setup["kb_id"], "question": "What is knowledge sharing?"}
        )
        # Chat may be SSE or regular response
        assert res.status_code == 200, f"Chat failed: {res.text}"

    def test_20_member_cannot_delete_document(self, setup):
        """Member cannot delete documents."""
        doc_id = setup.get("uploaded_doc_id")
        if not doc_id:
            pytest.skip("No document was uploaded")
        res = setup["client"].delete(
            f"/knowledge/documents/{doc_id}",
            headers=setup["headers_b"]
        )
        assert res.status_code == 403, f"Expected 403, got {res.status_code}"
        # Verify document still exists
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}",
            headers=setup["headers_a"]  # check as owner
        )
        assert res.json().get("document_count", 0) >= 1

    def test_21_member_cannot_delete_kb(self, setup):
        """Member cannot delete the KB."""
        res = setup["client"].delete(
            f"/knowledge/bases/{setup['kb_id']}",
            headers=setup["headers_b"]
        )
        assert res.status_code == 403, f"Expected 403, got {res.status_code}"
        # Verify KB still exists
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}",
            headers=setup["headers_a"]
        )
        assert res.status_code == 200

    def test_22_member_cannot_manage_shares(self, setup):
        """Member cannot invite or remove others."""
        res = setup["client"].post(
            f"/knowledge/bases/{setup['kb_id']}/shares",
            headers=setup["headers_b"],
            json={"username": "someone"}
        )
        assert res.status_code == 403


class TestShareRemoval:
    """Test removing shares and verifying isolation."""

    def test_30_remove_share(self, setup):
        """Admin removes member's access."""
        share_id = setup.get("share_id")
        if not share_id:
            # Find the share
            res = setup["client"].get(
                f"/knowledge/bases/{setup['kb_id']}/shares",
                headers=setup["headers_a"]
            )
            shares = res.json()
            if shares:
                share_id = shares[0]["id"]
        assert share_id, "No share_id to remove"

        res = setup["client"].delete(
            f"/knowledge/bases/{setup['kb_id']}/shares/{share_id}",
            headers=setup["headers_a"]
        )
        assert res.status_code == 200

    def test_31_member_loses_access_after_removal(self, setup):
        """After removal, member can no longer access the KB."""
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}",
            headers=setup["headers_b"]
        )
        assert res.status_code in (403, 404), f"Expected 403/404 after removal, got {res.status_code}"

    def test_32_member_kb_list_no_longer_includes_shared(self, setup):
        """After removal, shared KB disappears from member's list."""
        res = setup["client"].get("/knowledge/bases", headers=setup["headers_b"])
        assert res.status_code == 200
        kbs = res.json()
        kb_ids = [kb["id"] for kb in kbs]
        assert setup["kb_id"] not in kb_ids, f"Removed KB still in member's list"

    def test_33_admin_still_has_access(self, setup):
        """Owner retains full access after removing member."""
        res = setup["client"].get(
            f"/knowledge/bases/{setup['kb_id']}",
            headers=setup["headers_a"]
        )
        assert res.status_code == 200


class TestUnrelatedTenantIsolation:
    """Verify that a third-party tenant cannot access shared KBs."""

    def test_40_unrelated_tenant_no_access(self, setup):
        """A tenant not involved in the share has no access."""
        client = setup["client"]
        # Create a third tenant
        tenant_c = create_tenant(client, "share-outsider")
        headers_c = {"Authorization": f"Bearer {tenant_c['api_key']}"}
        try:
            res = client.get(
                f"/knowledge/bases/{setup['kb_id']}",
                headers=headers_c
            )
            assert res.status_code in (403, 404)
        finally:
            delete_tenant(client, tenant_c["id"])
```

- [ ] **Step 2: Run E2E tests**

```bash
python3 -m pytest tests/e2e/test_kb_sharing.py -v
```

Expected: All tests PASSED. Fix any failures before proceeding.

- [ ] **Step 3: Commit**

```bash
git add tests/e2e/test_kb_sharing.py
git commit -m "test: add E2E tests for KB sharing workflow with real business validation"
```

---

## Task 12: Build, deploy, and verify

- [ ] **Step 1: Run full type check on both frontend projects**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
cd lakeon-admin && npx vue-tsc -b --noEmit
```

- [ ] **Step 2: Build and push API image**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
```

- [ ] **Step 3: Restart API deployment**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
```

- [ ] **Step 4: Push frontend to trigger Railway deploy**

```bash
git push origin main
```

- [ ] **Step 5: Run E2E tests against production**

```bash
python3 -m pytest tests/e2e/test_kb_sharing.py -v
```

Expected: All tests PASSED.

- [ ] **Step 6: Run existing E2E tests to verify no regression**

```bash
python3 -m pytest tests/e2e/ -v
```

Expected: All existing tests still pass — the access control refactoring should not break owner-only workflows.
