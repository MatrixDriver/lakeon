<template>
  <Teleport to="body">
    <div class="cmd-overlay" @click.self="emit('close')">
      <div class="cmd-box">
        <div class="cmd-input-area">
          <svg class="cmd-search-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            ref="inputRef"
            v-model="query"
            class="cmd-input"
            placeholder="搜索页面或操作..."
            @keydown.down.prevent="moveDown"
            @keydown.up.prevent="moveUp"
            @keydown.enter.prevent="executeActive"
            @keydown.esc="emit('close')"
          />
          <kbd class="cmd-kbd">ESC</kbd>
        </div>
        <div class="cmd-results" v-if="filteredGroups.length > 0">
          <template v-for="group in filteredGroups" :key="group.label">
            <div class="cmd-group-label">{{ group.label }}</div>
            <div
              v-for="item in group.items"
              :key="item.label"
              class="cmd-item"
              :class="{ 'cmd-item-active': flatIndex(item) === activeIndex }"
              @click="execute(item)"
              @mouseenter="activeIndex = flatIndex(item)"
            >
              <span class="cmd-item-icon">{{ item.icon }}</span>
              <span class="cmd-item-label">{{ item.label }}</span>
            </div>
          </template>
        </div>
        <div class="cmd-empty" v-else>
          没有匹配结果
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'

const emit = defineEmits<{ close: [] }>()
const router = useRouter()

const query = ref('')
const activeIndex = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

interface CmdItem {
  label: string
  icon: string
  route: string
}

interface CmdGroup {
  label: string
  items: CmdItem[]
}

const allGroups: CmdGroup[] = [
  {
    label: '页面',
    items: [
      { label: '数据库', icon: '◈', route: '/dashboard' },
      { label: '时间旅行', icon: '◷', route: '/timetravel' },
      { label: 'SQL 编辑器', icon: '⟩', route: '/sql' },
      { label: '知识库', icon: '◇', route: '/knowledge' },
      { label: '原文搜索', icon: '⊘', route: '/knowledge/search' },
      { label: '记忆库', icon: '◎', route: '/memory' },
      { label: '记忆浏览', icon: '◉', route: '/memory/browse' },
      { label: '数据集', icon: '▤', route: '/datalake/datasets' },
      { label: '作业管理', icon: '▶', route: '/datalake/jobs' },
      { label: 'Notebook', icon: '▧', route: '/datalake/notebook' },
      { label: 'API Key', icon: '⊟', route: '/apikey' },
      { label: '账户设置', icon: '⊙', route: '/account' },
      { label: '监控面板', icon: '◫', route: '/monitor' },
      { label: '日志管理', icon: '≡', route: '/logs' },
      { label: '备份管理', icon: '⊞', route: '/backups' },
      { label: '数据迁移', icon: '⇄', route: '/import' },
      { label: '数据源', icon: '⊕', route: '/knowledge/datasources' },
    ],
  },
  {
    label: '操作',
    items: [
      { label: '创建数据库', icon: '+', route: '/dashboard' },
      { label: '创建知识库', icon: '+', route: '/knowledge' },
      { label: '创建记忆库', icon: '+', route: '/memory' },
      { label: '创建 Notebook', icon: '+', route: '/datalake/notebook' },
      { label: '创建 API Key', icon: '+', route: '/apikey' },
    ],
  },
]

const filteredGroups = computed<CmdGroup[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return allGroups
  return allGroups
    .map((group) => ({
      label: group.label,
      items: group.items.filter((item) => item.label.toLowerCase().includes(q)),
    }))
    .filter((group) => group.items.length > 0)
})

const flatItems = computed<CmdItem[]>(() => {
  return filteredGroups.value.flatMap((g) => g.items)
})

function flatIndex(item: CmdItem): number {
  return flatItems.value.indexOf(item)
}

watch(query, () => {
  activeIndex.value = 0
})

function moveDown() {
  if (flatItems.value.length === 0) return
  activeIndex.value = (activeIndex.value + 1) % flatItems.value.length
}

function moveUp() {
  if (flatItems.value.length === 0) return
  activeIndex.value = (activeIndex.value - 1 + flatItems.value.length) % flatItems.value.length
}

function executeActive() {
  const item = flatItems.value[activeIndex.value]
  if (item) execute(item)
}

function execute(item: CmdItem) {
  router.push(item.route)
  emit('close')
}

onMounted(() => {
  inputRef.value?.focus()
})
</script>

<style scoped>
.cmd-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 9999;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 120px;
}

.cmd-box {
  width: 520px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.2), 0 2px 8px rgba(0, 0, 0, 0.08);
  overflow: hidden;
}

.cmd-input-area {
  display: flex;
  align-items: center;
  padding: 14px 16px;
  border-bottom: 1px solid #e8e4df;
  gap: 10px;
}

.cmd-search-icon {
  flex-shrink: 0;
}

.cmd-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 15px;
  color: #2c3e50;
  background: transparent;
}

.cmd-input::placeholder {
  color: #94a3b8;
}

.cmd-kbd {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 6px;
  border: 1px solid #e8e4df;
  border-radius: 4px;
  color: #94a3b8;
  background: #f8f5f1;
  font-family: inherit;
  line-height: 1.4;
}

.cmd-results {
  max-height: 360px;
  overflow-y: auto;
  padding: 4px 0;
}

.cmd-group-label {
  font-size: 10px;
  text-transform: uppercase;
  color: #94a3b8;
  padding: 8px 16px;
  letter-spacing: 0.05em;
}

.cmd-item {
  display: flex;
  align-items: center;
  padding: 8px 16px;
  font-size: 14px;
  color: #2c3e50;
  cursor: pointer;
  gap: 10px;
}

.cmd-item:hover,
.cmd-item-active {
  background: #f8f5f1;
}

.cmd-item-icon {
  width: 20px;
  text-align: center;
  color: #94a3b8;
  flex-shrink: 0;
}

.cmd-item-label {
  flex: 1;
}

.cmd-empty {
  padding: 32px 16px;
  text-align: center;
  color: #94a3b8;
  font-size: 14px;
}
</style>
