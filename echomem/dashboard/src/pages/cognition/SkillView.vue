<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useCognitionStore } from '@/stores/cognition'
import { useMemoryStore } from '@/stores/memory'
import EmptyState from '@/components/EmptyState.vue'
import Card from '@/components/Card.vue'
import Tag from '@/components/Tag.vue'

const cog = useCognitionStore()
const mem = useMemoryStore()
const { skills } = storeToRefs(cog)
const router = useRouter()
const ctx = ref('')

onMounted(async () => {
  if (!mem.items.length) await mem.loadInitial()
  ctx.value = mem.items[0]?.text ?? 'general'
  await cog.loadSkills(ctx.value)
})

const sorted = computed(() => [...skills.value].sort((a, b) => b.observed_count - a.observed_count))

function open(id: string) {
  router.push({ query: { ...router.currentRoute.value.query, lineage: id, kind: 'skill' } })
}
</script>

<template>
  <div v-if="sorted.length" class="skills">
    <Card v-for="s in sorted" :key="s.id" @click="open(s.id)" style="cursor: pointer">
      <h3>{{ s.name }}</h3>
      <div class="trig"><Tag tone="mono">{{ s.trigger_pattern }}</Tag></div>
      <ol class="steps">
        <li v-for="(step, i) in s.steps.slice(0, 5)" :key="i">{{ step }}</li>
      </ol>
      <div class="stats">
        <Tag>{{ s.observed_count }} 次观察</Tag>
        <Tag tone="accent">{{ s.success_count }} 次成功</Tag>
      </div>
    </Card>
  </div>
  <EmptyState v-else
    title="AI worker 待机中"
    body="当重复操作模式被识别时，Skill 会自动出现。"
  />
</template>

<style scoped>
.skills { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: var(--space-md); }
h3 { font-size: var(--fs-h3); margin-bottom: var(--space-sm); }
.trig { margin-bottom: var(--space-md); }
.steps { padding-left: var(--space-lg); margin: 0 0 var(--space-md); font-size: var(--fs-sm); color: var(--c-text); }
.steps li { padding: 2px 0; }
.stats { display: flex; gap: var(--space-sm); }
</style>
