<template>
  <div class="wiki-lint-panel">
    <!-- Header -->
    <div class="lint-header">
      <span class="lint-title">健康检查</span>
      <span v-if="issues.length > 0" class="lint-count">{{ issues.length }} 项</span>
      <span style="flex: 1;"></span>
      <button v-if="issues.length > 0" class="lint-fix-all-btn" @click="handleFixAll" :disabled="fixing">
        {{ fixing ? '修复中...' : '全部修复' }}
      </button>
      <span class="lint-close" @click="$emit('close')">&times;</span>
    </div>

    <!-- Checked at -->
    <div v-if="checkedAt" class="lint-checked-at">
      检查时间: {{ new Date(checkedAt).toLocaleString('zh-CN') }}
    </div>

    <!-- Empty state -->
    <div v-if="issues.length === 0" class="lint-empty">
      没有发现问题
    </div>

    <!-- Groups by category -->
    <div v-for="cat in groupedCategories" :key="cat.key" class="lint-group">
      <div class="lint-group-header">
        <span class="lint-badge" :style="{ background: cat.badgeBg, color: cat.badgeColor }">
          {{ cat.label }}
        </span>
        <span class="lint-group-count">{{ cat.issues.length }}</span>
        <span style="flex: 1;"></span>
        <button class="lint-fix-btn" @click="handleFixCategory(cat.key, cat.issues)" :disabled="fixing">
          修复
        </button>
      </div>
      <div v-for="(issue, i) in cat.issues" :key="i" class="lint-issue">
        <span class="lint-dot" :style="{ background: severityColor(issue.severity) }"></span>
        <span class="lint-page" @click="$emit('navigate', issue.page)">{{ issue.page }}</span>
        <span class="lint-desc">{{ issue.description }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { fixWikiLint, type LintIssue } from '@/api/knowledge'

const props = defineProps<{
  kbId: string
  issues: LintIssue[]
  summary: Record<string, number>
  checkedAt: string
}>()

const emit = defineEmits<{
  close: []
  navigate: [title: string]
  fixed: []
}>()

const fixing = ref(false)

const categoryMeta: Record<string, { label: string; badgeBg: string; badgeColor: string; order: number }> = {
  language: { label: '语言', badgeBg: '#f5ead5', badgeColor: '#a07030', order: 0 },
  orphan: { label: '孤儿', badgeBg: '#dceef8', badgeColor: '#3070a0', order: 1 },
  broken_link: { label: '断链', badgeBg: '#fde0e0', badgeColor: '#a03030', order: 2 },
  contradiction: { label: '矛盾', badgeBg: '#eeddf5', badgeColor: '#7030a0', order: 3 },
  stale: { label: '过时', badgeBg: '#def5de', badgeColor: '#307030', order: 4 },
  missing_link: { label: '建议', badgeBg: '#def5de', badgeColor: '#307030', order: 5 },
}

interface GroupedCategory {
  key: string
  label: string
  badgeBg: string
  badgeColor: string
  issues: LintIssue[]
}

const groupedCategories = computed<GroupedCategory[]>(() => {
  const groups: Record<string, LintIssue[]> = {}
  for (const issue of props.issues) {
    if (!groups[issue.category]) groups[issue.category] = []
    groups[issue.category]!.push(issue)
  }
  return Object.entries(groups)
    .map(([key, issues]) => {
      const meta = categoryMeta[key] || { label: key, badgeBg: '#f0ebe4', badgeColor: '#5a4a3a', order: 99 }
      return { key, label: meta.label, badgeBg: meta.badgeBg, badgeColor: meta.badgeColor, issues, order: meta.order }
    })
    .sort((a, b) => a.order - b.order)
})

function severityColor(severity: string): string {
  if (severity === 'error') return '#e07070'
  if (severity === 'warning') return '#e0c070'
  return '#a8d5a2'
}

async function handleFixCategory(category: string, issues: LintIssue[]) {
  fixing.value = true
  try {
    await fixWikiLint(props.kbId, [category], issues)
    emit('fixed')
  } catch (e) {
    console.error('Fix failed:', e)
  } finally {
    fixing.value = false
  }
}

async function handleFixAll() {
  fixing.value = true
  try {
    await fixWikiLint(props.kbId, [], props.issues)
    emit('fixed')
  } catch (e) {
    console.error('Fix all failed:', e)
  } finally {
    fixing.value = false
  }
}
</script>

<style scoped>
.wiki-lint-panel {
  width: 340px;
  flex-shrink: 0;
  border-left: 1px solid #e8e0d8;
  background: #faf8f5;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.lint-header {
  padding: 10px 14px;
  border-bottom: 1px solid #f0ebe4;
  display: flex;
  align-items: center;
  gap: 8px;
}

.lint-title {
  font-size: 13px;
  font-weight: 600;
  color: #3d3d3d;
}

.lint-count {
  font-size: 11px;
  color: #7a6a52;
  background: #f0ebe4;
  padding: 1px 6px;
  border-radius: 8px;
}

.lint-fix-all-btn {
  padding: 3px 10px;
  font-size: 11px;
  border: 1px solid #c19a6b;
  border-radius: 4px;
  background: #fff;
  color: #c19a6b;
  cursor: pointer;
  transition: background 0.15s;
}

.lint-fix-all-btn:hover:not(:disabled) {
  background: #fdf6ee;
}

.lint-fix-all-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.lint-close {
  cursor: pointer;
  color: #bbb;
  font-size: 18px;
  line-height: 1;
  margin-left: 4px;
}

.lint-close:hover {
  color: #888;
}

.lint-checked-at {
  padding: 6px 14px;
  font-size: 11px;
  color: #999;
  border-bottom: 1px solid #f0ebe4;
}

.lint-empty {
  padding: 32px 14px;
  text-align: center;
  font-size: 13px;
  color: #999;
}

.lint-group {
  border-bottom: 1px solid #f0ebe4;
}

.lint-group-header {
  padding: 8px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
}

.lint-badge {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 3px;
  font-weight: 500;
}

.lint-group-count {
  font-size: 11px;
  color: #999;
}

.lint-fix-btn {
  padding: 2px 8px;
  font-size: 11px;
  border: 1px solid #e0d8ce;
  border-radius: 3px;
  background: #fff;
  color: #8c7a68;
  cursor: pointer;
  transition: background 0.15s;
}

.lint-fix-btn:hover:not(:disabled) {
  background: #faf8f5;
}

.lint-fix-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.lint-issue {
  padding: 6px 14px 6px 22px;
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  line-height: 1.5;
}

.lint-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 5px;
}

.lint-page {
  color: #9a5b25;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lint-page:hover {
  text-decoration: underline;
}

.lint-desc {
  color: #5a4a3a;
}
</style>
