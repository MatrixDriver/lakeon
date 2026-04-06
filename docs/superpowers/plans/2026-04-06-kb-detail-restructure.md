# KB 详情页重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 KB 详情页为 Wiki(3栏) + 文档(全宽) 两个一级 tab，增加空 KB 引导页，Admin 增加 Wiki Agent prompt 管理。

**Architecture:** Console 前端从二级 tab 嵌套改为扁平两 tab。Wiki tab 采用页面列表+内容+图谱三栏布局，图谱可折叠。文档 tab 保留原有功能并整合切片。后端 wiki prompt 从硬编码改为数据库可配置，admin 新增编辑界面。

**Tech Stack:** Vue 3 + TypeScript (console/admin), Spring Boot + Java 17 (API), PostgreSQL

---

### Task 1: 重构 KnowledgeBaseDetail.vue — Wiki Tab 三栏布局

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue:1-640` (template + script tabs 部分)
- Modify: `lakeon-console/src/views/knowledge/WikiPage.vue` (全文 97 行)
- Modify: `lakeon-console/src/views/knowledge/WikiGraph.vue` (全文 124 行)

**目标：** 将 Wiki tab 从"WikiPage(55%) + WikiGraph(45%)"并排改为三栏布局：WikiPage 页面列表(190px) | 内容(flex) | WikiGraph(320px, 可折叠)。去掉 sources pane。

- [ ] **Step 1: 修改 tabs 定义**

在 `KnowledgeBaseDetail.vue` 中找到 tabs 数组（约 line 629-632），改为：

```typescript
const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'doc', label: '文档' },
  { key: 'wiki', label: 'Wiki' },
]
```

将 `activeTab` 默认值改为 `'overview'`。去掉 `manageTabs` 和 `manageSubTab`（约 line 634-641）。

- [ ] **Step 2: 重写 Wiki tab template**

将 Wiki tab 区域（约 line 33-40）从并排两 pane 改为三栏：

```html
<!-- Wiki Tab -->
<div v-if="activeTab === 'wiki'" style="display: flex; height: calc(100vh - 200px); margin-top: 0; position: relative;">
  <!-- WikiPage takes full left+center area -->
  <div style="flex: 1; overflow: hidden;">
    <WikiPage ref="wikiPageRef" :kb-id="(route.params.kbId as string)" />
  </div>
  <!-- Graph panel (collapsible) -->
  <div v-if="showGraph" style="width: 320px; border-left: 1px solid #e8e0d8; flex-shrink: 0; display: flex; flex-direction: column;">
    <div style="padding: 8px 12px; border-bottom: 1px solid #f0ebe4; display: flex; align-items: center; font-size: 13px; font-weight: 600; color: #3d3d3d;">
      知识图谱
      <span style="flex: 1;"></span>
      <span style="cursor: pointer; color: #bbb; font-size: 16px;" @click="showGraph = false" title="收起图谱">&times;</span>
    </div>
    <div style="flex: 1; overflow: hidden;">
      <WikiGraph :kb-id="(route.params.kbId as string)" @navigate="handleGraphNavigate" />
    </div>
  </div>
  <!-- Graph toggle button (shown when collapsed) -->
  <button v-if="!showGraph"
    style="position: absolute; right: 12px; top: 12px; padding: 4px 10px; font-size: 11px; border: 1px solid #e0d8ce; border-radius: 4px; background: #fff; color: #8c7a68; cursor: pointer; z-index: 2;"
    @click="showGraph = true">
    图谱
  </button>
