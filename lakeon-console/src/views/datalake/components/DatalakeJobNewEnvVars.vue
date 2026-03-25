<template>
  <div>
    <div class="section-title">环境变量</div>
    <div class="section-desc">绿色行为系统自动注入，不可删除。</div>

    <div class="env-table">
      <div class="env-row env-header">
        <div class="env-cell">变量名</div>
        <div class="env-cell">值</div>
        <div class="env-cell env-del"></div>
      </div>
      <div v-if="inputDatasetId" class="env-row env-auto">
        <div class="env-cell env-key">DATASET_PATH</div>
        <div class="env-cell">OBS 路径（自动注入）</div>
        <div class="env-cell env-del">—</div>
      </div>
      <div class="env-row env-auto">
        <div class="env-cell env-key">OUTPUT_PATH</div>
        <div class="env-cell">{{ outputPath || '自动生成路径' }}</div>
        <div class="env-cell env-del">—</div>
      </div>
      <div v-for="(row, i) in userVars" :key="i" class="env-row">
        <div class="env-cell">
          <input class="env-input" v-model="row.key" placeholder="KEY" @change="emitUpdate" />
        </div>
        <div class="env-cell">
          <input class="env-input" v-model="row.value" placeholder="value" @change="emitUpdate" />
        </div>
        <div class="env-cell env-del" @click="removeRow(i)">✕</div>
      </div>
      <div class="env-add" @click="addRow">＋ 添加环境变量</div>
    </div>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  inputDatasetId: string
  outputPath: string
  userVars: { key: string; value: string }[]
}>()
const emit = defineEmits<{ 'update:userVars': [value: { key: string; value: string }[]] }>()

function addRow() {
  emit('update:userVars', [...props.userVars, { key: '', value: '' }])
}
function removeRow(i: number) {
  const updated = props.userVars.filter((_, idx) => idx !== i)
  emit('update:userVars', updated)
}
function emitUpdate() {
  emit('update:userVars', [...props.userVars])
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 16px; }
.env-table { border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; max-width: 600px; }
.env-row { display: grid; grid-template-columns: 1fr 1fr 32px; border-bottom: 1px solid #f1f5f9; }
.env-row:last-of-type { border-bottom: none; }
.env-cell { padding: 8px 12px; font-size: 12px; color: #334155; border-right: 1px solid #f1f5f9; }
.env-cell:last-child { border-right: none; }
.env-header .env-cell { background: #f8fafc; font-size: 10px; font-weight: 700; color: #94a3b8; text-transform: uppercase; letter-spacing: .5px; }
.env-auto .env-cell { background: #f0fdf4; }
.env-auto .env-key { color: #15803d; font-weight: 700; font-family: monospace; }
.env-auto .env-del { color: #bbf7d0; cursor: default; display: flex; align-items: center; justify-content: center; }
.env-key { font-family: monospace; color: #6d28d9; font-weight: 600; }
.env-del { color: #94a3b8; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.env-del:hover { color: #ef4444; }
.env-input { background: none; border: none; outline: none; font-size: 12px; font-family: monospace; width: 100%; color: #334155; }
.env-add { padding: 8px 12px; font-size: 12px; color: #2563eb; cursor: pointer; background: #fff; border-top: 1px solid #f1f5f9; }
.env-add:hover { background: #f8fafc; }
</style>
