# DBay Control/Data Plane Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move DBay from a single CCE deployment into a commercial topology with a new control-plane CCE and the existing Neon data-plane CCE, then verify with live DBay E2E tests.

**Architecture:** Create a new control-plane CCE in the same VPC as the existing data-plane CCE. Deploy `lakeon-api x N` in the control-plane CCE for phase 1, keep `proxy`, `compute pods`, `pageserver`, `safekeeper`, `storage-broker`, and `storage-controller` in the data-plane CCE, and keep RDS/OBS/AOM/LTS as shared VPC cloud services.

**Tech Stack:** Huawei Cloud CCE, ELB, RDS, OBS, AOM/Prometheus, LTS, Helm, Spring Boot 3.3.5, Fabric8 Kubernetes client, pytest E2E.

---

## File Structure

- `deploy/cce/sites/hwstaff/site.conf`: split current single kubeconfig into control-plane and data-plane kubeconfigs.
- `deploy/cce/sites/hwstaff/values-control-plane.yaml`: new values file for control-plane CCE.
- `deploy/cce/sites/hwstaff/values-data-plane.yaml`: new values file for data-plane CCE.
- `deploy/cce/deploy-control-plane.sh`: new deployment script for phase-1 `lakeon-api`.
- `deploy/cce/deploy-data-plane.sh`: new deployment script for Neon data-plane services.
- `deploy/cce/status-control-plane.sh`: control-plane status and smoke checks.
- `deploy/cce/status-data-plane.sh`: data-plane status and smoke checks.
- `deploy/helm/lakeon/templates/deployment-api.yaml`: keep a single phase-1 `lakeon-api x N` Deployment in the control-plane CCE.
- `deploy/helm/lakeon/templates/service-api.yaml`: expose phase-1 `lakeon-api` through the control-plane API ELB.
- `deploy/helm/lakeon/templates/hpa-api.yaml`: HPA for phase-1 `lakeon-api`.
- `deploy/helm/lakeon/templates/hpa-proxy.yaml`: HPA for proxy in data-plane CCE.
- `deploy/helm/lakeon/templates/_helpers.tpl`: helpers for enabling control-plane or data-plane template groups.
- `deploy/helm/lakeon/templates/configmap-api.yaml`: split control-plane/data-plane environment variables.
- `deploy/helm/lakeon/templates/rbac.yaml`: add remote data-plane service-account/token objects and narrow RBAC where possible.
- `lakeon-api/src/main/java/com/lakeon/config/KubernetesConfig.java`: support local control-plane client and remote data-plane client.
- `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`: add `lakeon.data-plane.*`.
- `lakeon-api/src/main/resources/application.yml`: add data-plane endpoint/kubeconfig/token properties.
- `lakeon-api/src/test/java/com/lakeon/config/KubernetesConfigTest.java`: verify remote data-plane client configuration.
- `tests/e2e/test_control_data_plane_split.py`: live E2E coverage for API, proxy, DB creation, branch compute, and admin health.

## Task 1: Inventory Current Production State

**Files:**
- Read: `deploy/cce/sites/hwstaff/site.conf`
- Read: `deploy/cce/sites/hwstaff/values.yaml`
- Read: `deploy/cce/status.sh`
- Create: `docs/architecture/dbay-control-data-plane-inventory.md`

- [ ] **Step 1: Capture current cluster and service state**

Run:

```bash
SITE=hwstaff bash deploy/cce/status.sh
kubectl get nodes -o wide
kubectl get deploy,sts,svc,hpa -n lakeon -o wide
kubectl get pods -n lakeon-compute -o wide
```

Expected: current `lakeon-api`, `proxy`, `pageserver`, `safekeeper`, `storage-broker`, and compute pods are visible in the existing data-plane CCE.

- [ ] **Step 2: Document current cloud resource IDs**

Run:

```bash
SITE=hwstaff python3 deploy/cce/hwcloud.py discover
SITE=hwstaff python3 deploy/cce/hwcloud.py list-resources > /tmp/dbay-hwstaff-resources.json
```

Expected: resource JSON includes existing CCE, RDS, ELB, EIP, and node data.

