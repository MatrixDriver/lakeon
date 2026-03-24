<template>
  <main class="blog-list-page">
    <div class="blog-list-inner">
      <h1>{{ t('博客', 'Blog') }}</h1>
      <p class="blog-subtitle">{{ t('产品更新、技术解析、使用指南', 'Product updates, technical deep-dives, and guides') }}</p>
      <div class="post-list">
        <router-link
          v-for="post in sortedPosts"
          :key="post.slug"
          :to="`/blog/${post.slug}`"
          class="post-card"
        >
          <div class="post-meta">
            <span class="post-date">{{ post.date }}</span>
            <span class="post-category">{{ post.category }}</span>
          </div>
          <h2>{{ locale === 'zh' ? post.titleZh : post.title }}</h2>
          <p>{{ locale === 'zh' ? post.summaryZh : post.summary }}</p>
          <span class="post-read-more">{{ t('阅读全文', 'Read more') }} →</span>
        </router-link>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'
import { blogPosts } from '../../data/blog-posts'

const { locale, t } = useLocale()
const sortedPosts = computed(() =>
  [...blogPosts].sort((a, b) => b.date.localeCompare(a.date))
)
</script>

<style scoped>
.blog-list-page { min-height: 100vh; background: #f7f9fc; color: #1a1a1a; }
.blog-list-inner { max-width: 720px; margin: 0 auto; padding: 48px 24px; }
h1 { font-size: 32px; font-weight: 700; margin-bottom: 8px; }
.blog-subtitle { color: #666; margin-bottom: 32px; }
.post-list { display: flex; flex-direction: column; gap: 16px; }
.post-card {
  background: #fff; border: 1px solid #e5e5e5; border-radius: 10px;
  padding: 20px; text-decoration: none; display: block;
  transition: border-color 0.15s;
}
.post-card:hover { border-color: #7c3aed; }
.post-meta { display: flex; gap: 8px; margin-bottom: 8px; }
.post-date { font-size: 12px; color: #999; }
.post-category {
  font-size: 11px; color: #7c3aed;
  background: #7c3aed15; padding: 1px 6px; border-radius: 4px;
}
.post-card h2 { font-size: 16px; font-weight: 600; color: #1a1a1a; margin: 0 0 8px; }
.post-card p { font-size: 13px; color: #666; margin: 0 0 12px; line-height: 1.6; }
.post-read-more { font-size: 12px; color: #7c3aed; }
</style>
