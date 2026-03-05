<template>
  <div class="dialog-overlay" @click.self="$emit('close')">
    <div class="dialog-box dialog-wide">
      <div class="dialog-header">
        <h3>创建表</h3>
        <button class="dialog-close" @click="$emit('close')">&times;</button>
      </div>
      <div class="dialog-body">
        <div class="form-group">
          <label class="form-label">表名 <span class="required">*</span></label>
          <input v-model="tableName" class="form-input" placeholder="请输入表名" />
        </div>

        <div class="columns-section">
          <div class="columns-header">
            <label class="form-label">列定义</label>
            <button class="btn btn-small btn-default" @click="addColumn">+ 添加列</button>
          </div>
          <table class="columns-table" v-if="columns.length > 0">
            <thead>
              <tr>
                <th>列名</th>
                <th>类型</th>
                <th>可空</th>
                <th>默认值</th>
                <th>主键</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(col, i) in columns" :key="i">
                <td><input v-model="col.name" class="form-input form-input-sm" placeholder="name" /></td>
                <td>
                  <select v-model="col.type" class="form-select form-select-sm">
                    <option v-for="t in pgTypes" :key="t" :value="t">{{ t }}</option>
                  </select>
                </td>
                <td class="col-center">
                  <input type="checkbox" v-model="col.nullable" />
                </td>
                <td><input v-model="col.defaultValue" class="form-input form-input-sm" placeholder="DEFAULT" /></td>
                <td class="col-center">
                  <input type="checkbox" v-model="col.isPrimaryKey" />
                </td>
                <td>
                  <button class="btn-remove" @click="columns.splice(i, 1)" title="删除">&times;</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="columns-empty">点击"添加列"开始定义表结构</div>
        </div>

        <!-- SQL Preview -->
        <div class="sql-preview" v-if="previewSql">
          <label class="form-label">SQL 预览</label>
          <pre class="sql-code">{{ previewSql }}</pre>
        </div>
      </div>
      <div class="dialog-footer">
        <button class="btn btn-default" @click="$emit('close')">取消</button>
        <button
          class="btn btn-primary"
          :disabled="!canSubmit || creating"
          @click="handleCreate"
        >{{ creating ? '创建中...' : '创建' }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { databaseApi } from '../api/database'

const props = defineProps<{
  dbId: string
  schema: string
}>()

const emit = defineEmits<{
  close: []
  created: []
}>()

const pgTypes = [
  'integer', 'bigint', 'smallint', 'serial', 'bigserial',
  'text', 'varchar(255)', 'char(1)',
  'boolean',
  'real', 'double precision', 'numeric',
  'date', 'timestamp', 'timestamptz',
  'json', 'jsonb',
  'uuid', 'bytea',
]

interface ColumnDef {
  name: string
  type: string
  nullable: boolean
  defaultValue: string
  isPrimaryKey: boolean
}

const tableName = ref('')
const columns = ref<ColumnDef[]>([])
const creating = ref(false)

function addColumn() {
  columns.value.push({
    name: '',
    type: 'integer',
    nullable: true,
    defaultValue: '',
    isPrimaryKey: false,
  })
}

const canSubmit = computed(() => {
  return tableName.value.trim() && columns.value.length > 0 && columns.value.every(c => c.name.trim() && c.type)
})

const previewSql = computed(() => {
  if (!tableName.value.trim() || columns.value.length === 0) return ''

  const colDefs = columns.value.map(c => {
    let def = `  "${c.name}" ${c.type}`
    if (!c.nullable) def += ' NOT NULL'
    if (c.defaultValue) def += ` DEFAULT ${c.defaultValue}`
    return def
  })

  const pkCols = columns.value.filter(c => c.isPrimaryKey).map(c => `"${c.name}"`)
  if (pkCols.length > 0) {
    colDefs.push(`  PRIMARY KEY (${pkCols.join(', ')})`)
  }

  return `CREATE TABLE "${props.schema}"."${tableName.value}" (\n${colDefs.join(',\n')}\n);`
})

async function handleCreate() {
  if (!canSubmit.value) return
  creating.value = true
  try {
    await databaseApi.createTable(props.dbId, props.schema, {
      name: tableName.value.trim(),
      columns: columns.value.map(c => ({
        name: c.name.trim(),
        type: c.type,
        nullable: c.nullable,
        default_value: c.defaultValue || undefined,
      })),
      primary_key: columns.value.filter(c => c.isPrimaryKey).map(c => c.name.trim()) || undefined,
    })
    emit('created')
  } catch (e) {
    console.error('Failed to create table', e)
  } finally {
    creating.value = false
  }
}

// Add an initial column
addColumn()
</script>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.dialog-box {
  background: #fff;
  border-radius: 4px;
  min-width: 400px;
  max-width: 720px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}

.dialog-wide {
  width: 680px;
}

.dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #ebebeb;
}

.dialog-header h3 {
  margin: 0;
  font-size: 16px;
  color: #191919;
}

.dialog-close {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  color: #8a8e99;
  padding: 0 4px;
}

.dialog-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid #ebebeb;
}

.form-group {
  margin-bottom: 16px;
}

.form-label {
  display: block;
  font-size: 14px;
  color: #191919;
  margin-bottom: 6px;
  font-weight: 500;
}

.required {
  color: #d4380d;
}

.form-input {
  width: 100%;
  padding: 6px 10px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-input:focus {
  border-color: #0073e6;
  outline: none;
}

.form-input-sm {
  padding: 4px 8px;
  font-size: 13px;
}

.form-select {
  padding: 6px 10px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  font-size: 14px;
}

.form-select-sm {
  padding: 4px 8px;
  font-size: 13px;
}

.columns-section {
  margin-bottom: 16px;
}

.columns-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.columns-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.columns-table th,
.columns-table td {
  padding: 6px 6px;
  border-bottom: 1px solid #ebebeb;
  text-align: left;
}

.columns-table th {
  background: #f7f8fa;
  font-weight: 600;
  color: #575d6c;
  font-size: 12px;
}

.col-center {
  text-align: center;
}

.columns-empty {
  text-align: center;
  color: #8a8e99;
  padding: 20px;
  font-size: 13px;
}

.btn-remove {
  background: none;
  border: none;
  font-size: 18px;
  cursor: pointer;
  color: #8a8e99;
  padding: 0 4px;
}

.btn-remove:hover {
  color: #d4380d;
}

.sql-preview {
  margin-top: 8px;
}

.sql-code {
  background: #f2f3f5;
  padding: 12px;
  border-radius: 2px;
  font-size: 13px;
  font-family: monospace;
  white-space: pre-wrap;
  color: #191919;
  max-height: 200px;
  overflow-y: auto;
  margin: 0;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 16px;
  border: 1px solid transparent;
  border-radius: 2px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-small {
  padding: 3px 10px;
  font-size: 13px;
}

.btn-primary {
  background: #0073e6;
  color: #fff;
  border-color: #0073e6;
}

.btn-primary:hover {
  background: #005bb5;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-default {
  background: #fff;
  border: 1px solid #c2c6cc;
  color: #191919;
}

.btn-default:hover {
  border-color: #0073e6;
  color: #0073e6;
}
</style>
