<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">组件库</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="router.push('/datalake/components/register')">注册组件</button>
      </div>
    </div>

    <!-- 数据类型筛选 -->
    <div class="filter-section">
      <span class="filter-label">数据类型</span>
      <div class="filter-pills">
        <button
          v-for="dt in dataTypeOptions"
          :key="dt.value"
          class="pill"
          :class="{ active: dataTypeFilter === dt.value }"
          @click="dataTypeFilter = dt.value"
        >
          {{ dt.label }}
        </button>
      </div>
    </div>

    <!-- 处理阶段筛选 -->
    <div class="filter-section">
      <span class="filter-label">处理阶段</span>
      <div class="filter-pills">
        <button
          class="pill"
          :class="{ active: categoryFilter === '' }"
          @click="categoryFilter = ''"
        >全部</button>
        <button
          v-for="cat in categories"
          :key="cat"
          class="pill"
          :class="{ active: categoryFilter === cat }"
          :style="categoryFilter === cat ? { background: categoryColors[cat].border, color: '#fff', borderColor: categoryColors[cat].border } : {}"
          @click="categoryFilter = cat"
        >
          {{ categoryLabels[cat] }}
        </button>
      </div>
    </div>

    <!-- 搜索 -->
    <div class="search-row">
      <input v-model="search" class="filter-search" placeholder="搜索组件名称或描述..." />
    </div>

    <!-- 组件网格 -->
    <div class="comp-grid" v-if="filtered.length > 0">
      <div
        v-for="comp in filtered"
        :key="comp.id"
        class="comp-card"
        :style="{ borderLeftColor: catColor(comp.category) }"
        @click="openDetail(comp)"
      >
        <div class="comp-card-header">
          <svg class="comp-card-icon-svg" viewBox="0 0 24 24" width="16" height="16" fill="none" :stroke="catColor(comp.category)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="catIconPath(comp.category)" /></svg>
          <span class="comp-card-name">{{ comp.display_name }}</span>
          <span v-if="!comp.tenant_id" class="builtin-badge">内置</span>
        </div>
        <div class="comp-card-desc">{{ comp.description || comp.name }}</div>
        <div class="comp-card-meta">
          <span class="data-type-tag" :class="'dt-' + comp.data_type.toLowerCase()">{{ dataTypeLabel(comp.data_type) }}</span>
          <span class="meta-tag">v{{ comp.latest_version }}</span>
          <span class="meta-tag cat-tag" :style="{ background: categoryColors[comp.category]?.bg, color: categoryColors[comp.category]?.text }">
            {{ categoryLabels[comp.category] || comp.category }}
          </span>
        </div>
      </div>
    </div>

    <div v-if="filtered.length === 0 && !loading" class="empty-state" style="margin-top: 48px; text-align: center; color: #999;">
      暂无匹配的组件
    </div>

    <!-- 侧面板详情 -->
    <Teleport to="body">
      <Transition name="panel">
        <div v-if="selected" class="panel-overlay" @click.self="closeDetail">
          <div class="detail-panel">
            <div class="panel-header">
              <div class="panel-title-row">
                <svg class="panel-icon-svg" viewBox="0 0 24 24" width="20" height="20" fill="none" :stroke="catColor(selected.category)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="catIconPath(selected.category)" /></svg>
                <h2 class="panel-title">{{ selected.display_name }}</h2>
                <span class="data-type-tag" :class="'dt-' + selected.data_type.toLowerCase()">{{ dataTypeLabel(selected.data_type) }}</span>
                <span v-if="!selected.tenant_id" class="builtin-badge">内置</span>
              </div>
              <button class="panel-close" @click="closeDetail">&times;</button>
            </div>

            <!-- 基本信息 -->
            <section class="panel-section">
              <h3 class="section-title">基本信息</h3>
              <div class="info-grid">
                <div class="info-item"><span class="info-label">显示名称</span><span class="info-value">{{ selected.display_name }}</span></div>
                <div class="info-item"><span class="info-label">技术名称</span><span class="info-value mono">{{ selected.name }}</span></div>
                <div class="info-item"><span class="info-label">类别</span><span class="info-value"><span class="meta-tag cat-tag" :style="{ background: categoryColors[selected.category]?.bg, color: categoryColors[selected.category]?.text }">{{ categoryLabels[selected.category] }}</span></span></div>
                <div class="info-item"><span class="info-label">数据类型</span><span class="info-value"><span class="data-type-tag" :class="'dt-' + selected.data_type.toLowerCase()">{{ dataTypeLabel(selected.data_type) }}</span></span></div>
                <div class="info-item"><span class="info-label">最新版本</span><span class="info-value">v{{ selected.latest_version }}</span></div>
                <div class="info-item"><span class="info-label">来源</span><span class="info-value">{{ selected.tenant_id ? '自定义' : '平台内置' }}</span></div>
              </div>
              <div v-if="selected.description" class="desc-block">
                <span class="info-label">描述</span>
                <!-- 结构化描述展示 -->
                <template v-if="parsedDescription">
                  <p class="desc-summary">{{ parsedDescription.summary }}</p>

                  <div v-if="parsedDescription.techStack || parsedDescription.framework" class="desc-badges-row">
                    <div v-if="parsedDescription.techStack" class="desc-badge-group">
                      <span class="desc-badge-label">技术栈</span>
                      <span class="desc-badge tech-stack">{{ parsedDescription.techStack }}</span>
                    </div>
                    <div v-if="parsedDescription.framework" class="desc-badge-group">
                      <span class="desc-badge-label">执行框架</span>
                      <span class="desc-badge framework">{{ parsedDescription.framework }}</span>
                    </div>
                  </div>

                  <div v-if="parsedDescription.input || parsedDescription.output" class="desc-io-row">
                    <div v-if="parsedDescription.input" class="desc-io-card">
                      <span class="desc-io-icon">&#8594;</span>
                      <div class="desc-io-content">
                        <span class="desc-io-label">输入</span>
                        <span class="desc-io-value">{{ parsedDescription.input }}</span>
                      </div>
                    </div>
                    <div v-if="parsedDescription.output" class="desc-io-card">
                      <span class="desc-io-icon">&#8592;</span>
                      <div class="desc-io-content">
                        <span class="desc-io-label">输出</span>
                        <span class="desc-io-value">{{ parsedDescription.output }}</span>
                      </div>
                    </div>
                  </div>

                  <div v-if="parsedDescription.keyParams" class="desc-params-block">
                    <span class="desc-block-title">关键参数</span>
                    <div class="desc-params-content" v-html="formatKeyParams(parsedDescription.keyParams)"></div>
                  </div>

                  <div v-if="parsedDescription.typicalScenario" class="desc-scenario-block">
                    <span class="desc-block-title">典型场景</span>
                    <div class="desc-scenario-content">{{ parsedDescription.typicalScenario }}</div>
                  </div>

                  <div v-if="parsedDescription.extra && parsedDescription.extra.length > 0" class="desc-extra-block">
                    <div v-for="(line, idx) in parsedDescription.extra" :key="idx" class="desc-extra-line">{{ line }}</div>
                  </div>
                </template>
              </div>
            </section>

            <!-- 版本加载中 -->
            <div v-if="versionLoading" class="version-loading">
              <span class="spinner"></span> 加载版本详情...
            </div>

            <template v-if="selectedVersion">
              <!-- 技术详情 -->
              <section class="panel-section">
                <h3 class="section-title">技术详情</h3>
                <div class="info-grid">
                  <div class="info-item">
                    <span class="info-label">入口点</span>
                    <span class="info-value mono">{{ selectedVersion.entrypoint }}</span>
                  </div>
                  <div class="info-item">
                    <span class="info-label">执行框架</span>
                    <span class="info-value">
                      <span class="tech-badge" :class="isRayEntrypoint(selectedVersion.entrypoint) ? 'ray' : 'python'">
                        {{ isRayEntrypoint(selectedVersion.entrypoint) ? 'Ray 分布式' : 'Python 单机' }}
                      </span>
                    </span>
                  </div>
                  <div class="info-item">
                    <span class="info-label">GPU</span>
                    <span class="info-value">
                      <span class="tech-badge" :class="selectedVersion.requires_gpu ? 'gpu-yes' : 'gpu-no'">
                        {{ selectedVersion.requires_gpu ? '需要 GPU' : '不需要 GPU' }}
                      </span>
                    </span>
                  </div>
                  <div class="info-item">
                    <span class="info-label">执行模式</span>
                    <span class="info-value">
                      <span class="tech-badge" :class="selectedVersion.execution_mode === 'HUMAN_REVIEW' ? 'human' : 'auto'">
                        {{ selectedVersion.execution_mode === 'HUMAN_REVIEW' ? '人工审核' : '自动执行' }}
                      </span>
                    </span>
                  </div>
                  <div v-if="selectedVersion.requires_model" class="info-item">
                    <span class="info-label">依赖模型</span>
                    <span class="info-value mono">{{ selectedVersion.requires_model }}</span>
                  </div>
                </div>
                <div v-if="extractedTechs.length > 0" class="tech-libs">
                  <span class="info-label">使用的库/算法</span>
                  <div class="tech-lib-tags">
                    <span v-for="t in extractedTechs" :key="t" class="tech-lib-tag">{{ t }}</span>
                  </div>
                </div>
              </section>

              <!-- 参数配置 -->
              <section v-if="parsedParams && Object.keys(parsedParams.properties || {}).length > 0" class="panel-section">
                <h3 class="section-title">参数配置</h3>
                <div class="params-table">
                  <div class="params-header">
                    <span>参数</span><span>类型</span><span>默认值</span><span>描述</span>
                  </div>
                  <div
                    v-for="(prop, pname) in (parsedParams.properties as Record<string, SchemaProperty>)"
                    :key="String(pname)"
                    class="params-row"
                  >
                    <span class="param-name mono">{{ pname }}
                      <span v-if="(parsedParams.required || []).includes(String(pname))" class="required-mark">*</span>
                    </span>
                    <span class="param-type">{{ prop.type || '--' }}</span>
                    <span class="param-default mono">{{ prop.default !== undefined ? JSON.stringify(prop.default) : '--' }}</span>
                    <span class="param-desc">{{ prop.description || '--' }}</span>
                  </div>
                </div>
              </section>

              <!-- 输入/输出 -->
              <section v-if="hasInputOutput" class="panel-section">
                <h3 class="section-title">输入/输出</h3>
                <div class="io-grid">
                  <div v-if="parsedInput && Object.keys(parsedInput).length > 0" class="io-block">
                    <h4 class="io-title">输入 (Input)</h4>
                    <pre class="io-schema">{{ JSON.stringify(parsedInput, null, 2) }}</pre>
                  </div>
                  <div v-if="parsedOutput && Object.keys(parsedOutput).length > 0" class="io-block">
                    <h4 class="io-title">输出 (Output)</h4>
                    <pre class="io-schema">{{ JSON.stringify(parsedOutput, null, 2) }}</pre>
                  </div>
                </div>
              </section>

              <!-- 输出分支 -->
              <section v-if="outputBranches.length > 0" class="panel-section">
                <h3 class="section-title">输出分支</h3>
                <div class="branch-list">
                  <span v-for="b in outputBranches" :key="b" class="branch-tag">{{ b }}</span>
                </div>
              </section>
            </template>

            <div class="panel-footer">
              <button class="btn btn-secondary" @click="closeDetail">关闭</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  listComponents,
  getComponentLatestVersion,
  parseJsonSchema,
  parseOutputBranches,
  type PipelineComponent,
  type PipelineComponentVersion,
  type ComponentCategory,
  type ComponentDataType,
} from '@/api/pipeline'
import { categoryColors, categoryLabels, categoryIcons } from './components/pipeline/nodeStyles'

