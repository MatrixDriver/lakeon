# Pageserver Dicer Sharding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Lakeon ready for multi-pageserver Lakebase storage serving, starting with three stable pageserver nodes and a tenant-shard placement abstraction that Dicer can later drive.

**Architecture:** DBs are not permanently bound to pageservers. Lakeon treats `(neonTenantId, shardId)` as a storage serving shard with a current placement, epoch, and pageserver endpoint. Phase 1 uses deterministic static placement over three pageserver nodes; later phases replace the decision function with Dicer-backed load-aware assignment while preserving the same Lakeon routing contract.

**Tech Stack:** Spring Boot 3.3.5, Java 17, JPA, Helm, Kubernetes StatefulSet, Neon pageserver HTTP/PG APIs, pytest E2E.

---

## File Structure

- `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
  - Add `lakeon.neon.pageserver-nodes` and nested `PageserverNodeConfig`.
- `lakeon-api/src/main/java/com/lakeon/pageserver/PageserverNode.java`
  - Immutable node descriptor: `id`, `httpUrl`, `pgHost`, `pgPort`.
- `lakeon-api/src/main/java/com/lakeon/pageserver/PageserverPlacement.java`
  - Current serving assignment: `tenantId`, `shardId`, `node`, `epoch`, `source`.
- `lakeon-api/src/main/java/com/lakeon/pageserver/PageserverPlacementService.java`
  - Resolve current tenant-shard placement. Phase 1 is deterministic static placement; future Dicer implementation will plug in here.
- `lakeon-api/src/main/java/com/lakeon/neon/NeonApiClientFactory.java`
  - Cache `NeonApiClient` instances by pageserver HTTP URL.
- `lakeon-api/src/main/java/com/lakeon/neon/RoutedNeonApiClient.java`
  - Route tenant-scoped pageserver calls to the current placement.
- `lakeon-api/src/main/java/com/lakeon/k8s/ComputeSpecBuilder.java`
  - Build `pageserver_connstring` from current placement.
- `deploy/helm/lakeon/templates/statefulset-pageserver.yaml`
  - Replace the pageserver Deployment with a StatefulSet for stable pod DNS.
- `deploy/helm/lakeon/templates/service-pageserver-headless.yaml`
  - Add headless service for `pageserver-0.pageserver-headless...`.
- `deploy/helm/lakeon/templates/service-pageserver.yaml`
  - Keep aggregate service for compatibility and readiness probes.
- `tests/e2e/test_pageserver_placement.py`
  - E2E cases for placement visibility and routing once the admin/debug API is present.

## Phase 1: Static Three-Node Placement

### Task 1: Add placement configuration and value object tests

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pageserver/PageserverNode.java`
- Create: `lakeon-api/src/test/java/com/lakeon/pageserver/PageserverPlacementServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
@Test
void resolvesConfiguredNodesInDeterministicOrder() {
    LakeonProperties props = new LakeonProperties();
    props.getNeon().setPageserverNodes(List.of(
        new LakeonProperties.PageserverNodeConfig("ps-0", "http://pageserver-0:9898", "pageserver-0", 6400),
        new LakeonProperties.PageserverNodeConfig("ps-1", "http://pageserver-1:9898", "pageserver-1", 6400),
        new LakeonProperties.PageserverNodeConfig("ps-2", "http://pageserver-2:9898", "pageserver-2", 6400)
    ));
    PageserverPlacementService service = new PageserverPlacementService(props);

    PageserverPlacement placement = service.resolve("tenant-a", 0);

    assertThat(placement.tenantId()).isEqualTo("tenant-a");
    assertThat(placement.shardId()).isEqualTo(0);
    assertThat(placement.node().id()).startsWith("ps-");
    assertThat(placement.epoch()).isEqualTo(1L);
    assertThat(placement.source()).isEqualTo("static-hash");
}

@Test
void fallsBackToLegacyPageserverUrlWhenNoNodesConfigured() {
    LakeonProperties props = new LakeonProperties();
    props.getNeon().setPageserverUrl("http://pageserver:9898");
    PageserverPlacementService service = new PageserverPlacementService(props);

    PageserverPlacement placement = service.resolve("tenant-a", 0);

    assertThat(placement.node().id()).isEqualTo("default");
    assertThat(placement.node().httpUrl()).isEqualTo("http://pageserver:9898");
    assertThat(placement.node().pgHost()).isEqualTo("pageserver.lakeon.svc.cluster.local");
    assertThat(placement.node().pgPort()).isEqualTo(6400);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd lakeon-api
mvn -Dtest=PageserverPlacementServiceTest test
```

