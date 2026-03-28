# Notebook Phase 3 + 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add magic commands (%pip, %sh, %sql, %md), variable explorer, and Markdown cells to the notebook (Phase 3), plus distributed Ray cluster mode (Phase 4).

**Architecture:** Phase 3 is mostly repl_server.py changes (detect cell prefix → dispatch to subprocess/SQL) + frontend cell type toggle. Phase 4 extends NotebookService to optionally create a Ray head pod + worker pods instead of a single Python pod, reusing RayJobRunner patterns.

**Tech Stack:** Python 3.11 (repl_server.py), Spring Boot + Fabric8 K8s, Vue 3 + CodeMirror 6 + marked (markdown)

---

## File Structure

### Phase 3: Magic Commands + Variable Explorer + Markdown Cell
- **Modify:** `lakeon-api/src/main/resources/repl_server.py` — add %pip, %sh, %sql, vars command
- **Modify:** `lakeon-console/src/views/datalake/components/NotebookCell.vue` — cell type toggle, markdown rendering
- **Modify:** `lakeon-console/src/views/datalake/DatalakeNotebook.vue` — cell type in data model, markdown cell styling
- **Modify:** `lakeon-console/src/api/notebook.ts` — add `markdown` output type

### Phase 4: Distributed Ray Notebook
- **Modify:** `lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java` — Ray cluster creation/cleanup
- **Modify:** `lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionEntity.java` — add workerCount field
- **Create:** `lakeon-api/src/main/resources/db/migration/V23__notebook_session_workers.sql`

---

### Task 1: Magic Commands in repl_server.py

**Files:**
- Modify: `lakeon-api/src/main/resources/repl_server.py`

Add magic command detection in the `main()` loop. When a cell starts with `%pip`, `%sh`, `%sql`, or `%md`, dispatch to a handler instead of `_execute()`.

- [ ] **Step 1: Add magic command handlers**

Add these functions before `main()` in `repl_server.py`:

```python
def _handle_pip(req_id, args):
    """Handle %pip install <packages>"""
    import subprocess
    cmd = ["pip"] + args.split()
    start = time.time()
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if result.stdout:
            _emit({"id": req_id, "type": "stdout", "text": result.stdout})
        if result.stderr:
            _emit({"id": req_id, "type": "stderr", "text": result.stderr})
        if result.returncode != 0:
            _emit({"id": req_id, "type": "error", "traceback": f"pip exited with code {result.returncode}"})
    except subprocess.TimeoutExpired:
        _emit({"id": req_id, "type": "error", "traceback": "pip install timed out (120s)"})
    elapsed_ms = int((time.time() - start) * 1000)
    _emit({"id": req_id, "type": "done", "duration_ms": elapsed_ms, "exec_count": _exec_counter})

def _handle_sh(req_id, cmd):
    """Handle %sh <command>"""
    import subprocess
    start = time.time()
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=60)
        if result.stdout:
            _emit({"id": req_id, "type": "stdout", "text": result.stdout})
        if result.stderr:
            _emit({"id": req_id, "type": "stderr", "text": result.stderr})
        if result.returncode != 0:
            _emit({"id": req_id, "type": "error", "traceback": f"Command exited with code {result.returncode}"})
    except subprocess.TimeoutExpired:
        _emit({"id": req_id, "type": "error", "traceback": "Shell command timed out (60s)"})
    elapsed_ms = int((time.time() - start) * 1000)
    _emit({"id": req_id, "type": "done", "duration_ms": elapsed_ms, "exec_count": _exec_counter})

def _handle_sql(req_id, sql):
    """Handle %sql <query> — connects to LAKEON_DB_CONNSTR if set"""
    start = time.time()
    try:
        connstr = os.environ.get("LAKEON_DB_CONNSTR")
        if not connstr:
            _emit({"id": req_id, "type": "error", "traceback": "No database connected. Set LAKEON_DB_CONNSTR or select a database in the toolbar."})
            _emit({"id": req_id, "type": "done", "duration_ms": 0, "exec_count": _exec_counter})
            return
        import psycopg2
        import pandas as pd
        conn = psycopg2.connect(connstr)
        try:
            df = pd.read_sql(sql, conn)
            _emit({"id": req_id, "type": "result", "text": df.to_string(max_rows=20), "html": df.to_html(max_rows=50)})
            _emit({"id": req_id, "type": "stdout", "text": f"{len(df)} rows returned\n"})
            _globals["_df"] = df  # Store result as _df for further use
        finally:
            conn.close()
    except Exception:
        _emit({"id": req_id, "type": "error", "traceback": traceback.format_exc()})
    elapsed_ms = int((time.time() - start) * 1000)
    _emit({"id": req_id, "type": "done", "duration_ms": elapsed_ms, "exec_count": _exec_counter})

def _handle_md(req_id, text):
    """Handle %md — return markdown as-is for frontend rendering"""
    _emit({"id": req_id, "type": "markdown", "text": text})
    _emit({"id": req_id, "type": "done", "duration_ms": 0, "exec_count": _exec_counter})

def _handle_vars(req_id):
    """Return current variables (name, type, short repr)"""
    skip = {"__builtins__", "_"}
    variables = []
    for name, val in sorted(_globals.items()):
        if name.startswith("_") or name in skip:
            continue
        try:
            r = repr(val)
            if len(r) > 80:
                r = r[:77] + "..."
            variables.append({"name": name, "type": type(val).__name__, "repr": r})
        except Exception:
            variables.append({"name": name, "type": type(val).__name__, "repr": "<error>"})
    _emit({"id": req_id, "type": "vars", "variables": variables})
    _emit({"id": req_id, "type": "done", "duration_ms": 0, "exec_count": _exec_counter})
```