interface SchemaProperty {
  type?: string
  default?: unknown
  description?: string
  [key: string]: unknown
}

interface ParsedSchema {
  properties?: Record<string, SchemaProperty>
  required?: string[]
  [key: string]: unknown
}

const router = useRouter()
const loading = ref(true)
const components = ref<PipelineComponent[]>([])
const categoryFilter = ref<ComponentCategory | ''>('')
const dataTypeFilter = ref<ComponentDataType | ''>('')
const search = ref('')
const selected = ref<PipelineComponent | null>(null)
const selectedVersion = ref<PipelineComponentVersion | null>(null)
const versionLoading = ref(false)

interface ParsedDescription {
  summary: string
  techStack?: string
  framework?: string
  input?: string
  output?: string
  keyParams?: string
  typicalScenario?: string
  extra?: string[]
}

function parseComponentDescription(desc: string): ParsedDescription {
  if (!desc) return { summary: '' }

  const lines = desc.split('\n').map(l => l.trim()).filter(l => l.length > 0)
  if (lines.length === 0) return { summary: '' }

  const labelMap: Record<string, keyof ParsedDescription> = {
    '技术栈': 'techStack',
    '执行框架': 'framework',
    '输入': 'input',
    '输出': 'output',
    '关键参数': 'keyParams',
    '典型场景': 'typicalScenario',
  }

  const result: ParsedDescription = { summary: '', extra: [] }
  // First line(s) before any labeled line form the summary
  let summaryDone = false

  for (const line of lines) {
    let matched = false
    for (const [label, key] of Object.entries(labelMap)) {
      // Match patterns like "技术栈：xxx" or "技术栈: xxx"
      const pattern = new RegExp(`^${label}[：:]\\s*(.+)$`)
      const m = line.match(pattern)
      if (m) {
        ;(result as unknown as Record<string, string>)[key] = m[1]!.trim()
        matched = true
        summaryDone = true
        break
      }
    }
    if (!matched) {
      if (!summaryDone) {
        result.summary = result.summary ? result.summary + '\n' + line : line
      } else {
        result.extra!.push(line)
      }
    }
  }

  return result
}

