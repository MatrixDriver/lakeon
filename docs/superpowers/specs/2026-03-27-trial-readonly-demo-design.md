# Trial Readonly Demo Design

**Date:** 2026-03-27
**Status:** Draft

## Problem

Landing page "立即体验" users currently get an empty database. They see no data, can't experience the product's value, and can submit resource-intensive jobs. We need a guided, read-only demo experience with pre-populated data.

## Solution: Shared Demo Tenant + Trial Read-Only Mode

All trial users share a single pre-populated demo tenant. Trial users can browse and query but cannot create or modify resources.

## Architecture

### Demo Tenant

A special tenant created once during deployment, identified by config:

```yaml
# application.yml
lakeon:
  demo:
    tenant-id: ${LAKEON_DEMO_TENANT_ID:}
```

When `demo.tenant-id` is empty, the trial feature falls back to current behavior (create per-user database).

Demo tenant owns:
- **1 database** — e-commerce schema (`products`, `orders`, `customers`, `reviews`), ~200-500 rows sample data
- **1 knowledge base** — DBay product docs (help, API reference, getting started)
- **1 memory base** — simulated developer assistant memories (facts, episodes, traits)
- **2-3 completed datalake jobs** — viewable logs and results

The demo database follows normal compute lifecycle (auto-suspend after 5min idle, cold start ~8s, hot start ~100ms) so trial users experience real performance characteristics.

### Trial User Flow (Changed)

**Before:**
1. Click "立即体验" → POST `/api/v1/trial`
2. Create trial tenant + create empty database
3. Navigate to database manager

**After:**
1. Click "立即体验" → POST `/api/v1/trial`
2. Create lightweight trial tenant (no database, no compute resources)
3. Navigate to dashboard → see demo tenant's databases, knowledge bases, memory bases, jobs

### Backend Changes

#### 1. TrialService — Stop creating database

```java
// TrialService.createTrial()
// Remove: databaseService.create(tenant, ...)
// Add: result.put("trial", true)
// Add: result.put("demo_tenant_id", demoTenantId)  // if configured
```

Trial tenant is still created for:
- API key authentication
- Expiry tracking and cleanup
- Rate limiting identity

#### 2. TenantResponse — Add trial fields

```java
// TenantResponse.java
private Boolean trial;
@JsonProperty("expires_at")
private Instant expiresAt;
```

`GET /tenants/me` returns these fields so frontend knows the user's trial status.

#### 3. TrialDemoFilter — Redirect reads to demo tenant

New servlet filter (order after `ApiKeyFilter`):

```
Request comes in → ApiKeyFilter resolves trial tenant
→ TrialDemoFilter checks: is tenant.trial == true AND demo-tenant-id configured?
  → YES + GET/read request: replace request attribute tenant with demo tenant
  → YES + write request: return 403 trial_restricted
  → NO: pass through
```