</div>
```

- [ ] **Step 3: 添加 showGraph 状态变量**

在 script setup 中添加：

```typescript
const showGraph = ref(true)
```

- [ ] **Step 4: 修改 WikiPage.vue 为内含两栏布局**

WikiPage 已有页面列表 + 内容的布局。确认它的 `navigateToTitle` 方法仍通过 defineExpose 暴露。在 WikiPage 顶部添加工具栏（显示统计 + 更新记录按钮）：

在 WikiPage.vue template 最外层 div 内，内容区之前添加：

```html
<div style="padding: 8px 16px; border-bottom: 1px solid #f0ebe4; display: flex; align-items: center; gap: 8px; background: #fdfcfa;">
  <span style="font-size: 13px; font-weight: 600; color: #3d3d3d;">Wiki 页面</span>
  <span style="flex: 1;"></span>
  <span style="font-size: 11px; color: #bbb;">{{ pages.length }} 页</span>
  <button @click="showLog = true" style="padding: 3px 8px; font-size: 11px; border: 1px solid #e0d8ce; border-radius: 3px; background: #fff; color: #8c7a68; cursor: pointer; display: flex; align-items: center; gap: 4px;">
    <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
    更新记录
  </button>
</div>
```

过滤掉 index 和 log 页面，不在列表中显示：

```typescript
const displayPages = computed(() =>
  pages.value.filter(p => p.title !== 'index' && p.title !== 'log')
)
```

- [ ] **Step 5: 添加更新记录抽屉组件**

在 WikiPage.vue 中添加 log 抽屉（log.md 的 UI 替代）：

```html
<!-- Log drawer -->
<div v-if="showLog" style="position: fixed; right: 0; top: 0; width: 360px; height: 100vh; background: #fff; border-left: 1px solid #e8e0d8; box-shadow: -4px 0 12px rgba(0,0,0,0.06); z-index: 100; display: flex; flex-direction: column;">
  <div style="padding: 14px 16px; border-bottom: 1px solid #eee; display: flex; align-items: center; font-size: 14px; font-weight: 600;">
    更新记录
    <span style="flex: 1;"></span>
    <span style="cursor: pointer; color: #999; font-size: 18px;" @click="showLog = false">&times;</span>
  </div>
  <div style="flex: 1; overflow-y: auto; padding: 16px;">
    <div v-if="logContent" style="font-size: 13px; color: #444; line-height: 1.8; white-space: pre-wrap;">{{ logContent }}</div>
    <div v-else style="color: #ccc; text-align: center; padding: 40px;">暂无更新记录</div>
  </div>
</div>
```

添加相关状态和加载逻辑：

```typescript
const showLog = ref(false)
const logContent = ref('')

watch(showLog, async (val) => {
  if (val && !logContent.value) {
    // Find the log page and load its content
    const logPage = pages.value.find(p => p.title === 'log')
    if (logPage) {
      const res = await getWikiPageContent(props.kbId, logPage.id)
      logContent.value = res.content || ''
    }
  }
})
```

- [ ] **Step 6: 运行 type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```

Expected: 通过，无类型错误。

- [ ] **Step 7: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue lakeon-console/src/views/knowledge/WikiPage.vue lakeon-console/src/views/knowledge/WikiGraph.vue
git commit -m "feat(console): restructure Wiki tab — 3-column layout with collapsible graph"
```

---

### Task 2: 重构 KnowledgeBaseDetail.vue — 文档 Tab

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue:43-450` (template 文档管理区域)

**目标：** 将原来的 manage tab + sub-tabs（文档/数据源/搜索/切片/概览）整合为单个文档 tab，去掉搜索和概览 sub-tab，切片改为文档行的下钻操作。

- [ ] **Step 1: 重写文档 tab template**

将 `activeTab === 'manage'` 区域（约 line 43-448）改为 `activeTab === 'doc'`。去掉 sub-tab 导航条。直接展示：

1. 导入按钮行（上传文件、上传目录、导入 URL、OBS 数据源）
2. 上传/处理进度条（保留原有）
3. 状态筛选（保留原有）
4. 文件夹网格（保留原有）
5. 文档表格（保留原有，操作列增加"切片"链接）
6. 分页（保留原有）

去掉的部分：
- sub-tab 导航条（约 line 45-51）
- `manageSubTab === 'search'` 区域（约 line 378-445）
- `manageSubTab === 'overview'` 区域（约 line 53-78）
- `manageSubTab === 'chunks'` 区域（约 line 373-376）

把 `manageSubTab === 'documents'` 的内容直接放在 doc tab 下（去掉 v-if 条件）。
把 `manageSubTab === 'datasources'` 的内容放在文档列表下方（用一个分隔线 + 标题"OBS 数据源"分隔）。

