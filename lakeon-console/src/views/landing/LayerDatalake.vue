<template>
  <svg viewBox="0 0 440 260" class="layer-svg">
    <defs>
      <linearGradient id="dl-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f3e5f5" />
        <stop offset="100%" stop-color="#e1bee7" />
      </linearGradient>
      <linearGradient id="dl-mem" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#ff9800" />
        <stop offset="100%" stop-color="#e65100" />
      </linearGradient>
      <linearGradient id="dl-know" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#4caf50" />
        <stop offset="100%" stop-color="#2e7d32" />
      </linearGradient>
      <linearGradient id="dl-state" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#5c6bc0" />
        <stop offset="100%" stop-color="#283593" />
      </linearGradient>
      <linearGradient id="dl-base" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary, #0073e6)" />
        <stop offset="100%" stop-color="var(--pub-primary-dark, #005bb5)" />
      </linearGradient>
      <linearGradient id="dl-lake" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#7b1fa2" />
        <stop offset="100%" stop-color="#4a148c" />
      </linearGradient>
      <filter id="dl-sh1"><feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(123,31,162,0.15)" /></filter>
      <filter id="dl-sh2"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" /></filter>
    </defs>

    <ellipse cx="220" cy="130" rx="215" ry="120" fill="url(#dl-bg)" opacity="0.2" />

    <!-- Left triangle: State → Knowledge + Memory -->

    <!-- Apex: State -->
    <rect x="55" y="8" width="82" height="36" rx="10" fill="url(#dl-state)" filter="url(#dl-sh1)" />
    <text x="96" y="25" text-anchor="middle" font-size="10" font-weight="700" fill="#fff">{{ t('状态库', 'State') }}</text>
    <text x="96" y="38" text-anchor="middle" font-size="8" fill="rgba(255,255,255,0.85)">{{ t('会话 · 上下文', 'Session') }}</text>

    <!-- Bottom-left: Knowledge -->
    <rect x="8" y="55" width="82" height="36" rx="10" fill="url(#dl-know)" filter="url(#dl-sh1)" />
    <text x="49" y="72" text-anchor="middle" font-size="10" font-weight="700" fill="#fff">{{ t('知识库', 'Knowledge') }}</text>
    <text x="49" y="85" text-anchor="middle" font-size="8" fill="rgba(255,255,255,0.85)">{{ t('文档向量化', 'Vectorize') }}</text>

    <!-- Bottom-right: Memory -->
    <rect x="100" y="55" width="82" height="36" rx="10" fill="url(#dl-mem)" filter="url(#dl-sh1)" />
    <text x="141" y="72" text-anchor="middle" font-size="10" font-weight="700" fill="#fff">{{ t('记忆库', 'Memory') }}</text>
    <text x="141" y="85" text-anchor="middle" font-size="8" fill="rgba(255,255,255,0.85)">{{ t('持久化 · 反思', 'Persist') }}</text>

    <!-- Arrows: services → Lakebase -->
    <line x1="96" y1="44" x2="100" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="1.2" opacity="0.4" />
    <line x1="49" y1="91" x2="80" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="1.2" opacity="0.4" />
    <line x1="141" y1="91" x2="115" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="1.2" opacity="0.4" />

    <!-- Lakebase -->
    <rect x="15" y="110" width="195" height="52" rx="14" fill="url(#dl-base)" filter="url(#dl-sh1)" />
    <text x="112" y="135" text-anchor="middle" font-size="15" font-weight="800" fill="#fff">Lakebase</text>
    <text x="112" y="152" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.8)">{{ t('统一存储 · 多分支', 'Unified · Branching') }}</text>

    <!-- Right triangle: Multimodal → Analytics + Strategy -->

    <!-- Apex: Multimodal -->
    <rect x="300" y="8" width="82" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#dl-sh2)" />
    <text x="341" y="24" text-anchor="middle" font-size="9" font-weight="600" fill="#7b1fa2">{{ t('知识加工', 'Process') }}</text>
    <text x="341" y="36" text-anchor="middle" font-size="8" fill="var(--pub-text-3, #888)">{{ t('图文 · 音视频', 'Multimodal') }}</text>

    <!-- Bottom-left: Analytics -->
    <rect x="255" y="55" width="82" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#dl-sh2)" />
    <text x="296" y="71" text-anchor="middle" font-size="9" font-weight="600" fill="#7b1fa2">{{ t('行为分析', 'Analytics') }}</text>
    <text x="296" y="83" text-anchor="middle" font-size="8" fill="var(--pub-text-3, #888)">{{ t('Agent 习惯', 'Patterns') }}</text>

    <!-- Bottom-right: Strategy -->
    <rect x="347" y="55" width="82" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#dl-sh2)" />
    <text x="388" y="71" text-anchor="middle" font-size="9" font-weight="600" fill="#7b1fa2">{{ t('策略评估', 'Strategy') }}</text>
    <text x="388" y="83" text-anchor="middle" font-size="8" fill="var(--pub-text-3, #888)">{{ t('反思 · 优化', 'Reflect') }}</text>

    <!-- Arrows: capabilities → DataLake -->
    <line x1="341" y1="44" x2="335" y2="110" stroke="#7b1fa2" stroke-width="1.2" opacity="0.4" />
    <line x1="296" y1="91" x2="315" y2="110" stroke="#7b1fa2" stroke-width="1.2" opacity="0.4" />
    <line x1="388" y1="91" x2="350" y2="110" stroke="#7b1fa2" stroke-width="1.2" opacity="0.4" />

    <!-- AI DataLake -->
    <rect x="230" y="110" width="195" height="52" rx="14" fill="url(#dl-lake)" filter="url(#dl-sh1)" />
    <text x="327" y="135" text-anchor="middle" font-size="15" font-weight="800" fill="#fff">AI DataLake</text>
    <text x="327" y="152" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.8)">Parquet · Lance · {{ t('多模态', 'Multimodal') }}</text>

    <!-- Lakebase → DataLake arrow -->
    <line x1="210" y1="136" x2="230" y2="136" stroke="#7b1fa2" stroke-width="3" />
    <polygon points="231,136 223,131 223,141" fill="#7b1fa2" />

    <!-- Feedback: DataLake → Memory -->
    <path d="M 230,120 C 210,100 190,95 182,82" fill="none" stroke="#e65100" stroke-width="1.5" stroke-dasharray="4,3" opacity="0.6" />
    <polygon points="182,82 178,90 186,90" fill="#e65100" opacity="0.6" />
    <text x="210" y="98" text-anchor="middle" font-size="8" fill="#e65100" font-weight="600">{{ t('更新记忆策略', 'Update Strategy') }}</text>

    <!-- Bottom: data flywheel -->
    <rect x="130" y="210" width="180" height="26" rx="13" fill="var(--pub-surface, #fff)" stroke="#7b1fa2" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="220" y="227" text-anchor="middle" font-size="11" font-weight="600" fill="#7b1fa2">{{ t('数据飞轮 · 持续进化', 'Data Flywheel · Evolve') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