**Read requests** (redirected to demo tenant):
- `GET /api/v1/databases`
- `GET /api/v1/databases/{id}`
- `POST /api/v1/databases/{id}/query` (SELECT only — see SQL filter below)
- `GET /api/v1/databases/{id}/query/history`
- `GET /api/v1/knowledge/bases`
- `GET /api/v1/knowledge/bases/{id}`
- `GET /api/v1/knowledge/bases/{id}/documents`
- `POST /api/v1/knowledge/bases/{id}/search`
- `GET /api/v1/memory/bases`
- `GET /api/v1/memory/bases/{id}`
- `POST /api/v1/memory/bases/{id}/recall`
- `GET /api/v1/memory/browse`
- `GET /api/v1/memory/traits`
- `GET /api/v1/memory/messages`
- `GET /api/v1/memory/stats`
- `GET /api/v1/datalake/jobs`
- `GET /api/v1/datalake/jobs/{id}`
- `GET /api/v1/datalake/datasets`
- `GET /api/v1/tenants/me` (returns trial tenant's own info, NOT demo tenant)
- `GET /api/v1/usage` (returns trial tenant's own usage)

**Write requests** (blocked with 403):
- `POST /api/v1/databases` (create)
- `DELETE /api/v1/databases/{id}` (delete)
- `POST /api/v1/databases/{id}/import` (import)
- `POST /api/v1/databases/{id}/backups` (backup)
- `POST /api/v1/databases/{id}/branches` (branch)
- `PUT /api/v1/databases/{id}/suspend` (suspend)
- `PUT /api/v1/databases/{id}/resume` (resume)
- `POST /api/v1/knowledge/bases` (create KB)
- `DELETE /api/v1/knowledge/bases/{id}` (delete KB)
- `POST /api/v1/knowledge/bases/{id}/documents` (upload doc)
- `POST /api/v1/memory/bases` (create)
- `DELETE /api/v1/memory/bases/{id}` (delete)
- `POST /api/v1/memory/bases/{id}/ingest` (write memory)
- `POST /api/v1/memory/bases/{id}/digest` (process)
- `POST /api/v1/datalake/jobs` (submit job)
- `POST /api/v1/datalake/datasets` (create dataset)
- `POST /api/v1/api-keys` (create API key)
- `DELETE /api/v1/api-keys/{id}` (delete API key)
- `POST /api/v1/account/change-password` (change password)

403 response body:
```json
{
  "error": "trial_restricted",
  "message": "体验模式为只读，注册账号后可使用全部功能"
}
```

#### 4. SQL Write Filter

In `DatabaseQueryService.executeQuery()`, when tenant is trial:

```java
String upper = sql.strip().toUpperCase();
if (!(upper.startsWith("SELECT") || upper.startsWith("EXPLAIN") || upper.startsWith("SHOW") || upper.startsWith("\\d"))) {
    throw new TrialRestrictedException("体验模式仅支持 SELECT 查询");
}
```

This prevents INSERT/UPDATE/DELETE/DROP/CREATE/ALTER/TRUNCATE through the query endpoint.

#### 5. Database ID Validation

When trial user accesses `GET /databases/{id}` or `POST /databases/{id}/query`, the filter has already swapped the tenant to demo. The existing tenant-scoping in services (`WHERE tenant_id = ?`) will naturally only return demo tenant's databases. If a trial user tries to access a non-demo database ID, they get 404 — correct behavior.

### Frontend Changes

#### 1. Auth Store

```typescript
// stores/auth.ts
const isTrial = ref(localStorage.getItem('lakeon_is_trial') === 'true')
const expiresAt = ref(localStorage.getItem('lakeon_expires_at') || '')
```

Set during trial creation and refreshed from `GET /tenants/me`.

#### 2. Trial Banner (ConsoleLayout)

When `isTrial`:
```
┌─────────────────────────────────────────────────────────────────┐
│  体验模式 — 只读演示环境，剩余 XX:XX:XX  │  立即注册，解锁全部功能 →  │
└─────────────────────────────────────────────────────────────────┘
```

- Yellow/amber background, non-dismissable
- Countdown timer from `expires_at`
- "立即注册" links to `/login` registration tab

#### 3. Button Restrictions

For trial users, disable write-action buttons with tooltip:

```vue
<button :disabled="isTrial" :title="isTrial ? '注册后可用' : ''">
  创建数据库
</button>
```

Affected buttons across pages:
- Dashboard: "新建数据库"
- Database detail: "删除", "导入数据", "创建备份", "创建分支"
- Knowledge: "新建知识库", "上传文档"
- Memory: "新建记忆库"
- Datalake: "新建作业", "新建数据集"
- API Key: "创建 Key"
- Account: "修改密码"

#### 4. SQL Editor

Works normally for SELECT queries. When backend returns `trial_restricted` error, show inline message: "体验模式仅支持 SELECT 查询，注册后可执行写操作"

#### 5. Landing Page — Trial Navigation

After trial creation, navigate to `/dashboard` (not `/databases/{id}/manager`), so user sees the full console with demo data listed.

### Demo Data Initialization

#### SQL Script: `deploy/demo/init-demo-db.sql`

```sql
-- E-commerce demo schema
CREATE TABLE IF NOT EXISTS customers (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    city VARCHAR(50),
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE IF NOT EXISTS products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(50),
    price DECIMAL(10,2) NOT NULL,
    stock INTEGER DEFAULT 0,
    description TEXT,
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE IF NOT EXISTS orders (
    id SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customers(id),
    total DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE IF NOT EXISTS order_items (
    id SERIAL PRIMARY KEY,
    order_id INTEGER REFERENCES orders(id),
    product_id INTEGER REFERENCES products(id),
    quantity INTEGER NOT NULL,
    price DECIMAL(10,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS reviews (
    id SERIAL PRIMARY KEY,
    product_id INTEGER REFERENCES products(id),
    customer_id INTEGER REFERENCES customers(id),
    rating INTEGER CHECK (rating BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- Sample data (~200 rows total)
-- Customers (30 rows)
INSERT INTO customers (name, email, city) VALUES
('张三', 'zhangsan@example.com', '北京'),
('李四', 'lisi@example.com', '上海'),
('王五', 'wangwu@example.com', '深圳'),
-- ... (full data in actual script)
;

-- Products (50 rows across categories: 电子产品, 图书, 家居, 食品, 服装)
-- Orders (80 rows with various statuses)
-- Order items (150 rows)
-- Reviews (50 rows)
```

Actual sample data will be generated to be realistic and diverse.

#### Knowledge Base & Memory Base

Populated via API calls or a setup script `deploy/demo/init-demo-data.sh`:
1. Create knowledge base via `POST /knowledge/bases`
2. Upload DBay docs via `POST /knowledge/bases/{id}/documents`
3. Create memory base via `POST /memory/bases`
4. Ingest sample memories via `POST /memory/bases/{id}/ingest`

### Deployment

1. Manually create demo tenant via `POST /tenants` (or direct DB insert)
2. Create demo database (normal flow)
3. Run `init-demo-db.sql` against demo database
4. Run `init-demo-data.sh` for KB + memory
5. Set `LAKEON_DEMO_TENANT_ID` in deployment config
6. Deploy API with new filter code

### Cleanup

- Trial tenant cleanup continues as before (hourly, delete expired)
- Now cheaper: no databases to delete per trial tenant
- Demo tenant is never cleaned up (no `trial=true`, no `expires_at`)

### What Doesn't Change

- Registered user flow — completely unaffected
- Compute lifecycle — demo DB follows normal suspend/resume
- Existing trial cleanup scheduler — still works, just lighter
- Rate limiting — trial users still rate-limited by their own API key
