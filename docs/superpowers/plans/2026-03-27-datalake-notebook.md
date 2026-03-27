# Datalake Notebook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an interactive Python notebook to the DBay Console where users can execute code cell-by-cell against real datasets, then submit as a formal datalake job.

**Architecture:** Three layers — (1) `repl_server.py` runs in a K8s pod maintaining persistent Python exec context, communicates via JSON lines on stdin/stdout; (2) Spring Boot WebSocket endpoint bridges browser to pod via K8s exec API, manages session lifecycle in DB; (3) Vue 3 notebook page with CodeMirror cells, output rendering for text/DataFrame/Plotly charts.

**Tech Stack:** Spring Boot 3.3 + spring-websocket, Fabric8 K8s client exec API, Vue 3 + TypeScript + CodeMirror 6 + plotly.js, Python 3.11 (exec-based REPL)

---

## File Structure

### Backend (lakeon-api)
- **Modify:** `pom.xml` — add `spring-boot-starter-websocket` dependency
- **Create:** `src/main/resources/db/migration/V22__create_notebook_sessions.sql` — migration
- **Create:** `src/main/java/com/lakeon/notebook/NotebookSessionEntity.java` — JPA entity
- **Create:** `src/main/java/com/lakeon/notebook/NotebookSessionRepository.java` — repository
- **Create:** `src/main/java/com/lakeon/notebook/NotebookSessionStatus.java` — enum
- **Create:** `src/main/java/com/lakeon/notebook/NotebookService.java` — session lifecycle + pod management
- **Create:** `src/main/java/com/lakeon/notebook/NotebookWebSocketHandler.java` — WS handler + K8s exec bridge
- **Create:** `src/main/java/com/lakeon/notebook/NotebookWebSocketConfig.java` — Spring WS config
- **Create:** `src/main/java/com/lakeon/notebook/NotebookController.java` — REST endpoints for session CRUD
- **Create:** `src/main/resources/repl_server.py` — Python REPL script (injected as ConfigMap)

### Frontend (lakeon-console)
- **Create:** `src/views/datalake/DatalakeNotebook.vue` — main notebook page
- **Create:** `src/views/datalake/components/NotebookCell.vue` — individual cell component
- **Create:** `src/api/notebook.ts` — API + WebSocket client
- **Modify:** `src/router/index.ts` — add `/datalake/notebook` route

---

### Task 1: Database Migration + Entity

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V22__create_notebook_sessions.sql`
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionRepository.java`

- [ ] **Step 1: Create the migration**

Create `lakeon-api/src/main/resources/db/migration/V22__create_notebook_sessions.sql`:

```sql
CREATE TABLE notebook_sessions (
    id             VARCHAR(64) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'STARTING',
    pod_name       VARCHAR(128),
    namespace      VARCHAR(128),
    image          VARCHAR(256),
    dataset_ids    TEXT,
    last_active_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notebook_sessions_tenant ON notebook_sessions(tenant_id);
CREATE INDEX idx_notebook_sessions_status ON notebook_sessions(status);
```

- [ ] **Step 2: Create the status enum**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionStatus.java`:

```java
package com.lakeon.notebook;

public enum NotebookSessionStatus {
    STARTING, RUNNING, STOPPING, STOPPED
}
```

- [ ] **Step 3: Create the entity**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionEntity.java`:

```java
package com.lakeon.notebook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notebook_sessions")
public class NotebookSessionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotebookSessionStatus status;

    @Column(name = "pod_name", length = 128)
    private String podName;

    @Column(length = 128)
    private String namespace;

    @Column(length = 256)
    private String image;

    @Column(name = "dataset_ids", columnDefinition = "text")
    private String datasetIds;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = "nbs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        lastActiveAt = now;
    }

    @PreUpdate
    public void preUpdate() { updatedAt = Instant.now(); }

    // --- getters and setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public NotebookSessionStatus getStatus() { return status; }
    public void setStatus(NotebookSessionStatus status) { this.status = status; }
    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getDatasetIds() { return datasetIds; }
    public void setDatasetIds(String datasetIds) { this.datasetIds = datasetIds; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 4: Create the repository**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionRepository.java`:

```java
package com.lakeon.notebook;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotebookSessionRepository extends JpaRepository<NotebookSessionEntity, String> {
    Optional<NotebookSessionEntity> findByTenantIdAndStatus(String tenantId, NotebookSessionStatus status);
    List<NotebookSessionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<NotebookSessionEntity> findByStatusAndLastActiveAtBefore(NotebookSessionStatus status, Instant cutoff);
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V22__create_notebook_sessions.sql \
        lakeon-api/src/main/java/com/lakeon/notebook/
git commit -m "feat(notebook): add NotebookSession entity + migration"
```

---

### Task 2: REPL Server Python Script

**Files:**
- Create: `lakeon-api/src/main/resources/repl_server.py`

- [ ] **Step 1: Create repl_server.py**

Create `lakeon-api/src/main/resources/repl_server.py`:

