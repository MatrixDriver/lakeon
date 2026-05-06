# echomem Dashboard SPA · Design Spec

**Status**: Draft (2026-05-06)
**Scope**: Plan 4 of echomem roadmap — Vue 3 single-page dashboard served by the daemon
**Audience**: implementation agent for the writing-plans → executing-plans pipeline

---

## 1 · Goal & Audience

### Primary goal

Make the daemon's memory + cognition state legible through a browser, and make the **lineage of any cognition** (which raw memories and blobs it came from) the centerpiece. The dashboard is the visual demo of "echomem is not a black box."

### Primary audience: **demo-first**

The first viewer of this dashboard is a colleague / customer / candidate watching a screen-share. Information density is secondary to **narrative clarity**: clicking a synthesized cognition should make it obvious where it came from. The developer-self use case (browsing one's own memory) is served as a side effect, not the design center.

This A · Demo-first orientation drives several decisions below: the Hub-and-Spoke layout, the right-side cascading-column lineage drawer, the keep-empty-and-let-the-demo-fill-it empty-state strategy, and the Quick Ingest button in the top bar.

---

## 2 · Architecture

### 2.1 Repository layout

The echomem project is moving to its own repo at `~/code/echomem`. Layout:

```
echomem/
├── src/echomem/                      # existing Python daemon
│   ├── daemon/app.py                 # FastAPI app — gains a /dashboard mount
│   ├── api/                          # existing routers (memory/derivatives/context/skills/health)
│   └── _dashboard_dist/              # build artifact, gitignored, populated by build script
├── dashboard/                        # NEW — Vue 3 SPA source
│   ├── package.json
│   ├── vite.config.ts                # dev proxy to 8473
│   ├── tsconfig.json
│   ├── index.html
│   ├── public/
│   ├── src/
│   │   ├── main.ts
│   │   ├── App.vue
│   │   ├── router.ts                 # vue-router, hash mode
│   │   ├── stores/                   # Pinia
│   │   │   ├── memory.ts
│   │   │   ├── cognition.ts
│   │   │   ├── lineage.ts
│   │   │   ├── status.ts
│   │   │   └── ui.ts
│   │   ├── api/client.ts             # fetch wrapper
│   │   ├── styles/
│   │   │   ├── tokens.css            # Harbor Editorial palette + type scale + space scale
│   │   │   └── base.css
│   │   ├── components/               # base components, hand-written, no UI library
│   │   │   ├── AppShell.vue
│   │   │   ├── Tile.vue
│   │   │   ├── Card.vue
│   │   │   ├── Tag.vue
│   │   │   ├── Button.vue
│   │   │   ├── EmptyState.vue
│   │   │   ├── Banner.vue
│   │   │   ├── Drawer.vue
│   │   │   ├── ColumnList.vue
│   │   │   ├── Icon.vue              # hand-written SVG inline
│   │   │   └── QuickIngestDialog.vue
│   │   ├── pages/
│   │   │   ├── OverviewPage.vue
│   │   │   ├── MemoryPage.vue
│   │   │   ├── CognitionPage.vue
│   │   │   │   └── views/
│   │   │   │       ├── TimelineView.vue
│   │   │   │       ├── SummaryView.vue
│   │   │   │       ├── GraphView.vue       # d3-force
│   │   │   │       └── SkillView.vue
│   │   │   └── StatusPage.vue
│   │   └── lineage/
│   │       ├── LineageDrawer.vue
│   │       └── lineage.ts            # given a cognition id+kind, derives 3-column data
│   └── tests/
│       ├── unit/
│       └── e2e/
└── scripts/
    └── build_dashboard.sh            # npm ci && npm run build && rsync into _dashboard_dist
```

### 2.2 Distribution / loading model — **B 改良版**

- **Local dev**: daemon runs on `127.0.0.1:8473` (API only), dashboard runs on Vite dev server `localhost:5173` with proxy. Developer hits 5173.
- **Local prod test**: run `bash scripts/build_dashboard.sh`, then daemon serves `/dashboard` from `_dashboard_dist`. Developer hits `http://127.0.0.1:8473/dashboard`.
- **Release**: CI runs `build_dashboard.sh` before `python -m build`. The wheel includes `echomem/_dashboard_dist/**` via Hatch `force-include`. End user runs `pip install echomem` then `echomem start` and the dashboard is served at `/dashboard` with no Node toolchain required.

### 2.3 Daemon mount

`src/echomem/daemon/app.py` gains:

```python
from importlib.resources import files
from fastapi.staticfiles import StaticFiles

dist_path = files("echomem").joinpath("_dashboard_dist")
if dist_path.is_dir():
    app.mount("/dashboard",
              StaticFiles(directory=str(dist_path), html=True),
              name="dashboard")
```

`html=True` makes `/dashboard/` return `index.html`. Hash routing keeps the SPA self-contained — no server-side fallback rules needed.

### 2.4 UI library choice — **bare Vue 3 + CSS variables**

- No TinyVue, no shadcn-vue, no headless library. Hand-written components driven by `tokens.css`.
- The visualization layer adds **only** `d3-force` (~15 KB gzipped). Tree, timeline, and skill cards are CSS — no extra deps.
- Rationale: echomem is leaving the lakeon monorepo, so component reuse with console/admin is moot. The component surface (Sidebar / TopBar / Card / Tag / Table / Button / Drawer / ColumnList / EmptyState / Banner / QuickIngestDialog / Icon) is small enough to write cleanly. impeccable principle 5 — "no AI-generated fingerprint" — is hardest to satisfy by overriding a default-themed library.

### 2.5 Vite dev proxy

```ts
server: {
  port: 5173,
  proxy: {
    '/memory': 'http://127.0.0.1:8473',
    '/derivatives': 'http://127.0.0.1:8473',
    '/context': 'http://127.0.0.1:8473',
    '/skills': 'http://127.0.0.1:8473',
    '/health': 'http://127.0.0.1:8473',
  }
}
```

---

## 3 · Information Architecture

### 3.1 Layout — **Hub-and-Spoke (B)**

Top bar (brand + Quick Ingest) → top tabs → main content area.

**Four** top-level tabs: **总览 · 记忆 · 认知 · 状态**.

Lineage (来源) is intentionally not a top-level tab. It surfaces as a right-side drawer triggered by clicking any cognition card; see §3.4.

**总览** is the hub: counts + the latest cognition rendered as the main stage with its lineage entry-point. Other tabs are spokes for detail browsing.

### 3.2 Routes (vue-router, hash mode)

```
#/                      OverviewPage (default landing)
#/memory                MemoryPage  (with optional ?agent=&q=)
#/cognition             CognitionPage (default sub-view: timeline)
#/cognition/timeline
#/cognition/summary
#/cognition/graph
#/cognition/skill
#/status                StatusPage
```

Any route accepts `?lineage=<cognition_id>&kind=timeline|summary|graph|skill` — that triggers the LineageDrawer without changing the underlying page. Closing the drawer drops the query params.

### 3.3 Naming — "认知" replaces "衍生物"

- UI label: **认知** (cognition). Maps to the four cognitive-science categories embedded in the data model:
  - Timeline events → episodic
  - Summary trees → conceptual
  - Entity graph → relational
  - Skill cards → procedural ("Skill" stays untranslated in the UI per Q9.1·D)
- API path remains `/derivatives/...` — no backend rename. Only UI strings change.

### 3.4 Why lineage is a drawer, not a route

Demo-first: the action sequence "click a cognition → see where it came from" should never lose the surrounding context. A right-slide drawer with three cascading columns (cognition → memories → blob/URL) is Mac Finder column-view familiar; the user keeps the index visible behind it.

The drawer's `?lineage=...&kind=...` query param makes it deep-linkable for sharing, and back-button-closes-drawer for free.

---

## 4 · State Model & Data Flow

### 4.1 Pinia stores

| Store | Responsibility | State |
|---|---|---|
| `useStatusStore` | health, queue depth, global counts (drives top-bar status indicator + Overview tiles) | `health`, `ollama`, `queue:{embedder,timeline,summary,graph,skill}`, `counts:{memories,cognitions,entities,skills}`, `lastError` |
| `useMemoryStore` | memory list with cursor pagination + filters | `items`, `filters:{agent,sourceKind,query}`, `loading`, `cursor`, `hasMore` |
| `useCognitionStore` | cached data for the four sub-views | `timeline`, `summary[]`, `graph:{seed,nodes,edges}`, `skills`, `activeSub` |
| `useLineageStore` | given `(cognition_id, kind)`, resolves three-column data | `current:{cognition,memories,blobs}`, `loading`, `error` |
| `useUiStore` | drawer open/close, quick ingest open/close, polling pause flag, banner state | UI-only |

### 4.2 Polling — 10 s default, 2 s burst, visibility-aware

In `App.vue`:

```ts
const POLL_BASE_MS = 10_000
const POLL_BURST_MS = 2_000
let timer: number | null = null

function tick() {
  if (document.hidden) { schedule(); return }
  statusStore.refresh()
  routeStore.refreshActivePage()
  schedule()
}

function schedule() {
  const interval = statusStore.queueDepth > 0 ? POLL_BURST_MS : POLL_BASE_MS
  timer = window.setTimeout(tick, interval)
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) tick()
})
```

**Burst rule**: the moment any worker queue depth goes above 0, polling tightens to 2 s. As soon as all queues drain, it relaxes to 10 s. The Quick Ingest action manually triggers `tick()` immediately so the demo audience sees the new memory + cognition appear without waiting.

**Visibility**: `document.visibilitychange` pauses ticks when the tab is hidden and forces an immediate refresh on return.

### 4.3 Lineage resolution — pure frontend composition

No new backend endpoint. The frontend composes existing API:

| Cognition kind | Resolution |
|---|---|
| `timeline` | event has `member_memory_ids` → `GET /memory/{id}` for each → unique `source_ref` values starting with `sha256:` form the blob list |
| `summary` | summary node has `source_kind` + `source_ref` → either a memory or a blob; resolve directly |
| `graph` | for an entity node: list memories whose triples reference it (filter via `useMemoryStore` or fetch lazily) |
| `skill` | skill currently lacks a back-reference field; v1 shows "synthesized from N recurring contexts" placeholder. P5 may add `member_memory_ids` to the skill schema |

Trade-off: N+1 GETs to `/memory/{id}` per drawer open. At demo scale (≤20 memories per cognition), that is free. If the project ever needs to scale, add `GET /derivatives/lineage/{kind}/{id}` then.

### 4.4 API client (`src/api/client.ts`)

Thin `fetch` wrapper:

- Single base URL — same origin (works for both `5173` via proxy and `/dashboard` via mount).
- Error classification: `network` (caught `TypeError`), `client` (4xx), `server` (5xx). Each maps to a UI strategy (banner / toast / inline).
- No retry logic — keep it loud, banner shows status.

---

## 5 · Five Pages — Field & Interaction Spec

### 5.1 OverviewPage — the demo main stage

```
─ TopBar ────────────────────────────────────────────────────
  echomem                           [+ Quick ingest]
─ Tabs ──────────────────────────────────────────────────────
  ● 总览   记忆   认知   状态
─ Counts (4 tiles) ──────────────────────────────────────────
  [147 记忆] [38 认知] [12 实体] [3 Skills]
─ Main stage: latest cognition ──────────────────────────────
  小标签: 最近一条认知 · TIMELINE · 04-25 14:32
  H2:    "echomem Plan 4 启动"
  说明:  由 4 条记忆 + 1 个 blob 在 12 秒内合成
  来源胶囊（横排，最多 5 个 + "+N 更多"）：
    [mem · "spec §7.6"] → [mem · "选 B"] → [查看完整来源 →]
─ Recent activity (compact list, 5 rows) ────────────────────
  · 14:38 · 新认知合成 · summary "Hub-Spoke 选定"
  · 14:34 · ingest · "认知改名为 cognition"
  ...
```

**Field sources**:

- Counts: read from the `counts` block of `GET /health/diagnostic` (added by this spec; see §10.1).
- Latest cognition: query `/derivatives/timeline?start_ms=now-7d&end_ms=now` and pick the most recent event. If the result is empty, the page falls through to the second empty-state copy ("memories exist but no cognitions yet") — no fallback to other derivative kinds in v1, to keep the data path one query deep.
- Recent activity: client-side merge of recent memories from `GET /memory/list?limit=20` + the timeline window above. Sorted by timestamp, top 5.

### 5.2 MemoryPage

Compact table, 36 px row height, 13 px font (matches lakeon-admin density per `.impeccable.md` §Dual-App Consistency).

| Column | Source field |
|---|---|
| 时间 (relative) | `created_at` |
| agent | `agent_id` |
| source_kind | `source_kind` |
| 文本预览 (1-line ellipsis) | `text` |
| 操作 | inline buttons (delete) |

**Filters** (header row): agent multi-select (chips), source_kind segment (`explicit` / `session` / `document`), debounced text search (client-side filter — server has no `q` param, fine for v1 sizes).

**Pagination**: cursor-based, "Load more" button using `before` query param.

**Click row**: opens a right drawer (reuses `<Drawer>`) with full text + `meta` JSON pretty-printed + linked cognitions ("appears in 3 timeline events, 2 summary trees" — clicking opens LineageDrawer).

### 5.3 CognitionPage — four sub-views

Top sub-tabs switch among:

#### TimelineView

- Vertical timeline. Left axis shows time anchor (date + time). Right side shows event card.
- Each card: title (Source Serif 4, 16 px), summary preview (Geist Sans, 13 px, 2 lines max), badge with member count.
- Hover reveals `rationale` (LLM's reason for grouping these memories) in a tooltip.
- Click triggers LineageDrawer with `kind=timeline`.

#### SummaryView

- Group by `(source_kind, source_ref)`: each group is a tree.
- Render with native `<details>`, indented 16 px per level, no fancy library.
- Each node: text preview (80 char ellipsis), `token_estimate` badge, level label (`L0` / `L1` / `L2`).
- Click triggers LineageDrawer with `kind=summary`.

#### GraphView

- Top: seed selector (typeahead over entity names) + hops control (1 / 2 / 3, default 2).
- Canvas: 800×500 SVG, d3-force layout. Nodes are circles filled with `--c-primary`, radius scaled by degree (8–18 px). Edges are 1 px hairlines `var(--c-border)`. Labels are 11 px Geist Sans positioned with `text-anchor: middle`, dy offset to avoid overlap with the node.
- Drag nodes (standard d3-force drag handler).
- Click node → LineageDrawer with `kind=graph` (drawer shows: this entity's memories on the right column).
- Legend: tiny color-dot + label list mapping `kind` to fill color (entity / event / person / org / topic if present).

#### SkillView

- CSS grid of cards (3 columns at ≥1024 px, 2 at smaller).
- Card: name (Source Serif 4 18 px) · `trigger_pattern` (mono badge) · steps (numbered list, max 5 visible, expand for more) · `observed_count / success_count` badge.
- Sort by `observed_count` desc.
- Click card → LineageDrawer with `kind=skill` (v1 shows placeholder for source memories — see §4.3).

### 5.4 StatusPage — diagnostic panel (Q9.2 · B)

Three sections:

**Service health**

- daemon: status / version / data dir path / `db.sqlite` size on disk
- ollama: connectivity (ping result + latency) / `generate_model` / `embedding_model` / `embedding_dim`

**Pipeline workers**

- For each worker (embedder / timeline / summary / graph / skill): `last_run_at`, processed memory count, current queue depth, throttle state.

**Dead letters**

- Last 20 failed tasks: `mem_id` / worker name / error kind / retry count / timestamp. Each row expands to show traceback (collapsed by default).

No action buttons in v1 (deferred to P5). Surface only.

**Backend dependency**: this page needs daemon-side fields not currently exposed by `/health`. The spec adds **one new endpoint**: `GET /health/diagnostic` that returns the JSON shape consumed here. This is the only backend API change required by this dashboard.

### 5.5 LineageDrawer — cross-page right drawer

Width: `min(70vw, 960px)`, slides in over current page from the right. Background scrim 8 % opacity.

```
─ Header ─────────────────────────────────────────────
  来源 · "echomem Plan 4 启动"                  [×]
─ 3 columns ─────────────────────────────────────────
  认知 (1)         记忆 (4)                来源 (2)
  ────────────     ──────────────────      ───────────
  ● timeline ev   ● mem · "spec §7.6"     ● blob
    "Plan 4 启动"   mem · "选 B"             readme.md
    2026-04-25     mem · "认知 命名"        blob
    14:32          mem · "10s 轮询"          .impec.md
    rationale ↓
```

- Cascading interaction: clicking column 1's item lights up the rows in columns 2–3 that are upstream sources.
- Column 2 click (a memory) → highlights the blob it came from in column 3 (if any).
- Column 3 click on a blob → modal preview (first 5 KB text via `GET /context/read?sha=...`).
- ESC or `[×]` closes; the back button also closes (because it's a query-param state).

---

## 6 · Visual System (`tokens.css`)

Inherits the Harbor Editorial palette from `.impeccable.md`. Full token list:

```css
:root {
  /* Color */
  --c-primary: #2a4d6a;
  --c-primary-hover: #1e3a52;
  --c-primary-soft: #eef3f7;

  --c-accent: #c67d3a;
  --c-accent-text: #9a5b25;
  --c-accent-light: #fdf5ed;
  --c-accent-hover: #b56e2d;

  --c-bg: #ffffff;
  --c-bg-alt: #faf8f5;
  --c-bg-canvas: #f5f1ec;

  --c-text: #2c3e50;
  --c-text-muted: #6b7c8a;
  --c-text-faint: #98a4ae;

  --c-border: #e8e4df;
  --c-border-hover: #d6cfc6;
  --c-divider: #f0ece6;

  --c-danger: #c2614a;
  --c-success: #5fa86e;
  --c-warning: var(--c-accent);

  /* Type */
  --font-display: 'Source Serif 4', Georgia, 'Times New Roman', serif;
  --font-body: 'Geist Sans', system-ui, -apple-system, 'Segoe UI', sans-serif;
  --font-mono: 'JetBrains Mono', 'SF Mono', Menlo, monospace;

  --fs-xs: 11px;  --fs-sm: 12.5px;  --fs-md: 14px;
  --fs-lg: 16px;  --fs-h3: 18px;    --fs-h2: 22px;  --fs-h1: 28px;

  --lh-tight: 1.35;  --lh-body: 1.55;  --lh-loose: 1.7;
  --fw-regular: 400; --fw-medium: 500; --fw-semibold: 600;

  /* Space — 4 pt scale */
  --space-xxs: 2px;  --space-xs: 4px;   --space-sm: 8px;
  --space-md: 12px;  --space-lg: 16px;  --space-xl: 24px;
  --space-2xl: 32px; --space-3xl: 48px;

  /* Radius */
  --radius-sm: 3px; --radius-md: 4px; --radius-lg: 6px;

  /* Shadow */
  --shadow-sm: 0 1px 2px rgba(44, 62, 80, 0.04);
  --shadow-drawer: -4px 0 16px rgba(44, 62, 80, 0.06);

  /* Motion */
  --ease-out: cubic-bezier(0.22, 1, 0.36, 1);
  --ease-in-out: cubic-bezier(0.65, 0, 0.35, 1);
  --t-fast: 120ms; --t-base: 200ms; --t-drawer: 280ms;

  /* Layout */
  --top-bar-h: 48px;
  --tabs-h: 38px;
  --content-max: 1280px;
  --table-row-h: 36px;
  --table-cell-px: 12px;
}

@media (prefers-reduced-motion: reduce) {
  :root { --t-fast: 0ms; --t-base: 0ms; --t-drawer: 0ms; }
}
```

### Fonts — self-hosted via fontsource

```ts
import '@fontsource-variable/source-serif-4/index.css'
import '@fontsource-variable/geist/index.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
```

No Google Fonts CDN — preserves echomem's offline-first invariant and avoids tracking.

### Forbidden (`.impeccable.md` Design Principles 5 — "no AI fingerprint")

- `border-left: 3px+` decorative color stripes.
- `background-clip: text` gradient text.
- Any emoji as icon (✓ ✗ 🚀 🎉 etc.).
- Card-inside-card nesting.
- Single font family across the whole app.
- Purple→blue gradients, neon, cyan-on-black.
- Hero-big-number + small-label template.
- Bounce / elastic easing.

### Required

- Page titles in Source Serif 4; body / buttons / labels in Geist Sans.
- `:focus-visible { outline: 2px solid var(--c-accent); outline-offset: 2px; }` on every interactive element.
- 4 pt spacing scale via `--space-*` tokens — no raw pixel literals in component CSS.
- Tab/sub-tab active state uses 1.5 px bottom border in `--c-accent`, never a background fill.
- Tables have no zebra stripes; row hover uses `var(--c-primary-soft)`.
- Status uses paired encoding: dot color + text label (color is never the only signal).

### Icons

No icon library. Hand-written inline SVG behind a `<Icon name="...">` component. Required set: `arrow-right`, `chevron-down`, `close`, `plus`, `refresh`, `dot`, `search`. Total ≈ 6–8 icons, each ~200 bytes.

---

## 7 · Errors, Empty States, Loading

### 7.1 Error layers

| Type | Trigger | UI |
|---|---|---|
| daemon unreachable (network err / connection refused) | `fetch` throws `TypeError` | Top banner, 1 px top border in `--c-danger`, body in `--c-bg-alt`, copy: "无法连接 echomem daemon (127.0.0.1:8473)。请确认 `echomem start` 在跑。" with a "重试" button that pings `/health` |
| daemon 5xx | `response.status >= 500` | Same banner, copy includes status code + truncated response body |
| daemon 4xx | `response.status >= 400` | Inline toast bottom-right, dismisses after 3 s, does not block main flow |
| ollama unreachable | `/health/diagnostic` reports `ollama.status !== 'ok'` | Status page inline warning + Overview banner: "AI worker 暂停——ollama 离线。已 ingest 的记忆会在恢复后自动消化" |

Banners are 1 px top border + 8 px padding only — never solid color blocks (violates `.impeccable.md` 节奏胜过对齐).

### 7.2 Empty states (per page, hand-written prose)

| Page | Copy |
|---|---|
| Overview (no memories) | **"echomem 是空的。** 试试看：`echomem mem ingest "今天的笔记" --agent cli` 或点右上角 **+ Quick ingest**。" |
| Overview (memories exist, no cognitions yet) | **"记忆已就位，AI worker 还在消化中。** 第一条认知通常在 ingest 后 30–90 秒出现。状态页可看队列进度。" |
| Memory (no rows) | **"还没有记忆。** 用 CLI、HTTP 或 MCP 任一入口都可以。`curl 127.0.0.1:8473/memory/ingest -d '...'`" |
| Cognition (all four sub-views empty) | **"AI worker 待机中。** 当 agent 数据足够形成时间窗口 / 摘要层级 / 实体关系 / 重复操作时，对应的认知会自动出现。" |
| Status (pipeline idle) | **"管道空闲。** `ingest` 一条记忆触发新的处理周期。" |

Rendered by a single `<EmptyState title body actions>` component. Action buttons trigger Quick Ingest or copy the curl command.

### 7.3 Loading

- **First paint / route change**: skeleton cards (warm-grey shimmer, 1.2 s cycle).
- **Polling refresh**: never re-skeleton the main area. Instead a 1 px high progress bar in `--c-accent` fades in/out at the top of the viewport during the in-flight request. This is the demo's "look, it's thinking" affordance.
- **Drawer first open**: per-column skeleton; subsequent opens use cached lineage data.

### 7.4 Quick Ingest dialog

Triggered from top-bar `+ Quick ingest` button. Modal:

```
─ + Quick ingest ───────────────────────────────────────
  agent: [cli      ▾]   source_kind: [explicit ▾]
  ┌─────────────────────────────────────────────────┐
  │ (textarea, 6 rows, monospace, autofocus)         │
  └─────────────────────────────────────────────────┘
  ⌘+Enter 提交
                                  [取消]   [Ingest]
```

Submit: `POST /memory/ingest` → success toast "已 ingest，认知合成中..." → `statusStore.tick()` immediately → polling enters burst mode automatically because queue depth rises → main stage on Overview shows the new cognition within ~30–90 s (depending on Ollama model speed).

Failure: red toast with detail; textarea preserved.

`agent` dropdown options: union of agents seen in `useMemoryStore.items` plus a free-text "other..." input.

---

## 8 · Auth

**localhost-only, no token** (Q9.3 · A). The daemon binds 127.0.0.1:8473; same-origin SPA; CORS not relevant. No bearer token, no basic auth. Multi-user / remote serving is explicitly out of scope for this Plan; revisit if echomem ever gains a remote-mount mode.

---

## 9 · Testing

### 9.1 Unit (vitest)

Coverage targets:

- Pinia stores: 100 % line coverage on `lineage.ts`, `cognition.ts`, `status.ts`, `memory.ts`, `ui.ts`. Critical paths: cursor pagination merging, lineage 3-column resolution, polling burst/relax transitions, drawer state machine.
- API client: 100 %, including all four error classes (network / 4xx / 5xx / parse).
- Component-level interaction tests for `LineageDrawer`, `QuickIngestDialog`, `EmptyState`, `Banner`. Page-level components are covered by E2E.

### 9.2 E2E (Playwright)

Three scripts. Each starts a fresh daemon against a temp data directory and the Vite dev server.

#### `e2e/01-empty-to-first-cognition.spec.ts` — the demo path

1. Start daemon with empty `~/.echomem-test`.
2. Start dashboard dev server.
3. Visit `http://localhost:5173`.
4. Assert Overview shows the empty-state copy + Quick Ingest button.
5. Click `+ Quick ingest`, type "今天我决定用 Hub-and-Spoke 布局", submit.
6. Within 90 s assert memory count goes from 0 → 1.
7. Within 120 s assert at least one timeline cognition appears.
8. Click that cognition card. Assert LineageDrawer slides in with three columns: 1 cognition / 1 memory / 0 blobs.

#### `e2e/02-derivative-views-render.spec.ts`

Uses a pre-generated fixture SQLite database with ~10 memories and complete derivatives. Walks through all four sub-views and asserts non-empty rendering for each.

#### `e2e/03-error-recovery.spec.ts`

1. Start dashboard but **not** daemon.
2. Assert top banner "无法连接 daemon".
3. Start daemon.
4. Click banner's retry button.
5. Assert banner clears and main area refreshes.

### 9.3 Real-Ollama policy

Per `lakeon/CLAUDE.md`'s "不能作假 / 不能 SKIP" rule: `e2e/01` uses real Ollama. CI starts an Ollama container with a small model (`gemma:2b`, ~1 GB). Local `pytest` does not require Ollama by default — `e2e/01` is gated behind `npm run test:e2e:full`. CI runs the full set on every push.

---

## 10 · Required Backend Changes

This dashboard requires **one** small daemon addition.

### 10.1 `GET /health/diagnostic`

Returns the shape consumed by both the StatusPage (§5.4) and the Overview tiles / status bar (§5.1). Single endpoint, single round-trip.

```json
{
  "daemon": {
    "status": "ok",
    "version": "0.4.0",
    "data_dir": "/Users/jacky/.echomem",
    "db_size_bytes": 42113024
  },
  "ollama": {
    "status": "ok",
    "latency_ms": 12,
    "generate_model": "gemma2:2b",
    "embedding_model": "nomic-embed-text",
    "embedding_dim": 768
  },
  "workers": {
    "embedder": { "queue_depth": 0, "last_run_at": 1714030392, "processed_total": 147, "throttle": null },
    "timeline": { "queue_depth": 0, "last_run_at": 1714030392, "processed_total": 38,  "throttle": null },
    "summary":  { "queue_depth": 0, "last_run_at": 1714030392, "processed_total": 14,  "throttle": null },
    "graph":    { "queue_depth": 0, "last_run_at": 1714030392, "processed_total": 12,  "throttle": null },
    "skill":    { "queue_depth": 0, "last_run_at": 1714030392, "processed_total": 3,   "throttle": null }
  },
  "counts": {
    "memories": 147,
    "cognitions": 38,
    "entities": 12,
    "skills": 3
  },
  "dead_letter": [
    { "mem_id": "01H...", "worker": "summary", "kind": "ollama_timeout",
      "retries": 3, "at": 1714030310, "traceback": "..." }
  ]
}
```

`ollama.status` is `"ok"` | `"unreachable"` | `"timeout"`. The frontend treats anything other than `"ok"` as triggering the ollama warning banner.

Implementation: read existing in-memory state from `Orchestrator` and worker objects; the `counts` and `dead_letter` blocks read directly from the SQLite driver. No new persistence.

### 10.2 No other backend changes

Lineage resolution, memory list paging, recall, ingest, derivatives queries — all use existing endpoints documented in `src/echomem/api/`.

---

## 11 · Build & Release

### 11.1 `scripts/build_dashboard.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/.."

cd dashboard
npm ci
npm run build

DIST_TARGET="../src/echomem/_dashboard_dist"
rm -rf "$DIST_TARGET"
mkdir -p "$DIST_TARGET"
cp -R dist/. "$DIST_TARGET/"
echo "$(git rev-parse --short HEAD)" > "$DIST_TARGET/_BUILD_INFO"

echo "Dashboard built into $DIST_TARGET"
```

### 11.2 `pyproject.toml`

```toml
[tool.hatch.build.targets.wheel]
packages = ["src/echomem"]
force-include = { "src/echomem/_dashboard_dist" = "echomem/_dashboard_dist" }
```

### 11.3 `.gitignore`

```
dashboard/node_modules/
dashboard/dist/
src/echomem/_dashboard_dist/
```

### 11.4 CI skeleton (GitHub Actions)

```yaml
- name: Build dashboard
  run: bash scripts/build_dashboard.sh
- name: Run python tests
  run: pytest
- name: Run dashboard unit tests
  run: cd dashboard && npm test
- name: Run dashboard E2E (with real Ollama)
  run: |
    docker compose -f .github/ollama-compose.yml up -d
    cd dashboard && npm run test:e2e:full
- name: Build wheel
  run: python -m build
- name: Verify wheel contains dashboard
  run: unzip -l dist/*.whl | grep _dashboard_dist/index.html
```

---

## 12 · Out of Scope (Plan 5+)

- Edit / delete cognitions from the UI (currently view-only — delete exists for memories only)
- Real-time WebSocket updates (polling is sufficient for v1)
- Multi-agent UI switching beyond filter (single-user assumption holds)
- Status-page action buttons (rerun worker / clear dead letter / switch model)
- Mobile / small-screen responsive layout (≥1024 px target only)
- Bearer-token / basic-auth modes
- Skill back-references in lineage (requires schema addition)
- Custom theming (Light only)

---

## 13 · Open Questions / Risks

- **Real Ollama in CI**: ~1 GB model download per CI run. Needs caching + provisioner check before this plan ships. Mitigation: CI uses GitHub Actions `cache` with the model dir as the key; falls back to a smaller `tinyllama` if `gemma:2b` is unavailable.
- **N+1 lineage GETs**: cosmetic at v1 scale (≤20 memories per cognition). If a single power-user instance ever has cognitions referencing 200+ memories, add `GET /derivatives/lineage/{kind}/{id}`.
- **Hatch `force-include` semantics**: needs validation that the path arrives in the wheel as `echomem/_dashboard_dist/...` (not `src/echomem/_dashboard_dist/...`). If `force-include` doesn't strip prefixes correctly, fall back to copying the dist into a different staging location and including via `packages` entry.
- **Vue Router hash mode + StaticFiles**: requires that opening `/dashboard/` (with trailing slash) returns `index.html`. `StaticFiles(html=True)` does this; verify with the verification step in the implementation plan.

---

## 14 · Decision Trail

| # | Decision | Choice | Why |
|---|---|---|---|
| 1 | Audience | Demo-first | The first viewer is a colleague / customer / candidate watching a screen-share. Narrative clarity > information density. |
| 2 | Distribution | B 改良版 — independent repo, Vite dev (5173) + wheel-packaged dist | Clean git history, one-command install. CI builds dashboard before wheel. |
| 3 | UI library | Bare Vue 3 + tokens.css | echomem leaves the lakeon monorepo, so component reuse with console/admin is moot. Custom components keep the impeccable "no AI fingerprint" rule satisfied. |
| 4 | Visualization | D3-force + CSS-only for tree/timeline/skill | Smallest dep footprint; full visual control. Cytoscape is overkill at our scale. |
| 5 | Polling | 10 s default, 2 s burst when queue > 0, visibility-aware | Quiet at idle, lively during demo. WebSocket is P5+. |
| 6 | Empty / error | Crafted prose empty states + 1 px banner errors + Quick Ingest button | Empty itself is part of the demo narrative. No solid color blocks. |
| 7 | IA | Hub-and-Spoke (B): top tabs, Overview is the demo stage | Latest cognition + lineage is the protagonist of the home page. |
| 8 | Naming | "认知" replaces "衍生物" in UI; backend stays `/derivatives` | Maps to cognitive-science categories (episodic / conceptual / relational / procedural). |
| 9 | Lineage | Right-side drawer with three cascading columns; deep-linkable via `?lineage=&kind=` | Mac Finder mental model. Demo flow: click → drawer slides in → narrate left to right. |
| 10 | Skill subcategory | Untranslated `Skill` | Aligns with code / API. |
| 11 | Status page | Diagnostic surface, no action buttons | View-only is enough for v1; actions deferred to P5. |
| 12 | Auth | localhost-only, no token | daemon is single-user single-machine. Matches existing daemon binding. |
