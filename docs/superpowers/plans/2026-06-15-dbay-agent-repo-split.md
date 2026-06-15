# DBay Agent Repo Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the current monolithic `lakeon` codebase into a Lakebase core repo that powers `dbay.cloud` and a new `dbay-agent` repo for Knowledge, Memory, DataAgent, Datalake, Sources, and higher-level data intelligence products.

**Architecture:** `lakeon` becomes the stable Lakebase substrate: Serverless PostgreSQL, branches, versions, backup/PITR, proxy lifecycle, and LakebaseFS. `dbay-agent` becomes a separate application stack with its own API, Console, Railway project, CCE deployment, and one-way dependency on `dbay.cloud` Lakebase APIs. No shared database schema is allowed across repos; cross-repo calls use explicit API contracts and service tokens.

**Tech Stack:** Java 17 + Spring Boot 3.3.5 for APIs, Vue 3 + TypeScript + Vite for Consoles, Helm/Kubernetes on Huawei Cloud CCE, Railway for frontends, pytest/Vitest/Playwright for verification.

---

## Scope And Guardrails

This split must be staged. Do not delete Knowledge, Memory, DataAgent, Datalake, or Connector runtime code from `lakeon` until the equivalent module runs in `dbay-agent` against production-like Lakebase APIs and E2E tests prove the path works.

The first production result is:

```text
dbay.cloud
  lakeon repo
  Lakebase database + LakebaseFS only in the user-facing Console

agent.dbay.cloud or app.dbay.cloud
  dbay-agent repo
  Knowledge + Memory + DataAgent + Datalake + Sources
  depends on dbay.cloud APIs
```

Dependency direction:

```text
dbay-agent -> dbay.cloud Lakebase API
lakeon     -> no dependency on dbay-agent
```

## Target File Ownership

### Remain In `lakeon`

- `lakeon-api/src/main/java/com/lakeon/controller/**` for database, branch, version, backup, restore, SQL, auth, tenant, LakebaseFS controllers.
- `lakeon-api/src/main/java/com/lakeon/lbfs/**`.
- `lakeon-console/src/views/database/**`.
- `lakeon-console/src/views/lbfs/**`.
- `lakeon-console/src/api/database.ts`, `branch.ts`, `version.ts`, `backup.ts`, `lbfs.ts`, `tenant.ts`, `dbuser.ts`, `audit.ts`, `operation.ts`, `import.ts`.
- `dbay-fuse/**`, `dbay-cli/**` Lakebase/LakebaseFS commands, `dbay-mcp/**` only if it remains Lakebase/FS scoped.
- `deploy/cce/**`, `deploy/helm/lakeon/**` for Lakebase core control/data plane.
- `tests/e2e/test_database*.py`, `test_branch*.py`, `test_version.py`, `test_backup.py`, `test_connection.py`, `test_pitr.py`, `test_lbfs*.py`, and other Lakebase core tests.

### Move To `dbay-agent`

- `lakeon-api/src/main/java/com/lakeon/knowledge/**`.
- `lakeon-api/src/main/java/com/lakeon/memory/**` and related memory service modules.
- `lakeon-api/src/main/java/com/lakeon/agentstate/**`.
- Datalake, Ray, pipeline, notebook, dataset, connector, OBS connection, and DataAgent-specific API modules.
- `lakeon-console/src/views/knowledge/**`.
- `lakeon-console/src/views/memory/**`.
- `lakeon-console/src/views/agent-state/**`.
- `lakeon-console/src/views/datalake/**`.
- `lakeon-console/src/views/connectors/**`.
- `lakeon-console/src/api/knowledge.ts`, `memory.ts`, `agent-state.ts`, `datalake.ts`, `pipeline.ts`, `notebook.ts`, `notebooks.ts`, `connectors.ts`, `obs-connection.ts`.
- `knowledge/**`, `memory/**`, `lakeon-orchestrator/**`, `lakeon-wiki-agent/**`, `mem0-dbay/**`, and other high-level intelligence workers.
- E2E suites for Knowledge, Memory, DataAgent, Datalake, Ray, pipeline, wiki, sharing, and connectors.

