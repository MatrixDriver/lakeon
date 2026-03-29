<template>
  <svg viewBox="0 0 440 300" class="layer-svg" font-family="'DM Sans', sans-serif">
    <defs>
      <filter id="lb-sh1">
        <feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(30,45,61,0.10)" />
      </filter>
      <filter id="lb-sh2">
        <feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="rgba(30,45,61,0.06)" />
      </filter>
    </defs>

    <!-- background -->
    <rect x="30" y="8" width="380" height="284" rx="20" fill="#f5f6f8" opacity="0.5" />

    <!-- top row: clients -->
    <rect x="40" y="20" width="100" height="40" rx="8" fill="#ffffff" stroke="#c0c8d0" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="90" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#1e2d3d">mem0</text>
    <rect x="170" y="20" width="100" height="40" rx="8" fill="#ffffff" stroke="#c0c8d0" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="220" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#1e2d3d">Hindsight</text>
    <rect x="300" y="20" width="100" height="40" rx="8" fill="#ffffff" stroke="#c0c8d0" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="350" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#1e2d3d">Your Agent</text>

    <!-- connector lines -->
    <line x1="90" y1="60" x2="220" y2="110" stroke="#4a8b8c" stroke-width="1.5" opacity="0.45" />
    <line x1="220" y1="60" x2="220" y2="110" stroke="#4a8b8c" stroke-width="1.5" opacity="0.45" />
    <line x1="350" y1="60" x2="220" y2="110" stroke="#4a8b8c" stroke-width="1.5" opacity="0.45" />

    <!-- protocol badge -->
    <rect x="180" y="78" width="80" height="22" rx="11" fill="#ffffff" stroke="#4a8b8c" stroke-width="1.2" filter="url(#lb-sh2)" />
    <text x="220" y="93" text-anchor="middle" font-size="9" font-weight="700" fill="#4a8b8c">PG {{ t('协议', 'Protocol') }}</text>

    <!-- main box: Lakebase -->
    <rect x="80" y="110" width="280" height="60" rx="14" fill="#1e2d3d" filter="url(#lb-sh1)" />
    <text x="220" y="136" text-anchor="middle" font-size="18" font-weight="800" fill="#ffffff">Lakebase</text>
    <text x="220" y="156" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.7)">Serverless PostgreSQL</text>

    <!-- feature cards row 1 -->
    <rect x="80" y="190" width="80" height="30" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="120" y="205" text-anchor="middle" font-size="8" fill="#4a8b8c" font-weight="600">{{ t('按需弹性', 'Auto Scale') }}</text>
    <text x="120" y="215" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('自动伸缩', 'Elastic') }}</text>

    <rect x="175" y="190" width="90" height="30" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="220" y="205" text-anchor="middle" font-size="8" fill="#4a8b8c" font-weight="600">{{ t('时间旅行', 'Time Travel') }}</text>
    <text x="220" y="215" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('版本管理', 'Versioning') }}</text>

    <rect x="280" y="190" width="80" height="30" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="320" y="205" text-anchor="middle" font-size="8" fill="#4a8b8c" font-weight="600">{{ t('混合检索', 'Hybrid Search') }}</text>
    <text x="320" y="215" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('向量+全文+图+结构化', 'Vec+FTS+Graph+SQL') }}</text>

    <!-- feature cards row 2 -->
    <rect x="80" y="235" width="80" height="30" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="120" y="250" text-anchor="middle" font-size="8" fill="#4a8b8c" font-weight="600">{{ t('分支', 'Branch') }}</text>
    <text x="120" y="260" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('类 Git 管理', 'Git-like') }}</text>

    <rect x="175" y="235" width="90" height="30" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="220" y="250" text-anchor="middle" font-size="8" fill="#4a8b8c" font-weight="600">{{ t('自动休眠', 'Auto Sleep') }}</text>
    <text x="220" y="260" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('秒级唤醒', 'Fast Wake') }}</text>

    <rect x="280" y="235" width="80" height="30" rx="6" fill="#ffffff" stroke="#d8dce2" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="320" y="250" text-anchor="middle" font-size="8" fill="#4a8b8c" font-weight="600">{{ t('零运维', 'Zero Ops') }}</text>
    <text x="320" y="260" text-anchor="middle" font-size="7" fill="#8a9199">{{ t('全托管', 'Managed') }}</text>
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
