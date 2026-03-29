<template>
  <svg viewBox="0 0 440 280" class="layer-svg">
    <defs>
      <linearGradient id="gl2-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary-light, #e3f2fd)" />
        <stop offset="100%" stop-color="#90caf9" />
      </linearGradient>
      <linearGradient id="gl2-mem" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#ff9800" />
        <stop offset="100%" stop-color="#e65100" />
      </linearGradient>
      <linearGradient id="gl2-know" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#4caf50" />
        <stop offset="100%" stop-color="#2e7d32" />
      </linearGradient>
      <linearGradient id="gl2-base" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary, #0073e6)" />
        <stop offset="100%" stop-color="var(--pub-primary-dark, #005bb5)" />
      </linearGradient>
      <filter id="gs1"><feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(0,115,230,0.15)" /></filter>
      <filter id="gs2"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" /></filter>
    </defs>

    <ellipse cx="220" cy="140" rx="200" ry="120" fill="url(#gl2-bg)" opacity="0.25" />

    <rect x="145" y="10" width="150" height="42" rx="12" fill="var(--pub-surface, #fff)" stroke="#1565c0" stroke-width="1.5" filter="url(#gs2)" />
    <text x="220" y="36" text-anchor="middle" font-size="13" font-weight="700" fill="#1565c0">AI Agent</text>

    <line x1="185" y1="52" x2="140" y2="95" stroke="#ff9800" stroke-width="2" opacity="0.6" />
    <line x1="255" y1="52" x2="300" y2="95" stroke="#4caf50" stroke-width="2" opacity="0.6" />

    <rect x="170" y="62" width="100" height="22" rx="11" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" filter="url(#gs2)" />
    <text x="220" y="77" text-anchor="middle" font-size="9" font-weight="700" fill="var(--pub-primary, #0073e6)">MCP / Skill</text>

    <rect x="50" y="95" width="170" height="50" rx="14" fill="url(#gl2-mem)" filter="url(#gs1)" />
    <text x="135" y="118" text-anchor="middle" font-size="13" font-weight="700" fill="#fff">{{ t('记忆服务', 'Memory') }}</text>
    <text x="135" y="134" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.85)">{{ t('自动提取 · 状态持久化', 'Extract · Persist') }}</text>

    <rect x="240" y="95" width="170" height="50" rx="14" fill="url(#gl2-know)" filter="url(#gs1)" />
    <text x="325" y="118" text-anchor="middle" font-size="13" font-weight="700" fill="#fff">{{ t('知识服务', 'Knowledge') }}</text>
    <text x="325" y="134" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.85)">{{ t('文档向量化 · RAG', 'Vectorize · RAG') }}</text>

    <line x1="135" y1="145" x2="220" y2="180" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />
    <line x1="325" y1="145" x2="220" y2="180" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />

    <rect x="100" y="180" width="240" height="48" rx="14" fill="url(#gl2-base)" filter="url(#gs1)" />
    <text x="220" y="203" text-anchor="middle" font-size="14" font-weight="800" fill="#fff">Lakebase</text>
    <text x="220" y="219" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('统一存储引擎', 'Unified Storage') }}</text>

    <rect x="130" y="245" width="180" height="26" rx="13" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1" filter="url(#gs2)" />
    <text x="220" y="262" text-anchor="middle" font-size="10" font-weight="600" fill="var(--pub-primary, #0073e6)">{{ t('多库合一 · 减少依赖', 'All-in-one · Less Deps') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
