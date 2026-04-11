<template>
  <main class="oc-page">
    <div class="oc-inner">
      <router-link to="/integrations" class="back-link">← {{ t('返回集成', 'Back to Integrations') }}</router-link>

      <!-- Hero -->
      <section class="oc-hero">
        <h1>OpenClaw × DBay 记忆库</h1>
        <p class="oc-hero-sub">{{ t('为你的 OpenClaw 智能体赋予深度行为记忆——不只是事实，还有模式、情感和持续进化的特征洞察。', 'Give your OpenClaw agent deep behavioral memory — not just facts, but patterns, emotions, and continuously evolving trait insights.') }}</p>
        <div class="oc-hero-actions">
          <router-link to="/login" class="btn-primary-sm">{{ t('获取 API Key', 'Get API Key') }}</router-link>
          <a href="https://www.npmjs.com/package/@dbay/openclaw-dbay" target="_blank" rel="noopener noreferrer" class="btn-outline-sm">npm</a>
        </div>
      </section>

      <!-- TOC -->
      <nav class="oc-toc">
        <a v-for="item in toc" :key="item.id" :href="`#${item.id}`" class="toc-link">{{ item.label }}</a>
      </nav>

      <!-- Pain Points -->
      <section id="pain-points" class="oc-section">
        <h2>{{ t('OpenClaw 原生记忆的局限', 'Limitations of OpenClaw Native Memory') }}</h2>
        <p class="oc-section-sub">{{ t('OpenClaw 的 Markdown 记忆对简单场景够用，但随着使用深入，底层局限会越来越明显。', 'OpenClaw\'s Markdown memory works for simple use cases, but deeper usage reveals fundamental limitations.') }}</p>
        <div class="pain-list">
          <div v-for="p in painPoints" :key="p.key" class="pain-card">
            <div class="pain-hd">
              <span class="icon-x">✗</span>
              <div>
                <h3>{{ p.title }}</h3>
                <p>{{ p.desc }}</p>
                <div v-if="p.layers" class="pain-layers">
                  <p v-for="layer in p.layers" :key="layer" class="mono-sm">{{ layer }}</p>
                </div>
              </div>
            </div>
            <div class="pain-solution">
              <span class="icon-check">✓</span>
              <p>{{ p.solution }}</p>
            </div>
          </div>
        </div>
      </section>

      <!-- Value Proposition -->
      <section id="value" class="oc-section">
        <h2>{{ t('DBay 为你的 OpenClaw 智能体带来什么', 'What DBay Brings to Your OpenClaw Agent') }}</h2>
        <p class="oc-section-sub">{{ t('不只是更好的存储——而是智能体与记忆之间根本不同的关系。', 'Not just better storage — a fundamentally different relationship between agent and memory.') }}</p>
        <div class="value-list">
          <div v-for="v in values" :key="v.key" class="value-card">
            <h3>{{ v.title }}</h3>
            <p>{{ v.desc }}</p>
            <div v-if="v.example" class="value-example">{{ v.example }}</div>
          </div>
        </div>
      </section>

      <!-- Role Memory Spaces -->
      <section id="roles" class="oc-section">
        <h2>{{ t('角色专属记忆空间', 'Role-Specific Memory Spaces') }}</h2>
        <p class="oc-section-sub">{{ t('为不同的 OpenClaw 角色预置最优记忆策略。选择角色模板，30 秒创建专属记忆空间。', 'Preset optimal memory strategies for different OpenClaw roles. Choose a template, create your space in 30 seconds.') }}</p>
        <div class="roles-grid">
          <div v-for="r in roles" :key="r.key" class="role-card">
            <div class="role-hd">
              <span class="role-icon-dot" :style="{ background: roleColors[r.key] || '#c67d3a' }"></span>
              <h3>{{ r.title }}</h3>
            </div>
            <p>{{ r.desc }}</p>
          </div>
        </div>
      </section>

      <!-- Cognitive Architecture -->
      <section id="architecture" class="oc-section">
        <h2>{{ t('记忆认知架构', 'Memory Cognitive Architecture') }}</h2>
        <p class="oc-section-sub">{{ t('这不是存储优化，是记忆的认知层次不同。', 'This is not a storage optimization — it\'s a fundamentally different cognitive hierarchy of memory.') }}</p>
        <div class="arch-list">
          <div v-for="(layer, idx) in archLayers" :key="layer.key" class="arch-card">
            <div class="arch-num">L{{ idx + 1 }}</div>
            <div class="arch-body">
              <div class="arch-labels">
                <h3>{{ layer.label }}</h3>
                <span class="arch-type">{{ layer.type }}</span>
              </div>
              <p class="mono-sm">{{ layer.example }}</p>
            </div>
          </div>
        </div>
        <p class="arch-footer">{{ t('Facts 与 Episodes 关联 → Episodes 生成 Knowledge Graph → 所有层汇聚为 Traits（反思）', 'Facts link to Episodes → Episodes generate Knowledge Graph → all layers converge into Traits via reflection') }}</p>
      </section>

      <!-- Use Scenarios -->
      <section id="scenarios" class="oc-section">
        <h2>{{ t('最适合的使用场景', 'Best Use Cases') }}</h2>
        <p class="oc-section-sub">{{ t('当你的智能体需要「理解你」而不只是「记住你说的话」时，DBay 就是最佳选择。', 'When your agent needs to understand you, not just remember what you said — DBay is the right choice.') }}</p>
        <div class="scenarios-grid">
          <div v-for="s in scenarios" :key="s.key" class="scenario-card">
            <h3>{{ s.title }}</h3>
            <p>{{ s.desc }}</p>
            <p class="scenario-why">{{ s.why }}</p>
          </div>
        </div>
      </section>

      <!-- Why DBay -->
      <section id="why" class="oc-section">
        <h2>{{ t('为什么选择 DBay？', 'Why DBay?') }}</h2>
        <p class="oc-section-sub">{{ t('记忆有深度，Agent 才有温度。', 'Memory with depth gives agents soul.') }}</p>
        <div class="why-grid">
          <div v-for="w in whyItems" :key="w.key" class="why-card">
            <h3>{{ w.title }}</h3>
            <p>{{ w.desc }}</p>
          </div>
        </div>
      </section>

      <!-- Quick Start -->
      <section id="quickstart" class="oc-section">
        <h2>{{ t('快速开始', 'Quick Start') }}</h2>
        <p class="oc-section-sub">{{ t('两种接入方式，选择适合你的。', 'Two integration methods — choose what fits you.') }}</p>
        <div class="tabs">
          <div class="tab-bar">
            <button :class="['tab-btn', activeTab === 'plugin' ? 'active' : '']" @click="activeTab = 'plugin'">{{ t('插件（推荐）', 'Plugin (Recommended)') }}</button>
            <button :class="['tab-btn', activeTab === 'mcp' ? 'active' : '']" @click="activeTab = 'mcp'">MCP {{ t('直连', 'Direct') }}</button>
          </div>
          <div class="tab-content" v-if="activeTab === 'plugin'">
            <p class="step-label">1. {{ t('安装插件', 'Install the plugin') }}</p>
            <pre class="code-block"><code>openclaw plugins install @dbay/openclaw-dbay</code></pre>
            <p class="step-label">2. {{ t('配置 API Key', 'Configure API Key') }}</p>
            <pre class="code-block"><code>openclaw config set plugins.entries.openclaw-dbay.config.apiKey "dbay_sk_your_key_here"</code></pre>
            <p class="step-label">3. {{ t('启用记忆插件', 'Enable memory plugin') }}</p>
            <pre class="code-block"><code>openclaw config set plugins.slots.memory "openclaw-dbay"</code></pre>
            <p class="step-label">4. {{ t('重启 Gateway', 'Restart Gateway') }}</p>
            <pre class="code-block"><code>openclaw gateway restart</code></pre>
            <p class="tip-text">{{ t('完成。自动召回、自动捕获和自动提炼默认开启。', 'Done. Auto-recall, auto-capture, and auto-digest are enabled by default.') }}</p>
          </div>
          <div class="tab-content" v-else>
            <p class="step-label">1. {{ t('配置 MCP 连接', 'Configure MCP connection') }}</p>
            <pre class="code-block"><code>// 1. 先配置凭据