- [ ] **Step 2: 在文档表格操作列添加"切片"链接**

在文档表格的操作列（约 line 259）添加切片链接：

```html
<button v-if="doc.status === 'READY'" class="btn btn-text btn-small" style="color: #9a5b25;" @click.stop="router.push({ name: 'DocumentDetail', params: { kbId: route.params.kbId, docId: doc.id } })">切片</button>
```

- [ ] **Step 3: 清理无用变量**

删除 `manageTabs`、`manageSubTab` 变量定义和相关引用。删除 `searchQuery`、`searchResults`、`searchRewrittenQuery`、`isSearching` 等搜索相关状态（约 line 548-553）。删除 `handleSearch` 和搜索相关函数。

- [ ] **Step 4: 运行 type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): flatten doc tab — remove sub-tabs, integrate datasources"
```

---

### Task 3: 概览 Tab + 空 KB 引导页

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` (新增概览 tab 区域)

**目标：** 概览 tab 作为默认首页，显示 KB 基础信息。当 KB 无文档时，概览 tab 额外显示引导页面（导入→Wiki→对话→沉淀四步 + 上传区）。

- [ ] **Step 1: 添加空状态判断**

利用已有的 `docStats.total`（已在 onMounted 中加载）判断 KB 是否为空：

```typescript
const isEmptyKb = computed(() => docStats.value.total === 0 && !docLoading.value)
```

- [ ] **Step 2: 添加概览 tab template**

在 tab 内容区域添加概览 tab（复用原 overview sub-tab 的 KB 信息卡片 + 空状态引导）：

