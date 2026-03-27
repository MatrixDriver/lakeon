# Trial Readonly Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trial users share a pre-populated read-only demo tenant instead of getting empty individual databases.

**Architecture:** A `TrialDemoFilter` servlet filter intercepts trial user requests — redirecting reads to the demo tenant and blocking writes with 403. The demo tenant is identified by `LAKEON_DEMO_TENANT_ID` config. Frontend shows a trial banner and disables write buttons.

**Tech Stack:** Spring Boot (Java 17), Vue 3 + TypeScript, PostgreSQL

---

### Task 1: Add `trial` and `expires_at` to TenantResponse

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/model/dto/TenantResponse.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/service/TenantService.java:260-287`

- [ ] **Step 1: Add trial fields to TenantResponse**

In `lakeon-api/src/main/java/com/lakeon/model/dto/TenantResponse.java`, add two new fields after the `disabledAt` field (line 31):

```java
private Boolean trial;
@JsonProperty("expires_at")
@JsonInclude(JsonInclude.Include.NON_NULL)
private Instant expiresAt;
```

Add getters/setters:

```java
public Boolean getTrial() { return trial; }
public void setTrial(Boolean trial) { this.trial = trial; }
public Instant getExpiresAt() { return expiresAt; }
public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
```

Update the all-args constructor (line 35-48) to include `Boolean trial, Instant expiresAt` as new parameters, and assign them.

Update the Builder class — add fields and methods:

```java
private Boolean trial;
private Instant expiresAt;

public Builder trial(Boolean trial) { this.trial = trial; return this; }
public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
```

Update `build()` to pass the new fields to the constructor.

- [ ] **Step 2: Add trial fields to toResponse methods in TenantService**

In `lakeon-api/src/main/java/com/lakeon/service/TenantService.java`, update both `toResponse()` (line 260) and `toResponseWithDisabled()` (line 274) to include:

```java
.trial(entity.getTrial())
.expiresAt(entity.getExpiresAt())
```

Add `.trial(entity.getTrial()).expiresAt(entity.getExpiresAt())` before `.build()` in both methods.

- [ ] **Step 3: Verify compilation**

Run:
```bash
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/dto/TenantResponse.java lakeon-api/src/main/java/com/lakeon/service/TenantService.java
git commit -m "feat(trial): add trial and expires_at to TenantResponse"
```

---

### Task 2: Add demo tenant config to LakeonProperties

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yml`

- [ ] **Step 1: Add DemoConfig inner class to LakeonProperties**

In `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`, add a new field after `private MemoryConfig memory` (line 30):

```java
private DemoConfig demo = new DemoConfig();
```

Add getter/setter after line 67:

```java
public DemoConfig getDemo() { return demo; }
public void setDemo(DemoConfig demo) { this.demo = demo; }
```

Add the inner class at the end of the file (before the closing `}` of LakeonProperties):

```java
public static class DemoConfig {
    private String tenantId;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
```

- [ ] **Step 2: Add demo config to application.yml**

In `lakeon-api/src/main/resources/application.yml`, add after the `defaults` section (after line 83):

```yaml
  demo:
    tenant-id: ${LAKEON_DEMO_TENANT_ID:}
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java lakeon-api/src/main/resources/application.yml
git commit -m "feat(trial): add demo tenant config to LakeonProperties"
```

---

### Task 3: Create TrialDemoFilter

This is the core filter that intercepts trial user requests — redirecting reads to demo tenant and blocking writes.

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/config/TrialDemoFilter.java`

- [ ] **Step 1: Create the filter**

Create `lakeon-api/src/main/java/com/lakeon/config/TrialDemoFilter.java`:

```java
package com.lakeon.config;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

/**
 * Trial demo filter: for trial users, redirect reads to demo tenant, block writes.
 * Runs after ApiKeyFilter (@Order(1)) which sets the "tenant" request attribute.
 */