- [ ] **Step 3: Save inventory**

Create `docs/architecture/dbay-control-data-plane-inventory.md` with:

```markdown
# DBay Current Production Inventory

## Existing Data Plane CCE

- Kubeconfig: `~/.kube/cce-lakeon-config`
- Namespace: `lakeon`
- Compute namespace: `lakeon-compute`
- Public API endpoint before migration: `https://api.dbay.cloud:8443`
- Public PG endpoint before migration: `pg.dbay.cloud:4432`

## Existing Workloads

- `lakeon-api`: currently mixed control/data orchestration process
- `proxy`: PG entrypoint
- `pageserver`: Neon page storage/cache process
- `safekeeper`: Neon WAL quorum set
- `storage-broker` / `storage-controller`: Neon storage routing/metadata

## Migration Rule

Do not delete or redeploy existing data-plane services until the new control-plane CCE has passed smoke tests and DBay E2E.
```

- [ ] **Step 4: Commit inventory**

Run:

```bash
git add docs/architecture/dbay-control-data-plane-inventory.md
git commit -m "docs(deploy): inventory dbay production topology"
```

Expected: inventory committed separately from implementation.

## Task 2: Buy/Create the New Control-Plane CCE

**Files:**
- Modify: `deploy/cce/sites/hwstaff/site.conf`
- Create: `deploy/cce/sites/hwstaff/control-plane.env.example`
- Create: `docs/architecture/dbay-control-plane-cce-runbook.md`

- [ ] **Step 1: Create the control-plane CCE in Huawei Cloud**

Use Huawei Cloud console or API to create a new CCE cluster:

```text
Name: dbay-control-plane
Region: cn-north-4
Cluster spec: same CCE cluster type/spec as the existing data-plane CCE
VPC: same VPC as existing data-plane CCE
Subnet: same private subnet family as data-plane CCE
Node pool: same node flavor family as the existing CCE, but only a few nodes
Initial nodes: 2
Autoscaling max nodes: 3
Access: private network enabled; public API access allowed only with master ACL
Add-ons: CoreDNS, metrics-server, autoscaler, AOM/Prometheus integration, LTS collection
```

Expected: a new CCE cluster exists and can reach RDS private IP and data-plane private services.

- [ ] **Step 2: Download kubeconfig**

Run after cluster creation:

```bash
SITE=hwstaff python3 deploy/cce/download_kubeconfig.py --cluster dbay-control-plane --output ~/.kube/cce-dbay-control-plane-config
KUBECONFIG=~/.kube/cce-dbay-control-plane-config kubectl get nodes -o wide
```

Expected: `kubectl get nodes` succeeds against the new control-plane CCE.

- [ ] **Step 3: Update site config**

Modify `deploy/cce/sites/hwstaff/site.conf`:

```bash
# Kubernetes
SITE_CONTROL_KUBECONFIG="$HOME/.kube/cce-dbay-control-plane-config"
SITE_DATA_KUBECONFIG="$HOME/.kube/cce-lakeon-config"
SITE_KUBECONFIG="$SITE_CONTROL_KUBECONFIG"
```

Keep existing `ELB_EIP` and `CCE_EIP` lines unchanged until cutover.

- [ ] **Step 4: Add environment example**

Create `deploy/cce/sites/hwstaff/control-plane.env.example`:

```bash
# Copy values into deploy/cce/sites/hwstaff/.env; do not commit real secrets.
CONTROL_PLANE_CCE_CLUSTER_ID=""
DATA_PLANE_CCE_CLUSTER_ID=""
DATA_PLANE_KUBE_API_SERVER=""
DATA_PLANE_KUBE_TOKEN=""
DATA_PLANE_KUBE_CA_B64=""
RDS_PRIVATE_IP=""
RDS_PASSWORD=""
LOG_DB_DSN=""
CONNECTOR_SECRET_KEY=""
COMPUTE_JWT_PRIVATE_KEY=""
COMPUTE_JWT_PUBLIC_JWK=""
```

- [ ] **Step 5: Commit control-plane config skeleton**

Run:

```bash
git add deploy/cce/sites/hwstaff/site.conf deploy/cce/sites/hwstaff/control-plane.env.example
git commit -m "chore(deploy): add hwstaff control-plane cce config"
```

Expected: no real secrets committed.

## Task 3: Split Helm Values into Control Plane and Data Plane

**Files:**
- Create: `deploy/cce/sites/hwstaff/values-control-plane.yaml`
- Create: `deploy/cce/sites/hwstaff/values-data-plane.yaml`
- Modify: `deploy/helm/lakeon/values.yaml`
- Modify: `deploy/helm/lakeon/templates/_helpers.tpl`

- [ ] **Step 1: Add chart mode values**

Modify `deploy/helm/lakeon/values.yaml`:

```yaml
plane:
  mode: all # all | control | data

