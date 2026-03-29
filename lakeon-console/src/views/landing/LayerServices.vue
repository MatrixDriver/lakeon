<template>
  <svg viewBox="0 0 440 280" class="layer-svg" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="ls-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f4f4f5" />
        <stop offset="100%" stop-color="#e4e4e7" />
      </linearGradient>
      <linearGradient id="ls-mem" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#92400e" />
        <stop offset="100%" stop-color="#78350f" />
      </linearGradient>
      <linearGradient id="ls-know" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#3f3f46" />
        <stop offset="100%" stop-color="#27272a" />
      </linearGradient>
      <linearGradient id="ls-base" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#18181b" />
        <stop offset="100%" stop-color="#09090b" />
      </linearGradient>
      <filter id="ls-sh1"><feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="rgba(0,0,0,0.10)" /></filter>
      <filter id="ls-sh2"><feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="rgba(0,0,0,0.06)" /></filter>
    </defs>

    <ellipse cx="220" cy="140" rx="200" ry="120" fill="url(#ls-bg)" opacity="0.25" />

    <rect x="145" y="10" width="150" height="42" rx="12" fill="#fff" stroke="#d4d4d8" stroke-width="1" filter="url(#ls-sh2)" />
    <text x="220" y="36" text-anchor="middle" font-size="13" font-weight="700" fill="#18181b" font-family="'Plus Jakarta Sans', sans-serif">AI Agent</text>

    <line x1="185" y1="52" x2="140" y2="95" stroke="#92400e" stroke-width="1.5" opacity="0.5" />
    <line x1="255" y1="52" x2="300" y2="95" stroke="#71717a" stroke-width="1.5" opacity="0.5" />

    <rect x="170" y="62" width="100" height="22" rx="11" fill="#fff" stroke="#92400e" stroke-width="1.5" filter="url(#ls-sh2)" />
    <text x="220" y="77" text-anchor="middle" font-size="9" font-weight="700" fill="#92400e" font-family="'Plus Jakarta Sans', sans-serif">MCP / Skill</text>

    <rect x="50" y="95" width="170" height="50" rx="14" fill="url(#ls-mem)" filter="url(#ls-sh1)" />
    <text x="135" y="118" text-anchor="middle" font-size="13" font-weight="700" fill="#fff" font-family="'Plus Jakarta Sans', sans-serif">{{ t('记忆服务', 'Memory') }}</text>
    <text x="135" y="134" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.75)" font-family="'Plus Jakarta Sans', sans-serif">{{ t('自动提取 · 状态持久化', 'Extract · Persist') }}</text>

    <rect x="240" y="95" width="170" height="50" rx="14" fill="url(#ls-know)" filter="url(#ls-sh1)" />
    <text x="325" y="118" text-anchor="middle" font-size="13" font-weight="700" fill="#fff" font-family="'Plus Jakarta Sans', sans-serif">{{ t('知识服务', 'Knowledge') }}</text>
    <text x="325" y="134" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.75)" font-family="'Plus Jakarta Sans', sans-serif">{{ t('文档向量化 · RAG', 'Vectorize · RAG') }}</text>

    <line x1="135" y1="145" x2="220" y2="180" stroke="#a1a1aa" stroke-width="1.5" opacity="0.5" />
    <line x1="325" y1="145" x2="220" y2="180" stroke="#a1a1aa" stroke-width="1.5" opacity="0.5" />

    <rect x="100" y="180" width="240" height="48" rx="14" fill="url(#ls-base)" filter="url(#ls-sh1)" />
    <text x="220" y="203" text-anchor="middle" font-size="14" font-weight="800" fill="#fff" font-family="'Plus Jakarta Sans', sans-serif">Lakebase</text>
    <text x="220" y="219" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.7)" font-family="'Plus Jakarta Sans', sans-serif">{{ t('统一存储引擎', 'Unified Storage') }}</text>

    <rect x="130" y="245" width="180" height="26" rx="13" fill="#fff" stroke="#d4d4d8" stroke-width="1" filter="url(#ls-sh2)" />
    <text x="220" y="262" text-anchor="middle" font-size="10" font-weight="600" fill="#71717a" font-family="'Plus Jakarta Sans', sans-serif">{{ t('多库合一 · 减少依赖', 'All-in-one · Less Deps') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
