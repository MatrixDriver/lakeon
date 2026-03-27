# Datalake Notebook — Design Spec

## Goal

Add an interactive Python notebook to the DBay Console, allowing users to execute code cell-by-cell against real datasets before submitting as a formal datalake job.

## Architecture

```
Console (Browser)          lakeon-api (Spring Boot)         K8s (CCI/CCE)
┌──────────────┐    WS     ┌───────────────────┐   exec    ┌──────────────┐
│ Notebook UI  │◄─────────►│ NotebookWsHandler │◄─────────►│ REPL Pod     │
│ CodeMirror   │  JSON     │ SessionManager    │  stdin/   │ repl_server  │
│ plotly.js    │  frames   │                   │  stdout   │ python-data  │
└──────────────┘           └───────────────────┘           └──────────────┘
```

Three layers:

1. **REPL Pod** — uses existing `python-data` image. A `repl_server.py` script (injected via ConfigMap) maintains a persistent `exec()` context. Communicates via stdin/stdout JSON lines.

2. **lakeon-api** — new WebSocket endpoint at `/api/v1/datalake/notebook/ws`. Manages session lifecycle (create/reuse/destroy pod). Bridges frontend WebSocket to pod stdin/stdout via K8s exec API. New `NotebookSessionEntity` tracks pod state.

3. **Console frontend** — new page at `/datalake/notebook`. Cell editor (CodeMirror), output rendering (text, DataFrame HTML, Plotly JSON via plotly.js). Toolbar with kernel status, dataset binding, "Submit as Job".

## Components

### 1. REPL Server (`repl_server.py`)

Lightweight Python script injected into the pod as a ConfigMap. Runs in a loop reading JSON commands from stdin, executing them, and writing JSON results to stdout.

**Protocol (JSON lines over stdin/stdout):**

```json
// Request (stdin)
{"id": "c1", "type": "execute", "code": "import pandas as pd\ndf = pd.read_parquet(path)"}

// Response (stdout) — one or more per request
{"id": "c1", "type": "stdout", "text": "Loaded 10000 rows\n"}
{"id": "c1", "type": "result", "text": "          total  count\nEarbuds  7178.9     54", "html": "<table>...</table>"}
{"id": "c1", "type": "plotly", "data": {"data": [...], "layout": {...}}}
{"id": "c1", "type": "error", "ename": "KeyError", "evalue": "'missing_col'", "traceback": "..."}
{"id": "c1", "type": "done", "duration_ms": 312}
```

**Execution model:**
- Maintains a single `globals()` dict across all cells (variables persist)
- Captures stdout/stderr via `contextlib.redirect_stdout`
- Detects Plotly figures: if last expression is a plotly Figure, serialize to JSON
- Detects DataFrames: if last expression is a DataFrame, include `.to_html()` as `html` field
- Timeout per cell: 60 seconds (configurable)

**Plotly support:**
- `repl_server.py` monkey-patches `plotly.io.show()` to emit a `plotly` JSON frame instead of opening a browser
- Frontend receives plotly spec and renders with `Plotly.newPlot()`

**matplotlib fallback:**
- If matplotlib is used, `repl_server.py` patches `plt.show()` to save to a temp PNG, base64-encode it, and emit as `{"type": "image", "data": "base64...", "mime": "image/png"}`

### 2. Session Manager (Backend)

**New entity: `NotebookSessionEntity`**

| Field | Type | Description |
|-------|------|-------------|
| id | String | `nbs_` prefix + 12 hex |
| tenant_id | String | Owner tenant |
| status | Enum | STARTING, RUNNING, STOPPING, STOPPED |
| pod_name | String | K8s pod name |
| namespace | String | Tenant's datalake namespace |
| image | String | Container image used |
| dataset_ids | String | Comma-separated dataset IDs bound to session |
| last_active_at | Instant | Updated on each cell execution |
| created_at | Instant | |

**Lifecycle:**
1. **Start** — user opens notebook page → API creates pod in tenant namespace (or returns existing running session)
2. **Execute** — WebSocket frame with code → API exec into pod → stream stdout back
3. **Idle timeout** — scheduled task checks `last_active_at`, stops pods idle > 30 minutes
4. **Stop** — user clicks "Stop Kernel" or idle timeout → delete pod, set status STOPPED
5. **Reuse** — if user opens notebook and a RUNNING session exists, reconnect to it

**Pod creation** reuses `DatalakeNamespaceManager.ensureNamespace()` for tenant namespace, and follows `PythonJobRunner` patterns for:
- Image selection (preset `python-data`)
- OBS STS credential injection
- Dataset `DATASET_PATH` env vars
- Image pull secrets, node selector, tolerations

**Key difference from Job pods:** This pod runs as a Deployment (replicas=1) with `restartPolicy: Always`, not a Job. The entrypoint is `python /app/repl_server.py` (ConfigMap mounted).

**Concurrency:** Max 1 active session per tenant. Starting a new session when one exists returns the existing session.

### 3. WebSocket Handler (Backend)

**New Spring WebSocket endpoint:** `/api/v1/datalake/notebook/ws`

