<script setup lang="ts">
import { ref, watch } from 'vue'
import { listWikiPages, getWikiPageContent, type WikiPageItem } from '@/api/knowledge'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const props = defineProps<{ kbId: string }>()

const pages = ref<WikiPageItem[]>([])
const selectedPage = ref<WikiPageItem | null>(null)
const content = ref('')
const loading = ref(false)

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
  <div style="display: flex; gap: 16px; min-height: 400px;">
    <!-- Sidebar -->
    <div style="width: 220px; flex-shrink: 0; border-right: 1px solid #e8e0d8; padding-right: 12px; overflow-y: auto;">
      <div style="margin: 0 0 12px; color: #8c7a68; font-size: 13px; font-weight: 500;">
        Wiki 页面 ({{ pages.length }})
      </div>
      <div v-for="page in pages" :key="page.id"
           :class="['wiki-page-item', { active: selectedPage?.id === page.id }]"
           @click="openPage(page)">
        {{ page.filename.replace('.md', '') }}
      </div>
      <div v-if="pages.length === 0" style="color: #b0a090; font-size: 13px; padding: 8px 0;">
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
