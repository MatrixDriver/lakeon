<template>
  <div class="table-footer-bar">
    <div class="footer-left">
      <span class="footer-total">总条数：{{ total }}</span>
    </div>
    <div class="footer-right">
      <select class="page-size-select" :value="pageSize" @change="$emit('update:pageSize', Number(($event.target as HTMLSelectElement).value))">
        <option v-for="s in pageSizeOptions" :key="s" :value="s">{{ s }}</option>
      </select>
      <button class="page-btn" :disabled="currentPage <= 1" @click="$emit('update:currentPage', currentPage - 1)">&lt;</button>
      <template v-for="p in visiblePages" :key="p">
        <span v-if="p === '...'" class="page-ellipsis">...</span>
        <button
          v-else
          class="page-num"
          :class="{ active: p === currentPage }"
          @click="$emit('update:currentPage', p as number)"
        >{{ p }}</button>
      </template>
      <button class="page-btn" :disabled="currentPage >= totalPages" @click="$emit('update:currentPage', currentPage + 1)">&gt;</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  total: number
  pageSize: number
  currentPage: number
  pageSizeOptions?: number[]
}>(), {
  pageSizeOptions: () => [10, 20, 50],
})

defineEmits<{
  'update:pageSize': [size: number]
  'update:currentPage': [page: number]
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))

const visiblePages = computed(() => {
  const total = totalPages.value
  const current = props.currentPage
  const pages: (number | string)[] = []

  if (total <= 7) {
    for (let i = 1; i <= total; i++) pages.push(i)
    return pages
  }

  // Always show first page
  pages.push(1)

  if (current > 4) {
    pages.push('...')
  }

  // Pages around current
  const start = Math.max(2, current - 2)
  const end = Math.min(total - 1, current + 2)
  for (let i = start; i <= end; i++) {
    pages.push(i)
  }

  if (current < total - 3) {
    pages.push('...')
  }

  // Always show last page
  pages.push(total)

  return pages
})
</script>

<style scoped>
.table-footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-top: 1px solid #ebebeb;
  font-size: 13px;
  color: #575d6c;
  background: #fff;
}

.footer-total {
  color: #8a8e99;
}

.footer-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.page-size-select {
  padding: 3px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
  font-size: 13px;
  color: #333;
  background: #fff;
  cursor: pointer;
  outline: none;
  margin-right: 8px;
}

.page-size-select:focus {
  border-color: #0073e6;
}

.page-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  color: #333;
  transition: all 0.15s;
}

.page-btn:hover:not(:disabled) {
  border-color: #0073e6;
  color: #0073e6;
}

.page-btn:disabled {
  color: #c2c6cc;
  cursor: not-allowed;
}

.page-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  border: 1px solid transparent;
  border-radius: 2px;
  background: #fff;
  color: #333;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}

.page-num:hover:not(.active) {
  border-color: #0073e6;
  color: #0073e6;
}

.page-num.active {
  border-color: #0073e6;
  color: #0073e6;
  font-weight: 500;
}

.page-ellipsis {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  color: #8a8e99;
  font-size: 13px;
  letter-spacing: 1px;
}

@media (max-width: 768px) {
  .table-footer-bar {
    flex-wrap: wrap;
    gap: 8px;
    padding: 10px 12px;
  }

  .page-btn,
  .page-num {
    width: 36px;
    height: 36px;
  }

  .page-ellipsis {
    min-width: 24px;
    height: 36px;
  }

  .page-size-select {
    height: 36px;
    font-size: 14px;
  }
}
</style>
