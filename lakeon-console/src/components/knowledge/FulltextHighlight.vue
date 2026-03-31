<template>
  <div class="fulltext-highlight">
    <div class="fulltext-rendered" ref="contentRef" v-html="baseHtml"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch, onMounted } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  fulltext: string
  chunkContent?: string
  overlapPrev?: number
  chunkOffsetStart: number | null
  chunkOffsetEnd: number | null
}>()

const md = new MarkdownIt({ html: false, linkify: true, typographer: false })
const contentRef = ref<HTMLElement | null>(null)

const baseHtml = computed(() => md.render(props.fulltext || ''))

function highlightChunkInDom() {
  if (!contentRef.value) return

  // Reset DOM to clean HTML (removes previous highlights and text node fragmentation)
  contentRef.value.innerHTML = baseHtml.value

  const searchText = findSearchText()
  if (!searchText || searchText.length < 10) return

  // Walk text nodes
  const textNodes: Text[] = []
  const walker = document.createTreeWalker(contentRef.value, NodeFilter.SHOW_TEXT)
  let node: Text | null
  while ((node = walker.nextNode() as Text | null)) {
    textNodes.push(node)
  }

  // Build concatenated text
  let fullText = ''
  const nodeMap: { node: Text; start: number; end: number }[] = []
  for (const tn of textNodes) {
    const start = fullText.length
    fullText += tn.textContent || ''
    nodeMap.push({ node: tn, start, end: fullText.length })
  }

  const idx = fullText.indexOf(searchText)
  if (idx < 0) return

  // Highlight the full chunk content length, not just the search snippet
  const chunkPlain = stripMarkdown(props.chunkContent || '').trim()
  const highlightLen = Math.max(searchText.length, chunkPlain.length)
  const highlightStart = idx
  const highlightEnd = Math.min(fullText.length, idx + highlightLen)

  // Wrap matching text nodes with <mark>
  for (const entry of nodeMap) {
    if (entry.end <= highlightStart || entry.start >= highlightEnd) continue

    const nodeStart = Math.max(0, highlightStart - entry.start)
    const nodeEnd = Math.min(entry.node.textContent!.length, highlightEnd - entry.start)

    if (nodeStart === 0 && nodeEnd === entry.node.textContent!.length) {
      const mark = document.createElement('mark')
      mark.className = 'chunk-highlight'
      entry.node.parentNode!.replaceChild(mark, entry.node)
      mark.appendChild(entry.node)
    } else {
      const range = document.createRange()
      range.setStart(entry.node, nodeStart)
      range.setEnd(entry.node, nodeEnd)
      const mark = document.createElement('mark')
      mark.className = 'chunk-highlight'
      range.surroundContents(mark)
    }
  }

  // Scroll: find the nearest scrollable ancestor and use scrollIntoView
  setTimeout(() => {
    const mark = contentRef.value?.querySelector('.chunk-highlight')
    if (mark) {
      (mark as HTMLElement).scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, 200)
}

/**
 * Strip markdown syntax to get plain text for DOM matching.
 * The DOM text nodes don't contain markdown syntax (MarkdownIt removes it),
 * so we must strip it from the chunk content before indexOf.
 */
function stripMarkdown(text: string): string {
  return text
    .replace(/^#{1,6}\s+/gm, '')           // headings
    .replace(/\*\*(.+?)\*\*/g, '$1')       // bold
    .replace(/\*(.+?)\*/g, '$1')           // italic
    .replace(/__(.+?)__/g, '$1')           // bold alt
    .replace(/_(.+?)_/g, '$1')             // italic alt
    .replace(/`([^`]+)`/g, '$1')           // inline code
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1') // links
    .replace(/^\s*[-*+]\s+/gm, '')         // list markers
    .replace(/^\s*\d+\.\s+/gm, '')         // ordered list
    .replace(/^\s*>\s?/gm, '')             // blockquote
    .replace(/\|/g, '')                    // table pipes
    .replace(/[-:]{3,}/g, '')              // table separators
    .replace(/```[\s\S]*?```/g, '')        // fenced code blocks
}

function findSearchText(): string | null {
  if (!props.chunkContent || !props.fulltext) return null

  const content = props.chunkContent
  const overlap = props.overlapPrev ?? 0
  const strippedChunk = stripMarkdown(content).trim()

  // Strategy 1: Use char_offset to extract from fulltext, then strip markdown
  // Validate that the extracted text actually overlaps with chunk content
  // (offsets can be wrong when fulltext comes from chunk concatenation instead of OBS)
  if (props.chunkOffsetStart != null && props.chunkOffsetEnd != null
      && props.chunkOffsetEnd <= props.fulltext.length) {
    const raw = props.fulltext.substring(props.chunkOffsetStart, props.chunkOffsetEnd)
    const plain = stripMarkdown(raw).trim()
    // Verify: first 30 chars of extracted text should appear in chunk content
    const verify = plain.substring(0, 30)
    if (verify.length >= 10 && strippedChunk.includes(verify)) {
      // Use the START of the extracted text — this anchors to the correct position
      // in the DOM, avoiding mid-chunk matches that could land on similar content
      const snippet = plain.substring(0, Math.min(80, plain.length)).trim()
      if (snippet.length >= 10) return snippet
    }
  }

  // Strategy 2: Use chunk content directly — skip overlap prefix for uniqueness
  const uniqueStart = Math.min(overlap, strippedChunk.length)
  const uniquePart = strippedChunk.substring(uniqueStart).trim()

  if (uniquePart.length >= 20) {
    return uniquePart.substring(0, Math.min(80, uniquePart.length))
  }

  // Strategy 3: Fallback with raw content start
  const snippet = strippedChunk.substring(0, Math.min(80, strippedChunk.length)).trim()
  return snippet.length >= 10 ? snippet : null
}

// Highlight after mount (DOM is ready) and on chunk change
onMounted(() => {
  setTimeout(highlightChunkInDom, 100)
})

watch(
  () => [props.chunkContent, props.chunkOffsetStart],
  () => { nextTick(() => setTimeout(highlightChunkInDom, 100)) }
)
</script>

<style scoped>
.fulltext-highlight {
  /* No overflow here — let parent .tab-panel-fulltext be the scroll container */
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
  border-left: 3px solid #c67d3a;
  padding: 2px 0;
}
</style>
