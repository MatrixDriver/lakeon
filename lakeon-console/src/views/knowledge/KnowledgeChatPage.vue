<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listKnowledgeBases } from '@/api/knowledge'
import WikiChat from './WikiChat.vue'

const knowledgeBases = ref<any[]>([])
const selectedKbId = ref('')

onMounted(async () => {
  try {
    const resp = await listKnowledgeBases()
    knowledgeBases.value = resp.data
    if (resp.data.length > 0 && resp.data[0]) {
      selectedKbId.value = resp.data[0].id
    }
  } catch (e) {
    console.error('Failed to load KBs:', e)
  }
})
</script>

<template>
  <div style="height: 100%; display: flex; flex-direction: column;">
    <!-- KB selector -->
    <div style="padding: 0 0 16px; display: flex; align-items: center; gap: 12px;">
      <h2 style="font-size: 18px; color: #3d3d3d; margin: 0;">对话</h2>
      <select v-model="selectedKbId" style="border: 1px solid #d4c4b0; border-radius: 6px; padding: 6px 12px; font-size: 13px; color: #5a4a3a; outline: none; background: #fff;">
        <option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
      </select>
    </div>
    <!-- Chat -->
    <div style="flex: 1; overflow: hidden;">
      <WikiChat v-if="selectedKbId" :kb-id="selectedKbId" @navigate="() => {}" />
      <div v-else style="padding: 40px; text-align: center; color: #b0a090;">请先选择一个知识库</div>
    </div>
  </div>
</template>
