<template>
  <div class="fulltext-highlight" ref="containerRef">
    <div class="fulltext-rendered" v-html="renderedHtml"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  fulltext: string
  chunkContent?: string
  chunkOffsetStart: number | null
  chunkOffsetEnd: number | null
}>()

const md = new MarkdownIt({ html: false, linkify: true, typographer: false })
const containerRef = ref<HTMLElement | null>(null)

const MARKER_START = '\x00HLSTART\x00'
const MARKER_END = '\x00HLEND\x00'

/**
 * Find the highlight range: prefer text search (reliable), fall back to offsets.
 * Returns [start, end] in the fulltext string, or null if not found.
 */
function findHighlightRange(): [number, number] | null {
  const text = props.fulltext
  if (!text) return null

  // Strategy 1: search for chunk content in fulltext (skip overlap prefix)
  if (props.chunkContent) {
    const content = props.chunkContent
    // Try progressively shorter snippets from the middle to find a match
    // (start of chunk may have overlap from previous chunk)
    for (const skipChars of [0, 100, 200, 300]) {
      const searchText = content.substring(skipChars, skipChars + 200)
      if (searchText.length < 20) continue
      const idx = text.indexOf(searchText)
      if (idx >= 0) {
        // Found — expand to find full chunk boundaries
        // Search backward for the chunk start
        let start = idx
        const contentStart = content.substring(0, 50)
        const backSearch = text.lastIndexOf(contentStart, idx + 50)
        if (backSearch >= 0 && backSearch <= idx) {
          start = backSearch
        }
        // End = start + content length, clamped
        const end = Math.min(start + content.length, text.length)
        return [start, end]
      }
    }
  }

  // Strategy 2: use offsets (may be inaccurate)
  if (props.chunkOffsetStart != null && props.chunkOffsetEnd != null) {
    const s = Math.max(0, Math.min(props.chunkOffsetStart, text.length))
    const e = Math.max(s, Math.min(props.chunkOffsetEnd, text.length))
    if (e > s) return [s, e]
  }

  return null
}

const renderedHtml = computed(() => {
  const text = props.fulltext
  if (!text) return ''

  const range = findHighlightRange()
  if (!range) return md.render(text)

  const [s, e] = range
  const before = text.substring(0, s)
  const highlight = text.substring(s, e)
  const after = text.substring(e)

  const marked = before + MARKER_START + highlight + MARKER_END + after
  let html = md.render(marked)
  html = html
    .replace(/\x00HLSTART\x00/g, '<mark class="chunk-highlight">')
    .replace(/\x00HLEND\x00/g, '</mark>')
  return html
})

function scrollHighlightIntoView() {
  if (!containerRef.value) return
  const mark = containerRef.value.querySelector('.chunk-highlight')
  if (mark) {
    mark.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

watch(
  () => [props.chunkContent, props.chunkOffsetStart, props.chunkOffsetEnd],
  () => { nextTick(scrollHighlightIntoView) },
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

.fulltext-rendered :deep(p) { margin: 0 0 12px 0; }
.fulltext-rendered :deep(h1),
.fulltext-rendered :deep(h2),
.fulltext-rendered :deep(h3),
.fulltext-rendered :deep(h4) { margin: 16px 0 8px 0; font-weight: 600; color: #222; }
.fulltext-rendered :deep(ul),
.fulltext-rendered :deep(ol) { padding-left: 20px; margin: 0 0 12px 0; }
.fulltext-rendered :deep(li) { margin-bottom: 4px; }
.fulltext-rendered :deep(code) { background: #f3f4f6; border-radius: 3px; padding: 1px 4px; font-size: 13px; }
.fulltext-rendered :deep(pre) { background: #f3f4f6; border-radius: 4px; padding: 12px; overflow-x: auto; margin: 0 0 12px 0; }
.fulltext-rendered :deep(blockquote) { border-left: 3px solid #d0d5de; margin: 0 0 12px 0; padding: 4px 12px; color: #666; }

.fulltext-rendered :deep(.chunk-highlight) {
  background: rgba(0, 115, 230, 0.1);
  border-left: 3px solid #0073e6;
  padding: 2px 0;
  display: inline;
}
</style>
