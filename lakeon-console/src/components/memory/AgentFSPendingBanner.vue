<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api/client'

const pendingCount = ref(0)
const hasTarget = ref(true)
let timer: ReturnType<typeof setInterval> | null = null
const router = useRouter()

async function poll() {
  try {
    const [target, pending] = await Promise.all([
      api.get('/agentfs/memory-target'),
      api.get('/agentfs/memory-target/pending-derivation-count'),
    ])
    hasTarget.value = Boolean(target.data?.base_id)
    pendingCount.value = pending.data?.count ?? 0
  } catch (e) {
    // silent: don't show banner on auth errors
    hasTarget.value = true
    pendingCount.value = 0
  }
}

function goToMemoryBases() {
  router.push('/memory')
}

onMounted(() => {
  poll()
  timer = setInterval(poll, 30_000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div
    v-if="pendingCount > 0 && !hasTarget"
    class="pending-banner"
    @click="goToMemoryBases"
  >
    AgentFS 有 {{ pendingCount }} 条待派生 memory，点此选择一个目标 base
  </div>
</template>

<style scoped>
.pending-banner {
  background: var(--color-warm-accent-soft, #f3eae0);
  color: var(--color-warm-accent, #c97b3f);
  padding: 0.5rem 1rem;
  font-size: 0.9rem;
  border-bottom: 1px solid var(--color-warm-accent, #c97b3f);
  cursor: pointer;
  text-align: center;
}
.pending-banner:hover {
  background: #ecdfd0;
}
</style>
