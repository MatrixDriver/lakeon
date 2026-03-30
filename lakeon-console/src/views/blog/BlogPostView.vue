<template>
  <main class="blog-post-page">
    <div v-if="!post" class="post-not-found">
      <h1>404</h1>
      <p>{{ t('找不到这篇文章', 'Post not found') }}</p>
      <router-link to="/blog">← {{ t('返回博客', 'Back to blog') }}</router-link>
    </div>

    <article v-else class="post-article">
      <header class="post-header">
        <div class="post-meta">
          <span class="category-tag">{{ post.category }}</span>
          <span class="post-date">{{ post.date }}</span>
        </div>
        <h1 class="post-title">{{ locale === 'zh' ? post.titleZh : post.title }}</h1>
        <p class="post-summary">{{ locale === 'zh' ? post.summaryZh : post.summary }}</p>
      </header>

      <div class="post-content" v-html="renderedContent" />

      <footer class="post-footer">
        <router-link to="/blog" class="back-link">← {{ t('返回博客', 'Back to blog') }}</router-link>
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
.blog-post-page {
  min-height: 100vh;
  background: var(--pub-bg);
  color: var(--pub-text);
}

/* 404 */
.post-not-found {
  max-width: 400px;
  margin: 120px auto;
  text-align: center;
}
.post-not-found h1 {
  font-size: 64px;
  color: var(--pub-border);
  margin: 0 0 8px;
}
.post-not-found p {
  color: var(--pub-text-3);
  margin: 0 0 24px;
}
.post-not-found a {
  color: var(--pub-primary);
  text-decoration: none;
}

/* Article layout */
.post-article {
  max-width: 720px;
  margin: 0 auto;
  padding: 56px 24px 80px;
}

/* Header */
.post-header {
  margin-bottom: 40px;
}
.post-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.category-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 500;
  color: var(--pub-primary);
  background: var(--pub-primary-light, #ede9fe);
  border-radius: 20px;
  padding: 3px 12px;
}
.post-date {
  font-size: 12px;
  color: var(--pub-text-3);
}
.post-title {
  font-size: 36px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 16px;
  line-height: 1.3;
}
.post-summary {
  font-size: 15px;
  color: var(--pub-text-2);
  line-height: 1.7;
  font-style: italic;
  margin: 0;
}

/* Footer */
.post-footer {
  margin-top: 48px;
  padding-top: 24px;
  border-top: 1px solid var(--pub-border);
}
.back-link {
  color: var(--pub-primary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: color 0.25s ease;
}
.back-link:hover {
  text-decoration: underline;
}

/* Markdown content */
.post-content :deep(h2) {
  font-size: 24px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 32px 0 12px;
  line-height: 1.3;
}
.post-content :deep(h3) {
  font-size: 18px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 24px 0 10px;
}
.post-content :deep(p) {
  font-size: 15px;
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 16px;
}
.post-content :deep(ul),
.post-content :deep(ol) {
  padding-left: 16px;
  margin: 0 0 16px;
}
.post-content :deep(li) {
  font-size: 15px;
  color: var(--pub-text-2);
  line-height: 1.7;
}
.post-content :deep(a) {
  color: var(--pub-primary);
}
.post-content :deep(code) {
  font-family: monospace;
  background: var(--pub-code-bg, #f1f5f9);
  color: var(--pub-code, #7c3aed);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 13px;
}
.post-content :deep(pre) {
  background: #1e293b;
  border-radius: 12px;
  padding: 14px 16px;
  overflow-x: auto;
  margin: 0 0 16px;
}
.post-content :deep(pre code) {
  background: none;
  color: #a5f3fc;
  font-size: 14px;
  padding: 0;
}
.post-content :deep(blockquote) {
  border-left: 3px solid var(--pub-primary);
  background: var(--pub-primary-light, #ede9fe);
  padding: 12px 16px;
  margin: 0 0 16px;
  border-radius: 0 4px 4px 0;
}
.post-content :deep(blockquote p) {
  margin: 0;
}
</style>