### Shared Only By Contract

- Auth: `dbay-agent` calls `lakeon` token validation or tenant introspection API.
- Lakebase operations: `dbay-agent` calls stable Lakebase APIs, not RDS tables.
- Files: `dbay-agent` calls LakebaseFS API or writes to its own managed object storage; it must not import `lakeon` Java repositories directly.
- Types: if TypeScript/Java client SDKs are needed, generate or hand-maintain a small `lakebase-client` package in `dbay-agent`, versioned by API contract.

---

## Task 1: Freeze The Split Boundary In `lakeon`

**Files:**
- Create: `docs/architecture/dbay-agent-repo-split.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Write the architecture boundary document**

Create `docs/architecture/dbay-agent-repo-split.md` with:

```markdown
# DBay Repo Split Boundary

## Lakeon Repo

`lakeon` owns Lakebase Core:

- Serverless PostgreSQL lifecycle
- Neon pageserver / safekeeper / proxy integration
- database / branch / version / backup / PITR
- SQL execution and connection metadata
- LakebaseFS folder / file / sync APIs
- tenant auth needed by Lakebase Core
- Lakebase Console at `dbay.cloud`

`lakeon` must not depend on `dbay-agent`.

## DBay Agent Repo

`dbay-agent` owns:

- DataAgent
- Sources / Connectors
- Knowledge Base
- Memory Base
- Datalake / Ray / Notebook / Pipeline
- Agent-facing Console
- Intelligence workers

`dbay-agent` depends on Lakebase only through stable HTTP APIs at `dbay.cloud`.

## Contract

- No shared RDS schema across repos.
- No importing Java packages across repos.
- No shared Kubernetes namespace.
- Cross-repo communication uses service tokens and documented HTTP APIs.
```

- [ ] **Step 2: Add AGENTS.md note**

Append this section to `AGENTS.md`:

```markdown
## Repo Split Direction

`lakeon` is being narrowed to Lakebase Core: database lifecycle, branch/version, backup/PITR, proxy integration, tenant auth required by Lakebase, and LakebaseFS. Knowledge, Memory, DataAgent, Datalake, Sources/Connectors, Ray, Notebook, and pipeline functionality move to the sibling `dbay-agent` repo and must call Lakebase through stable HTTP APIs instead of sharing DB tables or Java packages.
```

- [ ] **Step 3: Commit**

Run:

```bash
git add AGENTS.md docs/architecture/dbay-agent-repo-split.md
git commit -m "docs(architecture): define dbay agent repo split"
```

Expected: one documentation commit on the current branch.

---

## Task 2: Create The Local `dbay-agent` Repo Skeleton

**Files:**
- Create repo directory: `/Users/jacky/code/dbay-agent`
- Create: `README.md`
- Create: `AGENTS.md`
- Create: `docs/architecture/lakebase-dependency.md`
- Create: `dbay-agent-api/pom.xml`
- Create: `dbay-agent-api/src/main/java/cloud/dbay/agent/DballAgentApplication.java`
- Create: `dbay-agent-api/src/main/java/cloud/dbay/agent/health/HealthController.java`
- Create: `dbay-agent-console/package.json`
- Create: `dbay-agent-console/index.html`
- Create: `dbay-agent-console/src/main.ts`
- Create: `dbay-agent-console/src/App.vue`
- Create: `deploy/helm/dbay-agent/Chart.yaml`
- Create: `deploy/helm/dbay-agent/values.yaml`

- [ ] **Step 1: Initialize repo**

Run:

```bash
cd /Users/jacky/code
mkdir -p dbay-agent
cd dbay-agent
git init
```

Expected: empty Git repo at `/Users/jacky/code/dbay-agent`.

- [ ] **Step 2: Create README**

Create `README.md`:

```markdown
# dbay-agent

