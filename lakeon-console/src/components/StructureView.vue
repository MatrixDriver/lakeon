<template>
  <div class="structure-view">
    <div v-if="loading" class="struct-loading">加载中...</div>
    <div v-else-if="!schema || !table" class="struct-empty">选择一个表以查看结构</div>
    <template v-else>
      <!-- Columns -->
      <div class="struct-section">
        <h4 class="struct-title">列 ({{ columns.length }})</h4>
        <table class="data-table" v-if="columns.length > 0">
          <thead>
            <tr>
              <th>#</th>
              <th>列名</th>
              <th>类型</th>
              <th>可空</th>
              <th>默认值</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="col in columns" :key="col.name">
              <td class="col-pos">{{ col.ordinal_position }}</td>
              <td class="col-name">{{ col.name }}</td>
              <td class="col-type">{{ col.data_type }}</td>
              <td>
                <span v-if="col.nullable" class="tag-yes">YES</span>
                <span v-else class="tag-no">NO</span>
              </td>
              <td class="col-default">{{ col.default_value || '-' }}</td>
              <td class="col-comment">{{ col.comment || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Indexes -->
      <div class="struct-section">
        <h4 class="struct-title">索引 ({{ indexes.length }})</h4>
        <table class="data-table" v-if="indexes.length > 0">
          <thead>
            <tr>
              <th>名称</th>
              <th>列</th>
              <th>唯一</th>
              <th>主键</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="idx in indexes" :key="idx.name">
              <td>{{ idx.name }}</td>
              <td>{{ idx.columns.join(', ') }}</td>
              <td>
                <span v-if="idx.is_unique" class="tag-yes">YES</span>
                <span v-else class="tag-no">NO</span>
              </td>
              <td>
                <span v-if="idx.is_primary" class="tag-pk">PK</span>
                <span v-else>-</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="struct-empty-inline">无索引</div>
      </div>

      <!-- Constraints -->
      <div class="struct-section">
        <h4 class="struct-title">约束 ({{ constraints.length }})</h4>
        <table class="data-table" v-if="constraints.length > 0">
          <thead>
            <tr>
              <th>名称</th>
              <th>类型</th>
              <th>列</th>
              <th>引用表</th>
              <th>引用列</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in constraints" :key="c.name">
              <td>{{ c.name }}</td>
              <td>
                <span class="constraint-type" :class="'ct-' + c.type.replace(' ', '_')">{{ c.type }}</span>
              </td>
              <td>{{ c.columns.join(', ') }}</td>
              <td>{{ c.ref_table || '-' }}</td>
              <td>{{ c.ref_columns ? c.ref_columns.join(', ') : '-' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="struct-empty-inline">无约束</div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { databaseApi, type ColumnInfo, type IndexInfo, type ConstraintInfo } from '../api/database'

const props = defineProps<{
  dbId: string
  schema: string
  table: string
}>()

const columns = ref<ColumnInfo[]>([])
const indexes = ref<IndexInfo[]>([])
const constraints = ref<ConstraintInfo[]>([])
const loading = ref(false)

async function loadStructure() {
  if (!props.schema || !props.table) return
  loading.value = true
  try {
    const [colRes, idxRes, conRes] = await Promise.all([
      databaseApi.listColumns(props.dbId, props.schema, props.table),
      databaseApi.listIndexes(props.dbId, props.schema, props.table),
      databaseApi.listConstraints(props.dbId, props.schema, props.table),
    ])
    columns.value = colRes.data
    indexes.value = idxRes.data
    constraints.value = conRes.data
  } catch (e) {
    console.error('Failed to load structure', e)
  } finally {
    loading.value = false
  }
}

watch(() => [props.schema, props.table], () => {
  if (props.schema && props.table) loadStructure()
}, { immediate: true })
</script>

<style scoped>
.structure-view {
  padding: 12px;
  overflow-y: auto;
  height: 100%;
}

.struct-loading, .struct-empty {
  padding: 40px 20px;
  text-align: center;
  color: #8a8e99;
  font-size: 14px;
}

.struct-section {
  margin-bottom: 24px;
}

.struct-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin: 0 0 10px;
}

.struct-empty-inline {
  color: #8a8e99;
  font-size: 13px;
  padding: 8px 0;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th,
.data-table td {
  padding: 6px 10px;
  border-bottom: 1px solid #ebebeb;
  text-align: left;
}

.data-table th {
  background: #f7f8fa;
  font-weight: 600;
  color: #575d6c;
}

.data-table tbody tr:hover {
  background: #f7f8fa;
}

.col-pos {
  color: #8a8e99;
  width: 30px;
}

.col-name {
  font-weight: 500;
  color: #191919;
}

.col-type {
  color: #0073e6;
  font-family: monospace;
  font-size: 12px;
}

.col-default {
  color: #575d6c;
  font-family: monospace;
  font-size: 12px;
}

.col-comment {
  color: #8a8e99;
}

.tag-yes {
  color: #52c41a;
  font-size: 12px;
  font-weight: 600;
}

.tag-no {
  color: #d4380d;
  font-size: 12px;
  font-weight: 600;
}

.tag-pk {
  display: inline-block;
  padding: 0 6px;
  background: #e6f4ff;
  color: #0073e6;
  border-radius: 2px;
  font-size: 11px;
  font-weight: 600;
}

.constraint-type {
  display: inline-block;
  padding: 0 6px;
  border-radius: 2px;
  font-size: 11px;
  font-weight: 600;
}

.ct-PRIMARY_KEY { background: #e6f4ff; color: #0073e6; }
.ct-FOREIGN_KEY { background: #fff7e6; color: #d46b08; }
.ct-UNIQUE { background: #f6ffed; color: #52c41a; }
.ct-CHECK { background: #f2f3f5; color: #575d6c; }
</style>
