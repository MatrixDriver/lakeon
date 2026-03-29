<template>
  <svg viewBox="0 0 440 280" class="layer-svg" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="ls3-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f5f7f6" />
        <stop offset="100%" stop-color="#dfe8e3" />
      </linearGradient>
      <linearGradient id="ls3-mem" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#b45309" />
        <stop offset="100%" stop-color="#92400e" />
      </linearGradient>
      <linearGradient id="ls3-know" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#2a7a5f" />
        <stop offset="100%" stop-color="#1a5c45" />
      </linearGradient>
      <linearGradient id="ls3-base" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#1a2e25" />
        <stop offset="100%" stop-color="#0f1d17" />
      </linearGradient>
      <filter id="ls3-sh1"><feDropShadow dx="0" dy="2" stdDeviation="2" flood-color="rgba(26,46,37,0.10)" /></filter>
      <filter id="ls3-sh2"><feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="rgba(0,0,0,0.06)" /></filter>
    </defs>

    <ellipse cx="220" cy="140" rx="200" ry="120" fill="url(#ls3-bg)" opacity="0.25" />

    <!-- Agent box -->
    <rect x="145" y="10" width="150" height="42" rx="12" fill="#fff" stroke="#1a2e25" stroke-width="1.2" filter="url(#ls3-sh2)" />
    <text x="220" y="36" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="13" font-weight="700" fill="#1a2e25">AI Agent</text>

    <line x1="185" y1="52" x2="140" y2="95" stroke="#b45309" stroke-width="1.5" opacity="0.45" />
    <line x1="255" y1="52" x2="300" y2="95" stroke="#2a7a5f" stroke-width="1.5" opacity="0.45" />

    <!-- Protocol pill -->
    <rect x="170" y="62" width="100" height="22" rx="11" fill="#fff" stroke="#2a7a5f" stroke-width="1.2" filter="url(#ls3-sh2)" />
    <text x="220" y="77" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="9" font-weight="700" fill="#2a7a5f">MCP / Skill</text>

    <!-- Memory service -->
    <rect x="50" y="95" width="170" height="50" rx="12" fill="url(#ls3-mem)" filter="url(#ls3-sh1)" />
    <text x="135" y="118" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="13" font-weight="700" fill="#fff">{{ t('记忆服务', 'Memory') }}</text>
    <text x="135" y="134" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('自动提取 · 状态持久化', 'Extract · Persist') }}</text>

    <!-- Knowledge service -->
    <rect x="240" y="95" width="170" height="50" rx="12" fill="url(#ls3-know)" filter="url(#ls3-sh1)" />
    <text x="325" y="118" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="13" font-weight="700" fill="#fff">{{ t('知识服务', 'Knowledge') }}</text>
    <text x="325" y="134" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('文档向量化 · RAG', 'Vectorize · RAG') }}</text>

    <line x1="135" y1="145" x2="220" y2="180" stroke="#1a2e25" stroke-width="1.5" opacity="0.35" />
    <line x1="325" y1="145" x2="220" y2="180" stroke="#1a2e25" stroke-width="1.5" opacity="0.35" />

    <!-- Lakebase -->
    <rect x="100" y="180" width="240" height="48" rx="12" fill="url(#ls3-base)" filter="url(#ls3-sh1)" />
    <text x="220" y="203" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="14" font-weight="800" fill="#fff">Lakebase</text>
    <text x="220" y="219" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="9" fill="rgba(255,255,255,0.75)">{{ t('统一存储引擎', 'Unified Storage') }}</text>

    <!-- Footer pill -->
    <rect x="130" y="245" width="180" height="26" rx="12" fill="#fff" stroke="#2a7a5f" stroke-width="1" filter="url(#ls3-sh2)" />
    <text x="220" y="262" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="10" font-weight="600" fill="#2a7a5f">{{ t('多库合一 · 减少依赖', 'All-in-one · Less Deps') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