pip install dbay-cli
dbay login

// 2. 配置 ~/.openclaw/mcp.json
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"]
    }
  }
}</code></pre>
            <p class="step-label">2. {{ t('重启 Gateway', 'Restart Gateway') }}</p>
            <pre class="code-block"><code>openclaw gateway restart</code></pre>
            <p class="tip-text">{{ t('重启后，智能体将直接获得 ingest、recall、digest 等 MCP 工具。', 'After restart, the agent gets direct access to ingest, recall, digest and other MCP tools.') }}</p>
          </div>
        </div>
      </section>

      <!-- How It Works -->
      <section id="how-it-works" class="oc-section">
        <h2>{{ t('工作原理', 'How It Works') }}</h2>
        <p class="oc-section-sub">{{ t('三个自动钩子在每次对话中运行，无需手动操作。', 'Three automatic hooks run on every conversation — no manual intervention needed.') }}</p>
        <div class="how-list">
          <div v-for="h in howItWorks" :key="h.key" class="how-card">
            <span class="phase-badge">{{ h.phase }}</span>
            <div>
              <h3>{{ h.title }}</h3>
              <p>{{ h.desc }}</p>
            </div>
          </div>
        </div>
      </section>

      <!-- ONE LLM Mode -->
      <section id="one-llm" class="oc-section">
        <h2>{{ t('ONE LLM 模式——零额外成本', 'ONE LLM Mode — Zero Extra Cost') }}</h2>
        <p class="oc-section-sub">{{ t('DBay 不需要额外的 LLM 做记忆提取——你的 OpenClaw 智能体自带的模型直接完成，零额外成本。', 'DBay doesn\'t need a separate LLM for memory extraction — your OpenClaw agent\'s own model handles it, at zero extra cost.') }}</p>
        <div class="one-llm-box">
          <p class="mono-lg">OpenClaw (Claude / GPT)</p>
          <p class="arrow">↓</p>
          <p class="mono-gray">{{ t('同一个 LLM 提取记忆', 'Same LLM extracts memories') }}</p>
          <p class="arrow">↓</p>
          <p class="mono-green">{{ t('零额外 LLM 成本', 'Zero extra LLM cost') }}</p>
        </div>
      </section>

      <!-- Agent Tools -->
      <section id="tools" class="oc-section">
        <h2>{{ t('智能体工具', 'Agent Tools') }}</h2>
        <p class="oc-section-sub">{{ t('五个工具用于显式记忆操作：', 'Five tools for explicit memory operations:') }}</p>
        <div class="tools-list">
          <div v-for="tool in agentTools" :key="tool.name" class="tool-row">
            <code>dbay_{{ tool.name }}</code>
            <p>{{ tool.desc }}</p>
          </div>
        </div>
      </section>

      <!-- Config Reference -->
      <section id="config" class="oc-section">
        <h2>{{ t('配置参考', 'Configuration Reference') }}</h2>
        <div class="config-table">
          <div class="config-row header">
            <span>{{ t('参数', 'Param') }}</span>
            <span>{{ t('类型', 'Type') }}</span>
            <span>{{ t('默认值', 'Default') }}</span>
            <span>{{ t('说明', 'Description') }}</span>
          </div>
          <div v-for="c in configRows" :key="c.param" class="config-row">
            <code>{{ c.param }}</code>
            <span class="type-text">{{ c.type }}</span>
            <span class="mono-sm">{{ c.default }}</span>
            <span>{{ c.desc }}</span>
          </div>
        </div>
      </section>

      <!-- Capabilities -->
      <section id="compare" class="oc-section">
        <h2>{{ t('DBay 核心能力', 'DBay Core Capabilities') }}</h2>
        <p class="oc-section-sub">{{ t('为 OpenClaw 提供的完整记忆能力一览。', 'Complete memory capabilities provided for OpenClaw.') }}</p>
        <div class="caps-grid">
          <div v-for="cap in capabilities" :key="cap" class="cap-item">
            <span class="icon-check">✓</span>
            <span>{{ cap }}</span>
          </div>
        </div>
        <div class="caps-footer">
          <p class="caps-tagline">{{ t('记忆有深度，Agent 才有温度。', 'Memory with depth gives agents soul.') }}</p>
          <p class="caps-sub">{{ t('从记住到理解，一步之遥。', 'From remembering to understanding — one step away.') }}</p>
        </div>
      </section>

      <!-- CTA -->
      <section class="oc-cta">
        <h2>{{ t('为 OpenClaw 添加长期记忆', 'Add Long-term Memory to OpenClaw') }}</h2>
        <p>{{ t('30 秒获取 API Key。', 'Get an API Key in 30 seconds.') }}</p>
        <router-link to="/login" class="btn-primary-lg">{{ t('立即开始', 'Get Started') }}</router-link>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()