```python
#!/usr/bin/env python3
"""
DBay Notebook REPL Server
Reads JSON commands from stdin, executes Python code, writes JSON results to stdout.
Maintains a persistent globals dict across all executions.
"""
import sys
import json
import time
import traceback
import io
import contextlib
import signal
import ast

# Persistent execution context
_globals = {"__builtins__": __builtins__}
_exec_counter = 0

# Plotly monkey-patch: capture fig.show() calls
_plotly_figures = []

def _patch_plotly():
    try:
        import plotly.io as pio
        _original_show = pio.show
        def _patched_show(fig, *args, **kwargs):
            _plotly_figures.append(fig.to_dict())
        pio.show = _patched_show
    except ImportError:
        pass

# matplotlib monkey-patch: capture plt.show() as base64 PNG
_mpl_images = []

def _patch_matplotlib():
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        _original_show = plt.show
        def _patched_show(*args, **kwargs):
            import base64
            buf = io.BytesIO()
            plt.savefig(buf, format="png", bbox_inches="tight", dpi=100)
            buf.seek(0)
            _mpl_images.append(base64.b64encode(buf.read()).decode())
            plt.close("all")
        plt.show = _patched_show
    except ImportError:
        pass

_patch_plotly()
_patch_matplotlib()

def _emit(msg):
    """Write a JSON line to stdout and flush."""
    sys.stdout.write(json.dumps(msg, ensure_ascii=False, default=str) + "\n")
    sys.stdout.flush()

def _get_last_expr(code):
    """If the last statement is an expression, return (code_without_last, last_expr_code).
    Otherwise return (code, None)."""
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return code, None
    if not tree.body:
        return code, None
    last = tree.body[-1]
    if isinstance(last, ast.Expr):
        # Remove last expression from code, return it separately
        lines = code.split("\n")
        last_line = last.lineno - 1
        code_before = "\n".join(lines[:last_line])
        expr_code = "\n".join(lines[last_line:last.end_lineno])
        return code_before, expr_code
    return code, None

def _execute(req_id, code):
    global _exec_counter
    _exec_counter += 1
    _plotly_figures.clear()
    _mpl_images.clear()
    start = time.time()

    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()

    try:
        code_body, expr_code = _get_last_expr(code)

        # Execute body (statements)
        with contextlib.redirect_stdout(stdout_buf), contextlib.redirect_stderr(stderr_buf):
            if code_body.strip():
                exec(code_body, _globals)

        # Evaluate last expression for result display
        result_val = None
        if expr_code and expr_code.strip():
            with contextlib.redirect_stdout(stdout_buf), contextlib.redirect_stderr(stderr_buf):
                result_val = eval(expr_code.strip(), _globals)
            # Store as _ (like Python REPL)
            _globals["_"] = result_val

        # Emit captured stdout
        out_text = stdout_buf.getvalue()
        if out_text:
            _emit({"id": req_id, "type": "stdout", "text": out_text})

        # Emit captured stderr
        err_text = stderr_buf.getvalue()
        if err_text:
            _emit({"id": req_id, "type": "stderr", "text": err_text})

        # Emit plotly figures
        for fig_dict in _plotly_figures:
            _emit({"id": req_id, "type": "plotly", "data": fig_dict})

        # Emit matplotlib images
        for img_b64 in _mpl_images:
            _emit({"id": req_id, "type": "image", "data": img_b64, "mime": "image/png"})

        # Emit result (last expression value)
        if result_val is not None:
            result_msg = {"id": req_id, "type": "result", "text": repr(result_val)}
            # DataFrame: include HTML rendering
            try:
                import pandas as pd
                if isinstance(result_val, (pd.DataFrame, pd.Series)):
                    result_msg["html"] = result_val.to_html(max_rows=50)
                    result_msg["text"] = result_val.to_string(max_rows=20)
            except ImportError:
                pass
            _emit(result_msg)

    except Exception:
        tb = traceback.format_exc()
        _emit({"id": req_id, "type": "error", "traceback": tb})

    elapsed_ms = int((time.time() - start) * 1000)
    _emit({"id": req_id, "type": "done", "duration_ms": elapsed_ms, "exec_count": _exec_counter})

def _handle_timeout(signum, frame):
    raise TimeoutError("Cell execution timed out (60s)")

def main():
    signal.signal(signal.SIGALRM, _handle_timeout)
    _emit({"type": "ready", "version": "1.0"})

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue

        req_type = req.get("type")
        req_id = req.get("id", "?")

        if req_type == "execute":
            code = req.get("code", "")
            timeout = req.get("timeout", 60)
            signal.alarm(timeout)
            try:
                _execute(req_id, code)
            except TimeoutError:
                _emit({"id": req_id, "type": "error", "traceback": "TimeoutError: Cell execution timed out (60s)"})
                _emit({"id": req_id, "type": "done", "duration_ms": timeout * 1000, "exec_count": _exec_counter})
            finally:
                signal.alarm(0)

        elif req_type == "status":
            _emit({"id": req_id, "type": "status", "exec_count": _exec_counter})

        elif req_type == "reset":
            _globals.clear()
            _globals["__builtins__"] = __builtins__
            _exec_counter = 0
            _patch_plotly()
            _patch_matplotlib()
            _emit({"id": req_id, "type": "reset_done"})

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/resources/repl_server.py
git commit -m "feat(notebook): add repl_server.py REPL script"
```

---

### Task 3: NotebookService — Session Lifecycle + Pod Management

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java`

- [ ] **Step 1: Add spring-websocket dependency to pom.xml**

In `lakeon-api/pom.xml`, add in the `<dependencies>` section:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

- [ ] **Step 2: Create NotebookService**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java`:

