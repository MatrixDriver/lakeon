<template>
  <svg viewBox="0 0 440 300" class="layer-svg" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="lb3-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f5f7f6" />
        <stop offset="100%" stop-color="#dfe8e3" />
      </linearGradient>
      <linearGradient id="lb3-main" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#2a7a5f" />
        <stop offset="100%" stop-color="#1a2e25" />
      </linearGradient>
      <linearGradient id="lb3-card" x1="0%" y1="0%" x2="0%" y2="100%">
        <stop offset="0%" stop-color="#f5f7f6" />
        <stop offset="100%" stop-color="#eaf0ec" />
      </linearGradient>
      <filter id="lb3-sh1">
        <feDropShadow dx="0" dy="2" stdDeviation="2" flood-color="rgba(26,46,37,0.08)" />
      </filter>
      <filter id="lb3-sh2">
        <feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="rgba(0,0,0,0.06)" />
      </filter>
    </defs>

    <ellipse cx="220" cy="150" rx="200" ry="130" fill="url(#lb3-bg)" opacity="0.35" />

    <!-- Top connectors -->
    <rect x="40" y="20" width="100" height="40" rx="10" fill="url(#lb3-card)" filter="url(#lb3-sh2)" />
    <text x="90" y="44" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="11" font-weight="600" fill="#2a7a5f">mem0</text>
    <rect x="170" y="20" width="100" height="40" rx="10" fill="url(#lb3-card)" filter="url(#lb3-sh2)" />
    <text x="220" y="44" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="11" font-weight="600" fill="#2a7a5f">Hindsight</text>
    <rect x="300" y="20" width="100" height="40" rx="10" fill="url(#lb3-card)" filter="url(#lb3-sh2)" />
    <text x="350" y="44" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="11" font-weight="600" fill="#2a7a5f">Your Agent</text>

    <line x1="90" y1="60" x2="220" y2="110" stroke="#2a7a5f" stroke-width="1.5" opacity="0.35" />
    <line x1="220" y1="60" x2="220" y2="110" stroke="#2a7a5f" stroke-width="1.5" opacity="0.35" />
    <line x1="350" y1="60" x2="220" y2="110" stroke="#2a7a5f" stroke-width="1.5" opacity="0.35" />

    <rect x="180" y="78" width="80" height="22" rx="11" fill="#fff" stroke="#2a7a5f" stroke-width="1.2" filter="url(#lb3-sh2)" />
    <text x="220" y="93" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="9" font-weight="700" fill="#2a7a5f">PG {{ t('协议', 'Protocol') }}</text>

    <!-- Main block -->
    <rect x="80" y="110" width="280" height="60" rx="12" fill="url(#lb3-main)" filter="url(#lb3-sh1)" />
    <text x="220" y="136" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="18" font-weight="800" fill="#fff">Lakebase</text>
    <text x="220" y="156" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="10" fill="rgba(255,255,255,0.75)">Serverless PostgreSQL</text>

    <!-- Feature cards row 1 -->
    <rect x="80" y="190" width="80" height="30" rx="8" fill="#fff" filter="url(#lb3-sh2)" />
    <text x="120" y="205" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" fill="#2a7a5f" font-weight="600">{{ t('按需弹性', 'Auto Scale') }}</text>
    <text x="120" y="215" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('自动伸缩', 'Elastic') }}</text>

    <rect x="175" y="190" width="90" height="30" rx="8" fill="#fff" filter="url(#lb3-sh2)" />
    <text x="220" y="205" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" fill="#2a7a5f" font-weight="600">{{ t('时间旅行', 'Time Travel') }}</text>
    <text x="220" y="215" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('版本管理', 'Versioning') }}</text>

    <rect x="280" y="190" width="80" height="30" rx="8" fill="#fff" filter="url(#lb3-sh2)" />
    <text x="320" y="205" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" fill="#2a7a5f" font-weight="600">{{ t('混合检索', 'Hybrid Search') }}</text>
    <text x="320" y="215" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('向量+全文+图+结构化', 'Vec+FTS+Graph+SQL') }}</text>

    <!-- Feature cards row 2 -->
    <rect x="80" y="235" width="80" height="30" rx="8" fill="#fff" filter="url(#lb3-sh2)" />
    <text x="120" y="250" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" fill="#2a7a5f" font-weight="600">{{ t('分支', 'Branch') }}</text>
    <text x="120" y="260" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('类 Git 管理', 'Git-like') }}</text>

    <rect x="175" y="235" width="90" height="30" rx="8" fill="#fff" filter="url(#lb3-sh2)" />
    <text x="220" y="250" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" fill="#2a7a5f" font-weight="600">{{ t('自动休眠', 'Auto Sleep') }}</text>
    <text x="220" y="260" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('秒级唤醒', 'Fast Wake') }}</text>

    <rect x="280" y="235" width="80" height="30" rx="8" fill="#fff" filter="url(#lb3-sh2)" />
    <text x="320" y="250" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="8" fill="#2a7a5f" font-weight="600">{{ t('零运维', 'Zero Ops') }}</text>
    <text x="320" y="260" text-anchor="middle" font-family="'Outfit', sans-serif" font-size="7" fill="#4a6358">{{ t('全托管', 'Managed') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg {
  width: 100%;
  height: auto;
}
</style>