Expected: compile failure because `PageserverPlacementService`, `PageserverPlacement`, `PageserverNode`, and `pageserverNodes` do not exist.

- [ ] **Step 3: Implement minimal config and value objects**

Add `PageserverNodeConfig` and `List<PageserverNodeConfig> pageserverNodes` to `LakeonProperties.NeonConfig`. Implement immutable records for `PageserverNode` and `PageserverPlacement`.

- [ ] **Step 4: Implement static placement**

Use SHA-256 over `tenantId + ":" + shardId`, modulo node count. Fall back to legacy `pageserverUrl` when no nodes are configured.

- [ ] **Step 5: Run tests and verify GREEN**

Run:

```bash
cd lakeon-api
mvn -Dtest=PageserverPlacementServiceTest test
```

Expected: all tests pass.

### Task 2: Route Neon HTTP calls through placement

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java`
- Create: `lakeon-api/src/main/java/com/lakeon/neon/NeonApiClientFactory.java`
- Create: `lakeon-api/src/main/java/com/lakeon/neon/RoutedNeonApiClient.java`
- Modify callers that currently inject `NeonApiClient`.

- [ ] **Step 1: Write failing tests**

Create tests that configure three nodes, call `RoutedNeonApiClient.createTenant("tenant-a")`, and verify the underlying factory returns a client for the expected placement URL.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd lakeon-api
mvn -Dtest=RoutedNeonApiClientTest test
```

Expected: compile failure because `RoutedNeonApiClient` does not exist.

- [ ] **Step 3: Add factory and routed client**

`RoutedNeonApiClient` exposes the same tenant-scoped methods used by Lakeon services:

- `createTenant(String tenantId)`
- `waitForTenantActive(String tenantId, int timeoutSeconds)`
- `createTimeline(String tenantId, CreateTimelineRequest request)`
- `createBranch(String tenantId, NeonApiClient.CreateBranchRequest request)`
- `deleteTenant(String tenantId)`
- `getTimeline(String tenantId, String timelineId)`
- `deleteTimeline(String tenantId, String timelineId)`
- `listTimelines(String tenantId)`
- `listTenants()`
- `getStatus()`
- `getLsnByTimestamp(String tenantId, String timelineId, Instant timestamp)`
- `getTimelineInfo(String tenantId, String timelineId)`

For tenant-scoped methods, resolve placement from `tenantId`; for global methods in Phase 1, use all nodes where appropriate or the first node for compatibility.

- [ ] **Step 4: Update services to inject routed client**

Replace `NeonApiClient` injection in services that operate on tenant/timeline data with `RoutedNeonApiClient`.

- [ ] **Step 5: Run targeted service tests**

Run:

```bash
cd lakeon-api
mvn -Dtest=DatabaseServiceTest,BranchServiceTest,VersionServiceTest,RecoveryServiceTest,ComputeLifecycleServiceTest test
```

Expected: all tests pass.