const _validTabs = ['plugin', 'mcp']
const _hashTab = window.location.hash.replace('#', '')
const activeTab = ref(_validTabs.includes(_hashTab) ? _hashTab : 'plugin')
watch(activeTab, (tab) => { window.location.hash = tab })

const toc = computed(() => [
  { id: 'pain-points', label: t('OpenClaw 的局限', 'OpenClaw Limitations') },
  { id: 'value', label: t('对用户的价值', 'Value for Users') },
  { id: 'roles', label: t('角色记忆空间', 'Role Memory Spaces') },
  { id: 'architecture', label: t('认知架构', 'Cognitive Architecture') },
  { id: 'scenarios', label: t('使用场景', 'Use Cases') },
  { id: 'why', label: t('为什么选 DBay', 'Why DBay') },
  { id: 'quickstart', label: t('快速开始', 'Quick Start') },
  { id: 'how-it-works', label: t('工作原理', 'How It Works') },
  { id: 'one-llm', label: 'ONE LLM' },
  { id: 'tools', label: t('智能体工具', 'Agent Tools') },
  { id: 'config', label: t('配置参考', 'Config') },
  { id: 'compare', label: t('能力一览', 'Capabilities') },
])

const painPoints = computed(() => [
  {
    key: 'tokenBloat',
    title: t('Markdown 文件全量加载，Token 开销大、响应慢', 'Markdown files load in full — high token cost, slow responses'),
    desc: t('OpenClaw 每次对话都把 MEMORY.md 文件全量加载到上下文中（默认上限约 50K Token）。记忆越多文件越大，上下文越臃肿——Token 成本线性增长，LLM 响应明显变慢。', 'OpenClaw loads all MEMORY.md files into context every conversation (default ~50K token limit). More memories = bigger files = bloated context — token costs grow linearly and LLM responses slow down.'),
    solution: t('DBay 不加载文件，而是通过向量语义检索按需召回最相关的记忆片段。同等信息量下，注入上下文仅为原始 Markdown 的 1/10，响应速度更快，Token 成本更低。', 'DBay doesn\'t load files — it retrieves only the most relevant memory fragments via semantic search. Same information at 1/10 the tokens, faster responses and lower cost.'),
  },
  {
    key: 'cantUnderstand',
    title: t('只能记住，不能理解', 'Can remember, can\'t understand'),
    desc: t('OpenClaw 的记忆是扁平文本——它会存下「我喜欢 TypeScript」和「我在学 Rust」，但永远不会发现「这个用户每半年就尝试一门新语言，且总是从系统级语言开始」。', 'OpenClaw\'s memory is flat text — it stores "I like TypeScript" and "I\'m learning Rust" but never discovers "this user tries a new language every 6 months, always starting with systems languages."'),
    solution: t('DBay 的 Digest 定期反思所有记忆，提取行为特征（Traits），带有置信度分数、强化次数和首次观测时间。记忆从被动存储进化为主动洞察。', 'DBay\'s Digest periodically reflects on all memories, extracting behavioral traits with confidence scores, reinforcement counts, and first-observed timestamps. Memory evolves from passive storage to active insight.'),
  },
  {
    key: 'compactionDegrades',
    title: t('Context compaction 导致记忆降级', 'Context compaction degrades memory'),
    desc: t('长对话中，OpenClaw 压缩并丢弃旧上下文——这是架构层面的硬限制。普通外部记忆存储的检索质量完全依赖向量相似度，容易遗漏关键上下文。', 'In long conversations, OpenClaw compresses and discards old context — a hard architectural limit. Generic external memory stores rely solely on vector similarity, easily missing critical context.'),
    solution: t('DBay 提供三路召回：向量语义搜索 + 知识图谱关系遍历 + 上下文推理。当用户问「上次我的项目进展如何」，它不只匹配关键词——还能通过实体链找到相关的人、技术栈和时间线。', 'DBay provides three-path retrieval: vector semantic search + knowledge graph traversal + contextual reasoning. When a user asks "how was my project last time," it doesn\'t just match keywords — it traverses entity chains to find related people, tech stacks, and timelines.'),
  },
  {
    key: 'noStructure',
    title: t('记忆是孤立数据点，没有结构', 'Memories are isolated data points, no structure'),
    desc: t('OpenClaw 的 .md 文件是扁平文本。大多数记忆方案只做到事实提取，依然是扁平的。DBay 把记忆组织成 4 层认知结构：', 'OpenClaw\'s .md files are flat text. Most memory solutions stop at fact extraction — still flat. DBay organizes memories into a 4-layer cognitive structure:'),
    layers: [
      'Facts（持久属性）：「用户是后端工程师，擅长 Python」',
      'Episodes（时间事件）：「3月5日重构了认证模块，感觉很有成就感」',
      'Triples（实体关系）：User --works-on--> AuthModule --uses--> OAuth2',
      'Traits（行为模式）：「用户在重构任务中表现最佳，偏好从架构层面思考」',
    ],
    solution: t('这不是存储优化——是记忆的认知层次不同。', 'This is not a storage optimization — it\'s a fundamentally different cognitive hierarchy.'),
  },
])

