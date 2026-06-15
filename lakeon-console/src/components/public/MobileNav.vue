<template>
  <div class="mobile-nav">
    <div class="mobile-nav-header">
      <button class="mobile-nav-close" @click="$emit('close')" aria-label="Close">✕</button>
    </div>
    <div class="mobile-nav-body">
      <router-link to="/product" class="mobile-nav-link" @click="$emit('close')">
        {{ t('方案', 'Solution') }}
      </router-link>
      <router-link to="/docs" class="mobile-nav-link" @click="$emit('close')">
        {{ t('文档', 'Docs') }}
      </router-link>
      <router-link to="/login" class="mobile-nav-link" @click="$emit('close')">
        {{ t('登录', 'Sign in') }}
      </router-link>
      <router-link to="/login?register=1" class="mobile-nav-cta" @click="$emit('close')">
        {{ t('开始使用', 'Get started') }}
      </router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

defineEmits<{ close: [] }>()
</script>

<style scoped>
.mobile-nav {
  position: fixed;
  inset: 0;
  background: #fff;
  z-index: 200;
  display: flex;
  flex-direction: column;
}
.mobile-nav-header {
  display: flex;
  justify-content: flex-end;
  padding: var(--space-lg) var(--space-xl);
  border-bottom: 1px solid var(--c-border-light);
}
.mobile-nav-close {
  background: none;
  border: none;
  color: var(--c-text-3);
  font-size: 20px;
  cursor: pointer;
}
.mobile-nav-body {
  display: flex;
  flex-direction: column;
  padding: var(--space-lg);
  gap: var(--space-xs);
}
.mobile-nav-link {
  font-family: var(--font-sans);
  font-size: 16px;
  color: var(--c-text);
  padding: var(--space-md) var(--space-lg);
  border-radius: 4px;
  text-decoration: none;
  transition: background 160ms ease-out;
}
.mobile-nav-link:hover {
  background: var(--c-hover);
}
.mobile-nav-cta {
  margin-top: var(--space-md);
  background: var(--c-accent);
  color: #fff;
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.02em;
  padding: var(--space-md) var(--space-lg);
  border-radius: 4px;
  text-decoration: none;
  text-align: center;
}
</style>
