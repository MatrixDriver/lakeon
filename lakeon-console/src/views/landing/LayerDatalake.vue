<template>
  <svg viewBox="0 0 440 310" class="layer-svg">
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
      <linearGradient id="gl3-ray" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#ff6f00" />
        <stop offset="100%" stop-color="#e65100" />
      </linearGradient>
      <filter id="gd1"><feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(123,31,162,0.15)" /></filter>
      <filter id="gd2"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" /></filter>
    </defs>

    <ellipse cx="220" cy="160" rx="215" ry="148" fill="url(#gl3-bg)" opacity="0.2" />

    <!-- Row 1: Memory + Knowledge + State Store -->
    <rect x="20" y="10" width="125" height="42" rx="12" fill="url(#gl3-mem)" filter="url(#gd1)" />
    <text x="82" y="30" text-anchor="middle" font-size="12" font-weight="700" fill="#fff">🧠 {{ t('记忆库', 'Memory') }}</text>
    <text x="82" y="44" text-anchor="middle" font-size="8" fill="rgba(255,255,255,0.85)">{{ t('持久化 · 反思', 'Persist · Reflect') }}</text>

    <rect x="157" y="10" width="125" height="42" rx="12" fill="url(#gl3-know)" filter="url(#gd1)" />
    <text x="220" y="30" text-anchor="middle" font-size="12" font-weight="700" fill="#fff">📚 {{ t('知识库', 'Knowledge') }}</text>
    <text x="220" y="44" text-anchor="middle" font-size="8" fill="rgba(255,255,255,0.85)">{{ t('文档向量化', 'Vectorize') }}</text>

    <rect x="295" y="10" width="125" height="42" rx="12" fill="url(#gl3-state)" filter="url(#gd1)" />
    <text x="357" y="30" text-anchor="middle" font-size="12" font-weight="700" fill="#fff">📋 {{ t('状态库', 'State Store') }}</text>
    <text x="357" y="44" text-anchor="middle" font-size="8" fill="rgba(255,255,255,0.85)">{{ t('会话 · 上下文', 'Session · Context') }}</text>

    <!-- Arrows: services → Lakebase -->
    <line x1="82" y1="52" x2="120" y2="78" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" opacity="0.5" />
    <line x1="220" y1="52" x2="120" y2="78" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" opacity="0.5" />
    <line x1="357" y1="52" x2="120" y2="78" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" opacity="0.5" />

    <!-- Row 2: Lakebase (left) + AI DataLake (right) -->
    <rect x="20" y="80" width="195" height="48" rx="14" fill="url(#gl3-base)" filter="url(#gd1)" />
    <text x="117" y="103" text-anchor="middle" font-size="14" font-weight="800" fill="#fff">🐘 Lakebase</text>
    <text x="117" y="119" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('统一存储 · 多分支', 'Unified · Branching') }}</text>

    <rect x="225" y="80" width="195" height="48" rx="14" fill="url(#gl3-lake)" filter="url(#gd1)" />
    <text x="322" y="103" text-anchor="middle" font-size="14" font-weight="800" fill="#fff">🌊 AI DataLake</text>
    <text x="322" y="119" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">Parquet · Lance · {{ t('多模态', 'Multimodal') }}</text>

    <!-- Arrow: Lakebase → AI DataLake (bold, colored) -->
    <line x1="215" y1="104" x2="225" y2="104" stroke="#7b1fa2" stroke-width="3" />
    <polygon points="226,104 218,99 218,109" fill="#7b1fa2" />
    <text x="220" y="96" text-anchor="middle" font-size="7" fill="#7b1fa2" font-weight="600">{{ t('数据流转', 'Data Flow') }}</text>

    <!-- Arrows: Lakebase + DataLake → Ray -->
    <line x1="150" y1="128" x2="220" y2="153" stroke="#e65100" stroke-width="1.5" opacity="0.5" />
    <line x1="290" y1="128" x2="220" y2="153" stroke="#e65100" stroke-width="1.5" opacity="0.5" />

    <!-- Row 3: Ray / Python Job Engine -->
    <rect x="80" y="155" width="280" height="44" rx="12" fill="url(#gl3-ray)" filter="url(#gd1)" />
    <text x="220" y="177" text-anchor="middle" font-size="13" font-weight="700" fill="#fff">⚙️ Ray / Python {{ t('作业引擎', 'Job Engine') }}</text>
    <text x="220" y="191" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.85)">{{ t('数据加工 · 分析 · 微调 · RL', 'Process · Analyze · Finetune · RL') }}</text>

    <!-- Feedback arrow: Ray → Memory (left side, "更新记忆策略") -->
    <path d="M 80,170 Q 15,170 15,104 Q 15,40 50,30 L 20,30" fill="none" stroke="#e65100" stroke-width="1.5" stroke-dasharray="4,3" opacity="0.5" />
    <polygon points="20,30 26,26 26,34" fill="#e65100" opacity="0.5" />
    <text x="5" y="105" font-size="7" fill="#e65100" font-weight="600" transform="rotate(-90,5,105)">{{ t('更新记忆策略', 'Update Strategy') }}</text>

    <!-- Row 4: Output capabilities -->
    <line x1="140" y1="199" x2="95" y2="218" stroke="#e65100" stroke-width="1.2" opacity="0.4" />
    <line x1="220" y1="199" x2="220" y2="218" stroke="#e65100" stroke-width="1.2" opacity="0.4" />
    <line x1="300" y1="199" x2="345" y2="218" stroke="#e65100" stroke-width="1.2" opacity="0.4" />

    <rect x="40" y="220" width="110" height="34" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="95" y="235" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">📊 {{ t('用户行为分析', 'User Analytics') }}</text>
    <text x="95" y="247" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('Agent 使用习惯', 'Agent Patterns') }}</text>

    <rect x="165" y="220" width="110" height="34" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="220" y="235" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">🔄 {{ t('记忆策略评估', 'Strategy Eval') }}</text>
    <text x="220" y="247" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('反思 · 优化', 'Reflect · Optimize') }}</text>

    <rect x="290" y="220" width="110" height="34" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="345" y="235" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">📦 {{ t('多模态知识加工', 'Multimodal Process') }}</text>
    <text x="345" y="247" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('图文 · 音视频', 'Image · Audio · Video') }}</text>

    <!-- Bottom: data flywheel label -->
    <rect x="130" y="268" width="180" height="26" rx="13" fill="var(--pub-surface, #fff)" stroke="#7b1fa2" stroke-width="1" filter="url(#gd2)" />
    <text x="220" y="285" text-anchor="middle" font-size="10" font-weight="600" fill="#7b1fa2">{{ t('数据飞轮 · 持续进化', 'Data Flywheel · Evolve') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
