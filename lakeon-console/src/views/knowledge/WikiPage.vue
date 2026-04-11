<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { listWikiPages, getWikiPageContent, deleteWikiPage, type WikiPageItem } from '@/api/knowledge'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ (e: 'select', title: string): void }>()

const pages = ref<WikiPageItem[]>([])
const selectedPage = ref<WikiPageItem | null>(null)
const content = ref('')
const loading = ref(false)

// Filter out internal pages (index, log)
const displayPages = computed(() =>
  pages.value.filter(p => p.filename !== 'index.md' && p.filename !== 'log.md')
)

// Log drawer
const showLog = ref(false)
const logContent = ref('')
const logLoading = ref(false)

async function openLogDrawer() {
  showLog.value = true
  if (logContent.value) return  // already loaded
  const logPage = pages.value.find(p => p.filename === 'log.md')
  if (!logPage) {
    logContent.value = '暂无日志'
    return
  }
  logLoading.value = true
  try {
    const resp = await getWikiPageContent(props.kbId, logPage.id)
    logContent.value = resp.data.content || '暂无日志'
  } catch {
    logContent.value = '加载失败'
  } finally {
    logLoading.value = false
  }
}

const pendingTitle = ref<string | null>(null)

async function loadPages() {
  try {
    const resp = await listWikiPages(props.kbId)
    pages.value = resp.data
    if (pendingTitle.value) {
      const t = pendingTitle.value
      pendingTitle.value = null
      navigateToTitle(t)
    }
  } catch (e) {
    console.error('Failed to load wiki pages:', e)
  }
}

async function openPage(page: WikiPageItem) {
  selectedPage.value = page
  // Scroll sidebar to show selected page
  nextTick(() => {
    const el = document.querySelector('.wiki-page-item.active')
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  })
  // Notify parent so graph can focus on this page
  const title = page.filename.replace(/\.md$/, '')
  emit('select', title)
  loading.value = true
  try {
    const resp = await getWikiPageContent(props.kbId, page.id)
    content.value = resp.data.content || ''
  } catch (e) {
    content.value = '加载失败'
  } finally {
    loading.value = false
  }
}

function navigateToTitle(title: string) {
  // Normalize for matching: lowercase, replace spaces with hyphens, strip .md
  const normalize = (s: string) => s.replace(/\.md$/, '').toLowerCase().replace(/[\s_]+/g, '-')
  const target = normalize(title)
  const page = pages.value.find(p => normalize(p.filename) === target)
    || pages.value.find(p => normalize(p.filename).includes(target))
    || pages.value.find(p => target.includes(normalize(p.filename)))
  if (page) {
    openPage(page)
  } else {
    // Pages not loaded yet — store as pending, loadPages will pick it up
    pendingTitle.value = title
  }
}

watch(() => props.kbId, loadPages, { immediate: true })

// Fullscreen modal
const showFullscreen = ref(false)
const fullscreenContentEl = ref<HTMLDivElement>()

