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

/** Map a normalized-text length back to original-text length (accounting for collapsed whitespace) */
function findOrigLength(orig: string, startIdx: number, normLen: number): number {
  let normCount = 0, i = startIdx
  while (normCount < normLen && i < orig.length) {
    if (/\s/.test(orig.charAt(i))) {
      while (i + 1 < orig.length && /\s/.test(orig.charAt(i + 1))) i++
    }
    i++
    normCount++
  }
  return i - startIdx
}

function highlightChunkInDom() {
  if (!contentRef.value) return

  // Reset DOM to clean HTML (removes previous highlights and text node fragmentation)
  contentRef.value.innerHTML = baseHtml.value

  const searchText = findSearchText()
  if (!searchText || searchText.length < 10) {
    console.warn('[FulltextHighlight] No valid searchText, skipping highlight')
    return
  }

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

  // Normalize whitespace for matching: collapse all whitespace to single space
  const norm = (s: string) => s.replace(/\s+/g, ' ')
  const fullTextNorm = norm(fullText)
  const searchNorm = norm(searchText)

  let idx = fullText.indexOf(searchText)
  let usedNorm = false
  if (idx < 0) {
    // Try normalized match
    const normIdx = fullTextNorm.indexOf(searchNorm)
    if (normIdx >= 0) {
      // Map normalized index back to original fullText index
      // Walk original fullText, counting non-collapsed chars
      let origIdx = 0, normCount = 0
      while (normCount < normIdx && origIdx < fullText.length) {
        if (/\s/.test(fullText.charAt(origIdx))) {
          // skip extra whitespace chars that were collapsed
          while (origIdx + 1 < fullText.length && /\s/.test(fullText.charAt(origIdx + 1))) origIdx++
        }
        origIdx++
        normCount++
      }
      idx = origIdx
      usedNorm = true
      console.log('[FulltextHighlight] Normalized match at normIdx', normIdx, '→ origIdx', idx)
    }
  }

  if (idx < 0) {
    console.warn('[FulltextHighlight] searchText not found in DOM text', {
      searchText,
      domTextLen: fullText.length,
      domTextFirst200: fullText.substring(0, 200),
    })
    return
  }
  if (!usedNorm) console.log('[FulltextHighlight] Direct match at index', idx)

  // Highlight the full chunk content length, not just the search snippet
  const chunkPlain = stripMarkdown(props.chunkContent || '').trim()
  // For normalized matches, calculate highlight length in original text
  const searchLenInOrig = usedNorm ? findOrigLength(fullText, idx, searchNorm.length) : searchText.length
  const highlightLen = Math.max(searchLenInOrig, chunkPlain.length)
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

  // Scroll: instant jump to highlighted chunk
  setTimeout(() => {
    const mark = contentRef.value?.querySelector('.chunk-highlight')
    if (mark) {
      (mark as HTMLElement).scrollIntoView({ behavior: 'instant', block: 'center' })
    }
  }, 50)
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
    .replace(/^\s*\d+\.\s{2,}/gm, '')     // ordered list (only if 2+ spaces after dot, avoid stripping "22. text")
    .replace(/^\s*>\s?/gm, '')             // blockquote
    .replace(/\|/g, '')                    // table pipes
    .replace(/[-:]{3,}/g, '')              // table separators
    .replace(/```[\s\S]*?```/g, '')        // fenced code blocks
}

/**
 * Extract a single-line snippet (no newlines) up to maxLen chars.
 * DOM text nodes don't contain newlines across paragraphs, so snippets
 * must stay within one paragraph to match via indexOf.
 */
function singleLineSnippet(text: string, maxLen = 80): string | null {
  const line = (text.split(/\n/)[0] ?? '').trim()
  const snippet = line.substring(0, maxLen).trim()
  return snippet.length >= 10 ? snippet : null
}

function findSearchText(): string | null {
  if (!props.chunkContent || !props.fulltext) return null

  const content = props.chunkContent
  const overlap = props.overlapPrev ?? 0
  const strippedChunk = stripMarkdown(content).trim()

  console.log('[FulltextHighlight] findSearchText start', {
    chunkContentLen: content.length,
    strippedChunkLen: strippedChunk.length,
    offsetStart: props.chunkOffsetStart,
    offsetEnd: props.chunkOffsetEnd,
    fulltextLen: props.fulltext.length,
    overlap,
    strippedFirst80: strippedChunk.substring(0, 80),
  })

  // Strategy 1: Use char_offset to extract from fulltext, then strip markdown
  if (props.chunkOffsetStart != null && props.chunkOffsetEnd != null
      && props.chunkOffsetEnd <= props.fulltext.length) {
    const raw = props.fulltext.substring(props.chunkOffsetStart, props.chunkOffsetEnd)
    const plain = stripMarkdown(raw).trim()
    const verify = plain.substring(0, 30)
    if (verify.length >= 10 && strippedChunk.includes(verify)) {
      const snippet = singleLineSnippet(plain)
      if (snippet) {
        console.log('[FulltextHighlight] Strategy 1 (offset) matched:', snippet)
        return snippet
      }
    }
    console.log('[FulltextHighlight] Strategy 1 failed, verify:', verify)
  }

  // Strategy 2: Use chunk content directly — skip overlap prefix for uniqueness
  const uniqueStart = Math.min(overlap, strippedChunk.length)
  const uniquePart = strippedChunk.substring(uniqueStart).trim()

  if (uniquePart.length >= 20) {
    const snippet = singleLineSnippet(uniquePart)
    if (snippet) {
      console.log('[FulltextHighlight] Strategy 2 (unique part) matched:', snippet)
      return snippet
    }
  }

  // Strategy 3: Fallback with raw content start
  const snippet3 = singleLineSnippet(strippedChunk)
  if (snippet3) {
    console.log('[FulltextHighlight] Strategy 3 (raw start) matched:', snippet3)
    return snippet3
  }

  // Strategy 4: Try matching raw chunk content (without stripping markdown) against DOM text
  // This handles cases where stripMarkdown over-strips (e.g., "22. " treated as list marker)
  const rawFirst = content.replace(/\n/g, ' ').trim().substring(0, 80).trim()
  if (rawFirst.length >= 10) {
    console.log('[FulltextHighlight] Strategy 4 (raw no-strip) trying:', rawFirst)
    return rawFirst
  }

  console.warn('[FulltextHighlight] All strategies failed, no searchText found')
  return null
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
