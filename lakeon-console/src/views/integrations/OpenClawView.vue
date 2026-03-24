<template>
  <main class="openclaw-page">
    <div class="openclaw-inner">
      <router-link to="/integrations" class="back-link">← {{ t('返回集成', 'Back to Integrations') }}</router-link>
      <div class="openclaw-hd">
        <h1>OpenClaw × DBay 记忆库</h1>
        <p>{{ t('龙虾 AI 助手与 DBay 记忆库的深度集成，实现自动回忆、自动捕获和自动反思', 'Deep integration of OpenClaw AI assistant with DBay Memory Store for auto-recall, auto-capture, and auto-digest') }}</p>
      </div>

      <section class="oc-section">
        <h2>{{ t('快速接入', 'Quick Setup') }}</h2>
        <ol class="oc-steps">
          <li>{{ t('安装 dbay-mcp', 'Install dbay-mcp') }}: <code>pip install dbay-mcp</code></li>
          <li>{{ t('在 OpenClaw 插件设置中配置 MCP 服务器地址', 'Configure MCP server address in OpenClaw plugin settings') }}</li>
          <li>{{ t('输入 DBay API Key，启用记忆自动捕获', 'Enter DBay API Key and enable auto-capture') }}</li>
        </ol>
      </section>

      <section class="oc-section">
        <h2>{{ t('核心功能', 'Core Features') }}</h2>
        <div class="oc-features">
          <div class="oc-feature">
            <h3>{{ t('自动回忆', 'Auto-Recall') }}</h3>
            <p>{{ t('每次对话开始前，自动从记忆库检索与当前话题相关的记忆，注入系统提示词', 'Before each conversation, automatically retrieves relevant memories and injects them into the system prompt') }}</p>
          </div>
          <div class="oc-feature">
            <h3>{{ t('自动捕获', 'Auto-Capture') }}</h3>
            <p>{{ t('对话结束后自动调用 ingest API，提取并存储重要事实、事件和偏好', 'After each conversation, automatically calls the ingest API to extract and store important facts, events, and preferences') }}</p>
          </div>
          <div class="oc-feature">
            <h3>{{ t('自动反思', 'Auto-Digest') }}</h3>
            <p>{{ t('定期调用 digest API，将积累的记忆合成为用户画像和行为模式', 'Periodically calls the digest API to synthesize accumulated memories into user profiles and behavioral patterns') }}</p>
          </div>
        </div>
      </section>

      <section class="oc-section">
        <h2>{{ t('两种集成方式', 'Two Integration Methods') }}</h2>
        <h3>{{ t('方式一：MCP 直连（零代码）', 'Method 1: MCP Direct Connect (zero code)') }}</h3>
        <pre class="code-block"><code>{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": { "DBAY_API_KEY": "your-key" }
    }
  }
}</code></pre>
        <h3>{{ t('方式二：OpenClaw 插件（自动化）', 'Method 2: OpenClaw Plugin (automated)') }}</h3>
        <pre class="code-block"><code>"openclaw-dbay": {
  "enabled": true,
  "config": {
    "apiKey": "your-dbay-api-key",
    "mode": "one-llm",
    "autoRecall": true,
    "autoCapture": true,
    "autoDigest": "session-end",
    "topK": 10,
    "includeTraits": true
  }
}</code></pre>
      </section>

      <section class="oc-section">
        <h2>{{ t('记忆闭环', 'Memory Loop') }}</h2>
        <div class="oc-loop">
          <div v-for="(step, i) in memoryLoop" :key="step.key" class="loop-step">
            <div class="loop-num">{{ i + 1 }}</div>
            <div>
              <strong>{{ step.title }}</strong>
              <p>{{ step.desc }}</p>
            </div>
          </div>
        </div>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const memoryLoop = computed(() => [
  { key: 'recall', title: t('自动回忆', 'Auto-Recall'), desc: t('对话开始，检索相关记忆注入上下文', 'Conversation starts, relevant memories injected into context') },
  { key: 'respond', title: t('个性化回复', 'Personalized Response'), desc: t('Agent 基于记忆增强的上下文生成回复', 'Agent generates response with memory-enriched context') },
  { key: 'capture', title: t('自动捕获', 'Auto-Capture'), desc: t('提取新事实、事件存入记忆库', 'Extract new facts and events into memory store') },
  { key: 'digest', title: t('自动反思', 'Auto-Digest'), desc: t('会话结束时发现行为模式，生成特征', 'Discover behavioral patterns at session end, generate traits') },
])
</script>

<style scoped>
.openclaw-page { min-height: 100vh; background: #0a0a0a; color: #e5e5e5; }
.openclaw-inner { max-width: 760px; margin: 0 auto; padding: 48px 24px 80px; }
.back-link { font-size: 13px; color: #7c3aed; text-decoration: none; display: inline-block; margin-bottom: 32px; }
.openclaw-hd { margin-bottom: 48px; }
.openclaw-hd h1 { font-size: 28px; font-weight: 700; margin-bottom: 10px; }
.openclaw-hd p { color: #888; font-size: 15px; line-height: 1.6; }
.oc-section { margin-bottom: 48px; }
.oc-section h2 { font-size: 20px; font-weight: 600; margin-bottom: 20px; padding-bottom: 8px; border-bottom: 1px solid #1a1a1a; }
.oc-section h3 { font-size: 15px; font-weight: 600; color: #ccc; margin: 16px 0 8px; }
.oc-steps { padding-left: 20px; display: flex; flex-direction: column; gap: 10px; }
.oc-steps li { font-size: 14px; color: #ccc; line-height: 1.6; }
.oc-steps code { font-family: monospace; background: #1a1a1a; padding: 1px 6px; border-radius: 3px; color: #60a5fa; font-size: 13px; }
.oc-features { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.oc-feature { background: #111; border: 1px solid #222; border-radius: 8px; padding: 16px; }
.oc-feature h3 { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: #a78bfa; }
.oc-feature p { font-size: 13px; color: #888; margin: 0; line-height: 1.6; }
.code-block {
  background: #0d0d0d; border: 1px solid #222; border-radius: 6px;
  padding: 14px; font-size: 12px; color: #a78bfa;
  overflow-x: auto; margin: 8px 0 16px; font-family: monospace; white-space: pre;
}
.oc-loop { display: flex; flex-direction: column; gap: 12px; }
.loop-step { display: flex; gap: 16px; align-items: flex-start; padding: 14px; background: #111; border: 1px solid #222; border-radius: 8px; }
.loop-num { width: 24px; height: 24px; border-radius: 50%; background: #7c3aed; color: #fff; font-size: 12px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.loop-step strong { font-size: 14px; font-weight: 600; display: block; margin-bottom: 4px; }
.loop-step p { font-size: 13px; color: #888; margin: 0; }
@media (max-width: 768px) {
  .oc-features { grid-template-columns: 1fr; }
}
</style>
