<template>
  <svg viewBox="0 0 440 280" class="layer-svg">
    <defs>
      <linearGradient id="gl3-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f3e5f5" />
        <stop offset="100%" stop-color="#e1bee7" />
      </linearGradient>
      <linearGradient id="gl3-mem" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#ff9800" />
        <stop offset="100%" stop-color="#e65100" />
      </linearGradient>
      <linearGradient id="gl3-know" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#4caf50" />
        <stop offset="100%" stop-color="#2e7d32" />
      </linearGradient>
      <linearGradient id="gl3-state" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#5c6bc0" />
        <stop offset="100%" stop-color="#283593" />
      </linearGradient>
      <linearGradient id="gl3-base" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary, #0073e6)" />
        <stop offset="100%" stop-color="var(--pub-primary-dark, #005bb5)" />
      </linearGradient>
      <linearGradient id="gl3-lake" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#7b1fa2" />
        <stop offset="100%" stop-color="#4a148c" />
      </linearGradient>
      <filter id="gd1"><feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(123,31,162,0.15)" /></filter>
      <filter id="gd2"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" /></filter>
    </defs>

    <ellipse cx="220" cy="140" rx="215" ry="130" fill="url(#gl3-bg)" opacity="0.2" />

    <!-- ══ Left column: Lakebase side ══ -->

    <!-- Top-left: Memory + Knowledge + State (stacked vertically) -->
    <rect x="15" y="10" width="90" height="36" rx="10" fill="url(#gl3-mem)" filter="url(#gd1)" />
    <text x="60" y="27" text-anchor="middle" font-size="11" font-weight="700" fill="#fff">🧠 {{ t('记忆库', 'Memory') }}</text>
    <text x="60" y="40" text-anchor="middle" font-size="7" fill="rgba(255,255,255,0.85)">{{ t('持久化 · 反思', 'Persist') }}</text>

    <rect x="113" y="10" width="90" height="36" rx="10" fill="url(#gl3-know)" filter="url(#gd1)" />
    <text x="158" y="27" text-anchor="middle" font-size="11" font-weight="700" fill="#fff">📚 {{ t('知识库', 'Knowledge') }}</text>
    <text x="158" y="40" text-anchor="middle" font-size="7" fill="rgba(255,255,255,0.85)">{{ t('文档向量化', 'Vectorize') }}</text>

    <rect x="15" y="54" width="90" height="36" rx="10" fill="url(#gl3-state)" filter="url(#gd1)" />
    <text x="60" y="71" text-anchor="middle" font-size="11" font-weight="700" fill="#fff">📋 {{ t('状态库', 'State') }}</text>
    <text x="60" y="84" text-anchor="middle" font-size="7" fill="rgba(255,255,255,0.85)">{{ t('会话 · 上下文', 'Session') }}</text>

    <!-- Arrows: services → Lakebase -->
    <line x1="60" y1="46" x2="110" y2="115" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" opacity="0.4" />
    <line x1="158" y1="46" x2="110" y2="115" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" opacity="0.4" />
    <line x1="60" y1="90" x2="110" y2="115" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" opacity="0.4" />

    <!-- Lakebase -->
    <rect x="15" y="115" width="195" height="52" rx="14" fill="url(#gl3-base)" filter="url(#gd1)" />
    <text x="112" y="140" text-anchor="middle" font-size="15" font-weight="800" fill="#fff">🐘 Lakebase</text>
    <text x="112" y="157" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('统一存储 · 多分支', 'Unified · Branching') }}</text>

    <!-- ══ Right column: AI DataLake side ══ -->

    <!-- DataLake capability cards (above DataLake) -->
    <rect x="230" y="10" width="95" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="277" y="26" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">📊 {{ t('用户行为分析', 'Analytics') }}</text>
    <text x="277" y="38" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('Agent 使用习惯', 'Agent Patterns') }}</text>

    <rect x="333" y="10" width="95" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="380" y="26" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">🔄 {{ t('记忆策略评估', 'Strategy Eval') }}</text>
    <text x="380" y="38" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('反思 · 优化', 'Reflect') }}</text>

    <rect x="280" y="54" width="95" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="327" y="70" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">📦 {{ t('多模态知识加工', 'Multimodal') }}</text>
    <text x="327" y="82" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('图文 · 音视频', 'Image · AV') }}</text>

    <!-- Arrows: capability cards → DataLake -->
    <line x1="277" y1="46" x2="310" y2="115" stroke="#7b1fa2" stroke-width="1.2" opacity="0.4" />
    <line x1="380" y1="46" x2="330" y2="115" stroke="#7b1fa2" stroke-width="1.2" opacity="0.4" />
    <line x1="327" y1="90" x2="320" y2="115" stroke="#7b1fa2" stroke-width="1.2" opacity="0.4" />

    <!-- AI DataLake -->
    <rect x="225" y="115" width="200" height="52" rx="14" fill="url(#gl3-lake)" filter="url(#gd1)" />
    <text x="325" y="140" text-anchor="middle" font-size="15" font-weight="800" fill="#fff">🌊 AI DataLake</text>
    <text x="325" y="157" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">Parquet · Lance · {{ t('多模态', 'Multimodal') }}</text>

    <!-- ══ Center: Lakebase → DataLake arrow ══ -->
    <line x1="210" y1="141" x2="225" y2="141" stroke="#7b1fa2" stroke-width="3" />
    <polygon points="226,141 218,136 218,146" fill="#7b1fa2" />

    <!-- ══ Feedback: DataLake → Memory (smooth curve) ══ -->
    <path d="M 225,155 C 195,195 30,195 30,90 L 30,46" fill="none" stroke="#e65100" stroke-width="1.5" stroke-dasharray="4,3" opacity="0.5" />
    <polygon points="30,46 26,54 34,54" fill="#e65100" opacity="0.5" />
    <text x="90" y="200" text-anchor="middle" font-size="8" fill="#e65100" font-weight="600">{{ t('更新记忆策略', 'Update Strategy') }}</text>

    <!-- ══ Bottom: data flywheel label ══ -->
    <rect x="130" y="220" width="180" height="26" rx="13" fill="var(--pub-surface, #fff)" stroke="#7b1fa2" stroke-width="1" filter="url(#gd2)" />
    <text x="220" y="237" text-anchor="middle" font-size="10" font-weight="600" fill="#7b1fa2">{{ t('数据飞轮 · 持续进化', 'Data Flywheel · Evolve') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
