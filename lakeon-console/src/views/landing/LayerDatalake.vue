<template>
  <svg viewBox="0 0 440 300" class="layer-svg" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="dl3-lake" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#6d28d9" />
        <stop offset="100%" stop-color="#4c1d95" />
      </linearGradient>
      <linearGradient id="dl3-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f5f7f6" />
        <stop offset="100%" stop-color="#dfe8e3" />
      </linearGradient>
      <linearGradient id="dl3-ray" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#b45309" />
        <stop offset="100%" stop-color="#92400e" />
      </linearGradient>
      <filter id="dl3-sh1"><feDropShadow dx="0" dy="2" stdDeviation="2" flood-color="rgba(109,40,217,0.10)" /></filter>
      <filter id="dl3-sh2"><feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="rgba(0,0,0,0.06)" /></filter>
    </defs>

    <ellipse cx="220" cy="150" rx="210" ry="140" fill="url(#dl3-bg)" opacity="0.25" />

    <!-- Source cards -->
    <rect x="20" y="15" width="110" height="38" rx="10" fill="#fff" stroke="#2a7a5f" stroke-width="1.2" filter="url(#dl3-sh2)" />
    <text x="75" y="38" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="10" font-weight="600" fill="#2a7a5f">{{ t('数据库', 'Database') }}</text>

    <rect x="165" y="15" width="110" height="38" rx="10" fill="#fff" stroke="#2a7a5f" stroke-width="1.2" filter="url(#dl3-sh2)" />
    <text x="220" y="38" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="10" font-weight="600" fill="#2a7a5f">{{ t('知识库', 'Knowledge') }}</text>

    <rect x="310" y="15" width="110" height="38" rx="10" fill="#fff" stroke="#b45309" stroke-width="1.2" filter="url(#dl3-sh2)" />
    <text x="365" y="38" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="10" font-weight="600" fill="#b45309">{{ t('记忆库', 'Memory') }}</text>

    <!-- Bi-directional arrows -->
    <line x1="75" y1="53" x2="75" y2="80" stroke="#6d28d9" stroke-width="1.2" />
    <polygon points="75,80 71,72 79,72" fill="#6d28d9" />
    <polygon points="75,53 71,61 79,61" fill="#6d28d9" />
    <line x1="220" y1="53" x2="220" y2="80" stroke="#6d28d9" stroke-width="1.2" />
    <polygon points="220,80 216,72 224,72" fill="#6d28d9" />
    <polygon points="220,53 216,61 224,61" fill="#6d28d9" />
    <line x1="365" y1="53" x2="365" y2="80" stroke="#6d28d9" stroke-width="1.2" />
    <polygon points="365,80 361,72 369,72" fill="#6d28d9" />
    <polygon points="365,53 361,61 369,61" fill="#6d28d9" />

    <text x="90" y="70" font-family="'Outfit', sans-serif" font-size="7" fill="#6d28d9" font-weight="600">{{ t('双向', 'Sync') }}</text>
    <text x="235" y="70" font-family="'Outfit', sans-serif" font-size="7" fill="#6d28d9" font-weight="600">{{ t('双向', 'Sync') }}</text>
    <text x="380" y="70" font-family="'Outfit', sans-serif" font-size="7" fill="#6d28d9" font-weight="600">{{ t('双向', 'Sync') }}</text>

    <!-- Data Lake main block -->
    <rect x="30" y="85" width="380" height="60" rx="12" fill="url(#dl3-lake)" filter="url(#dl3-sh1)" />
    <text x="220" y="112" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="18" font-weight="800" fill="#fff">{{ t('AI 多模态数据湖', 'AI Data Lake') }}</text>
    <text x="220" y="132" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="10" fill="rgba(255,255,255,0.75)">Parquet · Lance · {{ t('多模态存储', 'Multimodal') }}</text>

    <line x1="220" y1="145" x2="220" y2="170" stroke="#6d28d9" stroke-width="1.5" />

    <!-- Ray engine -->
    <rect x="80" y="170" width="280" height="44" rx="12" fill="url(#dl3-ray)" filter="url(#dl3-sh1)" />
    <text x="220" y="192" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="13" font-weight="700" fill="#fff">Ray / Python {{ t('作业引擎', 'Job Engine') }}</text>
    <text x="220" y="206" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('数据加工 · 分析 · 训练', 'Process · Analyze · Train') }}</text>

    <line x1="140" y1="214" x2="100" y2="240" stroke="#b45309" stroke-width="1" opacity="0.4" />
    <line x1="220" y1="214" x2="220" y2="240" stroke="#b45309" stroke-width="1" opacity="0.4" />
    <line x1="300" y1="214" x2="340" y2="240" stroke="#b45309" stroke-width="1" opacity="0.4" />

    <!-- Bottom feature cards -->
    <rect x="40" y="240" width="110" height="36" rx="8" fill="#fff" filter="url(#dl3-sh2)" />
    <text x="95" y="256" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" font-weight="600" fill="#6d28d9">{{ t('用户行为分析', 'User Analytics') }}</text>
    <text x="95" y="268" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('Agent 使用习惯', 'Agent Patterns') }}</text>

    <rect x="165" y="240" width="110" height="36" rx="8" fill="#fff" filter="url(#dl3-sh2)" />
    <text x="220" y="256" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" font-weight="600" fill="#6d28d9">{{ t('数据导出训练', 'Export Training') }}</text>
    <text x="220" y="268" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('记忆 → 微调数据', 'Memory → Finetune') }}</text>

    <rect x="290" y="240" width="110" height="36" rx="8" fill="#fff" filter="url(#dl3-sh2)" />
    <text x="345" y="256" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" font-weight="600" fill="#6d28d9">{{ t('数据治理', 'Governance') }}</text>
    <text x="345" y="268" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('清洗 · 归档', 'Clean · Archive') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