DBay Agent contains the data intelligence layer that runs on top of DBay Lakebase:

- DataAgent
- Sources / Connectors
- Knowledge Base
- Memory Base
- Datalake / Ray / Notebook / Pipeline

This repo depends on `dbay.cloud` Lakebase APIs. It must not read Lakebase metadata tables directly.
```

- [ ] **Step 3: Create AGENTS.md**

Create `AGENTS.md`:

```markdown
# DBay Agent Project

## Scope

This repo owns DBay's intelligence layer:

- DataAgent
- Sources / Connectors
- Knowledge Base
- Memory Base
- Datalake / Ray / Notebook / Pipeline

It depends on Lakebase through stable HTTP APIs exposed by `dbay.cloud`.

## Boundaries

- Do not import Java packages from `lakeon`.
- Do not read `lakeon` RDS tables directly.
- Do not deploy into the Lakebase CCE namespace.
- Use service tokens and documented Lakebase APIs for cross-service calls.

## Expected Structure

```text
dbay-agent-api/       Spring Boot API
dbay-agent-console/   Vue 3 Console
deploy/               Helm and cloud deployment
tests/e2e/            API and workflow E2E tests
docs/                 Architecture and API contracts
```
```

- [ ] **Step 4: Create Lakebase dependency doc**

Create `docs/architecture/lakebase-dependency.md`:

```markdown
# Lakebase Dependency

`dbay-agent` treats Lakebase as an external substrate.

## Required Lakebase APIs

- Auth / token introspection
- Tenant lookup
- Database create/list/get/delete
- Schema and table metadata
- SQL execution or connection information
- LakebaseFS folder create/list/get
- LakebaseFS file list/read/write/head
- Usage and quota lookup

## Forbidden Coupling

- Direct SQL reads from Lakebase metadata RDS
- Sharing JPA entities with `lakeon`
- Sharing Kubernetes namespaces
- Importing frontend modules from `lakeon-console`
```

- [ ] **Step 5: Create minimal Spring Boot API**

Create `dbay-agent-api/pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>cloud.dbay</groupId>
  <artifactId>dbay-agent-api</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <java.version>17</java.version>
    <spring-boot.version>3.3.5</spring-boot.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

Create `dbay-agent-api/src/main/java/cloud/dbay/agent/DbayAgentApplication.java`:

```java
package cloud.dbay.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DbayAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbayAgentApplication.class, args);
    }
}
```

Create `dbay-agent-api/src/main/java/cloud/dbay/agent/health/HealthController.java`:

```java
package cloud.dbay.agent.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "dbay-agent-api");
    }
}
```

- [ ] **Step 6: Create minimal Vue Console**

Create `dbay-agent-console/package.json`:

```json
{
  "name": "dbay-agent-console",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite --host 0.0.0.0",
    "build": "vue-tsc -b && vite build",
    "test": "vitest run"
  },
  "dependencies": {
    "@vitejs/plugin-vue": "^5.2.4",
    "typescript": "^5.9.0",
    "vite": "^6.3.5",
    "vue": "^3.5.16",
    "vue-tsc": "^2.2.10"
  },
  "devDependencies": {
    "vitest": "^3.2.4"
  }
}
```

Create `dbay-agent-console/index.html`:

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>DBay Agent</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

Create `dbay-agent-console/src/main.ts`:

```ts
import { createApp } from 'vue'
import App from './App.vue'

createApp(App).mount('#app')
```

Create `dbay-agent-console/src/App.vue`:

```vue
<template>
  <main class="shell">
    <section class="panel">
      <p class="eyebrow">DBay Agent</p>
      <h1>DataAgent 工作台</h1>
      <p>Sources、Knowledge、Memory 和 Datalake 将在这里接入 Lakebase。</p>
    </section>
  </main>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  background: #faf8f5;
  color: #2c3e50;
  display: grid;
  place-items: center;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

.panel {
  width: min(720px, calc(100vw - 48px));
  border: 1px solid #e8e4df;
  background: #fff;
  padding: 32px;
}

.eyebrow {
  color: #9a5b25;
  font-size: 13px;
  font-weight: 700;
}

h1 {
  margin: 0 0 12px;
  font-size: 32px;
}
</style>
```