dataPlane:
  kubeApiServer: ""
  kubeToken: ""
  kubeCaB64: ""
  namespace: lakeon
  computeNamespace: lakeon-compute
  pageserverUrl: "http://pageserver.lakeon.svc.cluster.local:9898"
  storageBrokerUrl: "http://storage-broker.lakeon.svc.cluster.local:50051"
```

- [ ] **Step 2: Add helper predicates**

Modify `deploy/helm/lakeon/templates/_helpers.tpl`:

```gotemplate
{{- define "lakeon.controlPlaneEnabled" -}}
{{- if or (eq (.Values.plane.mode | default "all") "all") (eq (.Values.plane.mode | default "all") "control") -}}true{{- end -}}
{{- end -}}

{{- define "lakeon.dataPlaneEnabled" -}}
{{- if or (eq (.Values.plane.mode | default "all") "all") (eq (.Values.plane.mode | default "all") "data") -}}true{{- end -}}
{{- end -}}
```

- [ ] **Step 3: Create control-plane values**

Create `deploy/cce/sites/hwstaff/values-control-plane.yaml`:

```yaml
plane:
  mode: control

global:
  imagePullSecrets:
    - name: swr-secret

api:
  replicas: 3
  serviceType: LoadBalancer
  adminToken: "lakeon-sre-2026"
  elb:
    id: "46b20c38-5c54-4781-9f00-d25d8c1717a8"
    class: "performance"
    port: 8443
  ssl:
    enabled: true
  image:
    repository: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-api
    tag: "0.9.246-agentstate-status-cf491a9c"

pageserver:
  replicas: 0
safekeeper:
  replicas: 0
storageBroker:
  replicas: 0
proxy:
  replicas: 0
orchestrator:
  enabled: false
wikiAgent:
  enabled: false
console:
  enabled: false
admin:
  enabled: false
```

- [ ] **Step 4: Create data-plane values**

Create `deploy/cce/sites/hwstaff/values-data-plane.yaml` from current `values.yaml`, then set:

```yaml
plane:
  mode: data

api:
  replicas: 0
  serviceType: ClusterIP

proxy:
  replicas: 2
  externalHost: "pg.dbay.cloud"
  serviceType: LoadBalancer

pageserver:
  replicas: 1

safekeeper:
  replicas: 3

storageBroker:
  replicas: 1