function formatKeyParams(params: string): string {
  // Parse "name（desc）、name（desc）" or "name(desc), name(desc)" patterns
  // Wrap parameter names in <code> tags
  return params.replace(/([a-zA-Z_][a-zA-Z0-9_]*)/g, (_match, name: string) => {
    return `<code class="param-code">${name}</code>`
  })
}

const categories: ComponentCategory[] = ['DATA_PREP', 'EXTRACT', 'CLEAN', 'FILTER', 'QC', 'LABEL', 'PUBLISH']

const dataTypeOptions: { value: ComponentDataType | '', label: string }[] = [
  { value: '', label: '全部' },
  { value: 'VIDEO', label: '视频' },
  { value: 'TEXT', label: '文本' },
  { value: 'UNIVERSAL', label: '通用' },
]

function dataTypeLabel(dt: string): string {
  const map: Record<string, string> = { VIDEO: '视频', TEXT: '文本', UNIVERSAL: '通用', IMAGE: '图片', AUDIO: '音频', DOCUMENT: '文档' }
  return map[dt] || dt
}

function catColor(cat: string): string { return categoryColors[cat as ComponentCategory]?.border || '#ccc' }
function catIconPath(cat: string): string { return categoryIcons[cat as ComponentCategory] || categoryIcons.DATA_PREP }

