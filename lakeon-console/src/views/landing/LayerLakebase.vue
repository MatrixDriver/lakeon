<template>
  <svg viewBox="0 0 440 300" class="layer-svg">
    <defs>
      <linearGradient id="gl1-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary-light, #e3f2fd)" />
        <stop offset="100%" stop-color="#bbdefb" />
      </linearGradient>
      <linearGradient id="gl1-main" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary, #0073e6)" />
        <stop offset="100%" stop-color="var(--pub-primary-dark, #005bb5)" />
      </linearGradient>
      <linearGradient id="gl1-green" x1="0%" y1="0%" x2="0%" y2="100%">
        <stop offset="0%" stop-color="#e8f5e9" />
        <stop offset="100%" stop-color="#c8e6c9" />
      </linearGradient>
      <filter id="sh1">
        <feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(0,115,230,0.15)" />
      </filter>
      <filter id="sh2">
        <feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" />
      </filter>
    </defs>

    <ellipse cx="220" cy="150" rx="200" ry="130" fill="url(#gl1-bg)" opacity="0.4" />

    <rect x="40" y="20" width="100" height="40" rx="10" fill="url(#gl1-green)" filter="url(#sh2)" />
    <text x="90" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#2e7d32">🧠 mem0</text>
    <rect x="170" y="20" width="100" height="40" rx="10" fill="url(#gl1-green)" filter="url(#sh2)" />
    <text x="220" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#2e7d32">👁 Hindsight</text>
    <rect x="300" y="20" width="100" height="40" rx="10" fill="url(#gl1-green)" filter="url(#sh2)" />
    <text x="350" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#2e7d32">🤖 Your Agent</text>

    <line x1="90" y1="60" x2="220" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />
    <line x1="220" y1="60" x2="220" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />
    <line x1="350" y1="60" x2="220" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />

    <rect x="180" y="78" width="80" height="22" rx="11" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" filter="url(#sh2)" />
    <text x="220" y="93" text-anchor="middle" font-size="9" font-weight="700" fill="var(--pub-primary, #0073e6)">PG {{ t('协议', 'Protocol') }}</text>

    <rect x="80" y="110" width="280" height="60" rx="16" fill="url(#gl1-main)" filter="url(#sh1)" />
    <text x="220" y="136" text-anchor="middle" font-size="18" font-weight="800" fill="#fff">🐘 Lakebase</text>
    <text x="220" y="156" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.8)">Serverless PostgreSQL</text>

    <rect x="60" y="190" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="100" y="205" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">⚡ {{ t('按需弹性', 'Auto Scale') }}</text>
    <text x="100" y="215" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('自动伸缩', 'Elastic') }}</text>

    <rect x="155" y="190" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="195" y="205" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">⏱ {{ t('时间旅行', 'Time Travel') }}</text>
    <text x="195" y="215" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('版本管理', 'Versioning') }}</text>

    <rect x="250" y="190" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="290" y="205" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">🔍 pgvector</text>
    <text x="290" y="215" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('向量搜索', 'Vector') }}</text>

    <rect x="60" y="235" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="100" y="250" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">🔀 {{ t('分支', 'Branch') }}</text>
    <text x="100" y="260" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('类 Git 管理', 'Git-like') }}</text>

    <rect x="155" y="235" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="195" y="250" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">💤 {{ t('自动休眠', 'Auto Sleep') }}</text>
    <text x="195" y="260" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('秒级唤醒', 'Fast Wake') }}</text>

    <rect x="250" y="235" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="290" y="250" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">🔒 {{ t('零运维', 'Zero Ops') }}</text>
    <text x="290" y="260" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('全托管', 'Managed') }}</text>
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
