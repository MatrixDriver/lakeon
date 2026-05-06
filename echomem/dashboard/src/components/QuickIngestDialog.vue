<script setup lang="ts">
import { inject, ref } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useStatusStore } from '@/stores/status'
import { api, ApiClient } from '@/api/client'
import Button from './Button.vue'
import Icon from './Icon.vue'

const ui = useUiStore()
const status = useStatusStore()
const client = inject<ApiClient>('apiClient', api)

const text = ref('')
const agent = ref('cli')
const sourceKind = ref('explicit')
const submitting = ref(false)
const error = ref<string | null>(null)

async function submit() {
  if (!text.value.trim() || submitting.value) return
  submitting.value = true
  error.value = null
  try {
    await client.post('/memory/ingest', {
      text: text.value, agent_id: agent.value, source_kind: sourceKind.value,
    })
    text.value = ''
    ui.toggleQuickIngest(false)
    status.refresh().catch(() => undefined)
  } catch (e) {
    error.value = (e as { message?: string }).message ?? 'submit failed'
  } finally { submitting.value = false }
}
</script>

<template>
  <div v-if="ui.quickIngestOpen" class="overlay" @click.self="ui.toggleQuickIngest(false)">
    <form class="dialog" @submit.prevent="submit">
      <header class="head">
        <h3>+ Quick ingest</h3>
        <button type="button" class="close" @click="ui.toggleQuickIngest(false)" aria-label="close">
          <Icon name="close" />
        </button>
      </header>
      <div class="row">
        <label>agent
          <select v-model="agent">
            <option value="cli">cli</option>
            <option value="cc">cc</option>
            <option value="openclaw">openclaw</option>
            <option value="hermes">hermes</option>
          </select>
        </label>
        <label>source_kind
          <select v-model="sourceKind">
            <option value="explicit">explicit</option>
            <option value="session">session</option>
            <option value="document">document</option>
          </select>
        </label>
      </div>
      <textarea
        v-model="text" rows="6" autofocus
        placeholder="今天我决定..."
        @keydown.meta.enter.prevent="submit"
      />
      <p v-if="error" class="err">{{ error }}</p>
      <footer class="foot">
        <span class="hint">⌘+Enter 提交</span>
        <div class="actions">
          <Button @click="ui.toggleQuickIngest(false)">取消</Button>
          <Button variant="primary" :disabled="submitting || !text.trim()" @click="submit">Ingest</Button>
        </div>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed; inset: 0;
  background: rgba(44, 62, 80, 0.18);
  display: flex; align-items: center; justify-content: center;
  z-index: 60;
}
.dialog {
  background: var(--c-bg); border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  width: min(560px, 90vw);
  display: flex; flex-direction: column;
  padding: var(--space-lg);
  gap: var(--space-md);
}
.head { display: flex; justify-content: space-between; align-items: center; }
.head h3 { font-size: var(--fs-h3); }
.close { background: transparent; border: none; cursor: pointer; color: var(--c-text-muted); }
.row { display: flex; gap: var(--space-lg); font-size: var(--fs-xs); color: var(--c-text-muted); }
.row label { display: flex; flex-direction: column; gap: 4px; }
.row select { font-family: var(--font-body); padding: 4px 8px; border: 1px solid var(--c-border); border-radius: var(--radius-sm); }
textarea {
  font-family: var(--font-mono); font-size: var(--fs-sm);
  padding: var(--space-md); border: 1px solid var(--c-border); border-radius: var(--radius-md);
  resize: vertical;
}
.err { color: var(--c-danger); font-size: var(--fs-xs); margin: 0; }
.foot { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: var(--fs-xs); color: var(--c-text-muted); }
.actions { display: flex; gap: var(--space-sm); }
</style>