```java
package com.lakeon.notebook;

import com.lakeon.config.LakeonProperties;
import com.lakeon.datalake.DatalakeNamespaceManager;
import com.lakeon.dataset.DatasetEntity;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.dataset.DatasetStatus;
import com.lakeon.obs.ObsStsService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class NotebookService {
    private static final Logger log = LoggerFactory.getLogger(NotebookService.class);
    private static final int IDLE_TIMEOUT_MINUTES = 30;

    private final NotebookSessionRepository repository;
    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final DatalakeNamespaceManager nsManager;
    private final ObsStsService obsStsService;
    private final DatasetRepository datasetRepository;

    public NotebookService(NotebookSessionRepository repository,
                           KubernetesClient k8sClient,
                           LakeonProperties props,
                           DatalakeNamespaceManager nsManager,
                           ObsStsService obsStsService,
                           DatasetRepository datasetRepository) {
        this.repository = repository;
        this.k8sClient = k8sClient;
        this.props = props;
        this.nsManager = nsManager;
        this.obsStsService = obsStsService;
        this.datasetRepository = datasetRepository;
    }

    /**
     * Get or create a notebook session for the tenant. Max 1 active session per tenant.
     */
    public NotebookSessionEntity getOrCreateSession(String tenantId, String imageKey, List<String> datasetIds) {
        // Reuse existing RUNNING session
        Optional<NotebookSessionEntity> existing = repository.findByTenantIdAndStatus(tenantId, NotebookSessionStatus.RUNNING);
        if (existing.isPresent()) {
            NotebookSessionEntity session = existing.get();
            session.setLastActiveAt(Instant.now());
            return repository.save(session);
        }

        // Also check STARTING (pod still being created)
        existing = repository.findByTenantIdAndStatus(tenantId, NotebookSessionStatus.STARTING);
        if (existing.isPresent()) return existing.get();

        // Create new session
        String ns = nsManager.ensureNamespace(tenantId);
        String image = resolveImage(imageKey);

        NotebookSessionEntity session = new NotebookSessionEntity();
        session.setTenantId(tenantId);
        session.setStatus(NotebookSessionStatus.STARTING);
        session.setNamespace(ns);
        session.setImage(image);
        session.setDatasetIds(datasetIds != null ? String.join(",", datasetIds) : null);
        session = repository.save(session);

        String podName = "notebook-" + session.getId().replace("_", "-");
        session.setPodName(podName);

        try {
            createReplPod(session, tenantId, datasetIds);
            session.setStatus(NotebookSessionStatus.RUNNING);
        } catch (Exception e) {
            log.error("Failed to create notebook pod for tenant {}: {}", tenantId, e.getMessage());
            session.setStatus(NotebookSessionStatus.STOPPED);
        }
        return repository.save(session);
    }

    public Optional<NotebookSessionEntity> getSession(String tenantId) {
        return repository.findByTenantIdAndStatus(tenantId, NotebookSessionStatus.RUNNING)
                .or(() -> repository.findByTenantIdAndStatus(tenantId, NotebookSessionStatus.STARTING));
    }

    public List<NotebookSessionEntity> listSessions(String tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public void stopSession(String tenantId, String sessionId) {
        NotebookSessionEntity session = repository.findById(sessionId).orElse(null);
        if (session == null || !session.getTenantId().equals(tenantId)) return;
        if (session.getStatus() == NotebookSessionStatus.STOPPED) return;

        session.setStatus(NotebookSessionStatus.STOPPING);
        repository.save(session);

        try {
            if (session.getPodName() != null && session.getNamespace() != null) {
                // Delete pod
                k8sClient.pods().inNamespace(session.getNamespace())
                        .withName(session.getPodName()).delete();
                // Delete ConfigMap
                k8sClient.configMaps().inNamespace(session.getNamespace())
                        .withName(session.getPodName() + "-repl").delete();
            }
        } catch (Exception e) {
            log.warn("Failed to delete notebook pod {}: {}", session.getPodName(), e.getMessage());
        }

        session.setStatus(NotebookSessionStatus.STOPPED);
        repository.save(session);
    }

    public void touchSession(String sessionId) {
        repository.findById(sessionId).ifPresent(s -> {
            s.setLastActiveAt(Instant.now());
            repository.save(s);
        });
    }

    /**
     * Idle timeout: stop sessions inactive for 30 minutes.
     */
    @Scheduled(fixedRate = 60000) // check every minute
    public void reapIdleSessions() {
        Instant cutoff = Instant.now().minusSeconds(IDLE_TIMEOUT_MINUTES * 60L);
        List<NotebookSessionEntity> idle = repository.findByStatusAndLastActiveAtBefore(
                NotebookSessionStatus.RUNNING, cutoff);
        for (NotebookSessionEntity session : idle) {
            log.info("Reaping idle notebook session {} for tenant {}", session.getId(), session.getTenantId());
            stopSession(session.getTenantId(), session.getId());
        }
    }

    private void createReplPod(NotebookSessionEntity session, String tenantId, List<String> datasetIds) {
        String ns = session.getNamespace();
        String podName = session.getPodName();
        String image = session.getImage();

        // Load repl_server.py from classpath
        String replScript = loadReplScript();

        // Create ConfigMap with repl_server.py
        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(podName + "-repl")
                    .withNamespace(ns)
                .endMetadata()
                .addToData("repl_server.py", replScript)
                .build();
        k8sClient.configMaps().inNamespace(ns).resource(cm).createOrReplace();

        // Build env vars
        List<EnvVar> envVars = new ArrayList<>();
        ObsStsService.StsCredentials creds = obsStsService.getCredentials(tenantId);
        envVars.add(new EnvVarBuilder().withName("OBS_ACCESS_KEY_ID").withValue(creds.accessKey()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_SECRET_ACCESS_KEY").withValue(creds.secretKey()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_SECURITY_TOKEN").withValue(creds.sessionToken()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_ENDPOINT").withValue(props.getObs().getEndpoint()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_BUCKET").withValue(props.getObs().getBucket()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_REGION").withValue(
                props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4").build());

        // Dataset paths
        if (datasetIds != null) {
            String bucket = props.getObs().getBucket();
            boolean single = datasetIds.size() == 1;
            for (String dsId : datasetIds) {
                DatasetEntity ds = datasetRepository.findByIdAndTenantId(dsId, tenantId).orElse(null);
                if (ds != null && ds.getStatus() == DatasetStatus.READY && ds.getObsPath() != null) {
                    String obsUri = "obs://" + bucket + "/" + ds.getObsPath();
                    String safeName = ds.getName().replaceAll("\\s+", "_").toLowerCase();
                    envVars.add(new EnvVarBuilder().withName("DATASET_PATH_" + safeName).withValue(obsUri).build());
                    if (single) {
                        envVars.add(new EnvVarBuilder().withName("DATASET_PATH").withValue(obsUri).build());
                    }
                }
            }
        }

        // VK nodeSelector + tolerations
        Map<String, String> nodeSelector = Map.of(
                props.getDatalake().getVkNodeSelectorKey(),
                props.getDatalake().getVkNodeSelectorValue());

        List<Toleration> tolerations = List.of(new TolerationBuilder()
                .withKey("virtual-kubelet.io/provider")
                .withOperator("Exists")
                .withEffect("NoSchedule")
                .build());

        List<LocalObjectReference> imagePullSecrets = props.getK8s().getImagePullSecrets().stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> new LocalObjectReferenceBuilder().withName(n).build())
                .toList();

        // Build Pod
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withNamespace(ns)
                    .addToLabels("app", "notebook")
                    .addToLabels("lakeon.io/tenant-id", tenantId)
                    .addToLabels("lakeon.io/session-id", session.getId())
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .withNodeSelector(nodeSelector)
                    .withTolerations(tolerations)
                    .withImagePullSecrets(imagePullSecrets)
                    .addNewContainer()
                        .withName("repl")
                        .withImage(image)
                        .withCommand("python", "/app/repl_server.py")
                        .withStdin(true)
                        .withStdinOnce(false)
                        .withTty(false)
                        .withEnv(envVars)
                        .withNewResources()
                            .addToRequests("cpu", new io.fabric8.kubernetes.api.model.Quantity("500m"))
                            .addToRequests("memory", new io.fabric8.kubernetes.api.model.Quantity("2Gi"))
                            .addToLimits("cpu", new io.fabric8.kubernetes.api.model.Quantity("2"))
                            .addToLimits("memory", new io.fabric8.kubernetes.api.model.Quantity("4Gi"))
                        .endResources()
                        .addNewVolumeMount()
                            .withName("repl-script")
                            .withMountPath("/app/repl_server.py")
                            .withSubPath("repl_server.py")
                            .withReadOnly(true)
                        .endVolumeMount()
                    .endContainer()
                    .addNewVolume()
                        .withName("repl-script")
                        .withNewConfigMap()
                            .withName(podName + "-repl")
                        .endConfigMap()
                    .endVolume()
                .endSpec()
                .build();

        k8sClient.pods().inNamespace(ns).resource(pod).create();
        log.info("Created notebook pod {}/{}", ns, podName);
    }

    private String resolveImage(String imageKey) {
        if (imageKey == null || imageKey.isBlank()) imageKey = "python-data";
        Map<String, String> presets = props.getDatalake().getPresetImages();
        return presets.getOrDefault(imageKey,
                presets.getOrDefault("python-data", "python:3.11-slim"));
    }

    private String loadReplScript() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("repl_server.py")) {
            if (is == null) throw new IllegalStateException("repl_server.py not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load repl_server.py", e);
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/pom.xml \
        lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java
git commit -m "feat(notebook): add NotebookService with session lifecycle + pod management"
```

