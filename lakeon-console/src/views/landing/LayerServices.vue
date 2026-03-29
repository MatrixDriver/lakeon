<template>
  <svg viewBox="0 0 440 280" class="layer-svg" font-family="'DM Sans', sans-serif">
    <defs>
      <filter id="sv-sh1">
        <feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(30,45,61,0.10)" />
      </filter>
      <filter id="sv-sh2">
        <feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="rgba(30,45,61,0.06)" />
      </filter>
    </defs>

    <!-- background -->
    <rect x="30" y="4" width="380" height="270" rx="20" fill="#f5f6f8" opacity="0.5" />

    <!-- AI Agent -->
    <rect x="145" y="10" width="150" height="42" rx="8" fill="#ffffff" stroke="#c0c8d0" stroke-width="1" filter="url(#sv-sh2)" />
    <text x="220" y="36" text-anchor="middle" font-size="13" font-weight="700" fill="#1e2d3d">AI Agent</text>

    <!-- connector lines from agent -->
    <line x1="185" y1="52" x2="140" y2="95" stroke="#c67d3a" stroke-width="1.5" opacity="0.45" />
    <line x1="255" y1="52" x2="300" y2="95" stroke="#4a8b8c" stroke-width="1.5" opacity="0.45" />

    <!-- MCP / Skill badge -->
    <rect x="170" y="62" width="100" height="22" rx="11" fill="#ffffff" stroke="#4a8b8c" stroke-width="1.2" filter="url(#sv-sh2)" />
    <text x="220" y="77" text-anchor="middle" font-size="9" font-weight="700" fill="#4a8b8c">MCP / Skill</text>

    <!-- Memory Service -->
    <rect x="50" y="95" width="170" height="50" rx="10" fill="#c67d3a" filter="url(#sv-sh1)" />
    <text x="135" y="118" text-anchor="middle" font-size="13" font-weight="700" fill="#ffffff">{{ t('记忆服务', 'Memory Service') }}</text>
    <text x="135" y="134" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('自动提取 · 状态持久化', 'Extract · Persist') }}</text>

    <!-- Knowledge Service -->
    <rect x="240" y="95" width="170" height="50" rx="10" fill="#4a8b8c" filter="url(#sv-sh1)" />
    <text x="325" y="118" text-anchor="middle" font-size="13" font-weight="700" fill="#ffffff">{{ t('知识服务', 'Knowledge Service') }}</text>
    <text x="325" y="134" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('文档向量化 · RAG', 'Vectorize · RAG') }}</text>

    <!-- connector lines to Lakebase -->
    <line x1="135" y1="145" x2="220" y2="180" stroke="#1e2d3d" stroke-width="1.5" opacity="0.4" />
    <line x1="325" y1="145" x2="220" y2="180" stroke="#1e2d3d" stroke-width="1.5" opacity="0.4" />

    <!-- Lakebase -->
    <rect x="100" y="180" width="240" height="48" rx="12" fill="#1e2d3d" filter="url(#sv-sh1)" />
    <text x="220" y="203" text-anchor="middle" font-size="14" font-weight="800" fill="#ffffff">Lakebase</text>
    <text x="220" y="219" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.7)">{{ t('统一存储引擎', 'Unified Storage') }}</text>

    <!-- bottom badge -->
    <rect x="130" y="245" width="180" height="26" rx="13" fill="#ffffff" stroke="#c0c8d0" stroke-width="1" filter="url(#sv-sh2)" />
    <text x="220" y="262" text-anchor="middle" font-size="10" font-weight="600" fill="#1e2d3d">{{ t('多库合一 · 减少依赖', 'All-in-one · Less Deps') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
