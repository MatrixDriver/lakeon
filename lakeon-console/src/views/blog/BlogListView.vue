<template>
  <main class="blog-list-page">
    <!-- Hero -->
    <section class="blog-hero">
      <div class="blog-hero-inner">
        <h1>Blog</h1>
        <p class="blog-hero-subtitle">{{ t('产品更新、技术解析、使用指南', 'Product updates, technical deep-dives, and guides') }}</p>
      </div>
    </section>

    <div class="blog-body">
      <!-- Featured article -->
      <router-link
        v-if="featured"
        :to="`/blog/${featured.slug}`"
        class="featured-card"
      >
        <span class="category-tag">{{ featured.category }}</span>
        <h2 class="featured-title">{{ locale === 'zh' ? featured.titleZh : featured.title }}</h2>
        <p class="featured-summary">{{ locale === 'zh' ? featured.summaryZh : featured.summary }}</p>
        <div class="featured-footer">
          <span class="post-date">{{ featured.date }}</span>
          <span class="read-link">{{ t('阅读', 'Read') }} →</span>
        </div>
      </router-link>

      <!-- Other articles grid -->
      <div v-if="others.length" class="post-grid">
        <router-link
          v-for="post in others"
          :key="post.slug"
          :to="`/blog/${post.slug}`"
          class="post-card"
        >
          <span class="category-tag">{{ post.category }}</span>
          <h3 class="post-title">{{ locale === 'zh' ? post.titleZh : post.title }}</h3>
          <p class="post-summary">{{ locale === 'zh' ? post.summaryZh : post.summary }}</p>
          <div class="post-footer">
            <span class="post-date">{{ post.date }}</span>
            <span class="read-link">{{ t('阅读', 'Read') }} →</span>
          </div>
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

const featured = computed(() => sortedPosts.value[0] ?? null)
const others = computed(() => sortedPosts.value.slice(1))
</script>

<style scoped>
.blog-list-page {
  min-height: 100vh;
  background: var(--pub-bg);
  color: var(--pub-text);
}

/* Hero */
.blog-hero {
  background: var(--pub-surface);
  border-bottom: 1px solid var(--pub-border);
  padding: 56px 24px 48px;
  text-align: center;
}
.blog-hero-inner {
  max-width: 720px;
  margin: 0 auto;
}
.blog-hero h1 {
  font-size: 32px;
  font-weight: 700;
  margin: 0 0 10px;
  color: var(--pub-text);
}
.blog-hero-subtitle {
  font-size: 15px;
  color: var(--pub-text-2);
  margin: 0;
}

/* Body container */
.blog-body {
  max-width: 960px;
  margin: 0 auto;
  padding: 40px 24px 80px;
}

/* Category tag */
.category-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 500;
  color: var(--pub-primary);
  background: var(--pub-primary-light, #ede9fe);
  border-radius: 4px;
  padding: 2px 8px;
  margin-bottom: 12px;
}

/* Featured card */
.featured-card {
  display: block;
  background: #fff;
  border: 1px solid var(--pub-border);
  border-radius: 12px;
  padding: 32px;
  text-decoration: none;
  margin-bottom: 32px;
  transition: box-shadow 0.2s;
}
.featured-card:hover {
  box-shadow: 0 4px 20px var(--pub-shadow, rgba(0,0,0,0.08));
}
.featured-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
  line-height: 1.3;
}
.featured-summary {
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 20px;
}
.featured-footer {
  display: flex;
  align-items: center;
  gap: 16px;
}

/* Grid */
.post-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

/* Post card */
.post-card {
  display: block;
  background: #fff;
  border: 1px solid var(--pub-border);
  border-radius: 12px;
  padding: 20px;
  text-decoration: none;
  transition: box-shadow 0.2s;
}
.post-card:hover {
  box-shadow: 0 6px 24px var(--pub-shadow, rgba(0,0,0,0.10));
}
.post-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 10px;
  line-height: 1.35;
}
.post-summary {
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 16px;
}
.post-footer {
  display: flex;
  align-items: center;
  gap: 16px;
}

/* Shared */
.post-date {
  font-size: 12px;
  color: var(--pub-text-3);
}
.read-link {
  font-size: 13px;
  color: var(--pub-primary);
  font-weight: 500;
}

@media (max-width: 640px) {
  .post-grid {
    grid-template-columns: 1fr;
  }
}
</style>