- [ ] **Step 2: Add `import os` at the top** (needed for `os.environ` in `_handle_sql`)

Add `import os` to the existing imports at the top of the file.

- [ ] **Step 3: Modify the main loop to detect magic commands**

In the `main()` function, replace the `if req_type == "execute":` block:

```python
        if req_type == "execute":
            code = req.get("code", "")
            timeout = req.get("timeout", 60)

            # Magic command detection
            stripped = code.strip()
            if stripped.startswith("%pip "):
                _handle_pip(req_id, stripped[5:])
            elif stripped.startswith("%sh "):
                _handle_sh(req_id, stripped[4:])
            elif stripped.startswith("%sql "):
                _handle_sql(req_id, stripped[5:])
            elif stripped.startswith("%sql\n"):
                _handle_sql(req_id, stripped[4:].strip())
            elif stripped.startswith("%md"):
                _handle_md(req_id, stripped[3:].strip())
            else:
                signal.alarm(timeout)
                try:
                    _execute(req_id, code)
                except TimeoutError:
                    _emit({"id": req_id, "type": "error", "traceback": "TimeoutError: Cell execution timed out (60s)"})
                    _emit({"id": req_id, "type": "done", "duration_ms": timeout * 1000, "exec_count": _exec_counter})
                finally:
                    signal.alarm(0)
```

Also add a handler for `"vars"` type alongside the existing `"status"` and `"reset"` handlers:

```python
        elif req_type == "vars":
            _handle_vars(req_id)
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/resources/repl_server.py
git commit -m "feat(notebook): add %pip, %sh, %sql, %md magic commands + variable explorer"
```

---

### Task 2: Markdown Cell + Variable Explorer in Frontend

**Files:**
- Modify: `lakeon-console/src/views/datalake/components/NotebookCell.vue`
- Modify: `lakeon-console/src/views/datalake/DatalakeNotebook.vue`
- Modify: `lakeon-console/src/api/notebook.ts`

- [ ] **Step 1: Add `marked` package for markdown rendering**

```bash
cd lakeon-console && npm install marked
```

- [ ] **Step 2: Update NotebookCell.vue — add cell type toggle + markdown output**

In the `<template>`, add a cell type toggle button in the header (next to the delete button):

```html
<button class="nb-cell-btn" @click="$emit('toggleType')" :title="cellType === 'code' ? 'Switch to Markdown' : 'Switch to Code'">
  {{ cellType === 'code' ? 'Py' : 'Md' }}
</button>
```

Add a new prop `cellType` (default `'code'`) and emit `'toggleType'`.

For markdown cells, instead of CodeMirror editor, show a textarea (edit mode) or rendered HTML (display mode). When a markdown cell is "run", it renders the markdown and emits a `markdown` output.

Add markdown output rendering in the output section:

```html
<div v-else-if="out.type === 'markdown'" class="nb-out-markdown" v-html="renderMarkdown(out.text)"></div>
```

Add the render function in `<script setup>`:

```typescript
import { marked } from 'marked'

function renderMarkdown(text: string): string {
  return marked.parse(text) as string
}
```

Add style for markdown output:

```css
.nb-out-markdown { padding: 8px 14px; line-height: 1.6; font-size: 14px; }
.nb-out-markdown :deep(h1) { font-size: 20px; margin: 12px 0 8px; }
.nb-out-markdown :deep(h2) { font-size: 17px; margin: 10px 0 6px; }
.nb-out-markdown :deep(code) { background: #f1f5f9; padding: 1px 5px; border-radius: 3px; font-size: 12px; }
.nb-out-markdown :deep(pre) { background: #f1f5f9; padding: 10px; border-radius: 6px; overflow-x: auto; }
.nb-out-markdown :deep(ul), .nb-out-markdown :deep(ol) { padding-left: 20px; }
```