- [ ] **Step 7: Create Helm placeholder**

Create `deploy/helm/dbay-agent/Chart.yaml`:

```yaml
apiVersion: v2
name: dbay-agent
description: DBay Agent API deployment
type: application
version: 0.1.0
appVersion: "0.1.0"
```

Create `deploy/helm/dbay-agent/values.yaml`:

```yaml
image:
  repository: swr.cn-north-4.myhuaweicloud.com/flex/dbay-agent-api
  tag: "0.1.0"

lakebase:
  apiBaseUrl: "https://api.dbay.cloud:8443/api/v1"

service:
  port: 8080
```

- [ ] **Step 8: Verify skeleton**

Run:

```bash
cd /Users/jacky/code/dbay-agent/dbay-agent-api
./mvnw test
```

If no Maven wrapper exists, run:

```bash
mvn test
```

Expected: build succeeds or reports only missing local Maven installation.

Run:

```bash
cd /Users/jacky/code/dbay-agent/dbay-agent-console
npm install
npm run build
```

Expected: Vue typecheck and Vite build pass.

- [ ] **Step 9: Commit skeleton**

Run:

```bash
cd /Users/jacky/code/dbay-agent
git add .
git commit -m "chore: initialize dbay agent repository"
```

Expected: first commit in `dbay-agent`.

---

## Task 3: Add Lakebase Client Contract To `dbay-agent`

**Files:**
- Create: `dbay-agent-api/src/main/java/cloud/dbay/agent/lakebase/LakebaseProperties.java`
- Create: `dbay-agent-api/src/main/java/cloud/dbay/agent/lakebase/LakebaseClient.java`
- Create: `dbay-agent-api/src/main/java/cloud/dbay/agent/lakebase/LakebaseHealthController.java`
- Create: `dbay-agent-api/src/test/java/cloud/dbay/agent/lakebase/LakebaseClientTest.java`

- [ ] **Step 1: Add properties**

Create `LakebaseProperties.java`:

```java
package cloud.dbay.agent.lakebase;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lakebase")
public record LakebaseProperties(String apiBaseUrl, String serviceToken) {
}
```

- [ ] **Step 2: Add HTTP client**

Create `LakebaseClient.java`:

```java
package cloud.dbay.agent.lakebase;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LakebaseClient {
    private final RestClient restClient;
    private final LakebaseProperties properties;

    public LakebaseClient(RestClient.Builder builder, LakebaseProperties properties) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.apiBaseUrl()).build();
    }

    public Map<?, ?> health() {
        return restClient.get()
                .uri("/../actuator/health")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .body(Map.class);
    }

    private String bearer() {
        if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
            return "";
        }
        return "Bearer " + properties.serviceToken();
    }
}
```

- [ ] **Step 3: Enable configuration properties**

Modify `DbayAgentApplication.java`:

```java
package cloud.dbay.agent;

import cloud.dbay.agent.lakebase.LakebaseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LakebaseProperties.class)
public class DbayAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbayAgentApplication.class, args);
    }
}
```

- [ ] **Step 4: Add controller**

Create `LakebaseHealthController.java`:

```java
package cloud.dbay.agent.lakebase;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LakebaseHealthController {
    private final LakebaseClient client;

    public LakebaseHealthController(LakebaseClient client) {
        this.client = client;
    }

    @GetMapping("/api/v1/lakebase/health")
    public Map<?, ?> lakebaseHealth() {
        return client.health();
    }
}
```

- [ ] **Step 5: Add unit test**

Create `LakebaseClientTest.java`:

```java
package cloud.dbay.agent.lakebase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LakebaseClientTest {
    @Test
    void propertiesHoldApiBaseUrlAndToken() {
        LakebaseProperties props = new LakebaseProperties("https://api.dbay.cloud:8443/api/v1", "token");
        assertThat(props.apiBaseUrl()).isEqualTo("https://api.dbay.cloud:8443/api/v1");
        assertThat(props.serviceToken()).isEqualTo("token");
    }
}
```

- [ ] **Step 6: Run tests and commit**

Run:

```bash
cd /Users/jacky/code/dbay-agent/dbay-agent-api
mvn test
```

Expected: tests pass.

Commit:

```bash
cd /Users/jacky/code/dbay-agent
git add dbay-agent-api
git commit -m "feat(api): add lakebase client boundary"
```

---

## Task 4: Narrow `lakeon-console` First-Level Navigation

**Files:**
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`
- Modify: `lakeon-console/src/__tests__/ConsoleLayout.test.ts`

- [ ] **Step 1: Change expected navigation test**

Modify the test to assert Lakebase-only navigation:

```ts
expect(railLabels).toEqual(['数据', 'FS', '运维'])
expect(wrapper.find('.side-title').text()).toContain('数据')
expect(wrapper.find('.sidebar-nav').text()).toContain('数据库')
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/jacky/code/lakeon/lakeon-console
npm run test -- ConsoleLayout
```

Expected: test fails because the current rail still contains Agent, Knowledge, LakebaseFS, Memory.

- [ ] **Step 3: Update `workspaceModes`**

In `ConsoleLayout.vue`, keep only:

```ts
type WorkspaceMode = {
  id: 'data' | 'lbfs' | 'ops'
  label: string
  shortLabel: string
  icon: 'database' | 'lbfs' | 'ops'
  description: string
  to: string
  match: string[]
  groups: NavGroup[]
}
```

The `data` mode groups:

```ts
{
  id: 'data',
  label: '数据',
  shortLabel: '数据',
  icon: 'database',
  description: 'Lakebase 数据库工作台',
  to: '/dashboard',
  match: ['/dashboard', '/databases', '/timetravel', '/sql', '/import'],
  groups: [
    {
      title: '数据目录',
      items: [
        { label: '数据总览', to: '/dashboard', icon: '▣' },
      ],
    },
    {
      title: '数据库',
      items: [
        { label: '数据库', to: '/dashboard', icon: '▣' },
        { label: '时间旅行', to: '/timetravel', icon: '◷' },
        { label: 'SQL 编辑器', to: '/sql', icon: '<>' },
        { label: '数据迁移', to: '/import', icon: '⇩' },
      ],
    },
  ],
}
```

The `lbfs` mode:

```ts
{
  id: 'lbfs',
  label: 'FS',
  shortLabel: 'FS',
  icon: 'lbfs',
  description: 'LakebaseFS 文件与目录',
  to: '/lbfs',
  match: ['/lbfs'],
  groups: [
    {
      title: '文件系统',
      items: [
        { label: '浏览文件', to: '/lbfs', icon: '□' },
      ],
    },
  ],
}
```

The `ops` mode remains for monitor, logs, usage, recycle bin, API key, and account.

- [ ] **Step 4: Remove top-level intelligence routes from console navigation only**

Do not delete routes yet. Keep `/knowledge`, `/memory`, `/agent-state`, and `/datalake` routes temporarily so old deep links do not hard 404 during migration. Hide them from the `workspaceModes` rail only.

- [ ] **Step 5: Run focused tests**

Run:

```bash
cd /Users/jacky/code/lakeon/lakeon-console
npm run test -- ConsoleLayout
npx vue-tsc -b --noEmit
```

Expected: Console layout test and typecheck pass.

- [ ] **Step 6: Commit**

Run:

```bash
cd /Users/jacky/code/lakeon
git add lakeon-console/src/layouts/ConsoleLayout.vue lakeon-console/src/__tests__/ConsoleLayout.test.ts
git commit -m "refactor(console): narrow lakebase navigation"
```

---

## Task 5: Split E2E Suites Into Lakebase Core And Agent Suites

**Files:**
- Create: `tests/e2e/lakebase_core.txt`
- Create: `tests/e2e/dbay_agent_migration.txt`
- Modify: `tests/e2e/README.md` if it exists; otherwise create it.

- [ ] **Step 1: Create Lakebase core list**

Create `tests/e2e/lakebase_core.txt`:

```text
tests/e2e/test_auth.py
tests/e2e/test_backup.py
tests/e2e/test_branch.py
tests/e2e/test_branch_extended.py
tests/e2e/test_connection.py
tests/e2e/test_database.py
tests/e2e/test_database_extended.py
tests/e2e/test_database_stress.py
tests/e2e/test_lbfs_etag_conflict.py
tests/e2e/test_lbfs_idempotent.py
tests/e2e/test_lbfs_mount_resume.py
tests/e2e/test_lbfs_phase2.py
tests/e2e/test_lbfs_pull.py
tests/e2e/test_lbfs_sync_roundtrip.py
tests/e2e/test_pitr.py
tests/e2e/test_tenant_account.py
tests/e2e/test_version.py
```

- [ ] **Step 2: Create agent migration list**

Create `tests/e2e/dbay_agent_migration.txt`:

```text
tests/e2e/test_datalake.py
tests/e2e/test_dataset.py
tests/e2e/test_dataset_extended.py
tests/e2e/test_derive_idempotent.py
tests/e2e/test_ext_login.py
tests/e2e/test_kb_sharing.py
tests/e2e/test_knowledge.py
tests/e2e/test_memory.py
tests/e2e/test_memory_encrypted.py
tests/e2e/test_pipeline.py
tests/e2e/test_pipeline_text.py
tests/e2e/test_ray.py
tests/e2e/test_ray_jobs.py
tests/e2e/test_wiki.py
tests/e2e/test_wiki_agent.py
tests/e2e/test_wiki_chat.py
```

- [ ] **Step 3: Document commands**

Create or update `tests/e2e/README.md`:

```markdown
# E2E Suites