function isRayEntrypoint(ep: string): boolean {
  return ep.includes('ray') || ep.includes('Ray') || ep.includes('distributed')
}

/** Extract known tech/lib names from description and entrypoint */
const knownTechs = ['PySceneDetect', 'pyscenedetect', 'ffmpeg', 'FFmpeg', 'MinHash', 'LSH', 'simhash', 'OpenCV', 'opencv', 'whisper', 'Whisper', 'spaCy', 'spacy', 'jieba', 'NLTK', 'nltk', 'langdetect', 'fasttext', 'tesseract', 'Tesseract', 'YOLO', 'yolo', 'pillow', 'Pillow', 'transformers', 'sentence-transformers', 'torch', 'pytorch', 'PyTorch', 'numpy', 'pandas', 'sklearn', 'scikit-learn']

const extractedTechs = computed(() => {
  if (!selected.value || !selectedVersion.value) return []
  const text = [selected.value.description || '', selectedVersion.value.entrypoint].join(' ')
  const found = new Set<string>()
  for (const t of knownTechs) {
    if (text.toLowerCase().includes(t.toLowerCase())) {
      found.add(t)
    }
  }
  return Array.from(found)
})

const parsedParams = computed<ParsedSchema>(() => {
  if (!selectedVersion.value) return {}
  return parseJsonSchema(selectedVersion.value.params_schema) as ParsedSchema
})

