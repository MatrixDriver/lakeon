<template>
  <svg viewBox="0 0 440 300" class="layer-svg">
    <defs>
      <linearGradient id="gl3-lake" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#7b1fa2" />
        <stop offset="100%" stop-color="#4a148c" />
      </linearGradient>
      <linearGradient id="gl3-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f3e5f5" />
        <stop offset="100%" stop-color="#e1bee7" />
      </linearGradient>
      <linearGradient id="gl3-ray" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#ff6f00" />
        <stop offset="100%" stop-color="#e65100" />
      </linearGradient>
      <filter id="gd1"><feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(123,31,162,0.15)" /></filter>
      <filter id="gd2"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" /></filter>
    </defs>

    <ellipse cx="220" cy="150" rx="210" ry="140" fill="url(#gl3-bg)" opacity="0.3" />

    <rect x="20" y="15" width="110" height="38" rx="10" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" filter="url(#gd2)" />
    <text x="75" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="var(--pub-primary, #0073e6)">🐘 {{ t('数据库', 'Database') }}</text>

    <rect x="165" y="15" width="110" height="38" rx="10" fill="var(--pub-surface, #fff)" stroke="#2e7d32" stroke-width="1.5" filter="url(#gd2)" />
    <text x="220" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#2e7d32">📚 {{ t('知识库', 'Knowledge') }}</text>

    <rect x="310" y="15" width="110" height="38" rx="10" fill="var(--pub-surface, #fff)" stroke="#e65100" stroke-width="1.5" filter="url(#gd2)" />
    <text x="365" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#e65100">🧠 {{ t('记忆库', 'Memory') }}</text>

    <line x1="75" y1="53" x2="75" y2="80" stroke="#7b1fa2" stroke-width="1.5" />
    <polygon points="75,80 71,72 79,72" fill="#7b1fa2" />
    <polygon points="75,53 71,61 79,61" fill="#7b1fa2" />
    <line x1="220" y1="53" x2="220" y2="80" stroke="#7b1fa2" stroke-width="1.5" />
    <polygon points="220,80 216,72 224,72" fill="#7b1fa2" />
    <polygon points="220,53 216,61 224,61" fill="#7b1fa2" />
    <line x1="365" y1="53" x2="365" y2="80" stroke="#7b1fa2" stroke-width="1.5" />
    <polygon points="365,80 361,72 369,72" fill="#7b1fa2" />
    <polygon points="365,53 361,61 369,61" fill="#7b1fa2" />

    <text x="90" y="70" font-size="7" fill="#7b1fa2" font-weight="600">{{ t('双向', 'Sync') }}</text>
    <text x="235" y="70" font-size="7" fill="#7b1fa2" font-weight="600">{{ t('双向', 'Sync') }}</text>
    <text x="380" y="70" font-size="7" fill="#7b1fa2" font-weight="600">{{ t('双向', 'Sync') }}</text>

    <rect x="30" y="85" width="380" height="60" rx="18" fill="url(#gl3-lake)" filter="url(#gd1)" />
    <text x="220" y="112" text-anchor="middle" font-size="18" font-weight="800" fill="#fff">🌊 {{ t('AI 多模态数据湖', 'AI Data Lake') }}</text>
    <text x="220" y="132" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.8)">Parquet · Lance · {{ t('多模态存储', 'Multimodal') }}</text>

    <line x1="220" y1="145" x2="220" y2="170" stroke="#7b1fa2" stroke-width="2" />

    <rect x="80" y="170" width="280" height="44" rx="12" fill="url(#gl3-ray)" filter="url(#gd1)" />
    <text x="220" y="192" text-anchor="middle" font-size="13" font-weight="700" fill="#fff">⚙️ Ray / Python {{ t('作业引擎', 'Job Engine') }}</text>
    <text x="220" y="206" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.85)">{{ t('数据加工 · 分析 · 训练', 'Process · Analyze · Train') }}</text>

    <line x1="140" y1="214" x2="100" y2="240" stroke="#e65100" stroke-width="1.2" opacity="0.5" />
    <line x1="220" y1="214" x2="220" y2="240" stroke="#e65100" stroke-width="1.2" opacity="0.5" />
    <line x1="300" y1="214" x2="340" y2="240" stroke="#e65100" stroke-width="1.2" opacity="0.5" />

    <rect x="40" y="240" width="110" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="95" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">📊 {{ t('用户行为分析', 'User Analytics') }}</text>
    <text x="95" y="268" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('Agent 使用习惯', 'Agent Patterns') }}</text>

    <rect x="165" y="240" width="110" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="220" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">🔄 {{ t('数据导出训练', 'Export Training') }}</text>
    <text x="220" y="268" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('记忆 → 微调数据', 'Memory → Finetune') }}</text>

    <rect x="290" y="240" width="110" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="345" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">🧹 {{ t('数据治理', 'Governance') }}</text>
    <text x="345" y="268" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('清洗 · 归档', 'Clean · Archive') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
