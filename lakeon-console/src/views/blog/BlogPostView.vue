<template>
  <main class="blog-post-page">
    <div v-if="!post" class="post-not-found">
      <h1>404</h1>
      <p>{{ t('找不到这篇文章', 'Post not found') }}</p>
      <router-link to="/blog">← {{ t('返回博客', 'Back to Blog') }}</router-link>
    </div>
    <article v-else class="post-article">
      <header class="post-header">
        <div class="post-meta">
          <span class="post-date">{{ post.date }}</span>
          <span class="post-category">{{ post.category }}</span>
        </div>
        <h1>{{ locale === 'zh' ? post.titleZh : post.title }}</h1>
        <p class="post-summary">{{ locale === 'zh' ? post.summaryZh : post.summary }}</p>
      </header>
      <div class="post-content" v-html="renderedContent" />
      <footer class="post-footer">
        <router-link to="/blog">← {{ t('返回博客列表', 'Back to Blog') }}</router-link>
      </footer>
    </article>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { useLocale } from '../../stores/locale'
import { getBlogPost } from '../../data/blog-posts'

const route = useRoute()
const { locale, t } = useLocale()

const post = computed(() => getBlogPost(route.params.slug as string))

const renderedContent = computed(() => {
  if (!post.value) return ''
  const md = locale.value === 'zh' ? post.value.contentZh : post.value.content
  const html = marked(md) as string
  return DOMPurify.sanitize(html)
})
</script>

<style scoped>
.blog-post-page { min-height: 100vh; background: #0a0a0a; color: #e5e5e5; }
.post-not-found { max-width: 400px; margin: 120px auto; text-align: center; }
.post-not-found h1 { font-size: 64px; color: #333; }
.post-not-found p { color: #888; margin: 8px 0 24px; }
.post-not-found a { color: #7c3aed; text-decoration: none; }
.post-article { max-width: 720px; margin: 0 auto; padding: 48px 24px 80px; }
.post-header { margin-bottom: 40px; padding-bottom: 24px; border-bottom: 1px solid #1a1a1a; }
.post-meta { display: flex; gap: 8px; margin-bottom: 12px; }
.post-date { font-size: 12px; color: #555; }
.post-category { font-size: 11px; color: #a78bfa; background: #7c3aed22; padding: 1px 6px; border-radius: 4px; }
.post-header h1 { font-size: 28px; font-weight: 700; margin: 0 0 12px; line-height: 1.3; }
.post-summary { color: #888; font-size: 15px; line-height: 1.6; margin: 0; }
.post-content :deep(h2) { font-size: 20px; font-weight: 600; margin: 32px 0 12px; }
.post-content :deep(h3) { font-size: 16px; font-weight: 600; margin: 24px 0 8px; }
.post-content :deep(p) { font-size: 14px; color: #ccc; line-height: 1.8; margin: 0 0 16px; }
.post-content :deep(ul), .post-content :deep(ol) { padding-left: 20px; margin: 0 0 16px; }
.post-content :deep(li) { font-size: 14px; color: #ccc; line-height: 1.8; }
.post-content :deep(code) { font-family: monospace; background: #1a1a1a; padding: 1px 5px; border-radius: 3px; font-size: 13px; color: #60a5fa; }
.post-content :deep(pre) { background: #111; border: 1px solid #222; border-radius: 8px; padding: 16px; overflow-x: auto; margin: 0 0 16px; }
.post-content :deep(pre code) { background: none; padding: 0; color: #ccc; }
.post-content :deep(blockquote) { border-left: 3px solid #7c3aed; padding-left: 16px; color: #888; margin: 0 0 16px; }
.post-footer { margin-top: 48px; padding-top: 24px; border-top: 1px solid #1a1a1a; }
.post-footer a { color: #7c3aed; text-decoration: none; font-size: 14px; }
</style>
