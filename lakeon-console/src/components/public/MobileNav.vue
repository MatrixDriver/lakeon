<template>
  <div class="mobile-nav">
    <div class="mobile-nav-header">
      <button class="mobile-nav-close" @click="$emit('close')" aria-label="Close">✕</button>
    </div>
    <div class="mobile-nav-body">
      <router-link to="/product" class="mobile-nav-link" @click="$emit('close')">
        {{ t('产品', 'Products') }}
      </router-link>
      <router-link to="/integrations" class="mobile-nav-link" @click="$emit('close')">
        {{ t('集成', 'Integrations') }}
      </router-link>
      <router-link to="/blog" class="mobile-nav-link" @click="$emit('close')">
        {{ t('博客', 'Blog') }}
      </router-link>
      <router-link to="/docs" class="mobile-nav-link" @click="$emit('close')">
        {{ t('文档', 'Docs') }}
      </router-link>
      <router-link to="/login" class="mobile-nav-cta" @click="$emit('close')">
        {{ t('登录', 'Sign In') }}
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
  padding: 16px 20px;
  border-bottom: 1px solid #e5e5e5;
}
.mobile-nav-close {
  background: none;
  border: none;
  color: #666;
  font-size: 18px;
  cursor: pointer;
}
.mobile-nav-body {
  display: flex;
  flex-direction: column;
  padding: 16px;
  gap: 4px;
}
.mobile-nav-link {
  font-size: 15px;
  color: #333;
  padding: 12px 16px;
  border-radius: 8px;
  text-decoration: none;
}
.mobile-nav-link:hover {
  background: #f5f5f5;
  color: #1a1a1a;
}
.mobile-nav-cta {
  margin-top: 12px;
  background: #1a1a1a;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  padding: 12px 16px;
  border-radius: 8px;
  text-decoration: none;
  text-align: center;
}
</style>
