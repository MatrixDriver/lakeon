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
      <span class="page-num">{{ currentPage }}</span>
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
  gap: 8px;
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
  border: 1px solid #0073e6;
  border-radius: 2px;
  background: #fff;
  color: #0073e6;
  font-size: 13px;
  font-weight: 500;
}
</style>