@Component
@Order(2)
public class TrialDemoFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(TrialDemoFilter.class);

    private final LakeonProperties props;
    private final TenantRepository tenantRepository;

    // Write methods that should be blocked for trial users
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    // POST endpoints that are read-like (search, query, recall)
    private static final Set<String> READ_POST_PATHS = Set.of(
        "/query",        // POST /databases/{id}/query — SQL queries (filtered separately)
        "/search",       // POST /knowledge/bases/{id}/search
        "/recall"        // POST /memory/bases/{id}/recall
    );

    // Paths that should always use the trial user's own tenant (not demo)
    private static final Set<String> OWN_TENANT_PATHS = Set.of(
        "/api/v1/tenants/me",
        "/api/v1/usage",
        "/api/v1/auth/"
    );

    public TrialDemoFilter(LakeonProperties props, TenantRepository tenantRepository) {
        this.props = props;
        this.tenantRepository = tenantRepository;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // Only process if tenant was set by ApiKeyFilter
        TenantEntity tenant = (TenantEntity) request.getAttribute("tenant");
        if (tenant == null || !Boolean.TRUE.equals(tenant.getTrial())) {
            chain.doFilter(req, res);
            return;
        }

        // Check if demo tenant is configured
        String demoTenantId = props.getDemo().getTenantId();
        if (demoTenantId == null || demoTenantId.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        // Check trial expiry
        if (tenant.getExpiresAt() != null && Instant.now().isAfter(tenant.getExpiresAt())) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"error\":{\"code\":\"TRIAL_EXPIRED\",\"message\":\"体验账号已过期，请注册账号继续使用\"}}"
            );
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Some paths should use trial user's own tenant
        for (String ownPath : OWN_TENANT_PATHS) {
            if (path.startsWith(ownPath)) {
                chain.doFilter(req, res);
                return;
            }
        }

        // Determine if this is a read or write operation
        boolean isRead = READ_METHODS.contains(method);
        if ("POST".equals(method)) {
            for (String readPostPath : READ_POST_PATHS) {
                if (path.endsWith(readPostPath)) {
                    isRead = true;
                    break;
                }
            }
        }

        if (!isRead) {
            // Block write operations
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"error\":{\"code\":\"TRIAL_RESTRICTED\",\"message\":\"体验模式为只读，注册账号后可使用全部功能\"}}"
            );
            return;
        }

        // Redirect reads to demo tenant
        TenantEntity demoTenant = tenantRepository.findById(demoTenantId).orElse(null);
        if (demoTenant == null) {
            log.warn("Demo tenant {} not found, falling back to trial tenant", demoTenantId);
            chain.doFilter(req, res);
            return;
        }

        request.setAttribute("tenant", demoTenant);
        chain.doFilter(req, res);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/TrialDemoFilter.java
git commit -m "feat(trial): add TrialDemoFilter for read-only demo mode"
```

---

### Task 4: Add SQL write filter for trial users

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/DatabaseQueryService.java:35-41,538-541`

- [ ] **Step 1: Add write SQL pattern and trial check**

In `lakeon-api/src/main/java/com/lakeon/service/DatabaseQueryService.java`, add a new pattern after the `DANGEROUS_SQL` pattern (after line 38):

```java
private static final Pattern WRITE_SQL = Pattern.compile(
    "^\\s*(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE)\\b",
    Pattern.CASE_INSENSITIVE
);
```

In `executeQuery()` method (line 538), add a trial check after the DANGEROUS_SQL check (after line 541):

```java
if (Boolean.TRUE.equals(tenant.getTrial()) && WRITE_SQL.matcher(sql).find()) {
    throw new BadRequestException("体验模式仅支持 SELECT 查询，注册账号后可执行写操作");
}
```

This requires adding the `getTrial()` check. The `tenant` parameter is already a `TenantEntity` which has the `trial` field.

- [ ] **Step 2: Verify compilation**

Run:
```bash
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/DatabaseQueryService.java
git commit -m "feat(trial): block write SQL for trial users in query service"
```

---

