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
            <label class="form-label">连接方式</label>
            <div class="radio-group">
              <label class="radio-item" :class="{ disabled: postgresConnectors.length === 0 }">
                <input type="radio" v-model="sourceMode" value="CONNECTOR" :disabled="postgresConnectors.length === 0" /> 选择连接器
              </label>
              <label class="radio-item">
                <input type="radio" v-model="sourceMode" value="TEMPORARY" /> 临时连接
              </label>
            </div>
            <div v-if="connectorsLoading" class="loading-text">正在加载连接器...</div>
            <div v-else-if="postgresConnectors.length === 0" class="hint-text">
              暂无可用的 PostgreSQL 连接器，可使用临时连接填写一次性源库信息。
            </div>
          </div>

          <div v-if="sourceMode === 'CONNECTOR'">
            <div class="form-group">
              <label class="form-label">PostgreSQL 连接器 <span class="required">*</span></label>
              <select v-model="selectedConnectorId" class="form-input">
                <option v-for="connector in postgresConnectors" :key="connector.id" :value="connector.id">
                  {{ connector.name }}<template v-if="connector.target_summary"> - {{ connector.target_summary }}</template>
                </option>
              </select>
            </div>
          </div>

          <div v-else>
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
            <div class="test-conn-row" style="margin-top: 12px;">
              <button class="btn btn-default btn-small" :disabled="!connFormValid || testingConn" @click="handleTestConn">
                {{ testingConn ? '测试中...' : '测试连接' }}
              </button>
              <span v-if="connTestResult === true" class="text-success">✓ 连接成功<span v-if="connVersion" class="conn-version"> ({{ connVersion }})</span><span v-if="walLevelInfo" class="conn-version"> | wal_level={{ walLevelInfo }}<template v-if="hasReplication"> ✓ replication</template><template v-else> ✗ no replication</template></span></span>
              <span v-if="connTestResult === false" class="text-error">✗ {{ connError }}</span>
            </div>
          </div>

          <div v-if="tablesLoading" class="loading-text" style="margin-top: 12px;">正在加载源表列表...</div>
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
              <label class="radio-item" :class="{ disabled: !syncAvailable }">
                <input type="radio" v-model="form.mode" value="SYNC" :disabled="!syncAvailable" /> 持续同步
              </label>
            </div>
            <div v-if="sourceMode === 'CONNECTOR' && !syncAvailable" class="hint-text">
              连接器导入当前支持整库导入和按表选择；持续同步需连接器测试元数据确认后启用。
            </div>
            <div v-else-if="!syncAvailable && walLevelInfo" class="hint-text">
              持续同步需要源库 wal_level=logical 且具有 replication 权限
            </div>
          </div>

          <div v-if="form.mode === 'SYNC'">
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
                  <span class="row-hint">(约 {{ t.estimated_rows > 0 ? t.estimated_rows : '?' }} 行)</span>
                </label>
              </div>
            </div>
            <div v-else class="empty-state"><p>未找到用户表</p></div>
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
                  <span class="row-hint">(约 {{ t.estimated_rows > 0 ? t.estimated_rows : '?' }} 行)</span>
                </label>
              </div>
            </div>
            <div v-else class="empty-state"><p>未找到用户表</p></div>
          </div>
        </div>

        <!-- Step 3: Confirm -->
        <div v-if="step === 2">
          <div v-if="form.mode !== 'SYNC'" class="form-group">
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
          <div v-if="form.mode !== 'SYNC' && form.conflictStrategy === 'REPLACE'" class="warning-box">
            覆盖模式将删除目标表后重新创建，已有数据将丢失。
          </div>
          <div v-if="form.mode === 'SYNC'" class="warning-box" style="border-color: #91d5ff; background: #fdf5ed; color: #2a4d6a;">
            持续同步将通过 PostgreSQL 逻辑复制实时同步数据变更。初始数据将自动复制，之后增量同步。
          </div>
          <div class="confirm-summary">
            <div class="summary-row"><span class="summary-label">源数据库:</span> {{ sourceSummary }}</div>
            <div class="summary-row"><span class="summary-label">导入模式:</span> {{ modeLabel }}</div>
            <div class="summary-row"><span class="summary-label">表数量:</span> {{ tableCount }} 张</div>
            <div v-if="form.mode !== 'SYNC'" class="summary-row"><span class="summary-label">冲突策略:</span> {{ form.conflictStrategy === 'APPEND' ? '追加' : '覆盖' }}</div>
          </div>
          <div v-if="createError" class="error-text" style="margin-top: 12px; color: #e34d59;">{{ createError }}</div>
        </div>
      </div>

      <div class="dialog-footer">
        <button v-if="step > 0" class="btn btn-default" @click="step--">上一步</button>
        <span v-else></span>
        <div>
          <button class="btn btn-default" @click="$emit('close')">取消</button>
          <button v-if="step < 2" class="btn btn-primary" :disabled="!canNext || tablesLoading" @click="nextStep">
            {{ tablesLoading ? '连接中...' : '下一步' }}
          </button>
          <button v-else class="btn btn-primary" :disabled="creating" @click="handleCreate">
            {{ creating ? '创建中...' : (form.mode === 'SYNC' ? '开始同步' : '开始导入') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { importApi, type SourceTableInfo } from '../../api/import'
import { connectorsApi, type Connector } from '../../api/connectors'

const props = defineProps<{ dbId: string; visible: boolean }>()
const emit = defineEmits<{ close: []; created: [task: any] }>()

const steps = ['连接信息', '选择表', '确认导入']
const step = ref(0)
const sourceMode = ref<'CONNECTOR' | 'TEMPORARY'>('TEMPORARY')

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

const connectorsLoading = ref(false)
const postgresConnectors = ref<Connector[]>([])
const selectedConnectorId = ref('')
const sourceTables = ref<SourceTableInfo[]>([])
const tablesLoading = ref(false)
const creating = ref(false)
const connError = ref('')
const testingConn = ref(false)
const connTestResult = ref<boolean | null>(null)
const connVersion = ref('')
const walLevelInfo = ref('')
const hasReplication = ref(false)
const createError = ref('')

const connFormValid = computed(() =>
  form.value.host && form.value.port && form.value.dbname && form.value.user && form.value.password
)

const allSelected = computed(() =>
  sourceTables.value.length > 0 && form.value.selectedTables.length === sourceTables.value.length
)

const tableCount = computed(() => {
  if (form.value.mode === 'FULL') return sourceTables.value.length
  return form.value.selectedTables.length
})

const syncAvailable = computed(() =>
  sourceMode.value === 'TEMPORARY' && walLevelInfo.value === 'logical' && hasReplication.value
)

const selectedConnector = computed(() =>
  postgresConnectors.value.find(c => c.id === selectedConnectorId.value) || null
)

const sourceSummary = computed(() => {
  if (sourceMode.value === 'CONNECTOR' && selectedConnector.value) {
    return selectedConnector.value.target_summary
      ? `${selectedConnector.value.name} (${selectedConnector.value.target_summary})`
      : selectedConnector.value.name
  }
  return `${form.value.host}:${form.value.port}/${form.value.dbname}`
})

const modeLabel = computed(() => {
  if (form.value.mode === 'FULL') return '整库导入'
  if (form.value.mode === 'SELECTIVE') return '按表选择'
  return '持续同步'
})

const canNext = computed(() => {
  if (step.value === 0) {
    return sourceMode.value === 'CONNECTOR' ? !!selectedConnectorId.value : connFormValid.value
  }
  if (step.value === 1) return form.value.mode === 'FULL' || form.value.selectedTables.length > 0
  return true
})

watch(() => props.visible, (v) => {
  if (v) {
    resetWizard()
    loadConnectors()
  }
}, { immediate: true })

watch(sourceMode, () => {
  sourceTables.value = []
  form.value.selectedTables = []
  clearConnectionTestMetadata()
  if (sourceMode.value === 'CONNECTOR' && form.value.mode === 'SYNC') {
    form.value.mode = 'FULL'
  }
})

watch(selectedConnectorId, () => {
  sourceTables.value = []
  form.value.selectedTables = []
})

function resetWizard() {
  step.value = 0
  sourceMode.value = 'TEMPORARY'
  postgresConnectors.value = []
  selectedConnectorId.value = ''
  sourceTables.value = []
  clearConnectionTestMetadata()
  createError.value = ''
}

function clearConnectionTestMetadata() {
  connTestResult.value = null
  connError.value = ''
  connVersion.value = ''
  walLevelInfo.value = ''
  hasReplication.value = false
}

async function loadConnectors() {
  connectorsLoading.value = true
  try {
    const res = await connectorsApi.list()
    postgresConnectors.value = res.data.filter(c => c.type === 'POSTGRESQL')
    const firstConnector = postgresConnectors.value[0]
    if (firstConnector) {
      sourceMode.value = 'CONNECTOR'
      selectedConnectorId.value = firstConnector.id
    } else {
      sourceMode.value = 'TEMPORARY'
      selectedConnectorId.value = ''
    }
  } catch (e) {
    console.error('Failed to load connectors', e)
    postgresConnectors.value = []
    sourceMode.value = 'TEMPORARY'
    selectedConnectorId.value = ''
  } finally {
    connectorsLoading.value = false
  }
}

async function handleTestConn() {
  testingConn.value = true
  connTestResult.value = null
  connError.value = ''
  connVersion.value = ''
  try {
    const res = await importApi.testConnection({
      host: form.value.host, port: form.value.port,
      dbname: form.value.dbname, user: form.value.user, password: form.value.password,
    })
    if (res.data.ok) {
      connTestResult.value = true
      connVersion.value = res.data.version || ''
      walLevelInfo.value = res.data.wal_level || ''
      hasReplication.value = res.data.has_replication || false
    } else {
      connTestResult.value = false
      connError.value = res.data.error || '连接失败'
    }
  } catch (e: any) {
    connTestResult.value = false
    connError.value = e.response?.data?.error?.message || e.message || '连接失败'
  } finally {
    testingConn.value = false
  }
}

async function loadSourceTables() {
  tablesLoading.value = true
  connError.value = ''
  try {
    if (sourceMode.value === 'CONNECTOR') {
      const res = await connectorsApi.listPostgresTables(selectedConnectorId.value)
      sourceTables.value = res.data
      return true
    }
    const res = await importApi.listSourceTables({
      host: form.value.host, port: form.value.port,
      dbname: form.value.dbname, user: form.value.user, password: form.value.password,
    })
    sourceTables.value = res.data
    return true
  } catch (e: any) {
    console.error('Failed to load source tables', e)
    const msg = e.response?.data?.error?.message || e.response?.data?.message || e.message || '连接失败'
    connError.value = msg
    return false
  } finally {
    tablesLoading.value = false
  }
}

async function nextStep() {
  if (step.value === 0) {
    const ok = await loadSourceTables()
    if (!ok) return
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
  createError.value = ''
  try {
    const commonPayload = {
      mode: form.value.mode,
      conflictStrategy: form.value.conflictStrategy,
      tables: (form.value.mode === 'SELECTIVE' || form.value.mode === 'SYNC') ? form.value.selectedTables : undefined,
    }
    const payload = sourceMode.value === 'CONNECTOR'
      ? {
          connectorId: selectedConnectorId.value,
          ...commonPayload,
        }
      : {
          sourceHost: form.value.host,
          sourcePort: form.value.port,
          sourceDbname: form.value.dbname,
          sourceUser: form.value.user,
          sourcePassword: form.value.password,
          ...commonPayload,
        }
    const res = await importApi.create(props.dbId, payload)
    emit('created', res.data)
  } catch (e: any) {
    console.error('Failed to create import', e)
    createError.value = e.response?.data?.error?.message || e.response?.data?.message || e.message || '创建导入任务失败'
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
.wizard-step.active { color: #9a5b25; font-weight: 600; }
.wizard-step.done { color: #386b47; }
.step-num {
  width: 22px; height: 22px;
  border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 12px;
  border: 1px solid #c2c6cc;
  flex-shrink: 0;
}
.wizard-step.active .step-num { border-color: #c67d3a; background: #9a5b25; color: #fff; }
.wizard-step.done .step-num { border-color: #386b47; background: #386b47; color: #fff; }
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
.form-label { display: block; font-size: 13px; color: #64748b; margin-bottom: 4px; }
.form-input {
  width: 100%; padding: 6px 10px; font-size: 14px;
  border: 1px solid #c2c6cc; border-radius: 4px; box-sizing: border-box;
}
.required { color: #d4380d; }
.test-conn-row { display: flex; align-items: center; gap: 12px; margin-top: 4px; }
.text-success { color: #386b47; font-size: 13px; }
.text-error { color: #d4380d; font-size: 13px; }
.conn-version { color: #8a8e99; font-size: 12px; }
.radio-group { display: flex; gap: 20px; margin-top: 4px; }
.radio-item { font-size: 14px; color: #2c3e50; cursor: pointer; display: flex; align-items: center; gap: 4px; }
.table-select-list { border: 1px solid #dfe1e6; border-radius: 4px; max-height: 260px; overflow-y: auto; }
.select-all-row { padding: 8px 12px; border-bottom: 1px solid #ebebeb; background: #f9f9f9; }
.table-checkbox-list { padding: 4px 0; }
.checkbox-item { display: flex; align-items: center; gap: 6px; padding: 4px 12px; font-size: 13px; cursor: pointer; }
.checkbox-item:hover { background: #f5f5f5; }
.row-hint { color: #8a8e99; font-size: 12px; }
.loading-text { color: #8a8e99; font-size: 13px; padding: 12px 0; }
.hint-text { color: #8a8e99; font-size: 12px; margin-top: 6px; }
.radio-item.disabled { color: #c2c6cc; cursor: not-allowed; }
.warning-box {
  padding: 8px 12px; background: color-mix(in oklch, var(--cs-warn) 10%, #fff); border: 1px solid #ffd591;
  border-radius: 4px; color: #d46b08; font-size: 13px; margin-bottom: 14px;
}
.confirm-summary { border: 1px solid #dfe1e6; border-radius: 4px; padding: 16px; }
.summary-row { font-size: 14px; color: #2c3e50; padding: 4px 0; }
.summary-label { color: #8a8e99; display: inline-block; min-width: 80px; }
</style>
