<template>
  <svg viewBox="0 0 440 300" class="layer-svg" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="dl-lake" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#18181b" />
        <stop offset="100%" stop-color="#27272a" />
      </linearGradient>
      <linearGradient id="dl-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f4f4f5" />
        <stop offset="100%" stop-color="#e4e4e7" />
      </linearGradient>
      <linearGradient id="dl-ray" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#92400e" />
        <stop offset="100%" stop-color="#78350f" />
      </linearGradient>
      <filter id="dl-sh1"><feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="rgba(0,0,0,0.10)" /></filter>
      <filter id="dl-sh2"><feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="rgba(0,0,0,0.06)" /></filter>
    </defs>

    <ellipse cx="220" cy="150" rx="210" ry="140" fill="url(#dl-bg)" opacity="0.3" />

    <rect x="20" y="15" width="110" height="38" rx="10" fill="#fff" stroke="#d4d4d8" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="75" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#18181b" font-family="'Plus Jakarta Sans', sans-serif">{{ t('数据库', 'Database') }}</text>

    <rect x="165" y="15" width="110" height="38" rx="10" fill="#fff" stroke="#d4d4d8" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="220" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#18181b" font-family="'Plus Jakarta Sans', sans-serif">{{ t('知识库', 'Knowledge') }}</text>

    <rect x="310" y="15" width="110" height="38" rx="10" fill="#fff" stroke="#d4d4d8" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="365" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#18181b" font-family="'Plus Jakarta Sans', sans-serif">{{ t('记忆库', 'Memory') }}</text>

    <line x1="75" y1="53" x2="75" y2="80" stroke="#71717a" stroke-width="1.5" />
    <polygon points="75,80 71,72 79,72" fill="#71717a" />
    <polygon points="75,53 71,61 79,61" fill="#71717a" />
    <line x1="220" y1="53" x2="220" y2="80" stroke="#71717a" stroke-width="1.5" />
    <polygon points="220,80 216,72 224,72" fill="#71717a" />
    <polygon points="220,53 216,61 224,61" fill="#71717a" />
    <line x1="365" y1="53" x2="365" y2="80" stroke="#71717a" stroke-width="1.5" />
    <polygon points="365,80 361,72 369,72" fill="#71717a" />
    <polygon points="365,53 361,61 369,61" fill="#71717a" />

    <text x="90" y="70" font-size="7" fill="#71717a" font-weight="600" font-family="'Plus Jakarta Sans', sans-serif">{{ t('双向', 'Sync') }}</text>
    <text x="235" y="70" font-size="7" fill="#71717a" font-weight="600" font-family="'Plus Jakarta Sans', sans-serif">{{ t('双向', 'Sync') }}</text>
    <text x="380" y="70" font-size="7" fill="#71717a" font-weight="600" font-family="'Plus Jakarta Sans', sans-serif">{{ t('双向', 'Sync') }}</text>

    <rect x="30" y="85" width="380" height="60" rx="18" fill="url(#dl-lake)" filter="url(#dl-sh1)" />
    <text x="220" y="112" text-anchor="middle" font-size="18" font-weight="800" fill="#fff" font-family="'Plus Jakarta Sans', sans-serif">{{ t('AI 多模态数据湖', 'AI Data Lake') }}</text>
    <text x="220" y="132" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.7)" font-family="'Plus Jakarta Sans', sans-serif">Parquet · Lance · {{ t('多模态存储', 'Multimodal') }}</text>

    <line x1="220" y1="145" x2="220" y2="170" stroke="#71717a" stroke-width="1.5" />

    <rect x="80" y="170" width="280" height="44" rx="12" fill="url(#dl-ray)" filter="url(#dl-sh1)" />
    <text x="220" y="192" text-anchor="middle" font-size="13" font-weight="700" fill="#fff" font-family="'Plus Jakarta Sans', sans-serif">Ray / Python {{ t('作业引擎', 'Job Engine') }}</text>
    <text x="220" y="206" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.75)" font-family="'Plus Jakarta Sans', sans-serif">{{ t('数据加工 · 分析 · 训练', 'Process · Analyze · Train') }}</text>

    <line x1="140" y1="214" x2="100" y2="240" stroke="#92400e" stroke-width="1" opacity="0.4" />
    <line x1="220" y1="214" x2="220" y2="240" stroke="#92400e" stroke-width="1" opacity="0.4" />
    <line x1="300" y1="214" x2="340" y2="240" stroke="#92400e" stroke-width="1" opacity="0.4" />

    <rect x="40" y="240" width="110" height="36" rx="8" fill="#fff" stroke="#e4e4e7" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="95" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#18181b" font-family="'Plus Jakarta Sans', sans-serif">{{ t('用户行为分析', 'User Analytics') }}</text>
    <text x="95" y="268" text-anchor="middle" font-size="7" fill="#a1a1aa" font-family="'Plus Jakarta Sans', sans-serif">{{ t('Agent 使用习惯', 'Agent Patterns') }}</text>

    <rect x="165" y="240" width="110" height="36" rx="8" fill="#fff" stroke="#e4e4e7" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="220" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#18181b" font-family="'Plus Jakarta Sans', sans-serif">{{ t('数据导出训练', 'Export Training') }}</text>
    <text x="220" y="268" text-anchor="middle" font-size="7" fill="#a1a1aa" font-family="'Plus Jakarta Sans', sans-serif">{{ t('记忆 → 微调数据', 'Memory → Finetune') }}</text>

    <rect x="290" y="240" width="110" height="36" rx="8" fill="#fff" stroke="#e4e4e7" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="345" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#18181b" font-family="'Plus Jakarta Sans', sans-serif">{{ t('数据治理', 'Governance') }}</text>
    <text x="345" y="268" text-anchor="middle" font-size="7" fill="#a1a1aa" font-family="'Plus Jakarta Sans', sans-serif">{{ t('清洗 · 归档', 'Clean · Archive') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
