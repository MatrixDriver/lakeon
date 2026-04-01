<template>
  <div class="yaml-editor">
    <textarea
      class="yaml-textarea"
      :value="value"
      @input="$emit('update:value', ($event.target as HTMLTextAreaElement).value)"
      placeholder="在此编辑 YAML..."
      spellcheck="false"
    ></textarea>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  value: string
}>()

defineEmits<{
  'update:value': [value: string]
}>()
</script>

<style scoped>
.yaml-editor { flex: 1; display: flex; flex-direction: column; }
.yaml-textarea {
  flex: 1; width: 100%; border: none; resize: none; padding: 16px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 13px;
  line-height: 1.6; color: #2c3e50; background: #faf9f7; outline: none;
  tab-size: 2;
}
</style>