```

- [ ] **Step 5: Render both value sets**

Run:

```bash
helm template lakeon deploy/helm/lakeon -f deploy/cce/sites/hwstaff/values-control-plane.yaml > /tmp/lakeon-control.yaml
helm template lakeon deploy/helm/lakeon -f deploy/cce/sites/hwstaff/values-data-plane.yaml > /tmp/lakeon-data.yaml
```

Expected: control render contains `lakeon-api`; data render contains `proxy`, `pageserver`, `safekeeper`, `storage-broker`, and no control API deployments.

## Task 4: Keep a Single Control-Plane API for Phase 1

**Files:**
- Modify: `deploy/helm/lakeon/templates/deployment-api.yaml`
- Modify: `deploy/helm/lakeon/templates/service-api.yaml`
- Create: `deploy/helm/lakeon/templates/hpa-api.yaml`

- [ ] **Step 1: Gate API deployment to control-plane mode**

Wrap `deployment-api.yaml` and `service-api.yaml` with:

```gotemplate
{{- if include "lakeon.controlPlaneEnabled" . }}
...
{{- end }}
```

Expected: `lakeon-api` renders in `plane.mode=control` and is skipped in `plane.mode=data`.

- [ ] **Step 2: Create API HPA**

Create `hpa-api.yaml`:

```yaml
{{- if include "lakeon.controlPlaneEnabled" . }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: lakeon-api
  namespace: {{ .Values.global.namespace }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: lakeon-api
  minReplicas: 2
  maxReplicas: 8
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
{{- end }}
```

- [ ] **Step 3: Keep control-plane internals unsplit**

Do not create `serving-api`, `admin-api`, or `LAKEON_SERVICE_ROLE` in phase 1. The control-plane CCE runs `lakeon-api x N` with the existing controller surface while the deployment boundary moves out of the data-plane CCE.

- [ ] **Step 4: Render and verify**

Run:

```bash
helm template lakeon deploy/helm/lakeon -f deploy/cce/sites/hwstaff/values-control-plane.yaml | rg "name: lakeon-api|kind: HorizontalPodAutoscaler"
```

Expected: rendered YAML includes `lakeon-api` Deployment, Service, and HPA.

## Task 5: Add Remote Data-Plane Kubernetes Client Support

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/config/KubernetesConfig.java`
- Modify: `lakeon-api/src/main/resources/application.yml`
- Create: `lakeon-api/src/test/java/com/lakeon/config/KubernetesConfigTest.java`

- [ ] **Step 1: Add properties**

Add to `LakeonProperties`:

```java
private DataPlaneConfig dataPlane = new DataPlaneConfig();

public DataPlaneConfig getDataPlane() { return dataPlane; }
public void setDataPlane(DataPlaneConfig dataPlane) { this.dataPlane = dataPlane; }

public static class DataPlaneConfig {
    private String kubeApiServer;
    private String kubeToken;
    private String kubeCaB64;
    private String namespace = "lakeon";
    private String computeNamespace = "lakeon-compute";

    public String getKubeApiServer() { return kubeApiServer; }
    public void setKubeApiServer(String kubeApiServer) { this.kubeApiServer = kubeApiServer; }
    public String getKubeToken() { return kubeToken; }
    public void setKubeToken(String kubeToken) { this.kubeToken = kubeToken; }
    public String getKubeCaB64() { return kubeCaB64; }
    public void setKubeCaB64(String kubeCaB64) { this.kubeCaB64 = kubeCaB64; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getComputeNamespace() { return computeNamespace; }
    public void setComputeNamespace(String computeNamespace) { this.computeNamespace = computeNamespace; }
}
```

- [ ] **Step 2: Add application config**

Add to `application.yml`:

```yaml
  data-plane:
    kube-api-server: ${LAKEON_DATA_PLANE_KUBE_API_SERVER:}
    kube-token: ${LAKEON_DATA_PLANE_KUBE_TOKEN:}
    kube-ca-b64: ${LAKEON_DATA_PLANE_KUBE_CA_B64:}
    namespace: ${LAKEON_DATA_PLANE_NAMESPACE:lakeon}
    compute-namespace: ${LAKEON_DATA_PLANE_COMPUTE_NAMESPACE:lakeon-compute}
```

- [ ] **Step 3: Add named data-plane client bean**

Modify `KubernetesConfig.java`:

```java
@Bean
public KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
}

@Bean("dataPlaneKubernetesClient")
public KubernetesClient dataPlaneKubernetesClient(LakeonProperties props) {
    var dp = props.getDataPlane();
    if (dp.getKubeApiServer() == null || dp.getKubeApiServer().isBlank()) {
        return new KubernetesClientBuilder().build();
    }
    var config = new io.fabric8.kubernetes.client.ConfigBuilder()
        .withMasterUrl(dp.getKubeApiServer())
        .withOauthToken(dp.getKubeToken())
        .withCaCertData(dp.getKubeCaB64())
        .withTrustCerts(false)
        .build();
    return new KubernetesClientBuilder().withConfig(config).build();
}
```

- [ ] **Step 4: Inject data-plane client into compute/data-plane managers**

Update constructors in:

```text
ComputePodManager
ComputeWarmPoolManager
AdminService
DatabaseQueryService
JobPodManager
ImportJobPodManager
Datalake* classes if still deployed from control plane
```

Use:

```java
public ComputePodManager(@Qualifier("dataPlaneKubernetesClient") KubernetesClient k8sClient, ...)
```

Expected: control-plane pods manage compute/data-plane resources through the remote data-plane Kubernetes client.

- [ ] **Step 5: Test remote client config**

Create `KubernetesConfigTest.java` asserting:

```java
@Test
void dataPlaneClientUsesRemoteMasterWhenConfigured() {
    LakeonProperties props = new LakeonProperties();
    props.getDataPlane().setKubeApiServer("https://10.0.0.10:5443");
    props.getDataPlane().setKubeToken("token");
    props.getDataPlane().setKubeCaB64(Base64.getEncoder().encodeToString("ca".getBytes(StandardCharsets.UTF_8)));

    KubernetesClient client = new KubernetesConfig().dataPlaneKubernetesClient(props);

    assertThat(client.getConfiguration().getMasterUrl()).isEqualTo("https://10.0.0.10:5443/");
}
```

Run:

```bash
cd lakeon-api && ./mvnw -Dtest=KubernetesConfigTest test
```

Expected: test passes.

## Task 6: Defer Serving/Admin API Split to Phase 2

**Files:**
- Phase 2 scope: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Phase 2 scope: `lakeon-api/src/main/resources/application.yml`
- Phase 2 scope: Helm deployment templates for `serving-api` and `admin-api`

- [ ] **Step 1: Keep phase 1 scope narrow**

Do not split `lakeon-api` internally during the CCE migration. Phase 1 deploys a single stateless `lakeon-api x N` in the new control-plane CCE and moves data-plane resources out of that cluster.

Expected: production risk stays focused on network, Helm, remote Kubernetes client, and traffic cutover.

- [ ] **Step 2: Record phase 2 boundary**

After phase 1 passes DBay E2E, create a separate phase 2 plan for:

```text
serving-api x N: high-frequency user/Agent API, proxy auth, DB/branch lifecycle
admin-api x N: SRE/admin API, DBay Scale Controller, quota, audit, reconcile
implementation: same artifact first, different Deployment/env role, then split code only if needed
```

Expected: no `LAKEON_SERVICE_ROLE`, `serving-api`, or `admin-api` deployment changes are required for phase 1.

## Task 7: Split Deploy Scripts

**Files:**
- Create: `deploy/cce/deploy-control-plane.sh`
- Create: `deploy/cce/deploy-data-plane.sh`
- Create: `deploy/cce/status-control-plane.sh`
- Create: `deploy/cce/status-data-plane.sh`
- Modify: `deploy/cce/site.sh`

- [ ] **Step 1: Add kubeconfig exports in site loader**

Modify `site.sh`:

```bash
export CONTROL_KUBECONFIG="${SITE_CONTROL_KUBECONFIG:-$SITE_KUBECONFIG}"
export DATA_KUBECONFIG="${SITE_DATA_KUBECONFIG:-$SITE_KUBECONFIG}"
```

- [ ] **Step 2: Create deploy-control-plane.sh**

Create script:

```bash
#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"
export KUBECONFIG="$CONTROL_KUBECONFIG"

helm upgrade --install lakeon-control "$SCRIPT_DIR/../helm/lakeon" \
  -f "$SITE_DIR/values-control-plane.yaml" \
  --set metadataDb.host=$RDS_PRIVATE_IP --set metadataDb.password=$RDS_PASSWORD \
  --set api.logDbDsn="$LOG_DB_DSN" \
  --set api.connectorSecretKey="$CONNECTOR_SECRET_KEY" \
  --set dataPlane.kubeApiServer="$DATA_PLANE_KUBE_API_SERVER" \
  --set dataPlane.kubeToken="$DATA_PLANE_KUBE_TOKEN" \
  --set dataPlane.kubeCaB64="$DATA_PLANE_KUBE_CA_B64" \
  --set-file computeJwt.privateKey=<(printf '%s' "$COMPUTE_JWT_PRIVATE_KEY") \
  --set-file computeJwt.publicJwk=<(printf '%s' "$COMPUTE_JWT_PUBLIC_JWK") \
  -n lakeon --create-namespace --timeout 10m --no-hooks

kubectl rollout status deployment/lakeon-api -n lakeon --timeout=240s
```

- [ ] **Step 3: Create deploy-data-plane.sh**

Create script:

```bash
#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"
export KUBECONFIG="$DATA_KUBECONFIG"

helm upgrade --install lakeon-data "$SCRIPT_DIR/../helm/lakeon" \
  -f "$SITE_DIR/values-data-plane.yaml" \
  --set obs.accessKey=$HWCLOUD_AK --set obs.secretKey=$HWCLOUD_SK \
  --set metadataDb.host=$RDS_PRIVATE_IP --set metadataDb.password=$RDS_PASSWORD \
  --take-ownership \
  -n lakeon --create-namespace --timeout 10m --no-hooks

kubectl rollout status deployment/proxy -n lakeon --timeout=240s
kubectl rollout status deployment/pageserver -n lakeon --timeout=240s
kubectl rollout status statefulset/safekeeper -n lakeon --timeout=240s
kubectl rollout status deployment/storage-broker -n lakeon --timeout=240s
```

- [ ] **Step 4: Add status scripts**

`status-control-plane.sh`:

```bash
#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"
export KUBECONFIG="$CONTROL_KUBECONFIG"
kubectl get deploy,svc,hpa,pods -n lakeon -o wide
```

`status-data-plane.sh`:

```bash
#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"
export KUBECONFIG="$DATA_KUBECONFIG"
kubectl get deploy,sts,svc,hpa,pods -n lakeon -o wide
kubectl get pods -n lakeon-compute -o wide
```

## Task 8: Deploy Data Plane First Without Traffic Cutover

**Files:**
- Use: `deploy/cce/deploy-data-plane.sh`
- Use: `deploy/cce/status-data-plane.sh`

- [ ] **Step 1: Render data-plane manifest**

Run:

```bash
helm template lakeon-data deploy/helm/lakeon -f deploy/cce/sites/hwstaff/values-data-plane.yaml > /tmp/dbay-data-plane.yaml
rg "kind: (Deployment|StatefulSet|Service|HorizontalPodAutoscaler)" /tmp/dbay-data-plane.yaml
```

Expected: only data-plane services are rendered.

- [ ] **Step 2: Deploy to existing data-plane CCE**

Run:

```bash
SITE=hwstaff bash deploy/cce/deploy-data-plane.sh
SITE=hwstaff bash deploy/cce/status-data-plane.sh
```

Expected: `proxy`, `pageserver`, `safekeeper`, `storage-broker`, `storage-controller`, and compute namespace remain healthy.

- [ ] **Step 3: Verify PG endpoint**

Run:

```bash
no_proxy=pg.dbay.cloud nc -z -w5 pg.dbay.cloud 4432
KUBECONFIG="$DATA_KUBECONFIG" kubectl exec -n lakeon deploy/proxy -- sh -c 'echo | timeout 5 openssl s_client -connect localhost:4432 -starttls postgres 2>&1' | rg "Server certificate"
```

Expected: TCP and TLS checks succeed.

## Task 9: Deploy Control Plane to New CCE

**Files:**
- Use: `deploy/cce/deploy-control-plane.sh`
- Use: `deploy/cce/status-control-plane.sh`

- [ ] **Step 1: Verify control-plane can reach RDS**

Run:

```bash
KUBECONFIG="$CONTROL_KUBECONFIG" kubectl run rds-netcheck -n lakeon --rm -it --restart=Never --image=postgres:15-alpine -- \
  sh -c 'pg_isready -h "$RDS_PRIVATE_IP" -p 5432'
```

Expected: RDS accepts private network connection.

- [ ] **Step 2: Verify control-plane can reach data-plane kube API**

Run:

```bash
curl -sk "$DATA_PLANE_KUBE_API_SERVER/version"
```

Expected: Kubernetes version JSON.

- [ ] **Step 3: Deploy control plane**

Run:

```bash
SITE=hwstaff bash deploy/cce/deploy-control-plane.sh
SITE=hwstaff bash deploy/cce/status-control-plane.sh
```

Expected: `lakeon-api x N` is ready in the new control-plane CCE.

- [ ] **Step 4: Verify remote data-plane actions**

Run against the control-plane `lakeon-api` internal endpoint:

```bash
KUBECONFIG="$CONTROL_KUBECONFIG" kubectl port-forward -n lakeon svc/lakeon-api 18088:8088
curl -sk http://localhost:18088/actuator/health
curl -sk -H "Authorization: Bearer lakeon-sre-2026" http://localhost:18088/api/v1/admin/dashboard
```

Expected: health is UP and dashboard returns tenant stats.

## Task 10: Add AOM/LTS Integration Hooks

**Files:**
- Modify: `deploy/helm/lakeon/templates/configmap-fluentbit.yaml`
- Modify: `deploy/helm/lakeon/templates/daemonset-fluentbit.yaml`
- Modify: `lakeon-api/src/main/resources/logback-spring.xml`
- Create: `docs/architecture/dbay-observability-runbook.md`

- [ ] **Step 1: Ensure both CCE clusters ship logs to LTS**

Configure Fluent Bit/LTS collection for:

```text
control-plane CCE: lakeon-api
data-plane CCE: proxy, pageserver, safekeeper, storage-broker, storage-controller, compute pods
```

- [ ] **Step 2: Ensure data-plane metrics are collected by AOM/Prometheus**

Verify these targets are scraped:

```text
proxy metrics port 7000
pageserver /metrics on 9898
safekeeper /metrics on 7676
lakeon-api /actuator/prometheus
compute pod metrics where available
```

- [ ] **Step 3: Document metric names**

Create `docs/architecture/dbay-observability-runbook.md`:

```markdown
# DBay Observability Runbook

## AOM / Prometheus Inputs

- proxy: connections, auth latency, routing failures
- pageserver: cache bytes, tenant states, remote download latency, eviction counters
- safekeeper: WAL write latency, quorum health, upload backlog
- compute pods: CPU, memory, pod phase, ready latency
- lakeon-api: request rate, p95 latency, error rate

## LTS Logs

- lakeon-api application logs
- proxy logs
- pageserver/safekeeper/storage logs
- compute pod logs
```

## Task 11: Cut Public API Traffic to Control Plane

**Files:**
- Modify: `deploy/cce/sites/hwstaff/values-control-plane.yaml`
- Verify: DNS/ELB listener for `api.dbay.cloud`

- [ ] **Step 1: Attach API ELB listener to `lakeon-api`**

Ensure `values-control-plane.yaml` uses the public API ELB and port `8443`.

Run:

```bash
SITE=hwstaff bash deploy/cce/deploy-control-plane.sh
```

Expected: `api.dbay.cloud:8443` routes to `lakeon-api` in the control-plane CCE.

- [ ] **Step 2: Verify API live endpoint**

Run:

```bash
no_proxy=api.dbay.cloud curl -sk -i https://api.dbay.cloud:8443/actuator/health
no_proxy=api.dbay.cloud curl -sk -H "Authorization: Bearer lakeon-sre-2026" https://api.dbay.cloud:8443/api/v1/admin/dashboard
```

Expected: health and dashboard succeed from the new control plane.

- [ ] **Step 3: Keep PG ELB on data-plane CCE**

Run:

```bash
no_proxy=pg.dbay.cloud nc -z -w5 pg.dbay.cloud 4432
```

Expected: PG endpoint still routes to data-plane `proxy`.

## Task 12: Run DBay E2E Tests

**Files:**
- Modify if needed: `tests/e2e/test_control_data_plane_split.py`
- Run existing: `tests/e2e`

- [ ] **Step 1: Add split-topology E2E test**

Create `tests/e2e/test_control_data_plane_split.py`:

```python
import os
import time
import requests

BASE = os.environ["DBAY_ENDPOINT"].rstrip("/")
TOKEN = os.environ["DBAY_ADMIN_TOKEN"]

def headers():
    return {"Authorization": f"Bearer {TOKEN}"}

def test_control_plane_admin_dashboard_live():
    r = requests.get(f"{BASE}/api/v1/admin/dashboard", headers=headers(), timeout=30, verify=False)
    assert r.status_code == 200
    body = r.json()
    assert "tenant_count" in body

def test_data_plane_components_visible_from_control_plane():
    r = requests.get(f"{BASE}/api/v1/admin/health/components", headers=headers(), timeout=30, verify=False)
    assert r.status_code == 200
    body = r.json()
    assert body["pageserver"]["status"] in {"UP", "OK", "HEALTHY"}
    assert body["safekeeper"]["status"] in {"UP", "OK", "HEALTHY"}
    assert body["proxy"]["status"] in {"UP", "OK", "HEALTHY"}
```

- [ ] **Step 2: Run targeted E2E first**

Run:

```bash
no_proxy="api.dbay.cloud,pg.dbay.cloud" \
DBAY_ENDPOINT="https://api.dbay.cloud:8443" \
DBAY_ADMIN_TOKEN="lakeon-sre-2026" \
python3 -m pytest tests/e2e/test_control_data_plane_split.py -v
```

Expected: all tests pass.

- [ ] **Step 3: Run full DBay E2E suite**

Run:

```bash
no_proxy="api.dbay.cloud,pg.dbay.cloud" \
DBAY_ENDPOINT="https://api.dbay.cloud:8443" \
DBAY_ADMIN_TOKEN="lakeon-sre-2026" \
python3 -m pytest tests/e2e -v --tb=short
```

Expected: all DBay E2E tests pass. Any `FAILED` result must be fixed; do not mark failures as skipped.

- [ ] **Step 4: Verify live DB workflow**

Run existing API/CLI workflow that covers:

```text
tenant creation
database creation
main branch compute wake
SQL query through pg.dbay.cloud
branch creation
branch compute wake
cleanup
```

Expected: user-facing DB workflow works through control-plane API and data-plane PG proxy.

## Task 13: Production Rollback Plan

**Files:**
- Create: `docs/architecture/dbay-control-data-plane-rollback.md`

- [ ] **Step 1: Write rollback doc**

Create:

```markdown
# DBay Control/Data Plane Rollback

## Trigger

Rollback if API live health fails, PG proxy fails, compute creation fails, or DBay E2E has any unresolved failure.

## Rollback

1. Point `api.dbay.cloud:8443` back to the existing single-cluster `lakeon-api` Service.
2. Keep `pg.dbay.cloud` on existing data-plane `proxy`.
3. Scale down control-plane `lakeon-api` to 0.
4. Redeploy existing single-cluster values:
   `SITE=hwstaff bash deploy/cce/deploy.sh --skip-test`
5. Run:
   `SITE=hwstaff bash deploy/cce/smoke-test.sh`
6. Run DBay E2E:
   `DBAY_ENDPOINT=https://api.dbay.cloud:8443 DBAY_ADMIN_TOKEN=lakeon-sre-2026 python3 -m pytest tests/e2e -v --tb=short`
```

- [ ] **Step 2: Commit rollback plan**

Run:

```bash
git add docs/architecture/dbay-control-data-plane-rollback.md
git commit -m "docs(deploy): add dbay split rollback plan"
```

## Final Verification

- [ ] `helm template` passes for control-plane values.
- [ ] `helm template` passes for data-plane values.
- [ ] `lakeon-api x N` ready in new control-plane CCE.
- [ ] Existing data-plane CCE keeps `proxy`, `pageserver`, `safekeeper`, `storage-broker`, `storage-controller` healthy.
- [ ] `api.dbay.cloud:8443` serves from control-plane CCE.
- [ ] `pg.dbay.cloud:4432` serves from data-plane CCE.
- [ ] AOM receives data-plane metrics and control-plane can read them.
- [ ] LTS receives control-plane and data-plane logs.
- [ ] Full DBay E2E suite passes with zero failures.
