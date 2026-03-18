<template>
  <div class="fulltext-highlight" ref="containerRef">
    <div v-if="hasOffset" class="fulltext-rendered" v-html="renderedHtml"></div>
    <div v-else class="fulltext-rendered fulltext-no-offset" v-html="renderedHtmlNoHighlight"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  fulltext: string
  chunkOffsetStart: number | null
  chunkOffsetEnd: number | null
}>()

const md = new MarkdownIt({ html: false, linkify: true, typographer: false })
const containerRef = ref<HTMLElement | null>(null)

const hasOffset = computed(
  () => props.chunkOffsetStart != null && props.chunkOffsetEnd != null
)

const MARKER_START = '\x00HLSTART\x00'
const MARKER_END = '\x00HLEND\x00'

const renderedHtml = computed(() => {
  if (!hasOffset.value) return ''
  const start = props.chunkOffsetStart!
  const end = props.chunkOffsetEnd!
  const text = props.fulltext

  // Clamp offsets
  const s = Math.max(0, Math.min(start, text.length))
  const e = Math.max(s, Math.min(end, text.length))

  const before = text.substring(0, s)
  const highlight = text.substring(s, e)
  const after = text.substring(e)

  const marked = before + MARKER_START + highlight + MARKER_END + after

  let html = md.render(marked)

  // Replace markers with highlight HTML
  html = html
    .replace(/\x00HLSTART\x00/g, '<mark class="chunk-highlight">')
    .replace(/\x00HLEND\x00/g, '</mark>')

  return html
})

const renderedHtmlNoHighlight = computed(() => {
  return md.render(props.fulltext)
})

function scrollHighlightIntoView() {
  if (!containerRef.value) return
  const mark = containerRef.value.querySelector('.chunk-highlight')
  if (mark) {
    mark.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

watch(
  () => [props.chunkOffsetStart, props.chunkOffsetEnd, props.fulltext],
  () => {
    nextTick(scrollHighlightIntoView)
  },
  { immediate: true }
)
</script>

<style scoped>
.fulltext-highlight {
  height: 100%;
  overflow-y: auto;
}

.fulltext-rendered {
  font-size: 14px;
  line-height: 1.8;
  color: #333;
  word-break: break-word;
}

/* markdown-it rendered elements */
.fulltext-rendered :deep(p) {
  margin: 0 0 12px 0;
}

.fulltext-rendered :deep(h1),
.fulltext-rendered :deep(h2),
.fulltext-rendered :deep(h3),
.fulltext-rendered :deep(h4) {
  margin: 16px 0 8px 0;
  font-weight: 600;
  color: #222;
}

.fulltext-rendered :deep(ul),
.fulltext-rendered :deep(ol) {
  padding-left: 20px;
  margin: 0 0 12px 0;
}

.fulltext-rendered :deep(li) {
  margin-bottom: 4px;
}

.fulltext-rendered :deep(code) {
  background: #f3f4f6;
  border-radius: 3px;
  padding: 1px 4px;
  font-size: 13px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.fulltext-rendered :deep(pre) {
  background: #f3f4f6;
  border-radius: 4px;
  padding: 12px;
  overflow-x: auto;
  margin: 0 0 12px 0;
}

.fulltext-rendered :deep(blockquote) {
  border-left: 3px solid #d0d5de;
  margin: 0 0 12px 0;
  padding: 4px 12px;
  color: #666;
}

.fulltext-rendered :deep(.chunk-highlight) {
  background: rgba(0, 115, 230, 0.1);
  border-left: 3px solid #0073e6;
  padding: 2px 0;
  display: inline;
}
</style>