const values = computed(() => [
  {
    key: 'smarter',
    title: t('Agent 越用越懂你', 'Agent gets smarter the more you use it'),
    desc: t('普通 OpenClaw 智能体第 100 次对话和第 1 次的理解深度相同。接入 DBay 后，智能体不仅积累事实，还从事实中提炼行为模式。用得越久，理解越深，回答越精准。', 'A plain OpenClaw agent has the same understanding depth on the 100th conversation as the 1st. With DBay, the agent accumulates facts and distills behavioral patterns. The longer you use it, the deeper it understands you.'),
    example: t('特征：「用户在收到技术反馈后需要休息缓冲，之后能更高效地解决问题」（置信度 0.85，已强化 3 次）', 'Trait: "User needs a buffer period after receiving technical feedback, then solves problems more efficiently" (confidence 0.85, reinforced 3×)'),
  },
  {
    key: 'crossContext',
    title: t('跨会话、跨 Agent 的连贯记忆', 'Coherent memory across sessions and agents'),
    desc: t('你在编程智能体里提到「最近压力大」，在日程智能体里频繁取消会议。单独看都是孤立事件。DBay 的 digest 能跨这些记忆发现：「用户近两周处于高压状态，社交回避倾向增强」。这种跨上下文的洞察，Markdown 文件做不到。', 'You mention "feeling stressed" in the coding agent and cancel meetings in the calendar agent. Individually they\'re isolated events. DBay\'s digest discovers across these memories: "user has been under high stress for two weeks, showing increased social avoidance." This cross-context insight is impossible with Markdown files.'),
    example: '',
  },
  {
    key: 'deepRecall',
    title: t('不只回答「你说过什么」，还能回答「你是什么样的人」', 'Not just "what you said" but "who you are"'),
    desc: t('没有 DBay：recall("我的编程习惯") 返回你提过的具体事实。有了 DBay：同样的查询还会返回 Traits——持久的行为模式，比如「偏好函数式风格，先写测试再写代码，遇到复杂问题先画架构图」。这让智能体从「带记忆的工具」变成「理解你的助手」。', 'Without DBay: recall("my coding habits") returns specific facts you\'ve mentioned. With DBay: the same query also returns Traits — persistent behavioral patterns like "prefers functional style, writes tests first, draws architecture diagrams for complex problems." This transforms the agent from "a tool with memory" to "an assistant that understands you."'),
    example: 'recall("我的编程习惯") → 事实 + 特征：「偏好组合优于继承，小 PR 重构，避免过早优化」',
  },
  {
    key: 'selfMaintain',
    title: t('记忆质量自动维护', 'Memory quality self-maintains'),
    desc: t('OpenClaw 用户常常抱怨 MEMORY.md 膨胀、信息过时、需要手动清理。DBay 的特征系统内建生命周期管理——置信度衰减、矛盾计数、重要性阈值。过时的模式自然降权，被验证的模式自动增强。记忆自我维护，无需手动干预。', 'OpenClaw users frequently complain about MEMORY.md bloat, stale information, and manual cleanup. DBay\'s trait system has built-in lifecycle management — confidence decay, contradiction counting, importance thresholds. Stale patterns naturally diminish; validated patterns auto-strengthen. Memory self-maintains with no manual intervention.'),
    example: '',
  },
  {
    key: 'emotion',
    title: t('情绪和时间维度', 'Emotional and temporal dimensions'),
    desc: t('OpenClaw 的记忆没有情绪标注和时间感知。DBay 的 Episodes 携带情绪元数据（效价、唤醒度、情绪标签）和时间表达。智能体不仅知道「用户做了什么」，还知道「用户当时的感受」。这对陪伴型和教练型智能体是关键差异化能力。', 'OpenClaw\'s memory has no emotional annotation or temporal awareness. DBay Episodes carry emotional metadata (valence, arousal, emotion tags) and temporal expressions. The agent knows not just "what the user did" but "how they felt doing it." This is a key differentiator for companion and coaching agents.'),
    example: t('事件：「重构了 auth 模块」→ 情绪：有成就感（效价 0.7，唤醒度 0.5）', 'Episode: "Refactored auth module" → Emotion: sense of achievement (valence 0.7, arousal 0.5)'),
  },
])