---

### Task 4: REST Controller for Session Management

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookController.java`

- [ ] **Step 1: Create NotebookController**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookController.java`:

```java
package com.lakeon.notebook;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/datalake/notebook")
public class NotebookController {

    private final NotebookService notebookService;

    public NotebookController(NotebookService notebookService) {
        this.notebookService = notebookService;
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(HttpServletRequest req,
                                              @RequestBody(required = false) Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        String imageKey = body != null ? (String) body.get("image") : null;
        @SuppressWarnings("unchecked")
        List<String> datasetIds = body != null ? (List<String>) body.get("dataset_ids") : null;

        NotebookSessionEntity session = notebookService.getOrCreateSession(tenant.getId(), imageKey, datasetIds);
        return sessionToMap(session);
    }

    @GetMapping("/sessions/current")
    public Map<String, Object> getCurrentSession(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        NotebookSessionEntity session = notebookService.getSession(tenant.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active session"));
        return sessionToMap(session);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> stopSession(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        notebookService.stopSession(tenant.getId(), id);
        return Map.of("stopped", id);
    }

    private Map<String, Object> sessionToMap(NotebookSessionEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("status", s.getStatus().name());
        m.put("pod_name", s.getPodName());
        m.put("image", s.getImage());
        m.put("dataset_ids", s.getDatasetIds());
        m.put("last_active_at", s.getLastActiveAt() != null ? s.getLastActiveAt().toString() : null);
        m.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        return m;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/notebook/NotebookController.java
git commit -m "feat(notebook): add REST controller for session create/get/stop"
```

---

### Task 5: WebSocket Handler + K8s Exec Bridge

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookWebSocketConfig.java`
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookWebSocketHandler.java`

- [ ] **Step 1: Create WebSocket config**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookWebSocketConfig.java`:

```java
package com.lakeon.notebook;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class NotebookWebSocketConfig implements WebSocketConfigurer {

    private final NotebookWebSocketHandler handler;

    public NotebookWebSocketConfig(NotebookWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/datalake/notebook/ws")
                .setAllowedOrigins("*");
    }
}
```

- [ ] **Step 2: Create WebSocket handler**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookWebSocketHandler.java`:

```java
package com.lakeon.notebook;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NotebookWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(NotebookWebSocketHandler.class);

    private final TenantRepository tenantRepository;
    private final NotebookService notebookService;
    private final KubernetesClient k8sClient;

    // Track active exec connections per WS session
    private final Map<String, ExecConnection> execConnections = new ConcurrentHashMap<>();

    public NotebookWebSocketHandler(TenantRepository tenantRepository,
                                     NotebookService notebookService,
                                     KubernetesClient k8sClient) {
        this.tenantRepository = tenantRepository;
        this.notebookService = notebookService;
        this.k8sClient = k8sClient;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        // Authenticate via query param ?token=lk_...
        String token = UriComponentsBuilder.fromUri(wsSession.getUri()).build()
                .getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            wsSession.close(CloseStatus.POLICY_VIOLATION.withReason("Missing token"));
            return;
        }

        TenantEntity tenant = tenantRepository.findByApiKey(token).orElse(null);
        if (tenant == null) {
            wsSession.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid token"));
            return;
        }

        wsSession.getAttributes().put("tenantId", tenant.getId());
        log.info("Notebook WS connected: tenant={}, session={}", tenant.getId(), wsSession.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        String tenantId = (String) wsSession.getAttributes().get("tenantId");
        if (tenantId == null) {
            wsSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String payload = message.getPayload();

        // Get the active notebook session
        NotebookSessionEntity session = notebookService.getSession(tenantId).orElse(null);
        if (session == null || session.getStatus() != NotebookSessionStatus.RUNNING) {
            wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"No active kernel. Start a session first.\"}"));
            return;
        }

        notebookService.touchSession(session.getId());

        // Get or create exec connection to pod
        ExecConnection conn = execConnections.computeIfAbsent(wsSession.getId(), id ->
                createExecConnection(session, wsSession));

        if (conn == null || conn.closed) {
            // Reconnect
            execConnections.remove(wsSession.getId());
            conn = createExecConnection(session, wsSession);
            if (conn == null) {
                wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"Failed to connect to kernel pod.\"}"));
                return;
            }
            execConnections.put(wsSession.getId(), conn);
        }

        // Forward message to pod stdin
        try {
            conn.stdin.write((payload + "\n").getBytes(StandardCharsets.UTF_8));
            conn.stdin.flush();
        } catch (IOException e) {
            log.warn("Failed to write to pod stdin: {}", e.getMessage());
            execConnections.remove(wsSession.getId());
            wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"Kernel connection lost. Reconnecting...\"}"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        ExecConnection conn = execConnections.remove(wsSession.getId());
        if (conn != null) {
            conn.close();
        }
        log.info("Notebook WS disconnected: session={}", wsSession.getId());
    }

    private ExecConnection createExecConnection(NotebookSessionEntity session, WebSocketSession wsSession) {
        try {
            PipedOutputStream stdinPipe = new PipedOutputStream();
            PipedInputStream stdinInput = new PipedInputStream(stdinPipe, 65536);

            ExecWatch exec = k8sClient.pods()
                    .inNamespace(session.getNamespace())
                    .withName(session.getPodName())
                    .redirectingInput()
                    .writingOutput(new OutputForwarder(wsSession))
                    .writingError(new OutputForwarder(wsSession))
                    .exec("python", "/app/repl_server.py");

            OutputStream stdin = exec.getInput();

            // Start a reader thread to forward pod stdout to WS
            ExecConnection conn = new ExecConnection(exec, stdin);
            log.info("Created exec connection to pod {}/{}", session.getNamespace(), session.getPodName());
            return conn;
        } catch (Exception e) {
            log.error("Failed to exec into notebook pod {}: {}", session.getPodName(), e.getMessage());
            return null;
        }
    }

    /**
     * Forwards pod output (stdout/stderr) to the WebSocket session.
     */
    private static class OutputForwarder extends OutputStream {
        private final WebSocketSession wsSession;
        private final StringBuilder buffer = new StringBuilder();

        OutputForwarder(WebSocketSession wsSession) {
            this.wsSession = wsSession;
        }

        @Override
        public void write(int b) throws IOException {
            char c = (char) b;
            buffer.append(c);
            if (c == '\n') {
                flush();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            String s = new String(b, off, len, StandardCharsets.UTF_8);
            buffer.append(s);
            // Flush complete lines
            int lastNewline = buffer.lastIndexOf("\n");
            if (lastNewline >= 0) {
                String complete = buffer.substring(0, lastNewline + 1);
                buffer.delete(0, lastNewline + 1);
                for (String line : complete.split("\n")) {
                    if (!line.isBlank()) {
                        try {
                            wsSession.sendMessage(new TextMessage(line));
                        } catch (Exception e) {
                            // WS closed, ignore
                        }
                    }
                }
            }
        }

        @Override
        public void flush() throws IOException {
            if (buffer.length() > 0) {
                String line = buffer.toString().trim();
                buffer.setLength(0);
                if (!line.isEmpty()) {
                    try {
                        wsSession.sendMessage(new TextMessage(line));
                    } catch (Exception e) {
                        // WS closed
                    }
                }
            }
        }
    }

    private static class ExecConnection {
        final ExecWatch exec;
        final OutputStream stdin;
        volatile boolean closed = false;

        ExecConnection(ExecWatch exec, OutputStream stdin) {
            this.exec = exec;
            this.stdin = stdin;
        }

        void close() {
            closed = true;
            try { exec.close(); } catch (Exception ignored) {}
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/notebook/NotebookWebSocketConfig.java \
        lakeon-api/src/main/java/com/lakeon/notebook/NotebookWebSocketHandler.java
git commit -m "feat(notebook): WebSocket handler with K8s exec bridge"
```

---

### Task 6: Frontend — API Client + WebSocket Manager

**Files:**
- Create: `lakeon-console/src/api/notebook.ts`

- [ ] **Step 1: Create notebook API client**

Create `lakeon-console/src/api/notebook.ts`:

```typescript
import client from './client'

// REST API
export function createSession(image?: string, datasetIds?: string[]) {
  return client.post('/datalake/notebook/sessions', { image, dataset_ids: datasetIds })
}

export function getCurrentSession() {
  return client.get('/datalake/notebook/sessions/current')
}

export function stopSession(id: string) {
  return client.delete(`/datalake/notebook/sessions/${id}`)
}

// WebSocket manager
export interface NotebookMessage {
  id?: string
  type: string
  code?: string
  text?: string
  html?: string
  data?: any
  traceback?: string
  duration_ms?: number
  exec_count?: number
  mime?: string
}

export class NotebookSocket {
  private ws: WebSocket | null = null
  private url: string
  private onMessage: (msg: NotebookMessage) => void
  private onStatus: (status: 'connecting' | 'connected' | 'disconnected') => void
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempts = 0

  constructor(
    onMessage: (msg: NotebookMessage) => void,
    onStatus: (status: 'connecting' | 'connected' | 'disconnected') => void,
  ) {
    const apiKey = localStorage.getItem('lakeon_api_key') || ''
    this.url = `wss://api.dbay.cloud:8443/api/v1/datalake/notebook/ws?token=${apiKey}`
    this.onMessage = onMessage
    this.onStatus = onStatus
  }

  connect() {
    if (this.ws) return
    this.onStatus('connecting')

    this.ws = new WebSocket(this.url)

    this.ws.onopen = () => {
      this.reconnectAttempts = 0
      this.onStatus('connected')
    }

    this.ws.onmessage = (event) => {
      try {
        const msg: NotebookMessage = JSON.parse(event.data)
        this.onMessage(msg)
      } catch {
        // Not JSON, ignore
      }
    }

    this.ws.onclose = () => {
      this.ws = null
      this.onStatus('disconnected')
      this.scheduleReconnect()
    }

    this.ws.onerror = () => {
      this.ws?.close()
    }
  }

  send(msg: NotebookMessage) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg))
    }
  }

  execute(id: string, code: string) {
    this.send({ type: 'execute', id, code })
  }

  disconnect() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.ws?.close()
    this.ws = null
  }

  private scheduleReconnect() {
    if (this.reconnectAttempts >= 5) return
    const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 10000)
    this.reconnectAttempts++
    this.reconnectTimer = setTimeout(() => this.connect(), delay)
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/api/notebook.ts
git commit -m "feat(notebook): add notebook API client + WebSocket manager"
```

---

### Task 7: Frontend — NotebookCell Component

**Files:**
- Create: `lakeon-console/src/views/datalake/components/NotebookCell.vue`

- [ ] **Step 1: Create NotebookCell component**

Create `lakeon-console/src/views/datalake/components/NotebookCell.vue`:

```vue
<template>
  <div class="nb-cell" :class="{ active: isActive, running: isRunning }">
    <!-- Header -->
    <div class="nb-cell-header">
      <span class="nb-cell-label">In [{{ execCount || ' ' }}]</span>
      <div class="nb-cell-actions">
        <span v-if="durationMs != null" class="nb-cell-time">{{ (durationMs / 1000).toFixed(1) }}s</span>
        <button class="nb-cell-btn" @click="$emit('run')" :disabled="isRunning" title="Run (Shift+Enter)">
          {{ isRunning ? '...' : '▶' }}
        </button>
        <button class="nb-cell-btn" @click="$emit('delete')" title="Delete cell">✕</button>
      </div>
    </div>

    <!-- Editor -->
    <div ref="editorEl" class="nb-cell-editor"></div>

    <!-- Output -->
    <div v-if="outputs.length > 0" class="nb-cell-output">
      <template v-for="(out, i) in outputs" :key="i">
        <pre v-if="out.type === 'stdout' || out.type === 'stderr'" class="nb-out-text" :class="{ stderr: out.type === 'stderr' }">{{ out.text }}</pre>
        <pre v-else-if="out.type === 'error'" class="nb-out-error">{{ out.traceback }}</pre>
        <div v-else-if="out.type === 'result' && out.html" class="nb-out-html" v-html="out.html"></div>
        <pre v-else-if="out.type === 'result'" class="nb-out-text">{{ out.text }}</pre>
        <div v-else-if="out.type === 'plotly'" class="nb-out-plotly" :ref="el => mountPlotly(el, out.data)"></div>
        <img v-else-if="out.type === 'image'" class="nb-out-image" :src="'data:' + out.mime + ';base64,' + out.data" />
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView, lineNumbers, highlightActiveLine, keymap } from '@codemirror/view'
import { python } from '@codemirror/lang-python'
import { oneDark } from '@codemirror/theme-one-dark'
import type { NotebookMessage } from '../../../api/notebook'

const props = defineProps<{
  code: string
  isActive: boolean
  isRunning: boolean
  execCount: number | null
  durationMs: number | null
  outputs: NotebookMessage[]
}>()

const emit = defineEmits<{
  'update:code': [value: string]
  'run': []
  'delete': []
  'focus': []
  'advance': []
}>()

const editorEl = ref<HTMLElement | null>(null)
let view: EditorView | null = null

onMounted(() => {
  if (!editorEl.value) return

  const shiftEnterRun = keymap.of([{
    key: 'Shift-Enter',
    run: () => { emit('run'); emit('advance'); return true },
  }, {
    key: 'Mod-Enter',
    run: () => { emit('run'); return true },
  }])

  const state = EditorState.create({
    doc: props.code,
    extensions: [
      lineNumbers(),
      highlightActiveLine(),
      python(),
      oneDark,
      shiftEnterRun,
      EditorView.updateListener.of(update => {
        if (update.docChanged) {
          emit('update:code', update.state.doc.toString())
        }
        if (update.focusChanged && update.view.hasFocus) {
          emit('focus')
        }
      }),
      EditorView.theme({
        '&': { minHeight: '40px', maxHeight: '400px' },
        '.cm-scroller': { overflow: 'auto' },
      }),
    ],
  })
  view = new EditorView({ state, parent: editorEl.value })
})

onUnmounted(() => view?.destroy())

function mountPlotly(el: any, data: any) {
  if (!el || !data) return
  nextTick(() => {
    const Plotly = (window as any).Plotly
    if (Plotly && el) {
      Plotly.newPlot(el, data.data || [], data.layout || {}, { responsive: true })
    }
  })
}
</script>

