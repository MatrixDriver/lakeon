<template>
  <div class="fulltext-highlight">
    <div class="fulltext-rendered" ref="contentRef" v-html="baseHtml"></div>
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
const contentRef = ref<HTMLElement | null>(null)

const baseHtml = computed(() => md.render(props.fulltext || ''))

/**
 * Find and highlight the chunk content in the rendered DOM using text node walking.
 * This avoids issues with markdown rendering eating markers.
 */
function highlightChunkInDom() {
  if (!contentRef.value) return

  // Remove any previous highlights
  contentRef.value.querySelectorAll('.chunk-highlight').forEach(el => {
    const parent = el.parentNode
    if (parent) {
      while (el.firstChild) parent.insertBefore(el.firstChild, el)
      parent.removeChild(el)
    }
  })

  const searchText = findSearchText()
  if (!searchText || searchText.length < 10) return

  // Walk text nodes and find the search text
  const textNodes: Text[] = []
  const walker = document.createTreeWalker(contentRef.value, NodeFilter.SHOW_TEXT)
  let node: Text | null
  while ((node = walker.nextNode() as Text | null)) {
    textNodes.push(node)
  }

  // Build concatenated text with node boundaries
  let fullText = ''
  const nodeMap: { node: Text; start: number; end: number }[] = []
  for (const tn of textNodes) {
    const start = fullText.length
    fullText += tn.textContent || ''
    nodeMap.push({ node: tn, start, end: fullText.length })
  }

  // Find the chunk text in the concatenated text
  const idx = fullText.indexOf(searchText)
  if (idx < 0) return

  const highlightStart = idx
  const highlightEnd = idx + searchText.length

  // Find which text nodes overlap with the highlight range
  for (const entry of nodeMap) {
    if (entry.end <= highlightStart || entry.start >= highlightEnd) continue

    const nodeStart = Math.max(0, highlightStart - entry.start)
    const nodeEnd = Math.min(entry.node.textContent!.length, highlightEnd - entry.start)

    if (nodeStart === 0 && nodeEnd === entry.node.textContent!.length) {
      // Whole node is highlighted
      const mark = document.createElement('mark')
      mark.className = 'chunk-highlight'
      entry.node.parentNode!.replaceChild(mark, entry.node)
      mark.appendChild(entry.node)
    } else {
      // Partial highlight — split the text node
      const range = document.createRange()
      range.setStart(entry.node, nodeStart)
      range.setEnd(entry.node, nodeEnd)
      const mark = document.createElement('mark')
      mark.className = 'chunk-highlight'
      range.surroundContents(mark)
    }
  }

  // Scroll to first highlight
  nextTick(() => {
    const mark = contentRef.value?.querySelector('.chunk-highlight')
    if (mark) {
      mark.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  })
}

function findSearchText(): string | null {
  if (!props.chunkContent) return null
  const content = props.chunkContent
  // Use a middle snippet (200 chars) to avoid overlap prefix issues
  // Try multiple positions
  for (const skip of [Math.floor(content.length / 3), 0, 100, 200]) {
    const snippet = content.substring(skip, skip + 200).trim()
    if (snippet.length >= 20) return snippet
  }
  return content.length >= 20 ? content.substring(0, 200) : null
}

watch(
  () => [props.chunkContent, props.chunkOffsetStart, baseHtml.value],
  () => { nextTick(() => nextTick(highlightChunkInDom)) },
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
  background: rgba(0, 115, 230, 0.12);
  border-left: 3px solid #0073e6;
  padding: 2px 0;
}
</style>