const roleColors: Record<string, string> = {
  family: '#c67d3a', work: '#2a4d6a', coding: '#2d6a4f',
  content: '#9a5b25', trading: '#0369a1', coach: '#9a5b25', custom: '#64748b',
}

const roles = computed(() => [
  { key: 'family', icon: 'family', title: t('家庭管家', 'Family Manager'), desc: t('家人口味、孩子学业、作息规律、采购清单、健康提醒', 'Family preferences, children\'s studies, daily schedules, shopping lists, health reminders') },
  { key: 'work', icon: 'work', title: t('工作助理', 'Work Assistant'), desc: t('同事关系、项目状态、会议决策、截止日期、沟通风格', 'Colleague relationships, project status, meeting decisions, deadlines, communication style') },
  { key: 'coding', icon: 'coding', title: t('编程伙伴', 'Coding Partner'), desc: t('技术栈、架构决策、代码规范、调试经验、部署配置', 'Tech stack, architecture decisions, code standards, debugging experience, deployment config') },
  { key: 'content', icon: 'content', title: t('内容创作', 'Content Creator'), desc: t('写作风格、发布频率、受众画像、过往内容、热点偏好', 'Writing style, publishing frequency, audience profile, past content, trending preferences') },
  { key: 'trading', icon: 'trading', title: t('交易助手', 'Trading Assistant'), desc: t('预算范围、品牌偏好、价格敏感度、历史交易、比价策略', 'Budget range, brand preferences, price sensitivity, transaction history, comparison strategies') },
  { key: 'coach', icon: 'coach', title: t('生活教练', 'Life Coach'), desc: t('情绪模式、压力因素、运动习惯、睡眠规律、个人目标', 'Emotion patterns, stress factors, exercise habits, sleep patterns, personal goals') },
  { key: 'custom', icon: 'custom', title: t('自定义角色', 'Custom Role'), desc: t('从空白开始，完全自定义记忆策略', 'Start from blank, fully customize memory strategy') },
])

const archLayers = computed(() => [
  { key: 'fact', label: 'Facts', type: t('持久属性', 'Persistent Attributes'), example: t('用户是后端工程师，擅长 Python', 'User is a backend engineer, proficient in Python') },
  { key: 'episode', label: 'Episodes', type: t('时间事件 + 情绪', 'Temporal Events + Emotion'), example: t('3月5日重构了认证模块，感觉很有成就感', 'Mar 5 refactored auth module, felt a strong sense of achievement') },
  { key: 'graph', label: 'Knowledge Graph', type: t('实体关系', 'Entity Relations'), example: 'User --works-on--> AuthModule --uses--> OAuth2' },
  { key: 'trait', label: 'Traits', type: t('行为模式', 'Behavioral Patterns'), example: t('用户在重构任务中表现最佳，偏好从架构层面思考', 'User performs best on refactoring tasks, prefers thinking from the architecture level') },
])

