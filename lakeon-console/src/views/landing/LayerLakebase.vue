<template>
  <svg viewBox="0 0 440 300" class="layer-svg">
    <defs>
      <linearGradient id="lb-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary-light, #e3f2fd)" />
        <stop offset="100%" stop-color="#bbdefb" />
      </linearGradient>
      <linearGradient id="lb-main" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary, #0073e6)" />
        <stop offset="100%" stop-color="var(--pub-primary-dark, #005bb5)" />
      </linearGradient>
      <linearGradient id="lb-green" x1="0%" y1="0%" x2="0%" y2="100%">
        <stop offset="0%" stop-color="#e8f5e9" />
        <stop offset="100%" stop-color="#c8e6c9" />
      </linearGradient>
      <filter id="lb-sh1"><feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(0,115,230,0.15)" /></filter>
      <filter id="lb-sh2"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" /></filter>
    </defs>

    <ellipse cx="220" cy="150" rx="210" ry="140" fill="url(#lb-bg)" opacity="0.3" />

    <!-- Row 1: Clients -->
    <rect x="20" y="10" width="125" height="42" rx="12" fill="url(#lb-green)" filter="url(#lb-sh2)" />
    <text x="82" y="30" text-anchor="middle" font-size="13" font-weight="700" fill="#2e7d32">mem0</text>
    <text x="82" y="44" text-anchor="middle" font-size="10" fill="#558b2f">{{ t('记忆框架', 'Memory SDK') }}</text>

    <rect x="157" y="10" width="125" height="42" rx="12" fill="url(#lb-green)" filter="url(#lb-sh2)" />
    <text x="220" y="30" text-anchor="middle" font-size="13" font-weight="700" fill="#2e7d32">Hindsight</text>
    <text x="220" y="44" text-anchor="middle" font-size="10" fill="#558b2f">{{ t('记忆框架', 'Memory SDK') }}</text>

    <rect x="295" y="10" width="125" height="42" rx="12" fill="url(#lb-green)" filter="url(#lb-sh2)" />
    <text x="357" y="30" text-anchor="middle" font-size="13" font-weight="700" fill="#2e7d32">Your Agent</text>
    <text x="357" y="44" text-anchor="middle" font-size="10" fill="#558b2f">{{ t('自定义应用', 'Custom App') }}</text>

    <!-- PG Protocol badge -->
    <rect x="170" y="62" width="100" height="24" rx="12" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" filter="url(#lb-sh2)" />
    <text x="220" y="79" text-anchor="middle" font-size="11" font-weight="700" fill="var(--pub-primary, #0073e6)">PG {{ t('协议', 'Protocol') }}</text>

    <!-- Lines from clients to Lakebase -->
    <line x1="82" y1="52" x2="220" y2="100" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />
    <line x1="220" y1="52" x2="220" y2="100" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />
    <line x1="357" y1="52" x2="220" y2="100" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />

    <!-- Lakebase -->
    <rect x="70" y="100" width="300" height="56" rx="16" fill="url(#lb-main)" filter="url(#lb-sh1)" />
    <text x="220" y="126" text-anchor="middle" font-size="20" font-weight="800" fill="#fff">Lakebase</text>
    <text x="220" y="146" text-anchor="middle" font-size="11" fill="rgba(255,255,255,0.8)">Serverless PostgreSQL</text>

    <!-- Feature cards row 1 -->
    <rect x="30" y="175" width="120" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#lb-sh2)" />
    <text x="90" y="196" text-anchor="middle" font-size="11" fill="var(--pub-primary, #0073e6)" font-weight="600">{{ t('按需弹性', 'Auto Scale') }}</text>

    <rect x="160" y="175" width="120" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#lb-sh2)" />
    <text x="220" y="196" text-anchor="middle" font-size="11" fill="var(--pub-primary, #0073e6)" font-weight="600">{{ t('时间旅行', 'Time Travel') }}</text>

    <rect x="290" y="175" width="120" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#lb-sh2)" />
    <text x="350" y="196" text-anchor="middle" font-size="11" fill="var(--pub-primary, #0073e6)" font-weight="600">{{ t('混合检索', 'Hybrid Search') }}</text>

    <!-- Feature cards row 2 -->
    <rect x="30" y="222" width="120" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#lb-sh2)" />
    <text x="90" y="243" text-anchor="middle" font-size="11" fill="var(--pub-primary, #0073e6)" font-weight="600">{{ t('分支管理', 'Branching') }}</text>

    <rect x="160" y="222" width="120" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#lb-sh2)" />
    <text x="220" y="243" text-anchor="middle" font-size="11" fill="var(--pub-primary, #0073e6)" font-weight="600">{{ t('自动休眠', 'Auto Sleep') }}</text>

    <rect x="290" y="222" width="120" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#lb-sh2)" />
    <text x="350" y="243" text-anchor="middle" font-size="11" fill="var(--pub-primary, #0073e6)" font-weight="600">{{ t('零运维', 'Zero Ops') }}</text>

    <!-- Bottom label -->
    <rect x="120" y="270" width="200" height="26" rx="13" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1" filter="url(#lb-sh2)" />
    <text x="220" y="287" text-anchor="middle" font-size="11" font-weight="600" fill="var(--pub-primary, #0073e6)">{{ t('PG 兼容 · 开箱即用', 'PG Compatible · Ready to Use') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