### Task 5: Simplify TrialService — stop creating database

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/TrialService.java:42-79`

- [ ] **Step 1: Simplify createTrial()**

In `lakeon-api/src/main/java/com/lakeon/service/TrialService.java`, replace the `createTrial()` method body (lines 42-79) with:

```java
@Transactional
public Map<String, Object> createTrial() {
    String trialId = UUID.randomUUID().toString().substring(0, 8);
    String username = "trial_" + trialId;

    TenantEntity tenant = new TenantEntity();
    tenant.setName(username);
    tenant.setUsername(username);
    tenant.setPasswordHash("");
    tenant.setTrial(true);
    tenant.setExpiresAt(Instant.now().plus(TRIAL_HOURS, ChronoUnit.HOURS));
    tenant.setMaxDatabases(0);
    tenant.setMaxStorageGb(0);
    tenant.setMaxComputeCu(0);
    tenant = tenantRepository.save(tenant);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tenant_id", tenant.getId());
    result.put("api_key", tenant.getApiKey());
    result.put("username", username);
    result.put("trial", true);
    result.put("expires_at", tenant.getExpiresAt());
    result.put("expires_in_hours", TRIAL_HOURS);

    log.info("Created trial account: {} (expires {})", tenant.getId(), tenant.getExpiresAt());
    return result;
}
```

Key changes:
- Removed database creation (no more `databaseService.create()`)
- Set quotas to 0 (trial users can't create anything)
- Added `"trial": true` to response
- Removed password generation (not needed)

- [ ] **Step 2: Remove unused DatabaseService dependency**

Since `createTrial()` no longer creates databases, remove `databaseService` from the constructor and field if no other method uses it. Check `cleanupExpiredTrials()` — it still uses `databaseService.delete()` for legacy trial tenants, so keep it.

Actually, keep `databaseService` — the cleanup job still needs it for any remaining old trial tenants with databases.

- [ ] **Step 3: Verify compilation**

Run:
```bash
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/TrialService.java
git commit -m "feat(trial): simplify trial creation — no database, zero quotas"
```

---

### Task 6: Frontend — add trial state to auth store

**Files:**
- Modify: `lakeon-console/src/stores/auth.ts`
- Modify: `lakeon-console/src/api/tenant.ts`

- [ ] **Step 1: Add trial fields to Tenant type**

In `lakeon-console/src/api/tenant.ts`, update the `Tenant` interface (line 3-8):

```typescript
export interface Tenant {
  id: string
  name: string
  api_key?: string
  created_at: string
  trial?: boolean
  expires_at?: string
}
```

- [ ] **Step 2: Add trial state to auth store**

In `lakeon-console/src/stores/auth.ts`, add trial state:

After the existing `ref` declarations (after line 8), add:

```typescript
const isTrial = ref(localStorage.getItem('lakeon_is_trial') === 'true')
const trialExpiresAt = ref(localStorage.getItem('lakeon_trial_expires_at') || '')
```

Add a `setTrial` function after `setTenant`:

```typescript
function setTrialState(trial: boolean, expiresAt?: string) {
  isTrial.value = trial
  trialExpiresAt.value = expiresAt || ''
  if (trial) {
    localStorage.setItem('lakeon_is_trial', 'true')
    if (expiresAt) localStorage.setItem('lakeon_trial_expires_at', expiresAt)
  } else {
    localStorage.removeItem('lakeon_is_trial')
    localStorage.removeItem('lakeon_trial_expires_at')
  }
}
```

Update the `logout()` function to clear trial state:

```typescript
function logout() {
  apiKey.value = ''
  tenantId.value = ''
  tenantName.value = ''
  isTrial.value = false
  trialExpiresAt.value = ''
  localStorage.removeItem('lakeon_api_key')
  localStorage.removeItem('lakeon_tenant_id')
  localStorage.removeItem('lakeon_tenant_name')
  localStorage.removeItem('lakeon_is_trial')
  localStorage.removeItem('lakeon_trial_expires_at')
}
```

Update the return statement to include new state/functions:

```typescript
return { apiKey, tenantId, tenantName, isTrial, trialExpiresAt, login, setTenant, setTrialState, logout }
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/stores/auth.ts lakeon-console/src/api/tenant.ts
git commit -m "feat(trial): add trial state to auth store"
```

---

### Task 7: Frontend — update trial flow in LandingView

**Files:**
- Modify: `lakeon-console/src/views/landing/LandingView.vue:273-299`

- [ ] **Step 1: Update startTrial function**

In `lakeon-console/src/views/landing/LandingView.vue`, modify the `startTrial()` function (lines 273-299). After the API key is saved (line 286), add trial state:

Replace lines 284-294 with:

```typescript
    localStorage.setItem('lakeon_api_key', data.api_key)
    authStore.apiKey = data.api_key
    authStore.setTenant(data.tenant_id, data.username || 'trial')
    authStore.setTrialState(true, data.expires_at)

    // Navigate to dashboard (trial users see demo data)
    router.push('/dashboard')
```

This removes the conditional navigation to database manager (since trial no longer creates a database).

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/landing/LandingView.vue
git commit -m "feat(trial): update landing trial flow — navigate to dashboard"
```

---

### Task 8: Frontend — add trial banner to ConsoleLayout

**Files:**
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`

- [ ] **Step 1: Add trial banner HTML**

In `lakeon-console/src/layouts/ConsoleLayout.vue`, add the banner after the `<header>` closing tag (after line 56), before `<div class="console-body">`:

```html
    <!-- Trial Banner -->
    <div v-if="authStore.isTrial" class="trial-banner">
      <div class="trial-banner-content">
        <span class="trial-banner-text">
          体验模式 — 只读演示环境<template v-if="trialTimeLeft">，剩余 {{ trialTimeLeft }}</template>
        </span>
        <router-link to="/login" class="trial-banner-cta" @click="authStore.logout()">
          注册账号，解锁全部功能
        </router-link>
      </div>
    </div>
```

- [ ] **Step 2: Add trial timer logic in script**

In the `<script setup>` section (starts around line 176), add after imports:

```typescript
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'
```

(update existing import to include `computed, onMounted, onUnmounted`)

Add after the existing script setup code:

```typescript
// Trial countdown
const trialTimeLeft = ref('')
let trialTimer: ReturnType<typeof setInterval> | null = null

function updateTrialCountdown() {
  if (!authStore.isTrial || !authStore.trialExpiresAt) {
    trialTimeLeft.value = ''
    return
  }
  const diff = new Date(authStore.trialExpiresAt).getTime() - Date.now()
  if (diff <= 0) {
    trialTimeLeft.value = '已过期'
    return
  }
  const h = Math.floor(diff / 3600000)
  const m = Math.floor((diff % 3600000) / 60000)
  trialTimeLeft.value = `${h}h ${m}m`
}

onMounted(() => {
  if (authStore.isTrial) {
    updateTrialCountdown()
    trialTimer = setInterval(updateTrialCountdown, 60000)
  }
})