const scenarios = computed(() => [
  { key: 'companion', title: t('长期陪伴型智能体', 'Long-term Companion Agent'), desc: t('与你共同成长数月、数年的个人 AI 助手。', 'A personal AI assistant that grows with you over months and years.'), why: t('需要行为特征和模式洞察，而不只是事实存储', 'Requires behavioral traits and pattern insights, not just fact storage') },
  { key: 'codeCoach', title: t('编程教练 / 导师', 'Coding Coach / Mentor'), desc: t('追踪技能成长，发现学习模式，调整教学风格。', 'Tracks skill growth, discovers learning patterns, adjusts teaching style.'), why: t('需要检测技能进步和偏好的学习方式', 'Needs to detect skill progression and preferred learning approaches') },
  { key: 'teamAgent', title: t('多智能体团队记忆', 'Multi-agent Team Memory'), desc: t('多个智能体共享记忆，具备隔离和知识图谱。', 'Multiple agents share memory with isolation and knowledge graph support.'), why: t('需要多租户隔离和实体关系推理', 'Requires multi-tenant isolation and entity relationship reasoning') },
  { key: 'wellness', title: t('心理健康 / 日记智能体', 'Wellness / Journal Agent'), desc: t('追踪情绪模式，发现压力触发因素，建议干预措施。', 'Tracks emotional patterns, discovers stress triggers, suggests interventions.'), why: t('需要情绪元数据和对行为趋势的反思', 'Requires emotional metadata and reflection on behavioral trends') },
])

const whyItems = computed(() => [
  { key: 'digest', title: t('自动提炼特征', 'Auto-distill Traits'), desc: t('从记忆中发现行为模式——习惯、偏好、情绪倾向。你的智能体会越用越懂你。', 'Discover behavioral patterns from memory — habits, preferences, emotional tendencies. Your agent gets smarter the more you use it.') },
  { key: 'oneLlm', title: 'ONE LLM 模式', desc: t('复用 OpenClaw 自带的模型做记忆提取，零额外 LLM 成本，提取质量一致。', 'Reuse OpenClaw\'s own model for memory extraction. Zero extra LLM cost, consistent extraction quality.') },
  { key: 'tripleMemory', title: t('四层记忆', 'Four-layer Memory'), desc: t('事实 + 事件（含情绪元数据）+ 知识图谱 + 特征。不是扁平存储，而是结构化理解。', 'Facts + Episodes (with emotional metadata) + Knowledge Graph + Traits. Not flat storage — structured understanding.') },
])

const howItWorks = computed(() => [
  { key: 'recall', phase: t('回复前', 'Before reply'), title: t('自动召回', 'Auto-Recall'), desc: t('回复前，智能体搜索相关的事实、事件和行为特征，自动注入到提示词上下文中。', 'Before replying, the agent searches for relevant facts, episodes, and behavioral traits and auto-injects them into the prompt context.') },
  { key: 'capture', phase: t('回复后', 'After reply'), title: t('自动捕获', 'Auto-Capture'), desc: t('回复后，新信息被存储。ONE LLM 模式下，OpenClaw 自带的模型提取事实、事件和知识图谱——无需额外 API 调用。', 'After replying, new information is stored. In ONE LLM mode, OpenClaw\'s own model extracts facts, episodes, and knowledge graph entries — no extra API calls.') },
  { key: 'digest', phase: t('会话结束', 'Session end'), title: t('自动提炼', 'Auto-Digest'), desc: t('会话结束时，分析积累的记忆发现行为模式。这些成为持久的特征洞察，提升未来的召回质量。', 'At session end, accumulated memories are analyzed to discover behavioral patterns. These become persistent trait insights that improve future recall quality.') },
])

const agentTools = computed(() => [
  { name: 'recall', desc: t('用自然语言搜索记忆——返回事实、事件和行为特征', 'Search memory with natural language — returns facts, episodes and behavioral traits') },
  { name: 'store', desc: t('显式存储信息到长期记忆', 'Explicitly store information to long-term memory') },
  { name: 'digest', desc: t('触发对近期记忆的行为模式分析', 'Trigger behavioral pattern analysis on recent memories') },
  { name: 'list', desc: t('浏览存储的记忆，可按类型过滤', 'Browse stored memories, filterable by type') },
  { name: 'forget', desc: t('按 ID 删除指定记忆', 'Delete a specific memory by ID') },
])

const configRows = computed(() => [
  { param: 'apiKey', type: 'string', default: '-', desc: t('DBay API Key (dbay_sk_...)', 'DBay API Key (dbay_sk_...)') },
  { param: 'baseUrl', type: 'string', default: 'https://api.dbay.cloud', desc: t('API 基础地址', 'API base URL') },
  { param: 'autoRecall', type: 'boolean', default: 'true', desc: t('每次回复前注入记忆', 'Inject memories before each reply') },
  { param: 'autoCapture', type: 'boolean', default: 'true', desc: t('每次回复后存储记忆', 'Store memories after each reply') },
  { param: 'autoDigest', type: 'string', default: 'session-end', desc: t('何时发现行为模式（"off" 或 "session-end"）', 'When to discover patterns ("off" or "session-end")') },
  { param: 'topK', type: 'integer', default: '10', desc: t('每轮最多召回的记忆数', 'Max memories recalled per turn') },
  { param: 'includeTraits', type: 'boolean', default: 'true', desc: t('召回结果中包含行为特征', 'Include behavioral traits in recall results') },
])