**Authentication:** API key passed as query param `?token=lk_...` (WebSocket doesn't support Authorization header easily).

**Message flow:**
1. Client connects → handler validates token, resolves tenant
2. Client sends `{"type": "execute", "id": "c1", "code": "..."}` → handler exec into pod, pipes stdin
3. Pod responds with JSON lines on stdout → handler forwards to client as WebSocket text frames
4. Client sends `{"type": "interrupt"}` → handler sends SIGINT to the exec process
5. Client sends `{"type": "status"}` → handler returns pod/session status

**K8s exec bridge:**
- Uses `k8sClient.pods().inNamespace(ns).withName(pod).exec(...)` to attach to the repl_server process
- Maintains one persistent exec connection per session (not per cell)
- If exec connection drops, reconnects on next execute

### 4. Frontend Notebook Page

**Route:** `/datalake/notebook`

**Toolbar:**
- Kernel status indicator (Starting / Running / Stopped)
- Image selector (python-data / ray)
- Dataset binding dropdown (user's READY datasets)
- "Submit as Job" button — merges all cells into one script, navigates to job creation page pre-filled
- "Stop Kernel" button
- "Clear All Outputs" button

**Cell editor:**
- CodeMirror 6 with Python syntax highlighting (reuse existing setup from DatalakeJobNewCode)
- Dark theme (one-dark, matching current code editor)
- Cell header: `In [n]` label, execution time, Run button
- Active cell: blue border
- Keyboard: Shift+Enter = run cell + advance, Ctrl+Enter = run cell in place

**Output rendering:**
- `stdout/stderr` → monospace text block
- `result` with `html` → rendered HTML (DataFrame tables)
- `plotly` → `Plotly.newPlot()` in a container div
- `image` → `<img src="data:image/png;base64,...">`
- `error` → red traceback block

**Cell management:**
- Add cell (button between cells + at bottom)
- Delete cell (button in cell header)
- Move cell up/down (drag or buttons)
- Cells persisted in `localStorage` (key: `notebook_cells_{tenantId}`) so they survive page refresh

**Submit as Job flow:**
1. User clicks "Submit as Job"
2. Frontend concatenates all cell code (in order) into one script string
3. Navigates to `/datalake/jobs/new` with the script pre-filled in `sessionStorage`
4. Existing DatalakeJobNew page picks it up (already has `sessionStorage.getItem('datalake_job_prefill')` logic)

## Data Flow: Cell Execution

```
1. User types code in Cell 3, presses Shift+Enter
2. Frontend sends WS: {"id":"c3","type":"execute","code":"df.describe()"}
3. API handler updates session.last_active_at
4. API writes to pod exec stdin: {"id":"c3","type":"execute","code":"df.describe()"}
5. repl_server.py receives, exec(code, globals_dict)
6. Captures stdout: {"id":"c3","type":"stdout","text":"..."}
7. Detects DataFrame result: {"id":"c3","type":"result","text":"...","html":"<table>..."}
8. Sends done: {"id":"c3","type":"done","duration_ms":45}
9. API forwards all frames to frontend via WS
10. Frontend renders output below Cell 3, updates In[3] counter
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Pod not ready yet | Frontend shows "Starting kernel..." spinner, WS queues messages |
| Cell timeout (60s) | repl_server sends SIGALRM, returns error frame |
| Pod OOM killed | Session status → STOPPED, frontend shows "Kernel died" with restart button |
| WS disconnect | Frontend auto-reconnects (exponential backoff), resumes session |
| OBS credentials expired | STS tokens are 24h; sessions < 30min idle timeout so always valid |
| User navigates away | WS closes, pod stays alive for 30min idle timeout |

## Database Migration

```sql
CREATE TABLE notebook_sessions (
    id          VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'STARTING',
    pod_name    VARCHAR(128),
    namespace   VARCHAR(128),
    image       VARCHAR(256),
    dataset_ids TEXT,
    last_active_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notebook_sessions_tenant ON notebook_sessions(tenant_id);
CREATE INDEX idx_notebook_sessions_status ON notebook_sessions(status);
```

## Security

- **Tenant isolation**: Each tenant's pod runs in their own namespace with NetworkPolicy (existing infra)
- **Resource limits**: Pod has CPU/memory limits; namespace has ResourceQuota (existing infra)
- **Credential scoping**: OBS STS tokens scoped to tenant's OBS paths only (existing infra)
- **No cross-tenant access**: Session lookup always filters by tenant_id
- **WebSocket auth**: API key validated on WS handshake, connection rejected if invalid

## Ray Support

Phase 1: When user selects "ray" image in the toolbar, the REPL pod uses the ray image. `ray.init()` starts Ray in single-node mode on the same pod. `@ray.remote` functions work but run locally — enough for debugging script logic, API correctness, and data flow. "Submit as Job" creates a real distributed Ray cluster for production execution.

Phase 2 (future): Notebook pod becomes Ray head, worker pods auto-created alongside. User gets distributed execution directly in notebook.

## Not In Scope (Phase 1)

- Distributed Ray cluster in notebook (single-node only, see above)
- Code completion / IntelliSense
- Collaborative editing (multi-user same notebook)
- Persistent notebook saving to OBS (cells only in localStorage)
- Cell output export to file