const parsedInput = computed(() => {
  if (!selectedVersion.value) return {}
  return parseJsonSchema(selectedVersion.value.input_schema)
})

const parsedOutput = computed(() => {
  if (!selectedVersion.value) return {}
  return parseJsonSchema(selectedVersion.value.output_schema)
})

const hasInputOutput = computed(() => {
  return (parsedInput.value && Object.keys(parsedInput.value).length > 0) ||
         (parsedOutput.value && Object.keys(parsedOutput.value).length > 0)
})

const outputBranches = computed(() => {
  if (!selectedVersion.value) return []
  return parseOutputBranches(selectedVersion.value.output_branches)
})

const parsedDescription = computed<ParsedDescription | null>(() => {
  if (!selected.value?.description) return null
  return parseComponentDescription(selected.value.description)
})

const filtered = computed(() => {
  let list = components.value
  if (categoryFilter.value) list = list.filter(c => c.category === categoryFilter.value)
  if (dataTypeFilter.value) list = list.filter(c => c.data_type === dataTypeFilter.value)
  if (search.value.trim()) {
    const q = search.value.trim().toLowerCase()
    list = list.filter(c =>
      c.display_name.toLowerCase().includes(q) ||
      c.name.toLowerCase().includes(q) ||
      (c.description || '').toLowerCase().includes(q)
    )
  }
  return list
})

async function openDetail(comp: PipelineComponent) {
  selected.value = comp
  selectedVersion.value = null
  versionLoading.value = true
  try {
    const res = await getComponentLatestVersion(comp.id)
    selectedVersion.value = res.data
  } catch (err) {
    console.error('Failed to load component version', err)
  } finally {
    versionLoading.value = false
  }
}

function closeDetail() {
  selected.value = null
  selectedVersion.value = null
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await listComponents()
    components.value = res.data
  } catch (err) {
    console.error('Failed to load components', err)
  } finally {
    loading.value = false
  }
})

</script>