- [ ] **Step 3: Update DatalakeNotebook.vue — cell type in data model + variable explorer**

Add `cellType: 'code' | 'markdown'` to the Cell interface. Default to `'code'`.

Add a toggle handler:

```typescript
function toggleCellType(i: number) {
  const cell = cells.value[i]
  if (cell) cell.cellType = cell.cellType === 'code' ? 'markdown' : 'code'
  saveCells()
}
```

Pass `cellType` as prop and listen for `@toggleType`.

Add a "Variables" button in the toolbar that sends a `vars` type message via WS and displays results in a side panel or collapsible section:

```html
<button class="nb-btn" @click="requestVars" :disabled="kernelStatus !== 'running'">Variables</button>
```

Add variables display (collapsible section below toolbar):

```html
<div v-if="showVars && variables.length > 0" class="nb-vars-panel">
  <table class="nb-vars-table">
    <thead><tr><th>Name</th><th>Type</th><th>Value</th></tr></thead>
    <tbody>
      <tr v-for="v in variables" :key="v.name">
        <td class="mono">{{ v.name }}</td>
        <td class="mono" style="color:#6b7280;">{{ v.type }}</td>
        <td class="mono" style="color:#334155;">{{ v.repr }}</td>
      </tr>
    </tbody>
  </table>
</div>
```

Handle the `vars` message type in `handleMessage`:

```typescript
if (msg.type === 'vars') {
  variables.value = msg.variables || []
  return
}
```

- [ ] **Step 4: Update notebook.ts — add markdown type to NotebookMessage**

Add `markdown` to the `type` union. Add `variables` field:

```typescript
export interface NotebookMessage {
  // ... existing fields
  variables?: Array<{ name: string; type: string; repr: string }>
}
```

- [ ] **Step 5: Save cellType in localStorage**

In `saveCells()`, include cellType. In `loadCells()`, restore it.

- [ ] **Step 6: Type check + Commit**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
git add lakeon-console/src/views/datalake/components/NotebookCell.vue \
        lakeon-console/src/views/datalake/DatalakeNotebook.vue \
        lakeon-console/src/api/notebook.ts \
        lakeon-console/package.json lakeon-console/package-lock.json
git commit -m "feat(notebook): markdown cells, variable explorer, magic command output rendering"
```

---

### Task 3: Distributed Ray Notebook (Phase 4)

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V23__notebook_session_workers.sql`
- Modify: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionEntity.java` — add workerCount
- Modify: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java` — Ray cluster mode
- Modify: `lakeon-console/src/views/datalake/DatalakeNotebook.vue` — worker count input

- [ ] **Step 1: Add migration for worker_count column**

Create `lakeon-api/src/main/resources/db/migration/V23__notebook_session_workers.sql`:

```sql
ALTER TABLE notebook_sessions ADD COLUMN worker_count INTEGER DEFAULT 0;
```

- [ ] **Step 2: Add workerCount field to NotebookSessionEntity**

Add to the entity:

```java
@Column(name = "worker_count")
private Integer workerCount;

public Integer getWorkerCount() { return workerCount; }
public void setWorkerCount(Integer workerCount) { this.workerCount = workerCount; }
```

- [ ] **Step 3: Add Ray cluster creation to NotebookService**

Add a new method `createRayNotebookCluster` that:

1. Creates a head pod (same as current notebook pod but using ray image, with `ray start --head --port=6379 --dashboard-host=0.0.0.0` as init command before repl_server.py)
2. Creates N worker pods (same namespace, ray image, `ray start --address=<head-pod-ip>:6379 --block`)
3. Head pod entrypoint: `bash -c "ray start --head --port=6379 --dashboard-host=0.0.0.0 && python -u /app/repl_server.py"`
4. Worker pod names: `{podName}-worker-{i}`
5. Labels: `app=notebook-worker`, `lakeon.io/session-id={sessionId}`

Modify `getOrCreateSession` to accept `workerCount` parameter. If `imageKey == "ray"` and `workerCount > 0`, call `createRayNotebookCluster` instead of `createNotebookPod`.

In `stopSession`, also delete worker pods (by label selector `lakeon.io/session-id={sessionId}`).

In `deletePodAndConfigMap`, add worker cleanup:

```java
// Delete worker pods
k8sClient.pods().inNamespace(session.getNamespace())
    .withLabel("lakeon.io/session-id", session.getId())
    .delete();
```

The head pod command needs to be changed for Ray mode:

```java
// For Ray head: start ray, then run repl_server
if (isRay) {
    container.withCommand("bash", "-c",
        "ray start --head --port=6379 --dashboard-host=0.0.0.0 --num-cpus=1 && python -u /app/repl_server.py");
} else {
    container.withCommand("python", "-u", "/app/repl_server.py");
}
```

