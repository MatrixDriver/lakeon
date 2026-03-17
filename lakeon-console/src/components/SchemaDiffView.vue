<template>
  <div class="schema-diff">
    <!-- Change stats badges -->
    <div class="diff-stats">
      <span v-if="diff.tables.added.length" class="diff-badge badge-added">+{{ diff.tables.added.length }} 新增表</span>
      <span v-if="diff.tables.modified.length" class="diff-badge badge-modified">{{ diff.tables.modified.length }} 修改表</span>
      <span v-if="diff.tables.removed.length" class="diff-badge badge-removed">-{{ diff.tables.removed.length }} 删除表</span>
      <span v-if="diff.indexes.added.length" class="diff-badge badge-index">+{{ diff.indexes.added.length }} 新增索引</span>
      <span v-if="diff.indexes.removed.length" class="diff-badge badge-index-removed">-{{ diff.indexes.removed.length }} 删除索引</span>
      <span v-if="isEmpty" class="diff-badge badge-empty">无差异</span>
    </div>

    <!-- Added tables -->
    <div v-for="table in diff.tables.added" :key="'add-' + table.name" class="diff-section diff-added">
      <div class="diff-section-header">+ {{ table.schema }}.{{ table.name }}</div>
      <div class="diff-columns">
        <div v-for="col in table.columns" :key="col.name" class="diff-col">
          <code>{{ col.name }}</code>
          <span class="col-type">{{ col.data_type }}</span>
          <span v-if="!col.is_nullable" class="col-constraint">NOT NULL</span>
          <span v-if="col.column_default" class="col-default">DEFAULT {{ col.column_default }}</span>
        </div>
      </div>
    </div>

    <!-- Removed tables -->
    <div v-for="table in diff.tables.removed" :key="'rm-' + table.name" class="diff-section diff-removed">
      <div class="diff-section-header">- {{ table.schema }}.{{ table.name }}</div>
      <div class="diff-columns">
        <div v-for="col in table.columns" :key="col.name" class="diff-col">
          <code>{{ col.name }}</code>
          <span class="col-type">{{ col.data_type }}</span>
        </div>
      </div>
    </div>

    <!-- Modified tables -->
    <div v-for="table in diff.tables.modified" :key="'mod-' + table.name" class="diff-section diff-modified">
      <div class="diff-section-header">~ {{ table.schema }}.{{ table.name }}</div>
      <div class="diff-columns">
        <div v-for="col in table.columns.added" :key="'a-' + col.name" class="diff-col diff-col-added">
          <span class="col-change">+</span>
          <code>{{ col.name }}</code>
          <span class="col-type">{{ col.data_type }}</span>
          <span v-if="!col.is_nullable" class="col-constraint">NOT NULL</span>
          <span v-if="col.column_default" class="col-default">DEFAULT {{ col.column_default }}</span>
        </div>
        <div v-for="col in table.columns.removed" :key="'r-' + col.name" class="diff-col diff-col-removed">
          <span class="col-change">-</span>
          <code>{{ col.name }}</code>
          <span class="col-type">{{ col.data_type }}</span>
        </div>
        <div v-for="col in table.columns.modified" :key="'m-' + col.name" class="diff-col diff-col-modified">
          <span class="col-change">~</span>
          <code>{{ col.name }}</code>
          <span v-if="col.old_type !== col.new_type" class="col-type-change">
            {{ col.old_type }} -> {{ col.new_type }}
          </span>
          <span v-if="col.old_nullable !== col.new_nullable" class="col-nullable-change">
            {{ col.new_nullable ? 'NULLABLE' : 'NOT NULL' }}
          </span>
          <span v-if="col.old_default !== col.new_default" class="col-default-change">
            DEFAULT {{ col.new_default || 'NULL' }}
          </span>
        </div>
      </div>
    </div>

    <!-- Index changes -->
    <div v-if="diff.indexes.added.length" class="diff-section diff-added">
      <div class="diff-section-header">+ 新增索引</div>
      <div class="diff-columns">
        <div v-for="idx in diff.indexes.added" :key="'ia-' + idx.name" class="diff-col">
          <code>{{ idx.name }}</code>
          <span class="col-type">on {{ idx.table_name }}</span>
          <span class="idx-def">{{ idx.definition }}</span>
        </div>
      </div>
    </div>

    <div v-if="diff.indexes.removed.length" class="diff-section diff-removed">
      <div class="diff-section-header">- 删除索引</div>
      <div class="diff-columns">
        <div v-for="idx in diff.indexes.removed" :key="'ir-' + idx.name" class="diff-col">
          <code>{{ idx.name }}</code>
          <span class="col-type">on {{ idx.table_name }}</span>
          <span class="idx-def">{{ idx.definition }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaDiffResponse } from '../api/diff'

const props = defineProps<{
  diff: SchemaDiffResponse
}>()

const isEmpty = computed(() =>
  props.diff.tables.added.length === 0 &&
  props.diff.tables.removed.length === 0 &&
  props.diff.tables.modified.length === 0 &&
  props.diff.indexes.added.length === 0 &&
  props.diff.indexes.removed.length === 0
)
</script>

<style scoped>
.schema-diff {
  font-size: 13px;
}

.diff-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.diff-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.badge-added { background: #f0fff4; color: #52c41a; border: 1px solid #b7eb8f; }
.badge-modified { background: #fffbe6; color: #d48806; border: 1px solid #ffe58f; }
.badge-removed { background: #fff1f0; color: #e6393d; border: 1px solid #ffa39e; }
.badge-index { background: #e6f7ff; color: #0073e6; border: 1px solid #91d5ff; }
.badge-index-removed { background: #fff1f0; color: #e6393d; border: 1px solid #ffa39e; }
.badge-empty { background: #f5f5f5; color: #8a8e99; border: 1px solid #d9d9d9; }

.diff-section {
  margin-bottom: 12px;
  border-radius: 4px;
  padding: 12px 16px;
}

.diff-added {
  background: #f0fff4;
  border-left: 3px solid #52c41a;
}

.diff-removed {
  background: #fff1f0;
  border-left: 3px solid #e6393d;
}

.diff-modified {
  background: #fffbe6;
  border-left: 3px solid #faad14;
}

.diff-section-header {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 8px;
  color: #191919;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.diff-columns {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.diff-col {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  font-size: 12px;
}

.diff-col code {
  font-weight: 500;
  color: #191919;
}

.col-type {
  color: #575d6c;
}

.col-constraint {
  color: #d46b08;
  font-size: 11px;
  font-weight: 500;
}

.col-default {
  color: #8a8e99;
  font-size: 11px;
}

.col-change {
  font-family: monospace;
  font-weight: 700;
  width: 14px;
  text-align: center;
}

.diff-col-added .col-change { color: #52c41a; }
.diff-col-removed .col-change { color: #e6393d; }
.diff-col-modified .col-change { color: #faad14; }

.col-type-change,
.col-nullable-change,
.col-default-change {
  font-size: 11px;
  color: #d46b08;
  background: #fff7e6;
  padding: 0 4px;
  border-radius: 2px;
}

.idx-def {
  color: #8a8e99;
  font-size: 11px;
  font-family: monospace;
}
</style>