interface TocItem { level: number; text: string; id: string }
// Used in template (fullscreen TOC sidebar)
// noinspection JSUnusedLocalSymbols
const toc = computed<TocItem[]>(() => {
  if (!content.value) return []
  const items: TocItem[] = []
  const lines = content.value.split('\n')
  for (const line of lines) {
    const m = line.match(/^(#{1,3})\s+(.+)/)
    if (m && m[1] && m[2]) {
      const text = m[2].trim()
      const id = text.replace(/\s+/g, '-').replace(/[^\w\u4e00-\u9fff-]/g, '').toLowerCase()
      items.push({ level: m[1].length, text, id })
    }
  }
  return items
})

function openFullscreen() {
  showFullscreen.value = true
  nextTick(() => {
    // Add id attributes to headings inside the fullscreen modal for anchor navigation
    if (fullscreenContentEl.value) {
      const headings = fullscreenContentEl.value.querySelectorAll('h1, h2, h3')
      headings.forEach(h => {
        const text = h.textContent?.trim() || ''
        h.id = text.replace(/\s+/g, '-').replace(/[^\w\u4e00-\u9fff-]/g, '').toLowerCase()
      })
    }
  })
}

// Used in template (fullscreen TOC click)
// noinspection JSUnusedLocalSymbols
function scrollToHeading(id: string) {
  const el = fullscreenContentEl.value?.querySelector('#' + CSS.escape(id))
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const deleting = ref(false)
async function handleDeletePage(page: WikiPageItem) {
  const name = page.filename.replace('.md', '')
  if (!confirm(`确定删除 Wiki 页面「${name}」？此操作不可撤销。`)) return
  deleting.value = true
  try {
    await deleteWikiPage(props.kbId, page.id)
    // Remove from list and clear content if it was selected
    pages.value = pages.value.filter(p => p.id !== page.id)
    if (selectedPage.value?.id === page.id) {
      selectedPage.value = null
      content.value = ''
    }
  } catch (e) {
    console.error('Failed to delete wiki page:', e)
  } finally {
    deleting.value = false
  }
}

defineExpose({ navigateToTitle })
</script>

<template>
  <div style="display: flex; gap: 16px; min-height: 400px; height: 100%;">
    <!-- Sidebar -->
    <div style="width: 160px; flex-shrink: 0; border-right: 1px solid #e8e0d8; padding-right: 12px; overflow-y: auto;">
      <div style="margin: 0 0 12px; color: #8c7a68; font-size: 13px; font-weight: 500; display: flex; align-items: center; justify-content: space-between;">
        <span>Wiki 页面 ({{ displayPages.length }})</span>
        <span style="cursor: pointer; font-size: 12px; color: #bbb;" title="查看日志" @click="openLogDrawer">日志</span>
      </div>
      <div v-for="page in displayPages" :key="page.id"
           :class="['wiki-page-item', { active: selectedPage?.id === page.id }]"
           @click="openPage(page)">
        <span class="wiki-page-name">{{ page.filename.replace('.md', '') }}</span>
        <span class="wiki-page-delete" title="删除此页面" @click.stop="handleDeletePage(page)">&times;</span>
      </div>
      <div v-if="displayPages.length === 0" style="color: #b0a090; font-size: 13px; padding: 8px 0;">
        暂无 wiki 页面，上传文章后自动生成
      </div>
    </div>

    <!-- Content -->
    <div style="flex: 1; overflow-y: auto; padding: 0 8px;">
      <div v-if="loading" style="color: #b0a090; padding: 20px;">加载中...</div>
      <div v-else-if="selectedPage">
        <div style="margin-bottom: 8px; font-size: 12px; color: #b0a090; display: flex; align-items: center;">
          版本 {{ selectedPage.metadata?.wiki_version || '1' }}
          · {{ selectedPage.updated_at || selectedPage.created_at }}
          <span style="flex: 1;"></span>
          <button class="expand-btn" @click="openFullscreen" title="全屏阅读">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/>
              <line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>
            </svg>
          </button>
        </div>
        <MarkdownRenderer :content="content" :kb-id="kbId" @navigate="navigateToTitle" />
      </div>
      <div v-else style="color: #b0a090; padding: 40px; text-align: center;">
        选择左侧的 wiki 页面查看内容
      </div>
    </div>

    <!-- Log Drawer -->
    <div v-if="showLog" style="position: fixed; inset: 0; z-index: 999;" @click.self="showLog = false">
      <div style="position: absolute; right: 0; top: 0; bottom: 0; width: 480px; background: #fff; box-shadow: -4px 0 16px rgba(0,0,0,0.1); display: flex; flex-direction: column;">
        <div style="padding: 14px 18px; border-bottom: 1px solid #f0ebe4; display: flex; align-items: center; justify-content: space-between;">
          <span style="font-size: 14px; font-weight: 600; color: #3d3d3d;">Wiki 更新日志</span>
          <span style="cursor: pointer; color: #bbb; font-size: 18px;" @click="showLog = false">&times;</span>
        </div>
        <div style="flex: 1; overflow-y: auto; padding: 16px 18px;">
          <div v-if="logLoading" style="color: #b0a090; padding: 20px;">加载中...</div>
          <MarkdownRenderer v-else :content="logContent" :kb-id="kbId" @navigate="navigateToTitle" />
        </div>
      </div>
    </div>
    <!-- Fullscreen Reading Modal -->
    <Teleport to="body">
      <div v-if="showFullscreen" class="fullscreen-overlay" @click.self="showFullscreen = false">
        <div class="fullscreen-modal">
          <div class="fullscreen-header">
            <span style="font-weight: 600; color: #3d3d3d;">{{ selectedPage?.filename?.replace('.md', '') }}</span>
            <button class="expand-btn" @click="showFullscreen = false" title="关闭">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          <div style="display: flex; flex: 1; overflow: hidden;">
            <!-- TOC sidebar -->
            <div v-if="toc.length > 0" class="fullscreen-toc">
              <div style="font-size: 12px; color: #b0a090; margin-bottom: 8px; font-weight: 600;">目录</div>
              <div v-for="item in toc" :key="item.id"
                   class="toc-item"
                   :style="{ paddingLeft: (item.level - 1) * 14 + 'px' }"
                   @click="scrollToHeading(item.id)">
                {{ item.text }}
              </div>
            </div>
            <!-- Content -->
            <div ref="fullscreenContentEl" class="fullscreen-content">
              <MarkdownRenderer :content="content" :kb-id="kbId" @navigate="navigateToTitle" />
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.wiki-page-item {
  padding: 6px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  margin-bottom: 2px;
  color: #5a4a3a;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.wiki-page-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wiki-page-delete {
  display: none;
  font-size: 16px;
  color: #c0b0a0;
  padding: 0 2px;
  line-height: 1;
  flex-shrink: 0;
}
.wiki-page-item:hover .wiki-page-delete { display: inline; }
.wiki-page-delete:hover { color: var(--cs-severe); }
.wiki-page-item:hover {
  background: #faf5f0;
}
.wiki-page-item.active {
  background: #f0e6d8;
  color: #8b6914;
  font-weight: 500;
}
.expand-btn {
  background: none;
  border: 1px solid #e0d8ce;
  border-radius: 4px;
  padding: 3px 6px;
  cursor: pointer;
  color: #8c7a68;
  display: inline-flex;
  align-items: center;
}
.expand-btn:hover {
  background: #faf5f0;
  color: #5a4a3a;
}
.fullscreen-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0,0,0,0.4);
  display: flex;
  align-items: center;
  justify-content: center;
}
.fullscreen-modal {
  width: 90vw;
  height: 88vh;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 8px 40px rgba(0,0,0,0.2);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.fullscreen-header {
  padding: 12px 20px;
  border-bottom: 1px solid #e8e0d8;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}
.fullscreen-toc {
  width: 200px;
  flex-shrink: 0;
  border-right: 1px solid #f0ebe4;
  padding: 16px;
  overflow-y: auto;
}
.toc-item {
  font-size: 13px;
  color: #5a4a3a;
  padding: 4px 6px;
  border-radius: 3px;
  cursor: pointer;
  margin-bottom: 1px;
}
.toc-item:hover {
  background: #faf5f0;
  color: #8b6914;
}
.fullscreen-content {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
}
</style>