Worker pod spec (no repl_server, just ray worker):

```java
Pod worker = new PodBuilder()
    .withNewMetadata()
        .withName(podName + "-worker-" + i)
        .withNamespace(ns)
        .addToLabels("app", "notebook-worker")
        .addToLabels("lakeon.io/session-id", session.getId())
    .endMetadata()
    .withNewSpec()
        .withRestartPolicy("Never")
        .withNodeSelector(nodeSelector)
        .withTolerations(tolerations)
        .withImagePullSecrets(imagePullSecrets)
        .addNewContainer()
            .withName("ray-worker")
            .withImage(image)
            .withCommand("bash", "-c",
                "ray start --address=" + headPodIp + ":6379 --num-cpus=1 --block")
            .withEnv(envVars)
            .withNewResources()
                .addToRequests("cpu", new Quantity("1"))
                .addToRequests("memory", new Quantity("2Gi"))
                .addToLimits("cpu", new Quantity("2"))
                .addToLimits("memory", new Quantity("4Gi"))
            .endResources()
        .endContainer()
    .endSpec()
    .build();
```

To get head pod IP, wait for pod to be Running and read `pod.getStatus().getPodIP()`:

```java
// Wait for head pod IP (poll up to 30s)
String headPodIp = null;
for (int attempt = 0; attempt < 30; attempt++) {
    Pod headPod = k8sClient.pods().inNamespace(ns).withName(podName).get();
    if (headPod != null && headPod.getStatus() != null && headPod.getStatus().getPodIP() != null) {
        headPodIp = headPod.getStatus().getPodIP();
        break;
    }
    Thread.sleep(1000);
}
```

- [ ] **Step 4: Update NotebookController — accept worker_count**

In `createSession`, extract `worker_count` from body and pass to `getOrCreateSession`:

```java
Integer workerCount = body != null && body.get("worker_count") != null
    ? ((Number) body.get("worker_count")).intValue() : 0;
```

Add `worker_count` to `sessionToMap` response.

- [ ] **Step 5: Update frontend — worker count input for Ray image**

In `DatalakeNotebook.vue`, add a worker count input that shows when `imageKey === 'ray'`:

```html
<input v-if="imageKey === 'ray'" type="number" v-model.number="workerCount" min="1" max="5"
       class="nb-select" style="width: 60px;" title="Worker count" />
```

Pass `workerCount` in the `createSession` call:

```typescript
const { data } = await createSession(imageKey.value, dsIds, imageKey.value === 'ray' ? workerCount.value : 0)
```

Update `notebook.ts`:

```typescript
export function createSession(image?: string, datasetIds?: string[], workerCount?: number) {
  return client.post('/datalake/notebook/sessions', { image, dataset_ids: datasetIds, worker_count: workerCount })
}
```

- [ ] **Step 6: Verify compilation**

```bash
cd lakeon-api && mvn compile -q
cd lakeon-console && npx vue-tsc -b --noEmit
```

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V23__notebook_session_workers.sql \
        lakeon-api/src/main/java/com/lakeon/notebook/NotebookSessionEntity.java \
        lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java \
        lakeon-api/src/main/java/com/lakeon/notebook/NotebookController.java \
        lakeon-console/src/views/datalake/DatalakeNotebook.vue \
        lakeon-console/src/api/notebook.ts
git commit -m "feat(notebook): distributed Ray cluster mode with configurable workers"
```

---

### Task 4: Build + Deploy + Verify

- [ ] **Step 1: Build backend**

```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 2: Build frontend**

```bash
cd lakeon-console && npm run build
```

- [ ] **Step 3: Deploy API**

```bash
IMAGE_TAG=0.9.123 ./deploy/cce/build-and-push-api.sh
# Update values-cce.yaml tag
./deploy/cce/deploy.sh --skip-test
```

- [ ] **Step 4: Push for Railway**

```bash
git push origin main
```

- [ ] **Step 5: Manual verification**

- [ ] Open notebook, type `%pip install requests`, run → shows pip output
- [ ] Type `%sh ls /`, run → shows filesystem listing
- [ ] Type `%md # Hello\nThis is **bold**`, run → renders as formatted markdown
- [ ] Click cell type toggle → switches between Py/Md
- [ ] Click "Variables" → shows variables panel with names/types/values
- [ ] Select "ray" image, set workers=2, start kernel → Ray cluster starts
- [ ] In cell: `import ray; ray.init(); print(ray.cluster_resources())` → shows cluster with multiple CPUs
- [ ] `@ray.remote` function works across workers
- [ ] Stop kernel → head + worker pods all deleted
