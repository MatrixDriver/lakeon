<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  content: string
  kbId: string
}>()

const emit = defineEmits<{
  (e: 'navigate', title: string): void
}>()

const md = new MarkdownIt({ html: false, linkify: true, typographer: true })

// Wikilink plugin: [[Page Name]] → clickable span
const wikilinkRule = (state: any) => {
  const src = state.src.slice(state.pos)
  const match = src.match(/^\[\[([^\]]+)\]\]/)
  if (!match) return false
  const token = state.push('wikilink', '', 0)
  token.content = match[1]
  state.pos += match[0].length
  return true
}
md.inline.ruler.push('wikilink', wikilinkRule)

md.renderer.rules.wikilink = (tokens: any, idx: number) => {
  const title = tokens[idx].content
  const escaped = title.replace(/"/g, '&quot;')
  return `<a class="wikilink" data-title="${escaped}" href="javascript:void(0)">${title}</a>`
}

const rendered = computed(() => md.render(props.content || ''))

function handleClick(e: Event) {
  const target = e.target as HTMLElement
  if (target.classList.contains('wikilink')) {
    const title = target.getAttribute('data-title')
    if (title) emit('navigate', title)
  }
}
</script>

<template>
  <div class="markdown-body" @click="handleClick" v-html="rendered" />
</template>

<style scoped>
.markdown-body {
  font-size: 15px;
  line-height: 1.75;
  color: #3d3d3d;
}
.markdown-body :deep(h1) {
  font-size: 1.6em;
  margin: 0.8em 0 0.4em;
  border-bottom: 1px solid #e8e0d8;
  padding-bottom: 0.3em;
  color: #2c2c2c;
}
.markdown-body :deep(h2) {
  font-size: 1.3em;
  margin: 0.7em 0 0.3em;
  color: #2c2c2c;
}
.markdown-body :deep(h3) {
  font-size: 1.1em;
  margin: 0.6em 0 0.2em;
  color: #3d3d3d;
}
.markdown-body :deep(p) {
  margin: 0.5em 0;
}
.markdown-body :deep(ul), .markdown-body :deep(ol) {
  padding-left: 1.5em;
}
.markdown-body :deep(li) {
  margin: 0.2em 0;
}
.markdown-body :deep(code) {
  background: #faf5f0;
  padding: 0.15em 0.4em;
  border-radius: 3px;
  font-size: 0.9em;
  color: #c25a3c;
}
.markdown-body :deep(pre) {
  background: #faf5f0;
  padding: 12px 16px;
  border-radius: 6px;
  overflow-x: auto;
  border: 1px solid #e8e0d8;
}
.markdown-body :deep(pre code) {
  background: none;
  padding: 0;
  color: #3d3d3d;
}
.markdown-body :deep(blockquote) {
  border-left: 3px solid #d4a574;
  padding-left: 12px;
  color: #666;
  margin: 0.5em 0;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 0.5em 0;
}
.markdown-body :deep(th), .markdown-body :deep(td) {
  border: 1px solid #e8e0d8;
  padding: 6px 12px;
  text-align: left;
}
.markdown-body :deep(th) {
  background: #faf5f0;
  font-weight: 600;
}
.markdown-body :deep(a) {
  color: #c25a3c;
  text-decoration: none;
}
.markdown-body :deep(a:hover) {
  text-decoration: underline;
}
.markdown-body :deep(a.wikilink) {
  color: #8b6914;
  border-bottom: 1px dashed #8b6914;
  text-decoration: none;
  cursor: pointer;
}
.markdown-body :deep(a.wikilink:hover) {
  color: #6b4f0e;
  border-bottom-style: solid;
}
.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid #e8e0d8;
  margin: 1em 0;
}
.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 4px;
}
</style>
