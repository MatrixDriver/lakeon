<template>
  <div
    v-if="visible"
    class="dialog-overlay"
    data-testid="restore-dialog"
    @click.self="emit('close')"
    @keydown.esc="emit('close')"
  >
    <div class="dialog-box dialog-confirm">
      <div class="dialog-header">
        <h3>恢复到时间点</h3>
        <button class="dialog-close" @click="emit('close')">&times;</button>
      </div>
      <div class="dialog-body">
        <div class="db-summary">
          将从 <code>{{ dbName }}</code> 创建一个新数据库，数据状态对齐到所选时间点。
        </div>

        <div v-if="loadingWindow" class="form-tip">正在读取 PITR 时间窗…</div>

        <div v-else-if="pitrWindow" class="window-info">
          <div class="window-row">
            <span class="window-label">可用区间</span>
            <span class="window-value">
              <code>{{ formatDisplay(pitrWindow.earliest) }}</code>
              <span class="window-arrow">→</span>
              <code>{{ formatDisplay(pitrWindow.latest) }}</code>
            </span>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">
            目标时间 (本地时区) <span class="required">*</span>
          </label>
          <input
            v-model="targetTime"
            class="form-input"
            type="datetime-local"
            step="1"
            data-testid="target-time"
            :min="minLocal"
            :max="maxLocal"
          />
          <div v-if="outOfRange" class="form-tip form-tip-warn">
            所选时间不在可用 PITR 区间内
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">
            新数据库名 <span class="form-hint">(可选，留空自动生成)</span>
          </label>
          <input
            v-model="newDbName"
            class="form-input"
            type="text"
            placeholder="例如 myapp_restored"
            data-testid="new-db-name"
          />
        </div>

        <div v-if="error" class="error-msg" data-testid="error">{{ error }}</div>
      </div>
      <div class="dialog-footer">
        <button class="btn btn-default" :disabled="loading" @click="emit('close')">
          取消
        </button>
        <button
          class="btn btn-primary"
          :disabled="!canSubmit"
          data-testid="confirm-restore"
          @click="submit"
        >
          {{ loading ? '恢复中…' : '恢复' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getPitrWindow, pitr, type PitrWindow } from '../../api/recovery'

const props = defineProps<{
  visible: boolean
  dbId: string
  dbName: string
}>()

const emit = defineEmits<{
  close: []
  restored: [newDbId: string]
}>()

const pitrWindow = ref<PitrWindow | null>(null)
const targetTime = ref<string>('')
const newDbName = ref<string>('')
const loading = ref(false)
const loadingWindow = ref(false)
const error = ref<string | null>(null)

// Convert API ISO string (e.g. "2026-04-23T10:15:30.123Z") to the format
// expected by <input type="datetime-local">: "YYYY-MM-DDTHH:mm:ss" in local time.
function isoToLocalInput(iso: string): string {
  if (!iso) return ''
  // Convert UTC ISO → local-time string for datetime-local input
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  // Format: YYYY-MM-DDTHH:mm:ss in local time
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// Convert local-time picker value ("YYYY-MM-DDTHH:mm" or "YYYY-MM-DDTHH:mm:ss")
// to a UTC ISO string suitable for the PITR API.
function localInputToIso(local: string): string {
  if (!local) return ''
  // datetime-local returns "2026-05-21T10:00" or "2026-05-21T10:00:30" (no Z)
  // new Date() parses this as local time, then toISOString() converts to UTC
  return new Date(local).toISOString()
}

function formatDisplay(iso: string): string {
  // Show UTC in a readable form without the trailing Z noise
  return iso.replace(/\.\d+Z?$/, 'Z')
}

const minLocal = computed(() =>
  pitrWindow.value ? isoToLocalInput(pitrWindow.value.earliest) : undefined,
)
const maxLocal = computed(() =>
  pitrWindow.value ? isoToLocalInput(pitrWindow.value.latest) : undefined,
)

const outOfRange = computed(() => {
  if (!pitrWindow.value || !targetTime.value) return false
  const t = targetTime.value
  return (
    (minLocal.value !== undefined && t < minLocal.value) ||
    (maxLocal.value !== undefined && t > maxLocal.value)
  )
})

const canSubmit = computed(
  () => !loading.value && !loadingWindow.value && !!targetTime.value && !outOfRange.value,
)

watch(
  () => props.visible,
  async (v) => {
    if (!v) return
    // Reset state on each open
    error.value = null
    targetTime.value = ''
    newDbName.value = ''
    pitrWindow.value = null
    loadingWindow.value = true
    try {
      const w = await getPitrWindow(props.dbId)
      pitrWindow.value = w
      // Default to the latest available point
      targetTime.value = isoToLocalInput(w.latest)
    } catch (e: any) {
      error.value = e?.response?.data?.error?.message || e?.message || '无法获取 PITR 时间窗'
    } finally {
      loadingWindow.value = false
    }
  },
  { immediate: true },
)

async function submit() {
  if (!canSubmit.value) return
  loading.value = true
  error.value = null
  try {
    const req: { target_time: string; new_db_name?: string } = {
      target_time: localInputToIso(targetTime.value),
    }
    const trimmedName = newDbName.value.trim()
    if (trimmedName) req.new_db_name = trimmedName
    const resp = await pitr(props.dbId, req)
    emit('restored', resp.new_db_id)
    emit('close')
  } catch (e: any) {
    error.value = e?.response?.data?.error?.message || e?.message || '恢复失败，请重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.form-group {
  margin-bottom: 16px;
}

.form-label {
  display: block;
  font-size: 14px;
  color: #2c3e50;
  margin-bottom: 6px;
  font-weight: 500;
}

.form-hint {
  font-weight: 400;
  color: #8a8e99;
  font-size: 12px;
}

.required {
  color: #ff4d4f;
}

.form-input {
  width: 100%;
  height: 32px;
  padding: 0 8px;
  font-size: 14px;
  border: 1px solid #c2c6cc;
  border-radius: 4px;
  box-sizing: border-box;
  font-family: inherit;
}

.form-input[type='datetime-local'] {
  font-variant-numeric: tabular-nums;
}

.form-input:focus {
  border-color: #c67d3a;
  outline: none;
}

.db-summary {
  font-size: 13px;
  color: #5b6470;
  margin-bottom: 14px;
  line-height: 1.5;
}

.db-summary code,
.window-value code {
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
  background: #faf8f5;
  border: 1px solid #e8e4df;
  border-radius: 3px;
  padding: 1px 6px;
  color: #2c3e50;
}

.window-info {
  background: #fdf5ed;
  border: 1px solid #f0dfc9;
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 16px;
}

.window-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.window-label {
  color: #9a5b25;
  font-weight: 500;
  flex-shrink: 0;
}

.window-value {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.window-arrow {
  color: #b88a5e;
}

.form-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #8a8e99;
}

.form-tip-warn {
  color: #d46b08;
}

.error-msg {
  color: #d4380d;
  font-size: 13px;
  margin-top: 4px;
  padding: 8px 10px;
  background: #fff2f0;
  border: 1px solid #ffccc7;
  border-radius: 4px;
}
</style>
