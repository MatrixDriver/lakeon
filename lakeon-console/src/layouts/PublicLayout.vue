<template>
  <div class="public-layout">
    <nav class="pub-nav">
      <div class="pub-nav-inner">
        <!-- Brand -->
        <router-link to="/" class="pub-brand">
          <span class="pub-brand-name">DBay</span>
          <span class="pub-brand-tag">{{ t('数据港湾', 'Data Harbor') }}</span>
        </router-link>

        <!-- Desktop nav -->
        <div class="pub-nav-links">
          <router-link to="/product" class="pub-nav-link">{{ t('方案', 'Solution') }}</router-link>
          <router-link to="/docs" class="pub-nav-link">{{ t('文档', 'Docs') }}</router-link>
        </div>

        <!-- Right side -->
        <div class="pub-nav-right">
          <button class="lang-btn" @click="toggleLocale">{{ locale === 'zh' ? 'EN' : '中' }}</button>
          <router-link to="/login" class="btn-signin">{{ t('登录', 'Sign in') }}</router-link>
          <router-link to="/login?register=1" class="btn-start">{{ t('开始使用', 'Get started') }}</router-link>
          <!-- Mobile hamburger -->
          <button class="hamburger" @click="mobileOpen = !mobileOpen" aria-label="Menu">
            <span></span><span></span><span></span>
          </button>
        </div>
      </div>

      <!-- Mobile overlay menu -->
      <MobileNav v-if="mobileOpen" @close="mobileOpen = false" />
    </nav>

    <router-view />

    <div class="pub-signature">
      <div class="pub-signature-inner">
        <span class="pub-signature-mark" aria-hidden="true"></span>
        <span class="pub-signature-refrain">{{ t('数据底座，在你这边。', 'A data foundation on your side.') }}</span>
        <span class="pub-signature-sub">{{ t('你的数据库，你的文件，你的控制权。', 'Your database. Your files. Your control.') }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useLocale } from '../stores/locale'
import MobileNav from '../components/public/MobileNav.vue'

const { locale, setLocale, t } = useLocale()
const mobileOpen = ref(false)

function toggleLocale() {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}

</script>

<style scoped>
.pub-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: color-mix(in oklch, #fff 92%, var(--c-bg-alt));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--c-border-light);
}
.pub-nav-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 clamp(20px, 3vw, 40px);
  height: 64px;
  display: flex;
  align-items: center;
  gap: clamp(16px, 3vw, 32px);
}
.pub-brand {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  text-decoration: none;
  white-space: nowrap;
}
.pub-brand-name {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 22px;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  line-height: 1;
}
.pub-brand-tag {
  font-family: var(--font-sans);
  font-weight: 500;
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--c-accent-text);
}
.pub-nav-links {
  display: flex;
  align-items: center;
  gap: var(--space-xl);
  flex: 1;
  margin-left: var(--space-xl);
}
.pub-nav-link {
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-2);
  padding: 6px 0;
  border-radius: 0;
  text-decoration: none;
  transition: color 160ms ease-out;
  position: relative;
}
.pub-nav-link:hover {
  color: var(--c-primary);
}
.pub-nav-link.router-link-active {
  color: var(--c-primary);
}
.pub-nav-link.router-link-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -2px;
  height: 2px;
  background: var(--c-accent);
  border-radius: 1px;
}
.pub-nav-right {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-left: auto;
}
.lang-btn {
  background: none;
  border: none;
  color: var(--c-text-3);
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.04em;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: color 160ms ease-out;
}
.lang-btn:hover {
  color: var(--c-accent-text);
}
.btn-signin {
  background: transparent;
  color: var(--c-text-2);
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  padding: 8px 0;
  text-decoration: none;
  transition: color 160ms ease-out;
}
.btn-signin:hover {
  color: var(--c-primary);
}
.btn-start {
  background: var(--c-accent);
  color: #fff !important;
  padding: 9px 20px;
  border-radius: 4px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.02em;
  text-decoration: none;
  transition: background 160ms ease-out;
}
.btn-start:hover {
  background: var(--c-accent-hover);
}
.btn-start:focus-visible {
  outline: 2px solid var(--c-accent);
  outline-offset: 2px;
}
.hamburger {
  display: none;
  flex-direction: column;
  gap: 4px;
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
}
.hamburger span {
  display: block;
  width: 20px;
  height: 2px;
  background: var(--c-text-2);
  border-radius: 1px;
}
@media (max-width: 768px) {
  .pub-nav-links { display: none; }
  .hamburger { display: flex; }
  .pub-brand-tag { display: none; }
}

.pub-signature {
  background: #fff;
  border-top: 1px solid var(--c-border-light);
  padding: clamp(48px, 6vw, 80px) clamp(20px, 3vw, 40px);
}
.pub-signature-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: clamp(16px, 2.5vw, 28px);
  flex-wrap: wrap;
}
.pub-signature-mark {
  display: inline-block;
  width: clamp(40px, 5vw, 64px);
  height: 1px;
  background: var(--c-accent);
}
.pub-signature-refrain {
  font-family: var(--font-display);
  font-style: italic;
  font-weight: 400;
  font-size: clamp(20px, 2vw, 28px);
  letter-spacing: 0.01em;
  color: var(--c-accent-text);
}
.pub-signature-sub {
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--c-text-3);
  letter-spacing: 0.02em;
}
@media (max-width: 600px) {
  .pub-signature-sub { flex-basis: 100%; }
}
</style>