```html
<!-- 概览 Tab -->
<div v-if="activeTab === 'overview'" style="margin-top: 24px;">
  <!-- KB 信息卡片 -->
  <div class="section-card" style="max-width: 600px;">
    <div class="section-header">知识库信息</div>
    <div style="padding: 16px; display: grid; grid-template-columns: 120px 1fr; gap: 12px; font-size: 14px;">
      <span style="color: #999;">名称</span><span>{{ kb?.name }}</span>
      <span style="color: #999;">描述</span><span>{{ kb?.description || '-' }}</span>
      <span style="color: #999;">文档数</span><span>{{ kb?.document_count ?? 0 }}</span>
      <span style="color: #999;">存储大小</span><span>{{ storageDisplay }}</span>
      <span style="color: #999;">Embedding 模型</span><span>BGE-M3 (1024维)</span>
      <span style="color: #999;">切片策略</span><span>结构化切片 (400 tokens)</span>
      <span style="color: #999;">状态</span>
      <span><span class="status-tag" :class="'tag-' + (kb?.status === 'READY' ? 'green' : 'blue')">{{ kb?.status === 'READY' ? '就绪' : kb?.status }}</span></span>
      <span style="color: #999;">创建时间</span><span>{{ kb?.created_at ? new Date(kb.created_at).toLocaleString('zh-CN') : '-' }}</span>
      <span style="color: #999;">底层数据库</span>
      <span v-if="kb?.database_id">
        <router-link :to="'/databases/' + kb.database_id" style="color: #2563eb; text-decoration: none;">{{ kb.database_id }}</router-link>
      </span>
      <span v-else>-</span>
    </div>
  </div>

  <!-- KB Summary -->
  <div v-if="kb?.summary" class="section-card" style="max-width: 600px; margin-top: 16px;">
    <div class="section-header">知识库概览</div>
    <div style="padding: 16px; font-size: 14px; line-height: 1.8; color: #333; white-space: pre-wrap;">{{ kb.summary }}</div>
  </div>

  <!-- Empty KB onboarding -->
  <div v-if="isEmptyKb" style="margin-top: 32px; display: flex; justify-content: center;">
    <div style="max-width: 560px; text-align: center;">
      <h2 style="font-size: 18px; color: #2c2420; margin-bottom: 6px;">开始构建你的知识库</h2>
      <p style="color: #999; font-size: 13px; margin-bottom: 24px;">上传文档，AI 自动整理为结构化的 Wiki 知识体系</p>
  <div style="max-width: 560px; text-align: center; padding: 40px;">
    <h2 style="font-size: 20px; color: #2c2420; margin-bottom: 8px;">开始构建你的知识库</h2>
    <p style="color: #999; font-size: 14px; margin-bottom: 28px;">上传文档，AI 自动整理为结构化的 Wiki 知识体系</p>
    <div style="display: flex; gap: 8px; margin-bottom: 28px;">
      <div style="flex: 1; background: #fdf6f4; border: 1px solid #c25a3c; border-radius: 8px; padding: 16px 10px; text-align: center;">
        <div style="width: 26px; height: 26px; border-radius: 50%; background: #c25a3c; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; margin-bottom: 6px;">1</div>
        <div style="font-size: 13px; font-weight: 600; color: #3d3d3d; margin-bottom: 3px;">导入</div>
        <div style="font-size: 11px; color: #999;">上传文件、目录或 URL</div>
      </div>
      <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
      <div style="flex: 1; background: #faf8f5; border: 1px solid #e8e0d8; border-radius: 8px; padding: 16px 10px; text-align: center;">
        <div style="width: 26px; height: 26px; border-radius: 50%; background: #e8e0d8; color: #8c7a68; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; margin-bottom: 6px;">2</div>
        <div style="font-size: 13px; font-weight: 600; color: #3d3d3d; margin-bottom: 3px;">Wiki</div>
        <div style="font-size: 11px; color: #999;">AI 自动生成 Wiki 和知识图谱</div>
      </div>
      <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
      <div style="flex: 1; background: #faf8f5; border: 1px solid #e8e0d8; border-radius: 8px; padding: 16px 10px; text-align: center;">
        <div style="width: 26px; height: 26px; border-radius: 50%; background: #e8e0d8; color: #8c7a68; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; margin-bottom: 6px;">3</div>
        <div style="font-size: 13px; font-weight: 600; color: #3d3d3d; margin-bottom: 3px;">对话</div>
        <div style="font-size: 11px; color: #999;">向知识库提问，深度探索</div>
      </div>
      <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
      <div style="flex: 1; background: #faf8f5; border: 1px solid #e8e0d8; border-radius: 8px; padding: 16px 10px; text-align: center;">
        <div style="width: 26px; height: 26px; border-radius: 50%; background: #e8e0d8; color: #8c7a68; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; margin-bottom: 6px;">4</div>
        <div style="font-size: 13px; font-weight: 600; color: #3d3d3d; margin-bottom: 3px;">沉淀</div>
        <div style="font-size: 11px; color: #999;">洞察保存回 Wiki</div>
      </div>
    </div>
    <label style="display: block; background: #faf8f5; border: 2px dashed #d4c4b0; border-radius: 8px; padding: 28px; cursor: pointer; transition: all 0.2s;"
           @mouseover="($event.target as HTMLElement).style.borderColor = '#c25a3c'"
           @mouseout="($event.target as HTMLElement).style.borderColor = '#d4c4b0'">
      <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="#c25a3c" stroke-width="1.5" style="margin-bottom: 8px;"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
      <p style="font-size: 13px; color: #5a4a3a; margin-bottom: 4px;">点击上传文件开始</p>
      <p style="font-size: 12px; color: #aaa;">支持 PDF、DOCX、Markdown、TXT 等格式</p>
      <input type="file" accept=".pdf,.docx,.doc,.xlsx,.xls,.xlsm,.pptx,.epub,.html,.htm,.md,.markdown,.txt" multiple style="display: none;" @change="handleUpload" />
    </label>
    <div style="margin-top: 12px;">
      <button @click="activeTab = 'doc'" style="padding: 5px 14px; font-size: 12px; border: 1px solid #d4c4b0; border-radius: 4px; background: #fff; color: #5a4a3a; cursor: pointer;">
        或前往文档 Tab 使用更多导入方式
      </button>
    </div>
    </div>
  </div>
</div>
```

- [ ] **Step 3: 运行 type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): add empty KB onboarding guide"
```

---

### Task 4: 后端 — Wiki Agent Prompt 可配置化

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java:38-80` (硬编码 prompt)
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java` (新增 admin 端点)
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java` (新增 wiki prompt 配置)