Lakebase Core tests remain in this repo:

```bash
xargs python3 -m pytest -v -s < tests/e2e/lakebase_core.txt
```

DBay Agent migration tests move to the `dbay-agent` repo:

```bash
cat tests/e2e/dbay_agent_migration.txt
```

Do not mark failing tests as skipped to hide failures. During migration, move each test with the module it validates.
```

- [ ] **Step 4: Run Lakebase core smoke**

Run:

```bash
cd /Users/jacky/code/lakeon
python3 -m pytest tests/e2e/test_auth.py tests/e2e/test_database.py tests/e2e/test_lbfs_idempotent.py -v -s
```

Expected: smoke tests pass or fail with real product issues that must be fixed before production cutover.

- [ ] **Step 5: Commit**

Run:

```bash
git add tests/e2e/lakebase_core.txt tests/e2e/dbay_agent_migration.txt tests/e2e/README.md
git commit -m "test(e2e): split lakebase and agent migration suites"
```

---

## Task 6: Prepare Deployment Separation

**Files:**
- Modify: `deploy/helm/lakeon/values.yaml`
- Create: `deploy/cce/dbay-agent/README.md` in `dbay-agent`
- Create: `deploy/railway/README.md` in `dbay-agent`

- [ ] **Step 1: Add lakeon deploy note**

In `deploy/helm/lakeon/values.yaml`, add comments above `datalake:` and `memory:` blocks:

```yaml
# MIGRATION NOTE:
# Datalake and intelligence workers are moving to the dbay-agent repo.
# Keep these values only until dbay-agent CCE deployment is live and E2E passes.
```

- [ ] **Step 2: Document dbay-agent CCE**

Create `/Users/jacky/code/dbay-agent/deploy/cce/dbay-agent/README.md`:

```markdown
# DBay Agent CCE

This deployment is separate from the Lakebase CCE used by `dbay.cloud`.

## Required Connectivity