const capabilities = computed(() => [
  t('自动召回', 'Auto-Recall'),
  t('自动捕获', 'Auto-Capture'),
  t('自动提炼（特征洞察）', 'Auto-Digest (Trait Insights)'),
  t('ONE LLM 模式（零额外成本）', 'ONE LLM Mode (zero extra cost)'),
  t('知识图谱', 'Knowledge Graph'),
  t('情绪元数据', 'Emotional Metadata'),
  t('睡眠期反思', 'Sleep-period Reflection'),
  t('中文支持', 'Chinese Language Support'),
])
</script>

<style scoped>
.oc-page { min-height: 100vh; background: var(--pub-bg); color: var(--pub-text); }
.oc-inner { max-width: 820px; margin: 0 auto; padding: 48px 24px 80px; }
.back-link { font-size: 13px; color: var(--pub-primary, #9a5b25); text-decoration: none; display: inline-block; margin-bottom: 32px; }

/* Hero */
.oc-hero { margin-bottom: 40px; }
.oc-hero h1 { font-size: 32px; font-weight: 700; margin-bottom: 12px; }
.oc-hero-sub { font-size: 16px; color: var(--pub-text-2); line-height: 1.6; margin-bottom: 20px; max-width: 600px; }
.oc-hero-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.btn-primary-sm { background: var(--pub-primary, #9a5b25); color: #fff; border: none; border-radius: 8px; padding: 8px 20px; font-size: 14px; font-weight: 600; cursor: pointer; text-decoration: none; transition: opacity 0.15s; }
.btn-primary-sm:hover { opacity: 0.9; }
.btn-outline-sm { background: transparent; color: var(--pub-text-2); border: 1px solid var(--pub-border); border-radius: 8px; padding: 8px 20px; font-size: 14px; cursor: pointer; text-decoration: none; }

/* TOC */
.oc-toc { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 48px; padding: 16px; background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; }
.toc-link { font-size: 12px; color: var(--pub-code); text-decoration: none; padding: 3px 8px; border: 1px solid var(--pub-accent-border); border-radius: 4px; background: var(--pub-accent-bg); }
.toc-link:hover { opacity: 0.8; }

/* Sections */
.oc-section { margin-bottom: 56px; scroll-margin-top: 80px; }
.oc-section h2 { font-size: 22px; font-weight: 600; margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px solid var(--pub-border); }
.oc-section-sub { font-size: 14px; color: var(--pub-text-2); margin-bottom: 24px; line-height: 1.6; }

/* Pain Points */
.pain-list { display: flex; flex-direction: column; gap: 16px; }
.pain-card { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 12px; padding: 18px; }
.pain-hd { display: flex; gap: 12px; margin-bottom: 12px; }
.pain-hd h3 { font-size: 14px; font-weight: 600; margin-bottom: 6px; color: var(--pub-text); }
.pain-hd p { font-size: 13px; color: var(--pub-text-2); line-height: 1.6; margin: 0; }
.pain-layers { margin: 8px 0 0; display: flex; flex-direction: column; gap: 2px; }
.pain-solution { display: flex; gap: 10px; align-items: flex-start; padding-top: 12px; border-top: 1px solid var(--pub-border); }
.pain-solution p { font-size: 13px; color: #386b47; margin: 0; line-height: 1.6; }
.icon-x { color: #c6333a; flex-shrink: 0; font-weight: 700; margin-top: 2px; }
.icon-check { color: #386b47; flex-shrink: 0; font-weight: 700; margin-top: 2px; }

/* Value */
.value-list { display: flex; flex-direction: column; gap: 16px; }
.value-card { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 12px; padding: 18px; }
.value-card h3 { font-size: 14px; font-weight: 600; margin-bottom: 8px; color: var(--pub-text); }
.value-card p { font-size: 13px; color: var(--pub-text-2); margin: 0; line-height: 1.6; }
.value-example { margin-top: 10px; background: var(--pub-code-bg); border: 1px solid var(--pub-border); border-radius: 5px; padding: 8px 12px; font-size: 12px; color: var(--pub-code); font-family: monospace; }

/* Roles */
.roles-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.role-card { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 14px; }
.role-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.role-icon-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.role-hd h3 { font-size: 13px; font-weight: 600; margin: 0; }
.role-card p { font-size: 12px; color: var(--pub-text-3); margin: 0; line-height: 1.5; }

/* Architecture */
.arch-list { display: flex; flex-direction: column; gap: 10px; }
.arch-card { display: flex; gap: 16px; align-items: flex-start; background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 14px; }
.arch-num { width: 32px; height: 32px; border-radius: 50%; background: var(--pub-hover); display: flex; align-items: center; justify-content: center; font-size: 11px; font-family: monospace; color: var(--pub-text-3); flex-shrink: 0; }
.arch-body { flex: 1; }
.arch-labels { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.arch-labels h3 { font-size: 14px; font-weight: 600; margin: 0; }
.arch-type { font-size: 11px; background: var(--pub-hover); color: var(--pub-text-4); padding: 2px 8px; border-radius: 4px; }
.arch-footer { margin-top: 12px; text-align: center; font-size: 12px; color: var(--pub-text-4); }

/* Scenarios */
.scenarios-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.scenario-card { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 16px; }
.scenario-card h3 { font-size: 14px; font-weight: 600; margin-bottom: 6px; }
.scenario-card p { font-size: 13px; color: var(--pub-text-2); margin: 0 0 8px; line-height: 1.5; }
.scenario-why { font-size: 12px; color: #386b47 !important; margin: 0 !important; }

/* Why */
.why-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.why-card { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 16px; }
.why-card h3 { font-size: 14px; font-weight: 600; margin-bottom: 8px; }
.why-card p { font-size: 13px; color: var(--pub-text-2); margin: 0; line-height: 1.5; }

/* Tabs */
.tabs { border: 1px solid var(--pub-border); border-radius: 8px; overflow: hidden; }
.tab-bar { display: flex; background: var(--pub-hover); border-bottom: 1px solid var(--pub-border); }
.tab-btn { padding: 10px 20px; font-size: 13px; font-weight: 500; background: transparent; border: none; cursor: pointer; color: var(--pub-text-3); }
.tab-btn.active { background: var(--pub-surface); color: var(--pub-code); border-bottom: 2px solid var(--pub-code); }
.tab-content { padding: 20px; background: var(--pub-surface); }
.step-label { font-size: 13px; color: var(--pub-text-2); margin: 0 0 8px; font-weight: 500; }
.tip-text { font-size: 13px; color: var(--pub-text-3); margin-top: 12px; }

/* How It Works */
.how-list { display: flex; flex-direction: column; gap: 12px; }
.how-card { display: flex; gap: 16px; align-items: flex-start; background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 16px; }
.how-card h3 { font-size: 14px; font-weight: 600; margin-bottom: 4px; }
.how-card p { font-size: 13px; color: var(--pub-text-2); margin: 0; line-height: 1.6; }
.phase-badge { font-size: 11px; background: var(--pub-code-bg); color: var(--pub-code); padding: 3px 10px; border-radius: 4px; white-space: nowrap; flex-shrink: 0; font-family: monospace; }

/* ONE LLM */
.one-llm-box { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 24px; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 6px; }
.mono-lg { font-family: monospace; font-size: 15px; font-weight: 600; }
.mono-gray { font-family: monospace; font-size: 13px; color: var(--pub-text-3); }
.mono-green { font-family: monospace; font-size: 14px; font-weight: 700; color: #386b47; }
.arrow { font-size: 16px; color: var(--pub-text-4); }

/* Tools */
.tools-list { display: flex; flex-direction: column; gap: 8px; }
.tool-row { display: flex; gap: 16px; align-items: flex-start; background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 12px 16px; }
.tool-row code { font-size: 13px; color: #386b47; font-family: monospace; flex-shrink: 0; width: 140px; }
.tool-row p { font-size: 13px; color: var(--pub-text-2); margin: 0; line-height: 1.5; }

/* Config */
.config-table { border: 1px solid var(--pub-border); border-radius: 8px; overflow: hidden; font-size: 13px; }
.config-row { display: grid; grid-template-columns: 150px 80px 140px 1fr; gap: 1px; background: var(--pub-border); }
.config-row.header > * { background: var(--pub-hover); color: var(--pub-text-4); font-size: 11px; font-weight: 600; text-transform: uppercase; }
.config-row > * { background: var(--pub-surface); padding: 9px 12px; }
.config-row code { font-family: monospace; color: #386b47; background: var(--pub-surface); padding: 9px 12px; }
.type-text { font-family: monospace; color: var(--pub-text-4); }

/* Capabilities */
.caps-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.cap-item { display: flex; align-items: center; gap: 10px; background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 6px; padding: 10px 14px; font-size: 13px; color: var(--pub-text-2); }
.caps-footer { margin-top: 24px; text-align: center; }
.caps-tagline { font-size: 16px; font-weight: 600; color: var(--pub-text); margin-bottom: 6px; }
.caps-sub { font-size: 13px; color: var(--pub-text-3); }

/* CTA */
.oc-cta { text-align: center; padding: 48px 0 0; border-top: 1px solid var(--pub-border); }
.oc-cta h2 { font-size: 24px; font-weight: 600; margin-bottom: 10px; }
.oc-cta p { font-size: 14px; color: var(--pub-text-2); margin-bottom: 20px; }
.btn-primary-lg { background: var(--pub-primary, #9a5b25); color: #fff; border: none; border-radius: 8px; padding: 12px 32px; font-size: 15px; font-weight: 600; cursor: pointer; text-decoration: none; transition: opacity 0.15s; }
.btn-primary-lg:hover { opacity: 0.9; }

/* Common */
.code-block { background: #1e293b; border: none; border-radius: 8px; padding: 14px 16px; font-size: 12px; color: #a5f3fc; overflow-x: auto; margin: 0 0 16px; font-family: monospace; white-space: pre; }
.mono-sm { font-size: 12px; font-family: monospace; color: var(--pub-text-3); }

@media (max-width: 768px) {
  .roles-grid { grid-template-columns: 1fr 1fr; }
  .why-grid { grid-template-columns: 1fr; }
  .scenarios-grid { grid-template-columns: 1fr; }
  .caps-grid { grid-template-columns: 1fr; }
  .config-row { grid-template-columns: 130px 70px 1fr; }
  .config-row > *:nth-child(3) { display: none; }
}
@media (max-width: 480px) {
  .roles-grid { grid-template-columns: 1fr; }
}
</style>