**目标：** 将 WikiService 中硬编码的 WIKI_AGENT_PROMPT 改为从配置读取，新增 admin API 端点支持在线编辑。

- [ ] **Step 1: 在 LakeonProperties 中添加 wiki prompt 配置项**

在 LakeonProperties.java 的 WikiConfig 内部类中添加：

```java
private String ingestPrompt = ""; // default loaded from WikiService
```

- [ ] **Step 2: 修改 WikiService 支持动态 prompt**

将 `WIKI_AGENT_PROMPT` 常量改为可通过配置覆盖：

```java
private String getIngestPrompt() {
    String custom = lakeonProperties.getWiki().getIngestPrompt();
    if (custom != null && !custom.isBlank()) {
        return custom;
    }
    return DEFAULT_WIKI_AGENT_PROMPT; // 原来的硬编码内容作为默认值
}
```

在 processIngest() 和 chat() 方法中，将直接引用 `WIKI_AGENT_PROMPT` 改为调用 `getIngestPrompt()`。

- [ ] **Step 3: 添加 Admin API 端点**

在 KnowledgeController.java 中添加（需 admin token 验证）：

```java
@GetMapping("/admin/wiki/config")
public Map<String, Object> getWikiConfig(@RequestHeader("X-Admin-Token") String token) {
    validateAdminToken(token);
    return Map.of(
        "ingest_prompt", getIngestPromptWithDefault(),
        "model", lakeonProperties.getWiki().getModel(),
        "base_url", lakeonProperties.getWiki().getBaseUrl()
    );
}

@PutMapping("/admin/wiki/config")
public Map<String, Object> updateWikiConfig(
        @RequestHeader("X-Admin-Token") String token,
        @RequestBody Map<String, String> body) {
    validateAdminToken(token);
    // Update in-memory config (persists until restart)
    if (body.containsKey("ingest_prompt")) {
        lakeonProperties.getWiki().setIngestPrompt(body.get("ingest_prompt"));
    }
    return Map.of("status", "ok");
}
```

- [ ] **Step 4: 编译验证**

```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java
git commit -m "feat(api): make wiki agent prompt configurable via admin API"
```

---

### Task 5: Admin 控制台 — Wiki Agent 配置 Tab

**Files:**
- Modify: `lakeon-admin/src/views/knowledge/KnowledgeList.vue:8-12` (tabs 定义)
- Modify: `lakeon-admin/src/api/admin.ts` (新增 wiki admin API)

**目标：** 在 admin KB 管理页面新增"Wiki Agent"tab，包含 prompt 编辑器和 LLM 配置。

- [ ] **Step 1: 在 admin.ts 添加 wiki admin API**

在 `lakeon-admin/src/api/admin.ts` 的 knowledge 区域末尾添加：

```typescript
getWikiConfig: () => api.get('/admin/wiki/config'),
updateWikiConfig: (data: { ingest_prompt?: string; model?: string; base_url?: string }) =>
  api.put('/admin/wiki/config', data),
```

- [ ] **Step 2: 在 KnowledgeList.vue 添加 tab**

在 tabs 定义中添加第四个 tab：

```html
<span :class="{ active: activeTab === 'wiki-agent' }" @click="activeTab = 'wiki-agent'">Wiki Agent</span>
```

- [ ] **Step 3: 添加 Wiki Agent tab 内容**

在 template 中添加 wiki-agent tab 的内容区：