<style scoped>
.nb-cell { border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 8px; }
.nb-cell.active { border-color: #2563eb; border-width: 2px; }
.nb-cell.running { border-color: #f59e0b; }

.nb-cell-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 12px; background: #f8fafc; border-bottom: 1px solid #e5e7eb;
}
.nb-cell.active .nb-cell-header { background: #eff6ff; border-color: #bfdbfe; }

.nb-cell-label { font-family: monospace; font-size: 11px; color: #6b7280; }
.nb-cell.active .nb-cell-label { color: #2563eb; }

.nb-cell-actions { display: flex; align-items: center; gap: 6px; }
.nb-cell-time { font-size: 10px; color: #9ca3af; }
.nb-cell-btn {
  background: none; border: 1px solid #d1d5db; border-radius: 4px;
  padding: 1px 8px; font-size: 11px; cursor: pointer; color: #374151;
}
.nb-cell-btn:hover { background: #f3f4f6; }
.nb-cell-btn:disabled { opacity: 0.4; cursor: default; }

.nb-cell-editor { min-height: 40px; }

.nb-cell-output { border-top: 1px solid #e5e7eb; background: #f9fafb; padding: 8px 14px; }
.nb-out-text { margin: 0; font-family: monospace; font-size: 12px; color: #334155; white-space: pre-wrap; }
.nb-out-text.stderr { color: #d97706; }
.nb-out-error { margin: 0; font-family: monospace; font-size: 12px; color: #ef4444; white-space: pre-wrap; background: #fef2f2; padding: 8px; border-radius: 4px; }
.nb-out-html { overflow-x: auto; font-size: 12px; }
.nb-out-html :deep(table) { border-collapse: collapse; font-size: 12px; }
.nb-out-html :deep(th), .nb-out-html :deep(td) { padding: 3px 10px; border: 1px solid #e5e7eb; }
.nb-out-html :deep(th) { background: #f1f5f9; font-weight: 600; }
.nb-out-plotly { min-height: 300px; }
.nb-out-image { max-width: 100%; border-radius: 4px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/components/NotebookCell.vue
git commit -m "feat(notebook): add NotebookCell component with CodeMirror + output rendering"
```

---

### Task 8: Frontend — Main Notebook Page

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakeNotebook.vue`
- Modify: `lakeon-console/src/router/index.ts`

- [ ] **Step 1: Create DatalakeNotebook page**

Create `lakeon-console/src/views/datalake/DatalakeNotebook.vue`:

```vue
<template>
  <div class="page-container">
    <!-- Toolbar -->
    <div class="nb-toolbar">
      <div class="nb-toolbar-left">
        <span class="nb-title">Notebook</span>
        <span class="nb-status" :class="kernelStatus">{{ statusLabel }}</span>
      </div>
      <div class="nb-toolbar-right">
        <select v-model="imageKey" class="nb-select" :disabled="kernelStatus === 'running'">
          <option value="python-data">python-data (pandas, numpy, plotly)</option>
          <option value="ray">ray (distributed)</option>
        </select>
        <select v-model="selectedDatasetId" class="nb-select">
          <option value="">-- 选择数据集 --</option>
          <option v-for="ds in datasets" :key="ds.id" :value="ds.id">{{ ds.name }}</option>
        </select>
        <button class="nb-btn nb-btn-primary" @click="submitAsJob" :disabled="cells.length === 0">Submit as Job</button>
        <button v-if="kernelStatus !== 'stopped'" class="nb-btn nb-btn-danger" @click="stopKernel">Stop Kernel</button>
        <button v-else class="nb-btn" @click="startKernel">Start Kernel</button>
      </div>
    </div>

    <!-- Cells -->
    <div class="nb-cells">
      <NotebookCell
        v-for="(cell, i) in cells"
        :key="cell.id"
        :code="cell.code"
        :is-active="activeIndex === i"
        :is-running="cell.running"
        :exec-count="cell.execCount"
        :duration-ms="cell.durationMs"
        :outputs="cell.outputs"
        @update:code="cell.code = $event; saveCells()"
        @run="runCell(i)"
        @delete="deleteCell(i)"
        @focus="activeIndex = i"
        @advance="advanceCell(i)"
      />
      <button class="nb-add-btn" @click="addCell()">+ Add Cell</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import NotebookCell from './components/NotebookCell.vue'
import { createSession, stopSession as apiStopSession, NotebookSocket, type NotebookMessage } from '../../api/notebook'
import client from '../../api/client'

const router = useRouter()

interface Cell {
  id: string
  code: string
  outputs: NotebookMessage[]
  running: boolean
  execCount: number | null
  durationMs: number | null
}

const cells = ref<Cell[]>([])
const activeIndex = ref(0)
const imageKey = ref('python-data')
const selectedDatasetId = ref('')
const datasets = ref<Array<{ id: string; name: string }>>([])
const sessionId = ref<string | null>(null)
const kernelStatus = ref<'stopped' | 'starting' | 'running' | 'disconnected'>('stopped')

let socket: NotebookSocket | null = null
let globalExecCounter = 0

const statusLabel = computed(() => {
  const map: Record<string, string> = { stopped: 'Stopped', starting: 'Starting...', running: 'Running', disconnected: 'Disconnected' }
  return map[kernelStatus.value] || kernelStatus.value
})

function newCell(code = ''): Cell {
  return { id: 'cell_' + Math.random().toString(36).slice(2, 8), code, outputs: [], running: false, execCount: null, durationMs: null }
}

function addCell(code = '') {
  cells.value.push(newCell(code))
  activeIndex.value = cells.value.length - 1
  saveCells()
}

function deleteCell(i: number) {
  if (cells.value.length <= 1) return
  cells.value.splice(i, 1)
  if (activeIndex.value >= cells.value.length) activeIndex.value = cells.value.length - 1
  saveCells()
}

function advanceCell(i: number) {
  if (i + 1 >= cells.value.length) addCell()
  else activeIndex.value = i + 1
}

function runCell(i: number) {
  const cell = cells.value[i]
  if (!cell.code.trim() || cell.running) return
  if (kernelStatus.value !== 'running') {
    startKernel().then(() => runCell(i))
    return
  }
  cell.outputs = []
  cell.running = true
  cell.durationMs = null
  socket?.execute(cell.id, cell.code)
}

function handleMessage(msg: NotebookMessage) {
  if (msg.type === 'ready') return

  const cell = cells.value.find(c => c.id === msg.id)
  if (!cell) return

  if (msg.type === 'done') {
    cell.running = false
    cell.durationMs = msg.duration_ms ?? null
    cell.execCount = msg.exec_count ?? null
    saveCells()
  } else {
    cell.outputs.push(msg)
  }
}

async function startKernel() {
  kernelStatus.value = 'starting'
  try {
    const dsIds = selectedDatasetId.value ? [selectedDatasetId.value] : undefined
    const { data } = await createSession(imageKey.value, dsIds)
    sessionId.value = data.id

    socket = new NotebookSocket(handleMessage, (status) => {
      if (status === 'connected') kernelStatus.value = 'running'
      else if (status === 'disconnected') kernelStatus.value = 'disconnected'
    })
    socket.connect()
  } catch (e: any) {
    kernelStatus.value = 'stopped'
    alert('Failed to start kernel: ' + (e.response?.data?.message || e.message))
  }
}

async function stopKernel() {
  if (sessionId.value) {
    try { await apiStopSession(sessionId.value) } catch { /* ignore */ }
  }
  socket?.disconnect()
  socket = null
  sessionId.value = null
  kernelStatus.value = 'stopped'
}

function submitAsJob() {
  const script = cells.value.map(c => c.code).filter(c => c.trim()).join('\n\n')
  sessionStorage.setItem('datalake_job_prefill', JSON.stringify({
    name: 'notebook-export',
    type: imageKey.value === 'ray' ? 'RAY' : 'PYTHON',
    inline_script: script,
  }))
  router.push('/datalake/jobs/new')
}

// Persist cells to localStorage
function saveCells() {
  const data = cells.value.map(c => ({ id: c.id, code: c.code }))
  localStorage.setItem('notebook_cells', JSON.stringify(data))
}

function loadCells() {
  try {
    const raw = localStorage.getItem('notebook_cells')
    if (raw) {
      const data = JSON.parse(raw) as Array<{ id: string; code: string }>
      cells.value = data.map(d => newCell(d.code))
      cells.value.forEach(c => c.id = 'cell_' + Math.random().toString(36).slice(2, 8))
    }
  } catch { /* ignore */ }
  if (cells.value.length === 0) addCell()
}

async function loadDatasets() {
  try {
    const { data } = await client.get('/datalake/datasets', { params: { status: 'READY' } })
    datasets.value = data.map((d: any) => ({ id: d.id, name: d.name }))
  } catch { /* ignore */ }
}

onMounted(() => {
  loadCells()
  loadDatasets()
})

onUnmounted(() => {
  socket?.disconnect()
})
</script>

<style scoped>
.nb-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 0; margin-bottom: 16px; border-bottom: 1px solid #e5e7eb;
}
.nb-toolbar-left { display: flex; align-items: center; gap: 10px; }
.nb-toolbar-right { display: flex; align-items: center; gap: 8px; }
.nb-title { font-size: 16px; font-weight: 700; color: #1e293b; }

.nb-status {
  font-size: 11px; padding: 2px 10px; border-radius: 10px;
  background: #f1f5f9; color: #64748b;
}
.nb-status.running { background: #dcfce7; color: #16a34a; }
.nb-status.starting { background: #fef9c3; color: #a16207; }
.nb-status.disconnected { background: #fee2e2; color: #dc2626; }

.nb-select { font-size: 12px; padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 4px; color: #374151; }
.nb-btn { font-size: 12px; padding: 5px 14px; border-radius: 6px; border: 1px solid #e5e7eb; background: white; color: #374151; cursor: pointer; }
.nb-btn:hover { background: #f9fafb; }
.nb-btn-primary { background: #2563eb; color: white; border: none; }
.nb-btn-primary:hover { background: #1d4ed8; }
.nb-btn-primary:disabled { background: #93c5fd; cursor: default; }
.nb-btn-danger { color: #ef4444; border-color: #fecaca; }
.nb-btn-danger:hover { background: #fef2f2; }

.nb-cells { max-width: 960px; }

.nb-add-btn {
  display: block; width: 100%; padding: 10px; margin-top: 4px;
  background: none; border: 2px dashed #e5e7eb; border-radius: 8px;
  color: #9ca3af; font-size: 13px; cursor: pointer; text-align: center;
}
.nb-add-btn:hover { border-color: #2563eb; color: #2563eb; }
</style>
```

- [ ] **Step 2: Add route**

In `lakeon-console/src/router/index.ts`, add after the existing datalake routes:

```typescript
{ path: '/datalake/notebook', name: 'DatalakeNotebook', component: () => import('../views/datalake/DatalakeNotebook.vue') },
```

- [ ] **Step 3: Add plotly.js to index.html**

In `lakeon-console/index.html`, add before the closing `</head>` tag:

```html
<script src="https://cdn.plot.ly/plotly-2.32.0.min.js" charset="utf-8"></script>
```

- [ ] **Step 4: Add sidebar entry**

In `lakeon-console/src/layouts/ConsoleLayout.vue` (or wherever the datalake sidebar nav is), add a "Notebook" link to `/datalake/notebook` alongside the existing datalake nav items.

- [ ] **Step 5: Type check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: No errors

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakeNotebook.vue \
        lakeon-console/src/views/datalake/components/NotebookCell.vue \
        lakeon-console/src/api/notebook.ts \
        lakeon-console/src/router/index.ts \
        lakeon-console/index.html
git commit -m "feat(notebook): add Notebook page with cells, toolbar, WebSocket integration"
```

---

### Task 9: Build + Deploy + Verify

- [ ] **Step 1: Build backend**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Build frontend**

Run: `cd lakeon-console && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Build and deploy API**

```bash
IMAGE_TAG=0.9.115 ./deploy/cce/build-and-push-api.sh
# Update values-cce.yaml tag to 0.9.115
./deploy/cce/deploy.sh
```

- [ ] **Step 4: Push for Railway deploy**

```bash
git push origin main
```

- [ ] **Step 5: Manual verification checklist**

- [ ] Navigate to `/datalake/notebook` — page loads with empty cell
- [ ] Click "Start Kernel" — status changes to Starting → Running
- [ ] Type `print("hello")` in cell, press Shift+Enter — output shows "hello"
- [ ] Type `import pandas as pd; pd.DataFrame({"a":[1,2],"b":[3,4]})` — HTML table output
- [ ] Type `import plotly.express as px; px.bar(x=["A","B"], y=[1,2]).show()` — plotly chart renders
- [ ] Click "Submit as Job" — redirects to job creation with script pre-filled
- [ ] Click "Stop Kernel" — status changes to Stopped
- [ ] Close tab, reopen — cells restored from localStorage