- Outbound HTTPS to `https://api.dbay.cloud:8443`
- Access to Huawei Cloud OBS/LTS/AOM as needed by agent workloads
- No access to Lakebase metadata RDS except through Lakebase APIs

## Namespaces

- `dbay-agent`
- `dbay-agent-workers`
```

- [ ] **Step 3: Document Railway project**

Create `/Users/jacky/code/dbay-agent/deploy/railway/README.md`:

```markdown
# Railway

DBay Agent Console deploys from this repo, not from `lakeon`.

Suggested domain:

- `agent.dbay.cloud`

The console talks to the dbay-agent API, which calls Lakebase APIs when it needs database or FS resources.
```

- [ ] **Step 4: Commit in both repos**

Run in `lakeon`:

```bash
git add deploy/helm/lakeon/values.yaml
git commit -m "docs(deploy): mark intelligence workloads for migration"
```

Run in `dbay-agent`:

```bash
git add deploy
git commit -m "docs(deploy): add dbay agent deployment boundaries"
```

---

## Task 7: Production Cutover Checklist

**Files:**
- Create: `docs/verification/dbay-agent-cutover-checklist.md`

- [ ] **Step 1: Create checklist**

Create `docs/verification/dbay-agent-cutover-checklist.md`:

```markdown
# DBay Agent Cutover Checklist

## Before Cutover

- [ ] `lakeon` Lakebase core E2E passes.
- [ ] `dbay-agent` API health endpoint passes.
- [ ] `dbay-agent` can call Lakebase health endpoint.
- [ ] `dbay-agent` can validate a Lakebase tenant token.
- [ ] `dbay-agent` can create/read a LakebaseFS folder through API.
- [ ] `dbay-agent` can create/read a Lakebase database through API.
- [ ] Railway project for DBay Agent Console is live.
- [ ] DBay Agent CCE namespace is live.

## Cutover

- [ ] Hide Knowledge, Memory, DataAgent, Datalake, Sources from `dbay.cloud` Console.
- [ ] Add cross-link from `dbay.cloud` to DBay Agent Console only after DBay Agent login works.
- [ ] Remove intelligence workers from Lakebase CCE after DBay Agent E2E passes.

## After Cutover

- [ ] Run Lakebase core E2E against `dbay.cloud`.
- [ ] Run DBay Agent E2E against DBay Agent API.
- [ ] Verify `dbay.cloud` public Console only exposes Lakebase and FS.
- [ ] Verify DBay Agent Console can use existing Lakebase tenant identity.
```

- [ ] **Step 2: Commit**

Run:

```bash
git add docs/verification/dbay-agent-cutover-checklist.md
git commit -m "docs(verification): add dbay agent cutover checklist"
```

---

## Verification Strategy

Run these after the first implementation phase:

```bash
cd /Users/jacky/code/lakeon/lakeon-console
npm run test -- ConsoleLayout
npx vue-tsc -b --noEmit
```

```bash
cd /Users/jacky/code/lakeon
python3 -m pytest tests/e2e/test_auth.py tests/e2e/test_database.py tests/e2e/test_lbfs_idempotent.py -v -s
```

```bash
cd /Users/jacky/code/dbay-agent/dbay-agent-console
npm install
npm run build
```

```bash
cd /Users/jacky/code/dbay-agent/dbay-agent-api
mvn test
```

Do not run destructive production deployment commands until the local split, contracts, and smoke tests pass.

---

## Self-Review

- Spec coverage: the plan covers repo boundary, new repo skeleton, Lakebase dependency client, Console navigation narrowing, E2E ownership split, deployment separation, and cutover verification.
- Placeholder scan: no "TBD" or unspecified implementation steps remain.
- Type consistency: `DbayAgentApplication`, `LakebaseProperties`, `LakebaseClient`, and `LakebaseHealthController` names are used consistently.
- Scope control: the first implementation phase creates boundaries and skeletons; actual module migration is intentionally deferred until the skeleton can call Lakebase APIs.
