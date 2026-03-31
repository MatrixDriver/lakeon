<template>
  <div class="table-toolbar">
    <div class="toolbar-search">
      <svg class="search-icon" viewBox="0 0 16 16" width="14" height="14" fill="#adb0b8">
        <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85zm-5.242.156a5 5 0 1 1 0-10 5 5 0 0 1 0 10z"/>
      </svg>
      <input
        type="text"
        :value="modelValue"
        @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
        class="search-input"
        :placeholder="placeholder"
      />
    </div>
    <div class="toolbar-extra">
      <slot name="extra" />
    </div>
    <div class="toolbar-actions">
      <button class="toolbar-icon-btn" @click="$emit('refresh')" :disabled="loading" :title="loading ? '加载中...' : '刷新'">
        <svg :class="{ spinning: loading }" viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
          <path d="M13.65 2.35a8 8 0 1 0 1.77 5.15h-2.02a6 6 0 1 1-1.13-3.87L10 6h6V0l-2.35 2.35z"/>
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  modelValue: string
  placeholder?: string
  loading?: boolean
}>(), {
  placeholder: '输入关键字搜索...',
  loading: false,
})

defineEmits<{
  'update:modelValue': [value: string]
  refresh: []
}>()
</script>

<style scoped>
.table-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 10px;
  height: 36px;
  background: #fff;
  margin-bottom: 0;
}

.toolbar-search {
  display: flex;
  align-items: center;
  flex: 1;
  gap: 8px;
}

.search-icon {
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 14px;
  color: #2c3e50;
  background: transparent;
  height: 100%;
}

.search-input::placeholder {
  color: #adb0b8;
}

.toolbar-extra {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-extra:empty {
  display: none;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  border-left: 1px solid #e8e8e8;
  padding-left: 10px;
}

.toolbar-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 4px;
  background: none;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
}

.toolbar-icon-btn:hover:not(:disabled) {
  color: #9a5b25;
  background: #f0f5ff;
}

.toolbar-icon-btn:disabled {
  color: #c2c6cc;
  cursor: not-allowed;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.spinning {
  animation: spin 0.8s linear infinite;
}

@media (max-width: 768px) {
  .table-toolbar {
    height: 40px;
  }

  .search-input {
    font-size: 16px;
  }

  .toolbar-icon-btn {
    width: 36px;
    height: 36px;
  }
}
</style>