```html
<div v-if="activeTab === 'wiki-agent'" style="padding: 24px;">
  <h3 style="font-size: 16px; margin-bottom: 16px;">Wiki Agent 配置</h3>

  <!-- LLM 配置 -->
  <div style="background: #f9f9f9; border: 1px solid #e8e8e8; border-radius: 8px; padding: 16px; margin-bottom: 20px; max-width: 600px;">
    <div style="font-weight: 600; margin-bottom: 12px;">LLM 配置</div>
    <div style="display: grid; grid-template-columns: 100px 1fr; gap: 8px; align-items: center; font-size: 13px;">
      <span style="color: #666;">Model</span>
      <input v-model="wikiConfig.model" class="form-input" style="max-width: 300px;" />
      <span style="color: #666;">Base URL</span>
      <input v-model="wikiConfig.base_url" class="form-input" style="max-width: 400px;" />
    </div>
  </div>

  <!-- Ingest Prompt -->
  <div style="margin-bottom: 16px;">
    <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
      <span style="font-weight: 600;">Ingest Prompt</span>
      <span style="font-size: 12px; color: #999;">文档导入后生成/更新 Wiki 页面的系统提示词</span>
    </div>
    <textarea v-model="wikiConfig.ingest_prompt"
      style="width: 100%; height: 400px; font-family: monospace; font-size: 13px; padding: 12px; border: 1px solid #d9d9d9; border-radius: 6px; resize: vertical; line-height: 1.6;"
    ></textarea>
  </div>

  <div style="display: flex; gap: 8px;">
    <button class="btn btn-primary" :disabled="wikiConfigSaving" @click="saveWikiConfig">
      {{ wikiConfigSaving ? '保存中...' : '保存配置' }}
    </button>
    <button class="btn btn-default" @click="loadWikiConfig">重新加载</button>
  </div>
</div>
```

- [ ] **Step 4: 添加相关状态和方法**

在 script setup 中添加：

```typescript
const wikiConfig = ref({ ingest_prompt: '', model: '', base_url: '' })
const wikiConfigSaving = ref(false)

async function loadWikiConfig() {
  try {
    const { data } = await adminApi.getWikiConfig()
    wikiConfig.value = data
  } catch (e) {
    console.warn('Failed to load wiki config', e)
  }
}

async function saveWikiConfig() {
  wikiConfigSaving.value = true
  try {
    await adminApi.updateWikiConfig(wikiConfig.value)
    alert('配置已保存')
  } catch (e: any) {
    alert('保存失败: ' + (e.message || '未知错误'))
  } finally {
    wikiConfigSaving.value = false
  }
}

// Load wiki config when tab switches
watch(() => activeTab.value, (tab) => {
  if (tab === 'wiki-agent' && !wikiConfig.value.ingest_prompt) {
    loadWikiConfig()
  }
})
```

- [ ] **Step 5: 运行 type check**

```bash
cd lakeon-admin && npx vue-tsc -b --noEmit
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-admin/src/views/knowledge/KnowledgeList.vue lakeon-admin/src/api/admin.ts
git commit -m "feat(admin): add Wiki Agent config tab with prompt editor"
```

---

### Task 6: 修复 URL 导入 502 错误

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java:202-220` (ingestUrl HTTP 请求)
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java:489-499` (错误码)

**目标：** 改进 URL 导入的 HTTP 请求（更真实的 User-Agent），区分错误类型返回合适的 HTTP 状态码。

- [ ] **Step 1: 改进 HttpRequest 构建**

在 WikiService.java 的 ingestUrl 方法（约 line 206-211），改进请求头：

```java
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build();
```

- [ ] **Step 2: 改进 Controller 错误处理**

在 KnowledgeController.java 的 ingest-url 端点（约 line 489-499），区分错误类型：

```java
@PostMapping("/wiki/ingest-url")
public ResponseEntity<?> ingestUrl(...) {
    try {
        var result = wikiService.ingestUrl(tenantId, kbId, url);
        return ResponseEntity.ok(result);
    } catch (RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("Failed to fetch URL")) {
            return ResponseEntity.status(422).body(Map.of("error", Map.of("message", msg)));
        }
        if (msg != null && msg.contains("content too short")) {
            return ResponseEntity.status(422).body(Map.of("error", Map.of("message", "页面内容过少，可能需要 JavaScript 渲染或登录访问")));
        }
        return ResponseEntity.status(500).body(Map.of("error", Map.of("message", msg)));
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git commit -m "fix(api): improve URL ingest — better User-Agent, proper error codes"
```

---

### Task 7: E2E 测试

**Files:**
- Create: `tests/e2e/test_wiki.py`

**目标：** 为 Wiki Agent 功能添加 API E2E 测试。

- [ ] **Step 1: 编写 wiki API 测试**

