<template>
  <div class="mem-base-selector">
    <label class="form-label" style="margin-bottom: 4px;">记忆库</label>
    <select class="form-input" v-model="selected" @change="onChange" style="max-width: 300px;">
      <option v-if="bases.length === 0" value="" disabled>暂无记忆库</option>
      <option v-for="b in bases" :key="b.id" :value="b.id">
        {{ b.name }} ({{ b.status }})
      </option>
    </select>
    <p v-if="bases.length === 0" style="font-size: 12px; color: #999; margin-top: 4px;">
      请先 <router-link to="/memory" style="color: #0073e6;">创建记忆库</router-link>
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listMemoryBases, type MemoryBase } from '@/api/memory'

const emit = defineEmits<{ change: [id: string] }>()

const route = useRoute()
const router = useRouter()
const bases = ref<MemoryBase[]>([])
const selected = ref('')

onMounted(async () => {
  try {
    const { data } = await listMemoryBases()
    bases.value = data.filter(b => b.status === 'READY')
  } catch (e) {
    console.error('Failed to load memory bases', e)
  }
  // Restore from query param or auto-select first
  const fromQuery = route.query.base as string
  if (fromQuery && bases.value.some(b => b.id === fromQuery)) {
    selected.value = fromQuery
  } else if (bases.value.length > 0) {
    selected.value = bases.value[0]!.id
    router.replace({ query: { ...route.query, base: selected.value } })
  }
  if (selected.value) emit('change', selected.value)
})

function onChange() {
  router.replace({ query: { ...route.query, base: selected.value } })
  emit('change', selected.value)
}
</script>