<style scoped>
/* ── 筛选区 ── */
.filter-section {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.filter-label {
  font-size: 12px;
  color: var(--c-text-2, #64748b);
  white-space: nowrap;
  min-width: 56px;
}
.filter-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.pill {
  padding: 4px 14px;
  border-radius: 20px;
  border: 1px solid var(--c-border, #e8e4df);
  background: #fff;
  color: var(--c-text-2, #64748b);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.pill:hover {
  border-color: var(--c-primary, #2a4d6a);
  color: var(--c-primary, #2a4d6a);
}
.pill.active {
  background: var(--c-primary, #2a4d6a);
  color: #fff;
  border-color: var(--c-primary, #2a4d6a);
}

.search-row {
  margin-bottom: 16px;
}
.filter-search {
  width: 100%;
  max-width: 400px;
  padding: 6px 12px;
  border: 1px solid var(--c-border, #e8e4df);
  border-radius: 6px;
  font-size: 12px;
  outline: none;
  transition: border-color 0.15s;
}
.filter-search:focus {
  border-color: var(--c-primary, #2a4d6a);
}

/* ── 组件网格 ── */
.comp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 12px;
}
.comp-card {
  border: 1px solid var(--c-border, #e8e4df);
  border-left: 3px solid;
  border-radius: 8px;
  padding: 14px;
  cursor: pointer;
  background: #fff;
  transition: box-shadow 0.15s, transform 0.15s;
}
.comp-card:hover {
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  transform: translateY(-1px);
}
.comp-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
}
.comp-card-icon { font-size: 16px; }
.comp-card-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text, #2c3e50);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.builtin-badge {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 3px;
  background: #eef6fe;
  color: #1a5276;
  white-space: nowrap;
}
.comp-card-desc {
  font-size: 12px;
  color: var(--c-text-3, #94a3b8);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.comp-card-meta {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.meta-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--c-bg-alt, #faf8f5);
  color: var(--c-text-2, #64748b);
}
.cat-tag {
  border: none;
}

/* ── 数据类型标签 ── */
.data-type-tag {
  font-size: 10px;
  padding: 1px 8px;
  border-radius: 3px;
  font-weight: 500;
}
.dt-video { background: #fff3e0; color: #e65100; }
.dt-text { background: #e3f2fd; color: #1565c0; }
.dt-universal { background: #f3e5f5; color: #7b1fa2; }
.dt-image { background: #e8f5e9; color: #2e7d32; }
.dt-audio { background: #fce4ec; color: #c62828; }
.dt-document { background: #fff8e1; color: #f57f17; }

/* ── 侧面板 ── */
.panel-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.25);
  z-index: 100;
  display: flex;
  justify-content: flex-end;
}
.detail-panel {
  width: 560px;
  max-width: 90vw;
  height: 100vh;
  background: #fff;
  box-shadow: -4px 0 24px rgba(0,0,0,0.1);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: 24px;
}
.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--c-border, #e8e4df);
}
.panel-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  flex: 1;
}
.panel-icon { font-size: 20px; }
.panel-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--c-text, #2c3e50);
  margin: 0;
}
.panel-close {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: var(--c-text-3, #94a3b8);
  padding: 0 4px;
  line-height: 1;
}
.panel-close:hover { color: var(--c-text, #2c3e50); }

/* ── 面板 section ── */
.panel-section {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--c-border-light, #f0ece7);
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text, #2c3e50);
  margin: 0 0 10px;
}
.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.info-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.info-label {
  font-size: 11px;
  color: var(--c-text-3, #94a3b8);
}
.info-value {
  font-size: 13px;
  color: var(--c-text, #2c3e50);
}
.mono {
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  word-break: break-all;
}
.desc-block {
  margin-top: 10px;
}
.desc-text {
  font-size: 13px;
  color: var(--c-text-2, #64748b);
  line-height: 1.5;
  margin-top: 4px;
}

/* ── 结构化描述 ── */
.desc-summary {
  font-size: 13px;
  color: var(--c-text-2, #64748b);
  line-height: 1.6;
  margin: 6px 0 12px;
  white-space: pre-line;
}
.desc-badges-row {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.desc-badge-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.desc-badge-label {
  font-size: 10px;
  color: var(--c-text-3, #94a3b8);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.desc-badge {
  display: inline-block;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 4px;
  font-weight: 500;
  white-space: nowrap;
}
.desc-badge.tech-stack {
  background: #eef6fe;
  color: #1a5276;
}
.desc-badge.framework {
  background: #fff8e1;
  color: #f57f17;
}
.desc-io-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 12px;
}
.desc-io-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--c-bg-alt, #faf8f5);
  border: 1px solid var(--c-border-light, #f0ece7);
  border-radius: 6px;
}
.desc-io-icon {
  font-size: 14px;
  color: var(--c-primary, #2a4d6a);
  margin-top: 1px;
  flex-shrink: 0;
}
.desc-io-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.desc-io-label {
  font-size: 10px;
  color: var(--c-text-3, #94a3b8);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.desc-io-value {
  font-size: 12px;
  color: var(--c-text, #2c3e50);
  line-height: 1.4;
}
.desc-block-title {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--c-text-2, #64748b);
  margin-bottom: 6px;
}
.desc-params-block {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--c-bg-alt, #faf8f5);
  border: 1px solid var(--c-border-light, #f0ece7);
  border-radius: 6px;
}
.desc-params-content {
  font-size: 12px;
  color: var(--c-text, #2c3e50);
  line-height: 1.6;
}
.desc-params-content :deep(.param-code) {
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 11px;
  padding: 1px 5px;
  border-radius: 3px;
  background: #e8e4df;
  color: #2a4d6a;
}
.desc-scenario-block {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: linear-gradient(135deg, #fdf5ed 0%, #faf8f5 100%);
  border: 1px solid #f0ece7;
  border-left: 3px solid #d4a76a;
  border-radius: 6px;
}
.desc-scenario-content {
  font-size: 12px;
  color: var(--c-text, #2c3e50);
  line-height: 1.5;
  font-style: italic;
}
.desc-extra-block {
  margin-bottom: 8px;
}
.desc-extra-line {
  font-size: 12px;
  color: var(--c-text-2, #64748b);
  line-height: 1.5;
  padding: 2px 0;
}

/* ── 技术标签 ── */
.tech-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
}
.tech-badge.ray { background: #e3f2fd; color: #1565c0; }
.tech-badge.python { background: #fff8e1; color: #f57f17; }
.tech-badge.gpu-yes { background: #fce4ec; color: #c62828; }
.tech-badge.gpu-no { background: var(--c-bg-alt, #faf8f5); color: var(--c-text-3, #94a3b8); }
.tech-badge.human { background: #f3e5f5; color: #7b1fa2; }
.tech-badge.auto { background: #e8f5e9; color: #2e7d32; }

.tech-libs { margin-top: 10px; }
.tech-lib-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
.tech-lib-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--c-accent-light, #fdf5ed);
  color: var(--c-accent-text, #9a5b25);
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
}

/* ── 参数表格 ── */
.params-table {
  font-size: 12px;
  border: 1px solid var(--c-border-light, #f0ece7);
  border-radius: 6px;
  overflow: hidden;
}
.params-header {
  display: grid;
  grid-template-columns: 140px 70px 100px 1fr;
  gap: 0;
  padding: 6px 10px;
  background: var(--c-bg-alt, #faf8f5);
  font-weight: 600;
  color: var(--c-text-2, #64748b);
  font-size: 11px;
}
.params-row {
  display: grid;
  grid-template-columns: 140px 70px 100px 1fr;
  gap: 0;
  padding: 6px 10px;
  border-top: 1px solid var(--c-border-light, #f0ece7);
  align-items: baseline;
}
.params-row:hover { background: var(--c-hover, #f8f5f1); }
.param-name {
  font-weight: 500;
  color: var(--c-text, #2c3e50);
}
.required-mark { color: var(--c-danger, #e6393d); }
.param-type { color: var(--c-text-3, #94a3b8); }
.param-default { color: var(--c-text-2, #64748b); font-size: 11px; }
.param-desc { color: var(--c-text-2, #64748b); }

/* ── 输入/输出 ── */
.io-grid { display: flex; flex-direction: column; gap: 12px; }
.io-block { }
.io-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-2, #64748b);
  margin: 0 0 6px;
}
.io-schema {
  font-size: 11px;
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  background: var(--c-bg-alt, #faf8f5);
  border: 1px solid var(--c-border-light, #f0ece7);
  border-radius: 6px;
  padding: 10px;
  overflow-x: auto;
  max-height: 200px;
  overflow-y: auto;
  color: var(--c-text, #2c3e50);
  margin: 0;
  white-space: pre;
}

/* ── 输出分支 ── */
.branch-list { display: flex; flex-wrap: wrap; gap: 6px; }
.branch-tag {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 4px;
  background: #e8f5e9;
  color: #2e7d32;
  font-weight: 500;
}

/* ── 版本加载 ── */
.version-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 0;
  color: var(--c-text-3, #94a3b8);
  font-size: 13px;
}
.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--c-border, #e8e4df);
  border-top-color: var(--c-primary, #2a4d6a);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* ── 面板底部 ── */
.panel-footer {
  margin-top: auto;
  padding-top: 16px;
  display: flex;
  justify-content: flex-end;
}

/* ── 面板动画 ── */
.panel-enter-active, .panel-leave-active {
  transition: opacity 0.2s ease;
}
.panel-enter-active .detail-panel, .panel-leave-active .detail-panel {
  transition: transform 0.25s ease;
}
.panel-enter-from {
  opacity: 0;
}
.panel-enter-from .detail-panel {
  transform: translateX(100%);
}
.panel-leave-to {
  opacity: 0;
}
.panel-leave-to .detail-panel {
  transform: translateX(100%);
}

/* ── 空状态 ── */
.empty-state {
  font-size: 14px;
}
</style>
