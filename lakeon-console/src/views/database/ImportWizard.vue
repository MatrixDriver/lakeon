<template>
  <div v-if="visible" class="dialog-overlay" @click.self="$emit('close')">
    <div class="dialog-box dialog-wizard">
      <div class="dialog-header">
        <h3>导入数据</h3>
        <button class="dialog-close" @click="$emit('close')">&times;</button>
      </div>

      <!-- Steps indicator -->
      <div class="wizard-steps">
        <div v-for="(s, i) in steps" :key="i" class="wizard-step" :class="{ active: step === i, done: step > i }">
          <span class="step-num">{{ step > i ? '✓' : i + 1 }}</span>
          <span class="step-label">{{ s }}</span>
        </div>
      </div>

      <div class="dialog-body">
        <!-- Step 1: Connection -->
        <div v-if="step === 0">
          <div class="form-group">
            <label class="form-label">主机地址 <span class="required">*</span></label>
            <input v-model="form.host" class="form-input" placeholder="例如: 192.168.0.100" />
          </div>
          <div class="form-row">
            <div class="form-group form-half">
              <label class="form-label">端口 <span class="required">*</span></label>
              <input v-model.number="form.port" type="number" class="form-input" />
            </div>
            <div class="form-group form-half">
              <label class="form-label">数据库名 <span class="required">*</span></label>
              <input v-model="form.dbname" class="form-input" placeholder="postgres" />
            </div>
          </div>
          <div class="form-row">
            <div class="form-group form-half">
              <label class="form-label">用户名 <span class="required">*</span></label>
              <input v-model="form.user" class="form-input" placeholder="postgres" />
            </div>
            <div class="form-group form-half">
              <label class="form-label">密码 <span class="required">*</span></label>
              <input v-model="form.password" type="password" class="form-input" />
            </div>
          </div>
        </div>

        <!-- Step 2: Table Selection -->
        <div v-if="step === 1">
          <div class="form-group">
            <label class="form-label">导入模式</label>
            <div class="radio-group">
              <label class="radio-item">
                <input type="radio" v-model="form.mode" value="FULL" /> 整库导入
              </label>
              <label class="radio-item">
                <input type="radio" v-model="form.mode" value="SELECTIVE" /> 按表选择
              </label>
            </div>
          </div>

          <div v-if="form.mode === 'SELECTIVE'">
            <div v-if="tablesLoading" class="loading-text">加载表列表...</div>
            <div v-else-if="sourceTables.length > 0" class="table-select-list">
              <div class="select-all-row">
                <label class="checkbox-item">
                  <input type="checkbox" :checked="allSelected" @change="toggleAll" /> 全选 ({{ sourceTables.length }} 张表)
                </label>
              </div>
              <div class="table-checkbox-list">
                <label v-for="t in sourceTables" :key="t.schema + '.' + t.table" class="checkbox-item">
                  <input type="checkbox" :value="t.schema + '.' + t.table" v-model="form.selectedTables" />
                  {{ t.schema }}.{{ t.table }}
                  <span class="row-hint">(约 {{ t.estimated_rows }} 行)</span>
                </label>
              </div>
            </div>
            <div v-else class="empty-state"><p>未找到用户表</p></div>
          </div>
        </div>

        <!-- Step 3: Confirm -->
        <div v-if="step === 2">
          <div class="form-group">
            <label class="form-label">冲突策略</label>
            <div class="radio-group">
              <label class="radio-item">
                <input type="radio" v-model="form.conflictStrategy" value="APPEND" /> 追加数据
              </label>
              <label class="radio-item">
                <input type="radio" v-model="form.conflictStrategy" value="REPLACE" /> 覆盖（先删后建）
              </label>
            </div>
          </div>
          <div v-if="form.conflictStrategy === 'REPLACE'" class="warning-box">
            覆盖模式将删除目标表后重新创建，已有数据将丢失。
          </div>
          <div class="confirm-summary">
            <div class="summary-row"><span class="summary-label">源数据库:</span> {{ form.host }}:{{ form.port }}/{{ form.dbname }}</div>
            <div class="summary-row"><span class="summary-label">导入模式:</span> {{ form.mode === 'FULL' ? '整库导入' : '按表选择' }}</div>
            <div class="summary-row"><span class="summary-label">表数量:</span> {{ tableCount }} 张</div>
            <div class="summary-row"><span class="summary-label">冲突策略:</span> {{ form.conflictStrategy === 'APPEND' ? '追加' : '覆盖' }}</div>
          </div>
        </div>
      </div>

      <div class="dialog-footer">
        <button v-if="step > 0" class="btn btn-default" @click="step--">上一步</button>
        <span v-else></span>
        <div>
          <button class="btn btn-default" @click="$emit('close')">取消</button>
          <button v-if="step < 2" class="btn btn-primary" :disabled="!canNext" @click="nextStep">下一步</button>
          <button v-else class="btn btn-primary" :disabled="creating" @click="handleCreate">
            {{ creating ? '创建中...' : '开始导入' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { importApi, type SourceTableInfo } from '../../api/import'

const props = defineProps<{ dbId: string; visible: boolean }>()
const emit = defineEmits<{ close: []; created: [task: any] }>()

const steps = ['连接信息', '选择表', '确认导入']
const step = ref(0)

const form = ref({
  host: '',
  port: 5432,
  dbname: '',
  user: 'postgres',
  password: '',
  mode: 'FULL' as string,
  conflictStrategy: 'APPEND' as string,
  selectedTables: [] as string[],
})

const sourceTables = ref<SourceTableInfo[]>([])
const tablesLoading = ref(false)
const creating = ref(false)

const connFormValid = computed(() =>
  form.value.host && form.value.port && form.value.dbname && form.value.user && form.value.password
)

const allSelected = computed(() =>
  sourceTables.value.length > 0 && form.value.selectedTables.length === sourceTables.value.length
)

const tableCount = computed(() =>
  form.value.mode === 'FULL' ? sourceTables.value.length : form.value.selectedTables.length
)

const canNext = computed(() => {
  if (step.value === 0) return connFormValid.value
  if (step.value === 1) return form.value.mode === 'FULL' || form.value.selectedTables.length > 0
  return true
})

watch(() => props.visible, (v) => {
  if (v) {
    step.value = 0
    sourceTables.value = []
  }
})

async function loadSourceTables() {
  tablesLoading.value = true
  try {
    const res = await importApi.listSourceTables({
      host: form.value.host, port: form.value.port,
      dbname: form.value.dbname, user: form.value.user, password: form.value.password,
    })
    sourceTables.value = res.data
  } catch (e) {
    console.error('Failed to load source tables', e)
  } finally {
    tablesLoading.value = false
  }
}

function nextStep() {
  if (step.value === 0 && sourceTables.value.length === 0) {
    loadSourceTables()
  }
  step.value++
}

function toggleAll() {
  if (allSelected.value) {
    form.value.selectedTables = []
  } else {
    form.value.selectedTables = sourceTables.value.map(t => t.schema + '.' + t.table)
  }
}

async function handleCreate() {
  creating.value = true
  try {
    const res = await importApi.create(props.dbId, {
      sourceHost: form.value.host,
      sourcePort: form.value.port,
      sourceDbname: form.value.dbname,
      sourceUser: form.value.user,
      sourcePassword: form.value.password,
      mode: form.value.mode,
      conflictStrategy: form.value.conflictStrategy,
      tables: form.value.mode === 'SELECTIVE' ? form.value.selectedTables : undefined,
    })
    emit('created', res.data)
  } catch (e) {
    console.error('Failed to create import', e)
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.dialog-wizard {
  width: 560px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}
.wizard-steps {
  display: flex;
  padding: 16px 24px;
  border-bottom: 1px solid #ebebeb;
  gap: 8px;
}
.wizard-step {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  color: #8a8e99;
  font-size: 13px;
}
.wizard-step.active { color: #0073e6; font-weight: 600; }
.wizard-step.done { color: #52c41a; }
.step-num {
  width: 22px; height: 22px;
  border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 12px;
  border: 1px solid #c2c6cc;
  flex-shrink: 0;
}
.wizard-step.active .step-num { border-color: #0073e6; background: #0073e6; color: #fff; }
.wizard-step.done .step-num { border-color: #52c41a; background: #52c41a; color: #fff; }
.dialog-body { padding: 20px 24px; overflow-y: auto; flex: 1; }
.dialog-footer {
  padding: 12px 24px;
  border-top: 1px solid #ebebeb;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.dialog-footer > div { display: flex; gap: 8px; }
.form-row { display: flex; gap: 12px; }
.form-half { flex: 1; }
.form-group { margin-bottom: 14px; }
.form-label { display: block; font-size: 13px; color: #575d6c; margin-bottom: 4px; }
.form-input {
  width: 100%; padding: 6px 10px; font-size: 14px;
  border: 1px solid #c2c6cc; border-radius: 2px; box-sizing: border-box;
}
.required { color: #d4380d; }
.test-conn-row { display: flex; align-items: center; gap: 12px; margin-top: 4px; }
.text-success { color: #52c41a; font-size: 13px; }
.text-error { color: #d4380d; font-size: 13px; }
.radio-group { display: flex; gap: 20px; margin-top: 4px; }
.radio-item { font-size: 14px; color: #191919; cursor: pointer; display: flex; align-items: center; gap: 4px; }
.table-select-list { border: 1px solid #dfe1e6; border-radius: 2px; max-height: 260px; overflow-y: auto; }
.select-all-row { padding: 8px 12px; border-bottom: 1px solid #ebebeb; background: #f9f9f9; }
.table-checkbox-list { padding: 4px 0; }
.checkbox-item { display: flex; align-items: center; gap: 6px; padding: 4px 12px; font-size: 13px; cursor: pointer; }
.checkbox-item:hover { background: #f5f5f5; }
.row-hint { color: #8a8e99; font-size: 12px; }
.loading-text { color: #8a8e99; font-size: 13px; padding: 12px 0; }
.warning-box {
  padding: 8px 12px; background: #fff7e6; border: 1px solid #ffd591;
  border-radius: 2px; color: #d46b08; font-size: 13px; margin-bottom: 14px;
}
.confirm-summary { border: 1px solid #dfe1e6; border-radius: 2px; padding: 16px; }
.summary-row { font-size: 14px; color: #191919; padding: 4px 0; }
.summary-label { color: #8a8e99; display: inline-block; min-width: 80px; }
</style>