onUnmounted(() => {
  if (trialTimer) clearInterval(trialTimer)
})
```

- [ ] **Step 3: Add trial banner styles**

Add in the `<style>` section:

```css
.trial-banner {
  background: linear-gradient(90deg, #fff3cd, #ffeaa7);
  border-bottom: 1px solid #f0d78e;
  padding: 6px 16px;
  text-align: center;
  font-size: 13px;
  color: #856404;
  z-index: 100;
}
.trial-banner-content {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
}
.trial-banner-cta {
  color: #0073e6;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}
.trial-banner-cta:hover {
  text-decoration: underline;
}
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/layouts/ConsoleLayout.vue
git commit -m "feat(trial): add trial banner with countdown to ConsoleLayout"
```

---

### Task 9: Frontend — disable write buttons for trial users

**Files:**
- Modify: `lakeon-console/src/views/dashboard/DashboardView.vue`
- Modify: `lakeon-console/src/components/SqlEditor.vue`

- [ ] **Step 1: Disable "创建数据库" button in DashboardView**

In `lakeon-console/src/views/dashboard/DashboardView.vue`:

Add auth store import in script setup:
```typescript
import { useAuthStore } from '../stores/auth'
const authStore = useAuthStore()
```

Modify the create button at line 7:
```html
<button class="btn btn-primary" @click="showCreateDialog = true" :disabled="authStore.isTrial" :title="authStore.isTrial ? '注册后可用' : ''">创建数据库</button>
```

Modify the welcome card create button at line 31:
```html
<button class="btn btn-primary btn-lg" @click="showCreateDialog = true" :disabled="authStore.isTrial" :title="authStore.isTrial ? '注册后可用' : ''">创建第一个数据库</button>
```

Modify the import link at line 32:
```html
<router-link v-if="!authStore.isTrial" to="/import" class="btn btn-outline btn-lg">导入已有数据库</router-link>
```

- [ ] **Step 2: Add trial hint to SQL Editor error display**

In `lakeon-console/src/components/SqlEditor.vue`, no changes needed — the backend already returns a clear error message ("体验模式仅支持 SELECT 查询") which the existing error display (line 361-362) will show correctly.

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/dashboard/DashboardView.vue
git commit -m "feat(trial): disable create buttons for trial users in dashboard"
```

---

### Task 10: Frontend — fetch trial state from /tenants/me on app load

**Files:**
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`

- [ ] **Step 1: Fetch tenant info on mount**

In `lakeon-console/src/layouts/ConsoleLayout.vue`, in the `onMounted` or script setup area, add a call to refresh trial state from the server:

```typescript
import { tenantApi } from '../api/tenant'

// Refresh trial state from server on mount
onMounted(async () => {
  if (authStore.apiKey) {
    try {
      const res = await tenantApi.me()
      const t = res.data
      if (t.trial) {
        authStore.setTrialState(true, t.expires_at)
      } else {
        authStore.setTrialState(false)
      }
    } catch {
      // ignore — will use cached state
    }
  }
})
```

If `onMounted` already exists, merge this into it.

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/layouts/ConsoleLayout.vue
git commit -m "feat(trial): refresh trial state from server on console load"
```

---

### Task 11: Create demo data SQL init script

**Files:**
- Create: `deploy/demo/init-demo-db.sql`

- [ ] **Step 1: Create the SQL init script**

Create `deploy/demo/init-demo-db.sql`:

```sql
-- Demo e-commerce database for trial users
-- Run against the demo tenant's database after creation

-- Customers
CREATE TABLE IF NOT EXISTS customers (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    phone VARCHAR(20),
    city VARCHAR(50),
    created_at TIMESTAMP DEFAULT now()
);

-- Products
CREATE TABLE IF NOT EXISTS products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stock INTEGER DEFAULT 0,
    description TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- Orders
CREATE TABLE IF NOT EXISTS orders (
    id SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customers(id),
    total DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT now()
);

-- Order items
CREATE TABLE IF NOT EXISTS order_items (
    id SERIAL PRIMARY KEY,
    order_id INTEGER REFERENCES orders(id),
    product_id INTEGER REFERENCES products(id),
    quantity INTEGER NOT NULL,
    price DECIMAL(10,2) NOT NULL
);

-- Reviews
CREATE TABLE IF NOT EXISTS reviews (
    id SERIAL PRIMARY KEY,
    product_id INTEGER REFERENCES products(id),
    customer_id INTEGER REFERENCES customers(id),
    rating INTEGER CHECK (rating BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- Enable vector extension for demo
CREATE EXTENSION IF NOT EXISTS vector;

-- Product embeddings (demonstrate vector capability)
ALTER TABLE products ADD COLUMN IF NOT EXISTS embedding vector(3);

-- ===== Sample Data =====

-- Customers (20)
INSERT INTO customers (name, email, phone, city) VALUES
('张三', 'zhangsan@example.com', '13800001001', '北京'),
('李四', 'lisi@example.com', '13800001002', '上海'),
('王五', 'wangwu@example.com', '13800001003', '深圳'),
('赵六', 'zhaoliu@example.com', '13800001004', '广州'),
('孙七', 'sunqi@example.com', '13800001005', '杭州'),
('周八', 'zhouba@example.com', '13800001006', '成都'),
('吴九', 'wujiu@example.com', '13800001007', '武汉'),
('郑十', 'zhengshi@example.com', '13800001008', '南京'),
('陈小明', 'chenxm@example.com', '13800001009', '西安'),
('林小红', 'linxh@example.com', '13800001010', '重庆'),
('黄大伟', 'huangdw@example.com', '13800001011', '天津'),
('刘美丽', 'liuml@example.com', '13800001012', '苏州'),
('杨建国', 'yangjg@example.com', '13800001013', '长沙'),
('朱文静', 'zhuwj@example.com', '13800001014', '青岛'),
('谢志强', 'xiezq@example.com', '13800001015', '大连'),
('马小龙', 'maxl@example.com', '13800001016', '厦门'),
('何丽华', 'helh@example.com', '13800001017', '合肥'),
('罗明辉', 'luomh@example.com', '13800001018', '郑州'),
('韩雪', 'hanxue@example.com', '13800001019', '昆明'),
('方圆', 'fangyuan@example.com', '13800001020', '福州')
ON CONFLICT (email) DO NOTHING;

-- Products (30)
INSERT INTO products (name, category, price, stock, description, embedding) VALUES
('MacBook Pro 16"', '电子产品', 18999.00, 50, 'Apple M3 Pro 芯片，18GB 内存，512GB 存储', '[0.1, 0.8, 0.3]'),
('iPhone 16 Pro', '电子产品', 8999.00, 200, 'A18 Pro 芯片，256GB，深空黑', '[0.15, 0.85, 0.25]'),
('AirPods Pro 2', '电子产品', 1899.00, 500, '主动降噪，自适应音频', '[0.12, 0.7, 0.4]'),
('iPad Air', '电子产品', 4799.00, 150, 'M2 芯片，10.9 英寸', '[0.13, 0.75, 0.35]'),
('Sony WH-1000XM5', '电子产品', 2499.00, 100, '无线降噪耳机，30小时续航', '[0.11, 0.65, 0.45]'),
('三体（全三册）', '图书', 89.00, 1000, '刘慈欣科幻巨著', '[0.8, 0.1, 0.5]'),
('算法导论', '图书', 128.00, 300, '经典计算机科学教材', '[0.85, 0.15, 0.6]'),
('小王子', '图书', 32.00, 800, '圣埃克苏佩里经典童话', '[0.75, 0.05, 0.55]'),
('人类简史', '图书', 68.00, 600, '尤瓦尔·赫拉利著', '[0.78, 0.08, 0.52]'),
('代码大全', '图书', 148.00, 200, '软件构造实用手册', '[0.88, 0.18, 0.65]'),
('北欧风落地灯', '家居', 399.00, 80, '简约设计，暖光 LED', '[0.3, 0.3, 0.8]'),
('记忆棉枕头', '家居', 199.00, 300, '慢回弹太空棉，护颈设计', '[0.25, 0.25, 0.85]'),
('陶瓷餐具套装', '家居', 259.00, 150, '日式简约风，6人份', '[0.28, 0.28, 0.82]'),
('智能加湿器', '家居', 189.00, 200, '静音设计，4L 大容量', '[0.22, 0.32, 0.78]'),
('收纳架三层', '家居', 129.00, 400, '免打孔安装，不锈钢材质', '[0.2, 0.35, 0.75]'),
('云南普洱茶饼', '食品', 168.00, 500, '2020年古树春茶，357g', '[0.5, 0.5, 0.2]'),
('有机坚果礼盒', '食品', 128.00, 300, '混合坚果 6 种，750g', '[0.48, 0.52, 0.18]'),
('意式浓缩咖啡豆', '食品', 89.00, 400, '中深度烘焙，500g', '[0.52, 0.48, 0.22]'),
('日本抹茶粉', '食品', 78.00, 250, '宇治抹茶，100g', '[0.45, 0.55, 0.15]'),
('新西兰蜂蜜', '食品', 158.00, 180, 'UMF 10+ 麦卢卡蜂蜜', '[0.55, 0.45, 0.25]'),
('纯棉 T 恤', '服装', 99.00, 600, '精梳棉，圆领基础款', '[0.6, 0.2, 0.6]'),
('运动跑鞋', '服装', 499.00, 200, '缓震回弹，透气网面', '[0.65, 0.22, 0.58]'),
('羊毛围巾', '服装', 259.00, 150, '100% 美利奴羊毛', '[0.58, 0.18, 0.62]'),
('牛仔外套', '服装', 399.00, 100, '复古水洗，纯棉面料', '[0.62, 0.24, 0.56]'),
('帆布双肩包', '服装', 179.00, 300, '防水涂层，大容量', '[0.55, 0.2, 0.65]'),
('机械键盘', '电子产品', 599.00, 250, 'Cherry 红轴，RGB 背光', '[0.14, 0.72, 0.38]'),
('4K 显示器 27"', '电子产品', 2299.00, 80, 'IPS 面板，Type-C 一线连', '[0.16, 0.82, 0.28]'),
('无线充电板', '电子产品', 129.00, 400, 'Qi 15W 快充', '[0.1, 0.6, 0.5]'),
('移动硬盘 2TB', '电子产品', 459.00, 300, 'USB 3.2，读速 1050MB/s', '[0.18, 0.78, 0.32]'),
('智能手表', '电子产品', 1599.00, 180, '血氧检测，GPS 定位', '[0.17, 0.8, 0.3]')
ON CONFLICT DO NOTHING;

-- Orders (40)
INSERT INTO orders (customer_id, total, status, created_at) VALUES
(1, 18999.00, 'completed', now() - interval '30 days'),
(1, 89.00, 'completed', now() - interval '25 days'),
(2, 8999.00, 'completed', now() - interval '28 days'),
(2, 259.00, 'shipped', now() - interval '3 days'),
(3, 1899.00, 'completed', now() - interval '20 days'),
(3, 399.00, 'completed', now() - interval '15 days'),
(4, 4799.00, 'completed', now() - interval '22 days'),
(4, 168.00, 'pending', now() - interval '1 day'),
(5, 2499.00, 'shipped', now() - interval '5 days'),
(5, 128.00, 'completed', now() - interval '18 days'),
(6, 599.00, 'completed', now() - interval '14 days'),
(6, 199.00, 'completed', now() - interval '10 days'),
(7, 2299.00, 'shipped', now() - interval '4 days'),
(7, 32.00, 'completed', now() - interval '12 days'),
(8, 129.00, 'completed', now() - interval '8 days'),
(8, 499.00, 'shipped', now() - interval '2 days'),
(9, 89.00, 'completed', now() - interval '16 days'),
(9, 1599.00, 'pending', now() - interval '1 day'),
(10, 399.00, 'completed', now() - interval '11 days'),
(10, 78.00, 'completed', now() - interval '7 days'),
(11, 8999.00, 'completed', now() - interval '26 days'),
(12, 148.00, 'completed', now() - interval '19 days'),
(13, 259.00, 'shipped', now() - interval '3 days'),
(14, 189.00, 'completed', now() - interval '9 days'),
(15, 18999.00, 'completed', now() - interval '24 days'),
(16, 158.00, 'completed', now() - interval '6 days'),
(17, 459.00, 'shipped', now() - interval '2 days'),
(18, 68.00, 'completed', now() - interval '13 days'),
(19, 1899.00, 'completed', now() - interval '17 days'),
(20, 99.00, 'pending', now()),
(1, 599.00, 'pending', now()),
(3, 128.00, 'shipped', now() - interval '2 days'),
(5, 179.00, 'completed', now() - interval '6 days'),
(7, 259.00, 'pending', now()),
(9, 399.00, 'shipped', now() - interval '1 day'),
(11, 129.00, 'completed', now() - interval '4 days'),
(13, 2499.00, 'pending', now()),
(15, 89.00, 'completed', now() - interval '8 days'),
(17, 1599.00, 'shipped', now() - interval '3 days'),
(19, 499.00, 'completed', now() - interval '5 days')
ON CONFLICT DO NOTHING;

-- Order items (60)
INSERT INTO order_items (order_id, product_id, quantity, price) VALUES
(1, 1, 1, 18999.00), (2, 6, 1, 89.00), (3, 2, 1, 8999.00),
(4, 13, 1, 259.00), (5, 3, 1, 1899.00), (6, 11, 1, 399.00),
(7, 4, 1, 4799.00), (8, 16, 1, 168.00), (9, 5, 1, 2499.00),
(10, 17, 1, 128.00), (11, 26, 1, 599.00), (12, 12, 1, 199.00),
(13, 27, 1, 2299.00), (14, 8, 1, 32.00), (15, 15, 1, 129.00),
(16, 22, 1, 499.00), (17, 18, 1, 89.00), (18, 30, 1, 1599.00),
(19, 24, 1, 399.00), (20, 19, 1, 78.00), (21, 2, 1, 8999.00),
(22, 10, 1, 148.00), (23, 23, 1, 259.00), (24, 14, 1, 189.00),
(25, 1, 1, 18999.00), (26, 20, 1, 158.00), (27, 29, 1, 459.00),
(28, 9, 1, 68.00), (29, 3, 1, 1899.00), (30, 21, 1, 99.00),
(31, 26, 1, 599.00), (32, 17, 1, 128.00), (33, 25, 1, 179.00),
(34, 23, 1, 259.00), (35, 11, 1, 399.00), (36, 28, 1, 129.00),
(37, 5, 1, 2499.00), (38, 18, 1, 89.00), (39, 30, 1, 1599.00),
(40, 22, 1, 499.00),
-- Some orders with multiple items
(1, 3, 1, 1899.00), (3, 3, 1, 1899.00), (7, 3, 1, 1899.00),
(11, 28, 2, 129.00), (15, 28, 1, 129.00), (21, 3, 1, 1899.00),
(25, 3, 1, 1899.00), (5, 28, 1, 129.00), (9, 12, 1, 199.00),
(13, 8, 2, 32.00), (17, 19, 1, 78.00), (19, 21, 2, 99.00),
(2, 8, 1, 32.00), (4, 19, 1, 78.00), (6, 12, 1, 199.00),
(8, 20, 1, 158.00), (10, 18, 2, 89.00), (12, 15, 1, 129.00),
(14, 9, 1, 68.00), (16, 21, 1, 99.00)
ON CONFLICT DO NOTHING;

-- Reviews (30)
INSERT INTO reviews (product_id, customer_id, rating, comment, created_at) VALUES
(1, 1, 5, '性能强劲，编译速度飞快，非常满意', now() - interval '28 days'),
(1, 15, 5, '屏幕显示效果一流，续航也很好', now() - interval '22 days'),
(2, 2, 4, '拍照效果很好，就是有点贵', now() - interval '26 days'),
(2, 11, 5, '手感好，速度快，值得升级', now() - interval '24 days'),
(3, 3, 5, '降噪效果非常好，通勤必备', now() - interval '18 days'),
(4, 4, 4, '轻薄便携，办公很方便', now() - interval '20 days'),
(5, 5, 5, '音质超好，降噪强，佩戴舒适', now() - interval '16 days'),
(6, 1, 5, '三体是神作，百看不厌', now() - interval '23 days'),
(6, 2, 5, '硬科幻巅峰，推荐所有人', now() - interval '15 days'),
(7, 12, 4, '经典教材，内容全面但有点厚', now() - interval '17 days'),
(8, 7, 5, '送给孩子的礼物，插画很美', now() - interval '10 days'),
(9, 18, 4, '视角很宏大，翻译也不错', now() - interval '11 days'),
(10, 12, 5, '程序员必读，实用性很强', now() - interval '17 days'),
(11, 3, 4, '灯光很柔和，就是底座有点大', now() - interval '13 days'),
(12, 6, 5, '睡眠质量明显改善，推荐', now() - interval '8 days'),
(13, 4, 4, '质感不错，很有日系风格', now() - interval '9 days'),
(16, 8, 5, '茶味醇厚，回甘明显', now() - interval '6 days'),
(17, 10, 4, '坚果新鲜，包装精美', now() - interval '5 days'),
(18, 9, 5, '咖啡香气浓郁，手冲效果好', now() - interval '14 days'),
(22, 8, 4, '缓震不错，跑步很舒服', now() - interval '7 days'),
(26, 6, 5, '手感好，打字声音清脆', now() - interval '12 days'),
(26, 11, 4, '灯效很炫，做工扎实', now() - interval '2 days'),
(27, 7, 5, '色彩准确，Type-C 很方便', now() - interval '3 days'),
(29, 17, 4, '传输速度很快，外观小巧', now() - interval '1 day'),
(30, 9, 5, '功能齐全，续航一周', now() - interval '4 days'),
(30, 18, 4, '运动检测准确，性价比高', now() - interval '2 days'),
(21, 20, 3, '纯棉舒服但容易起球', now() - interval '5 days'),
(24, 19, 4, '复古好看，就是有点硬', now() - interval '6 days'),
(25, 5, 5, '容量大，背着很舒服', now() - interval '4 days'),
(14, 14, 4, '很安静，加湿效果好', now() - interval '7 days')
ON CONFLICT DO NOTHING;

-- Create useful indexes for demo queries
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product ON order_items(product_id);
CREATE INDEX IF NOT EXISTS idx_reviews_product ON reviews(product_id);
CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);

-- Create a view for common analytics query
CREATE OR REPLACE VIEW product_sales_summary AS
SELECT
    p.id AS product_id,
    p.name AS product_name,
    p.category,
    p.price,
    COUNT(DISTINCT oi.order_id) AS order_count,
    SUM(oi.quantity) AS total_sold,
    ROUND(AVG(r.rating), 1) AS avg_rating,
    COUNT(DISTINCT r.id) AS review_count
FROM products p
LEFT JOIN order_items oi ON oi.product_id = p.id
LEFT JOIN reviews r ON r.product_id = p.id
GROUP BY p.id, p.name, p.category, p.price;
```

- [ ] **Step 2: Commit**

```bash
git add deploy/demo/init-demo-db.sql
git commit -m "feat(trial): add demo e-commerce database init script"
```

---

### Task 12: Create demo data setup script

**Files:**
- Create: `deploy/demo/setup-demo.sh`

- [ ] **Step 1: Create the setup script**

Create `deploy/demo/setup-demo.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

# Setup demo tenant and data for trial users.
# Prerequisites: API is running and accessible.
#
# Usage:
#   API_URL=https://api.dbay.cloud:8443 API_KEY=lk_xxx ./deploy/demo/setup-demo.sh
#
# Or for local:
#   API_URL=http://localhost:8090 API_KEY=lk_xxx ./deploy/demo/setup-demo.sh

API_URL="${API_URL:?Set API_URL (e.g. http://localhost:8090)}"
API_KEY="${API_KEY:?Set API_KEY for an admin or the demo tenant}"

echo "=== DBay Demo Data Setup ==="
echo "API: $API_URL"

# 1. Check connectivity
echo ""
echo "--- Checking API connectivity ---"
curl -sf "$API_URL/actuator/health" > /dev/null || { echo "ERROR: API not reachable at $API_URL"; exit 1; }
echo "API is healthy"

# 2. Get tenant info
echo ""
echo "--- Tenant info ---"
TENANT_INFO=$(curl -sf -H "Authorization: Bearer $API_KEY" "$API_URL/api/v1/tenants/me")
TENANT_ID=$(echo "$TENANT_INFO" | jq -r '.id')
TENANT_NAME=$(echo "$TENANT_INFO" | jq -r '.name')
echo "Tenant: $TENANT_NAME ($TENANT_ID)"

# 3. Create demo database (if not exists)
echo ""
echo "--- Creating demo database ---"
DB_RESPONSE=$(curl -sf -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name": "demo-ecommerce"}' \
  "$API_URL/api/v1/databases" 2>/dev/null || echo '{"error":"exists"}')
DB_ID=$(echo "$DB_RESPONSE" | jq -r '.id // empty')
if [ -z "$DB_ID" ]; then
  echo "Database may already exist, listing..."
  DB_ID=$(curl -sf -H "Authorization: Bearer $API_KEY" "$API_URL/api/v1/databases" | jq -r '.[0].id')
fi
echo "Database ID: $DB_ID"

# 4. Wait for database to be ready
echo ""
echo "--- Waiting for database to be ready ---"
for i in $(seq 1 30); do
  STATUS=$(curl -sf -H "Authorization: Bearer $API_KEY" "$API_URL/api/v1/databases/$DB_ID" | jq -r '.status')
  echo "  Status: $STATUS"
  if [ "$STATUS" = "RUNNING" ] || [ "$STATUS" = "SUSPENDED" ]; then
    break
  fi
  sleep 2
done

# 5. Run init SQL via query endpoint
echo ""
echo "--- Loading demo data ---"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SQL_FILE="$SCRIPT_DIR/init-demo-db.sql"

if [ ! -f "$SQL_FILE" ]; then
  echo "ERROR: $SQL_FILE not found"
  exit 1
fi

# Execute SQL statements one by one (split on semicolons, skip empty)
# For simplicity, send the whole script as one query
SQL_CONTENT=$(cat "$SQL_FILE")
RESPONSE=$(curl -sf -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg sql "$SQL_CONTENT" '{sql: $sql}')" \
  "$API_URL/api/v1/databases/$DB_ID/query" 2>&1 || true)
echo "Query response: $(echo "$RESPONSE" | head -c 200)"

# 6. Verify data
echo ""
echo "--- Verifying data ---"
for table in customers products orders order_items reviews; do
  COUNT=$(curl -sf -H "Authorization: Bearer $API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"sql\": \"SELECT COUNT(*) as count FROM $table\"}" \
    "$API_URL/api/v1/databases/$DB_ID/query" | jq -r '.rows[0][0]')
  echo "  $table: $COUNT rows"
done

echo ""
echo "=== Demo setup complete ==="
echo ""
echo "Set this in your deployment config:"
echo "  LAKEON_DEMO_TENANT_ID=$TENANT_ID"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x deploy/demo/setup-demo.sh
```

- [ ] **Step 3: Commit**

```bash
git add deploy/demo/setup-demo.sh deploy/demo/init-demo-db.sql
git commit -m "feat(trial): add demo setup script"
```

---

### Task 13: Update login flow — clear trial state

**Files:**
- Modify: `lakeon-console/src/stores/auth.ts`

- [ ] **Step 1: Clear trial on login**

In `lakeon-console/src/stores/auth.ts`, in the `login()` function, after successful login (after line 21 where `setTenant` is called), add:

```typescript
setTrialState(false)
```

This ensures that when a trial user registers and logs in, the trial state is cleared.

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/stores/auth.ts
git commit -m "feat(trial): clear trial state on login"
```

---

### Task 14: End-to-end verification

- [ ] **Step 1: Verify backend compiles**

```bash
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify frontend builds**

```bash
cd /Users/jacky/code/lakeon/lakeon-console && npx vue-tsc -b --noEmit
```
Expected: No errors

- [ ] **Step 3: Manual test checklist**

1. Start local environment
2. Create a demo tenant manually (register a user `demo`)
3. Set `LAKEON_DEMO_TENANT_ID` to the demo tenant's ID
4. Create a database under demo tenant, run `init-demo-db.sql`
5. Click "立即体验" on landing page
6. Verify: navigates to `/dashboard`, sees demo database
7. Verify: trial banner appears with countdown
8. Verify: "创建数据库" button is disabled
9. Verify: can open SQL editor and run `SELECT * FROM products LIMIT 10`
10. Verify: `INSERT INTO products ...` returns trial_restricted error
11. Verify: clicking "注册" in banner navigates to login page
12. Verify: after registering and logging in, trial banner disappears

- [ ] **Step 4: Commit any fixes**

```bash
git add -A && git commit -m "fix(trial): end-to-end fixes"
```
