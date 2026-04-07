<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { listWikiPages, getWikiPageContent, type WikiPageItem } from '@/api/knowledge'
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

async function loadPages() {
  try {
    const resp = await listWikiPages(props.kbId)
    pages.value = resp.data
  } catch (e) {
    console.error('Failed to load wiki pages:', e)
  }
}

async function openPage(page: WikiPageItem) {
  selectedPage.value = page
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
  const page = pages.value.find(p => p.filename === title + '.md')
  if (page) openPage(page)
}

watch(() => props.kbId, loadPages, { immediate: true })

defineExpose({ navigateToTitle })
</script>

<template>
  <div style="display: flex; gap: 16px; min-height: 400px; height: 100%;">
    <!-- Sidebar -->
    <div style="width: 220px; flex-shrink: 0; border-right: 1px solid #e8e0d8; padding-right: 12px; overflow-y: auto;">
      <div style="margin: 0 0 12px; color: #8c7a68; font-size: 13px; font-weight: 500; display: flex; align-items: center; justify-content: space-between;">
        <span>Wiki 页面 ({{ displayPages.length }})</span>
        <span style="cursor: pointer; font-size: 12px; color: #bbb;" title="查看日志" @click="openLogDrawer">日志</span>
      </div>
      <div v-for="page in displayPages" :key="page.id"
           :class="['wiki-page-item', { active: selectedPage?.id === page.id }]"
           @click="openPage(page)">
        {{ page.filename.replace('.md', '') }}
      </div>
      <div v-if="displayPages.length === 0" style="color: #b0a090; font-size: 13px; padding: 8px 0;">
        暂无 wiki 页面，上传文章后自动生成
      </div>
    </div>

    <!-- Content -->
    <div style="flex: 1; overflow-y: auto; padding: 0 8px;">
      <div v-if="loading" style="color: #b0a090; padding: 20px;">加载中...</div>
      <div v-else-if="selectedPage">
        <div style="margin-bottom: 8px; font-size: 12px; color: #b0a090;">
          版本 {{ selectedPage.metadata?.wiki_version || '1' }}
          · {{ selectedPage.updated_at || selectedPage.created_at }}
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
}
.wiki-page-item:hover {
  background: #faf5f0;
}
.wiki-page-item.active {
  background: #f0e6d8;
  color: #8b6914;
  font-weight: 500;
}
</style>
