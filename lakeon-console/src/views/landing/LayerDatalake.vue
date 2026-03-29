<template>
  <svg viewBox="0 0 440 300" class="layer-svg" font-family="'DM Sans', sans-serif">
    <defs>
      <filter id="dl-sh1">
        <feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(30,45,61,0.10)" />
      </filter>
      <filter id="dl-sh2">
        <feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="rgba(30,45,61,0.06)" />
      </filter>
    </defs>

    <!-- background -->
    <rect x="10" y="8" width="420" height="284" rx="20" fill="#f5f6f8" opacity="0.5" />

    <!-- top row: 3 source stores -->
    <rect x="20" y="15" width="110" height="38" rx="8" fill="#ffffff" stroke="#4a8b8c" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="75" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#1e2d3d">{{ t('数据库', 'Database') }}</text>

    <rect x="165" y="15" width="110" height="38" rx="8" fill="#ffffff" stroke="#4a8b8c" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="220" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#1e2d3d">{{ t('知识库', 'Knowledge') }}</text>

    <rect x="310" y="15" width="110" height="38" rx="8" fill="#ffffff" stroke="#c67d3a" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="365" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#1e2d3d">{{ t('记忆库', 'Memory') }}</text>

    <!-- bidirectional arrows -->
    <line x1="75" y1="53" x2="75" y2="80" stroke="#7a5195" stroke-width="1.2" />
    <polygon points="75,80 71,73 79,73" fill="#7a5195" />
    <polygon points="75,53 71,60 79,60" fill="#7a5195" />
    <line x1="220" y1="53" x2="220" y2="80" stroke="#7a5195" stroke-width="1.2" />
    <polygon points="220,80 216,73 224,73" fill="#7a5195" />
    <polygon points="220,53 216,60 224,60" fill="#7a5195" />
    <line x1="365" y1="53" x2="365" y2="80" stroke="#7a5195" stroke-width="1.2" />
    <polygon points="365,80 361,73 369,73" fill="#7a5195" />
    <polygon points="365,53 361,60 369,60" fill="#7a5195" />

    <text x="90" y="70" font-size="7" fill="#7a5195" font-weight="600">{{ t('双向', 'Sync') }}</text>
    <text x="235" y="70" font-size="7" fill="#7a5195" font-weight="600">{{ t('双向', 'Sync') }}</text>
    <text x="380" y="70" font-size="7" fill="#7a5195" font-weight="600">{{ t('双向', 'Sync') }}</text>

    <!-- Data Lake main box -->
    <rect x="30" y="85" width="380" height="60" rx="14" fill="#7a5195" filter="url(#dl-sh1)" />
    <text x="220" y="112" text-anchor="middle" font-size="18" font-weight="800" fill="#ffffff">{{ t('AI 多模态数据湖', 'AI Data Lake') }}</text>
    <text x="220" y="132" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.75)">Parquet · Lance · {{ t('多模态存储', 'Multimodal') }}</text>

    <!-- connector -->
    <line x1="220" y1="145" x2="220" y2="170" stroke="#7a5195" stroke-width="1.5" />

    <!-- Ray / Python Job Engine -->
    <rect x="80" y="170" width="280" height="44" rx="10" fill="#c67d3a" filter="url(#dl-sh1)" />
    <text x="220" y="192" text-anchor="middle" font-size="13" font-weight="700" fill="#ffffff">Ray / Python {{ t('作业引擎', 'Job Engine') }}</text>
    <text x="220" y="206" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('数据加工 · 分析 · 训练', 'Process · Analyze · Train') }}</text>

    <!-- connector lines to outputs -->
    <line x1="140" y1="214" x2="100" y2="240" stroke="#c67d3a" stroke-width="1" opacity="0.4" />
    <line x1="220" y1="214" x2="220" y2="240" stroke="#c67d3a" stroke-width="1" opacity="0.4" />
    <line x1="300" y1="214" x2="340" y2="240" stroke="#c67d3a" stroke-width="1" opacity="0.4" />

    <!-- output cards -->
    <rect x="40" y="240" width="110" height="36" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="95" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7a5195">{{ t('用户行为分析', 'User Analytics') }}</text>
    <text x="95" y="268" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('Agent 使用习惯', 'Agent Patterns') }}</text>

    <rect x="165" y="240" width="110" height="36" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="220" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7a5195">{{ t('数据导出训练', 'Export Training') }}</text>
    <text x="220" y="268" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('记忆 → 微调数据', 'Memory to Finetune') }}</text>

    <rect x="290" y="240" width="110" height="36" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#dl-sh2)" />
    <text x="345" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7a5195">{{ t('数据治理', 'Governance') }}</text>
    <text x="345" y="268" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('清洗 · 归档', 'Clean · Archive') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