### Task 3: Compute config uses current placement

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/k8s/ComputeSpecBuilder.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/k8s/ComputeSpecBuilderJwksTest.java`

- [ ] **Step 1: Write failing test**

```java
@Test
void generateComputeConfig_usesTenantPlacementPageserverConnstring() throws Exception {
    LakeonProperties props = newProps();
    props.getNeon().setPageserverNodes(List.of(
        new LakeonProperties.PageserverNodeConfig("ps-0", "http://pageserver-0:9898", "pageserver-0", 6400),
        new LakeonProperties.PageserverNodeConfig("ps-1", "http://pageserver-1:9898", "pageserver-1", 6400),
        new LakeonProperties.PageserverNodeConfig("ps-2", "http://pageserver-2:9898", "pageserver-2", 6400)
    ));
    ObjectMapper om = new ObjectMapper();
    ComputeSpecBuilder builder = new ComputeSpecBuilder(props, om, new PageserverPlacementService(props));
    DatabaseEntity e = new DatabaseEntity();
    e.setId("db_test");
    e.setName("test");
    e.setNeonTenantId("tenant-a");
    e.setNeonTimelineId("timeline-a");

    String json = builder.generateComputeConfig(e, 0);
    String conn = om.readTree(json).path("spec").path("pageserver_connstring").asText();

    assertThat(conn).startsWith("postgresql://pageserver-");
    assertThat(conn).endsWith(":6400");
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
cd lakeon-api
mvn -Dtest=ComputeSpecBuilderJwksTest#generateComputeConfig_usesTenantPlacementPageserverConnstring test
```

Expected: compile failure until constructor and placement-aware connstring are implemented.

- [ ] **Step 3: Implement placement-aware connstring**

Inject `PageserverPlacementService`. For `DatabaseEntity` with `neonTenantId`, use `placementService.resolve(neonTenantId, 0).node().pgConnstring()`. Retain legacy no-tenant fallback.

- [ ] **Step 4: Run tests and verify GREEN**

Run:

```bash
cd lakeon-api
mvn -Dtest=ComputeSpecBuilderJwksTest test
```

Expected: all tests pass.

### Task 4: Helm renders stable 3-node pageserver topology

**Files:**
- Modify: `deploy/helm/lakeon/values.yaml`
- Replace: `deploy/helm/lakeon/templates/deployment-pageserver.yaml`
- Create: `deploy/helm/lakeon/templates/service-pageserver-headless.yaml`
- Modify: `deploy/helm/lakeon/templates/configmap-api.yaml`
- Modify: `deploy/helm/lakeon/templates/configmap-pageserver.yaml`

- [ ] **Step 1: Add Helm render test command**

Run before changes:

```bash
helm template lakeon deploy/helm/lakeon --set pageserver.replicas=3 | rg 'kind: StatefulSet|pageserver-0|LAKEON_NEON_PAGESERVER_NODES'
```

Expected: no StatefulSet and no pageserver node list.

- [ ] **Step 2: Implement Helm changes**

Use StatefulSet `pageserver` with `serviceName: pageserver-headless`, `replicas: 3`, stable pod DNS, and generated `LAKEON_NEON_PAGESERVER_NODES`.

- [ ] **Step 3: Run Helm render verification**

Run:

```bash
helm template lakeon deploy/helm/lakeon --set pageserver.replicas=3 | rg 'kind: StatefulSet|name: pageserver-headless|LAKEON_NEON_PAGESERVER_NODES|pageserver-2.pageserver-headless'
```

Expected: all patterns appear.

## Phase 2: Failover Semantics

### Task 5: Persist assignment epochs outside DB binding

Add a placement state table keyed by `(tenant_id, shard_id)` with `current_node_id`, `epoch`, `status`, and timestamps. This is not a DB binding; it is the current serving assignment. Dicer will later become the source of assignment changes.

E2E:

- `test_pageserver_failure_reassigns_tenant_shard`
- `test_assignment_epoch_prevents_stale_pageserver_serving`
- `test_unaffected_tenants_continue_on_other_pageservers`

## Phase 3: Dicer Control Plane

### Task 6: Add Dicer client and fallback strategy

Add a Dicer-backed placement resolver behind the `PageserverPlacementService` interface. Dicer outage must not break existing DBs; Lakeon uses persisted assignment for known tenant shards.

E2E:

- `test_dicer_assignment_created_for_new_tenant`
- `test_dicer_unavailable_uses_persisted_assignment`
- `test_dicer_assignment_survives_api_restart`
- `test_no_cross_pageserver_tenant_access`

## Phase 4: Load-Aware Rebalance

### Task 7: Add load signals and safe rebalance

Feed pageserver load metrics into Dicer. New tenants avoid hot pageservers. Existing hot/cold shard movement requires safe detach/attach and compute drain or restart.

E2E:

- `test_hot_tenant_generates_load_signal`
- `test_new_tenants_avoid_hot_pageserver`
- `test_rebalance_plan_is_safe_before_execution`
- `test_rebalance_move_preserves_data`
- `test_rebalance_does_not_move_active_compute_without_policy`

## Verification

Run before claiming completion:

```bash
cd lakeon-api
mvn test
cd ..
helm template lakeon deploy/helm/lakeon --set pageserver.replicas=3 >/tmp/lakeon-pageserver-render.yaml
python3 -m pytest tests/e2e --collect-only -q
```

For deployed CCE validation:

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl get pods -n lakeon -l app=pageserver -o wide
python3 -m pytest tests/e2e/test_pageserver_placement.py -v
```