```python
"""Wiki Agent E2E tests."""
import pytest
import httpx
import time

BASE = "https://api.dbay.cloud:8443/api/v1"
ADMIN_TOKEN = "lakeon-sre-2026"

@pytest.fixture(scope="module")
def test_kb():
    """Create a temporary KB for testing, cleanup after."""
    headers = {"X-Admin-Token": ADMIN_TOKEN}
    # Create a test tenant
    r = httpx.post(f"{BASE}/tenants", json={"name": f"wiki-test-{int(time.time())}"}, headers=headers, verify=False)
    assert r.status_code == 200
    tenant = r.json()
    tenant_id = tenant["id"]
    token = tenant.get("api_key") or tenant.get("token")

    # Create KB
    kb_headers = {"Authorization": f"Bearer {token}"}
    r = httpx.post(f"{BASE}/knowledge", json={"name": "Wiki E2E Test KB"}, headers=kb_headers, verify=False)
    assert r.status_code == 200
    kb = r.json()

    yield {"tenant_id": tenant_id, "kb_id": kb["id"], "token": token, "headers": kb_headers}

    # Cleanup
    httpx.delete(f"{BASE}/tenants/{tenant_id}", headers=headers, verify=False)


def test_wiki_pages_empty(test_kb):
    """New KB should have no wiki pages."""
    r = httpx.get(f"{BASE}/knowledge/wiki/pages", params={"kb_id": test_kb["kb_id"]},
                  headers=test_kb["headers"], verify=False)
    assert r.status_code == 200
    assert r.json() == [] or len(r.json()) == 0


def test_wiki_graph_empty(test_kb):
    """New KB should have empty graph."""
    r = httpx.get(f"{BASE}/knowledge/wiki/graph", params={"kb_id": test_kb["kb_id"]},
                  headers=test_kb["headers"], verify=False)
    assert r.status_code == 200
    data = r.json()
    assert "nodes" in data
    assert "edges" in data


def test_wiki_chat(test_kb):
    """Wiki chat should work even with empty KB."""
    r = httpx.post(f"{BASE}/knowledge/wiki/chat",
                   json={"kb_id": test_kb["kb_id"], "question": "hello", "history": []},
                   headers=test_kb["headers"], verify=False, timeout=30)
    # Should return answer (might be "no content" type answer)
    assert r.status_code == 200
    data = r.json()
    assert "answer" in data


def test_admin_wiki_config():
    """Admin should be able to read wiki config."""
    headers = {"X-Admin-Token": ADMIN_TOKEN}
    r = httpx.get(f"{BASE}/admin/wiki/config", headers=headers, verify=False)
    assert r.status_code == 200
    data = r.json()
    assert "ingest_prompt" in data
    assert "model" in data
```

- [ ] **Step 2: 运行测试**

```bash
python3 -m pytest tests/e2e/test_wiki.py -v
```

- [ ] **Step 3: Commit**

```bash
git add tests/e2e/test_wiki.py
git commit -m "test: add wiki agent E2E tests"
```

---

### Task 8: 构建部署

**Files:**
- Modify: `deploy/cce/sites/hwstaff/values.yaml:54` (API image tag)
- Run: `deploy/cce/build-and-push-api.sh`

**目标：** 构建新版 API 镜像并部署，前端 push 到 GitHub 自动部署。

- [ ] **Step 1: 更新 API image tag**

在 `deploy/cce/sites/hwstaff/values.yaml` 中将 api.image.tag 从 `"0.9.200"` 改为 `"0.9.201"`。
同步更新 `deploy/cce/build-and-push-api.sh` 中的 `IMAGE_TAG`。

- [ ] **Step 2: 构建并推送 API 镜像**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
```

- [ ] **Step 3: 重启 API 部署**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
```

- [ ] **Step 4: Push 前端代码（自动触发 Railway 部署）**

```bash
git pull --rebase origin main
git push origin main
```

- [ ] **Step 5: 验证部署**

等待 2 分钟后访问 console 确认新界面生效。运行 E2E 测试验证 API：

```bash
python3 -m pytest tests/e2e/test_wiki.py -v
```
