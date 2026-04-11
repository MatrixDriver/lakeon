<template>
  <div class="ppage">
    <!-- 01 · Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <router-link to="/" class="ppage-back">← {{ t('回到首页', 'Back to home') }}</router-link>
        <div class="ppage-eyebrow">{{ t('能力 03 · 记忆库', 'Capability 03 · Memory') }}</div>

        <h1 class="ppage-manifesto-title">
          <span class="mm-title-a">{{ t('Agent 的长期记忆，', "The agent's long-term memory —") }}</span>
          <span class="mm-title-b">{{ t('会自己反思你是谁。', "it thinks about who you are.") }}</span>
        </h1>

        <p class="ppage-manifesto-lede">
          {{ t(
            '不是一个被动的 KV 存储。DBay Memory 每晚 digest 最近的对话，抽出你的开发偏好、架构倾向、决策风格。几天后打开 Memory 面板，你会看到它自动积累出的"你是谁"。跨 Agent 共享，本地加密，即使数据库被拖走也无法还原。',
            "Not a passive KV store. DBay Memory digests your conversations every night, extracts your coding preferences, architectural leanings, and decision style. A few days in, open the Memory panel and you'll see it's quietly written down who you are. Shared across agents, locally encrypted — even a full database leak stays unreadable."
          ) }}
        </p>
      </div>
    </section>

    <!-- 02 · Scene -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('一个场景', 'The situation') }}</h2>

        <div class="ppage-scene">
          <p>
            {{ t(
              '你和 Claude Code 说过无数次你偏好 TypeScript 严格模式、你不用 semicolons、你在这个团队用 camelCase。但每一次新 session 开始，它都忘了。',
              "You've told Claude Code, dozens of times, that you prefer strict TypeScript, no semicolons, camelCase on this team. Every new session it forgets."
            ) }}
          </p>
          <p>
            {{ t(
              '你想"我需要一个记忆层"，花了半个下午研究 Mem0、Zep、Letta。每一个都要接一套 API，每一个都停在 KV 检索，每一个都不跨 Agent 共享，每一个都不敢让你存 API key。',
              "You decide you need a memory layer. You burn half an afternoon reading Mem0, Zep, Letta. Every one wants its own API. Every one stops at KV retrieval. None of them share across agents. None of them let you store an API key with a straight face."
            ) }}
          </p>
          <p>
            <em>{{ t(
              '他们解决的是"存储"，你真正需要的是"理解"。',
              "They solve storage. What you actually need is understanding."
            ) }}</em>
          </p>
        </div>
      </div>
    </section>

    <!-- 03 · Contrast -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('大部分记忆层停在哪里', 'Where most memory layers stop') }}</h2>

        <div class="ppage-contrast">
          <div class="ppage-contrast-col is-faded">
            <h3>{{ t('通用 KV / 向量记忆层', 'Generic KV / vector memory layers') }}</h3>
            <ul>
              <li>{{ t('存起来，再用 cosine 找回来', 'Store it, cosine-search it back') }}</li>
              <li>{{ t('没有 "reflection" 概念 · 它永远是一堆碎片', 'No "reflection" concept — it stays a pile of fragments') }}</li>
              <li>{{ t('每次 recall 都把所有相关碎片塞进 context · token 爆炸', 'Every recall stuffs matching fragments into context — tokens explode') }}</li>
              <li>{{ t('单 Agent · 单 session · 不跨工具共享', 'Single agent, single session, not shared across tools') }}</li>
              <li>{{ t('明文存储 · 你不敢把 API key 写进去', 'Plaintext storage — you\'d never drop an API key in') }}</li>
            </ul>
          </div>
          <div class="ppage-contrast-col is-accent">
            <h3>DBay Memory</h3>
            <ul>
              <li>{{ t('每晚 digest 反刍最近对话 · 抽出 traits', 'Nightly digest walks recent conversations and extracts traits') }}</li>
              <li>{{ t('几天后自动出现"反思洞察" · 你是谁', 'A few days in, "reflection insights" appear — who you are') }}</li>
              <li>{{ t('召回时带回条目，不是整段历史 · token 成本 50–70% ↓', 'Recall returns entries, not raw history — token cost drops 50–70%') }}</li>
              <li>{{ t('跨 Agent · 跨 session · 跨机器 · 跨项目', 'Across agents, sessions, machines, projects') }}</li>
              <li>{{ t('本地加密 · 三因素密钥 · 服务端永远拿不到明文', 'Locally encrypted · three-factor keys · ciphertext-only on the server') }}</li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- 04 · Approach -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('三层做法', 'Three layers, one substrate') }}</h2>

        <div class="ppage-approach">
          <div class="ppage-stack">
            <div class="ppage-stack-layer is-bold">
              <span class="ppage-stack-name">{{ t('① 反思层 · reflection', '① Reflection layer') }}</span>
              <span class="ppage-stack-note">{{ t('每晚 digest 抽 traits', 'Nightly digest pulls traits') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">{{ t('② 召回层 · recall', '② Recall layer') }}</span>
              <span class="ppage-stack-note">{{ t('四路融合 · 带引用', '4-way fusion · with citations') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">{{ t('③ 加密存储 · encrypted', '③ Encrypted store') }}</span>
              <span class="ppage-stack-note">{{ t('三因素密钥 · PBKDF2', 'Three-factor keys · PBKDF2') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">Lakebase + BM25</span>
              <span class="ppage-stack-note">{{ t('同一个 substrate', 'Same substrate') }}</span>
            </div>
          </div>

          <div class="ppage-section-prose">
            <p>
              {{ t('①', '①') }}
              <strong>{{ t('反思层', 'Reflection') }}</strong>
              {{ t(
                '。DBay Memory 每晚跑一次 digest：读当日所有对话，用 LLM 抽出用户偏好、技术倾向、决策风格，把它们沉淀为 traits。三天后，Memory 面板上会出现自动产出的"反思洞察"条目，附置信度分数。',
                ". DBay Memory runs a nightly digest: it reads the day's conversations, asks an LLM to extract preferences, technical leanings, and decision style, and settles them as traits. Three days in, reflection insights show up in the Memory panel with confidence scores."
              ) }}
            </p>
            <p>
              {{ t('②', '②') }}
              <strong>{{ t('召回层', 'Recall') }}</strong>
              {{ t(
                '。Agent 问 "用户偏好什么 runtime?" 时，Memory 返回的不是一堆原始对话片段，而是被反思过的条目 —— 带原对话的引用。LoCoMo 长时记忆基准上召回准确率 82%，同一次测试相比 "全历史塞进 context" 的基线，输入 token 减少 50–70%。',
                ". When an agent asks \"what runtime does the user prefer?\", Memory doesn't return raw conversation fragments. It returns reflected entries with citations back to the original messages. On the LoCoMo long-term memory benchmark, recall accuracy lands at 82%, and input tokens drop 50–70% compared to the \"stuff the whole history in\" baseline."
              ) }}
            </p>
            <p>
              {{ t('③', '③') }}
              <strong>{{ t('加密存储', 'Encrypted store') }}</strong>
              {{ t(
                '。所有记忆条目在',
                '. Every memory entry is encrypted'
              ) }}
              <strong>{{ t('本地', ' locally') }}</strong>
              {{ t(
                '加密后才写入 DBay。密钥通过三因素派生 (密码 + device salt + base salt) + PBKDF2 得到，服务端永远只存密文。即便数据库被整库拖走，也无法在没有原始三因素的情况下还原出明文。这就是为什么你敢把 OPENAI_API_KEY 写进记忆层。',
                ' before it\'s written. The key is derived from three factors (password + device salt + base salt) through PBKDF2. The server never sees plaintext. Even a full database exfiltration stays unreadable without the original three factors. That\'s why you can store OPENAI_API_KEY in the memory layer without flinching.'
              ) }}
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- 05 · Today you can do -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <div class="ppage-today">
          <span class="ppage-today-eyebrow">{{ t('今天你就能做', 'You can do this today') }}</span>
          <h2>{{ t('让 Claude Code 明天就记得你', 'Make Claude Code remember you, starting tomorrow') }}</h2>

          <div class="ppage-today-prose">
            <p>{{ t('把 dbay MCP 装进 Claude Code：', 'Install the dbay MCP into Claude Code:') }}</p>
            <code class="ppage-code">$ claude mcp add --scope user dbay -- python -m dbay_mcp</code>

            <p>{{ t('然后正常用 Claude Code。有事告诉它：', 'Then just use Claude Code as usual. Tell it things:') }}</p>
            <p>
              <em>{{ t(
                '"记住：这个团队偏好 camelCase，不用 semicolons，runtime 是 Bun。"',
                '"Remember: camelCase on this team, no semicolons, runtime is Bun."'
              ) }}</em>
            </p>
            <p>
              {{ t(
                '下次你在另一个项目打开 Claude Code，它还记得。跨机器、跨 repo、跨 session。',
                'Next time you open Claude Code in another project, it still knows. Across machines, repos, and sessions.'
              ) }}
            </p>

            <p>
              {{ t(
                '用三天之后，在 console 的 Memory 面板打开',
                "Three days in, open your Memory panel and switch to the"
              ) }}
              <span class="ppage-code-inline">{{ t('反思洞察', 'Reflection Insights') }}</span>
              {{ t(
                '标签 —— DBay 已经自己从对话里抽出了你的开发模式。这些条目不是你写的，是它读你读出来的。',
                " tab — DBay has already pulled your patterns out of the conversations. You didn't write them. It read you."
              ) }}
            </p>

            <p>
              {{ t(
                '你也可以把秘钥写进去。API key、数据库连接串、token —— 三因素密钥派生，本地加密，服务端永远只看到密文：',
                'You can also drop secrets in. API keys, database URLs, tokens — three-factor key derivation, local encryption, ciphertext on the server:'
              ) }}
            </p>
            <code class="ppage-code">$ dbay memory set OPENAI_API_KEY sk-...
Encrypted locally. Server stored ciphertext only.</code>
          </div>

          <p class="ppage-today-aside">
            {{ t(
              '顺便说一句：这套召回比"把历史都塞进 context"省 50–70% 的输入 token。对你的 API 账单和 Agent 延迟都有可感的差别。',
              "Side note: this recall costs 50–70% fewer input tokens than stuffing the full history into context. Your API bill and your agent latency both get quieter."
            ) }}
          </p>
        </div>
      </div>
    </section>

    <!-- 06 · Hard numbers -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('三个可以被验证的事实', 'Three facts you can verify') }}</h2>

        <div class="ppage-numbers">
          <div class="ppage-num">
            <div class="ppage-num-val">82<span class="ppage-num-unit">%</span></div>
            <div class="ppage-num-label">LoCoMo</div>
            <p class="ppage-num-note">
              {{ t('LoCoMo 长时记忆任务基准上的召回准确率。', 'Recall accuracy on the LoCoMo long-term memory benchmark.') }}
            </p>
          </div>
          <div class="ppage-num">
            <div class="ppage-num-val">50–70<span class="ppage-num-unit">% ↓</span></div>
            <div class="ppage-num-label">{{ t('输入 token', 'input tokens') }}</div>
            <p class="ppage-num-note">
              {{ t('相比把全历史塞进上下文的基线减少的输入 token 数。', 'Reduction versus stuffing the full history into context.') }}
            </p>
          </div>
          <div class="ppage-num">
            <div class="ppage-num-val">3<span class="ppage-num-unit">{{ t(' 因素', '-factor') }}</span></div>
            <div class="ppage-num-label">{{ t('密钥派生', 'key derivation') }}</div>
            <p class="ppage-num-note">
              {{ t('密码 + device salt + base salt，PBKDF2 本地派生。', 'Password + device salt + base salt, PBKDF2 locally.') }}
            </p>
          </div>
        </div>
      </div>
    </section>

    <footer class="ppage-footer">
      <div class="ppage-footer-inner">
        <h3 class="ppage-footer-title">{{ t('继续了解', 'Keep reading') }}</h3>
        <div class="ppage-footer-grid">
          <router-link to="/product/lakebase" class="ppage-footer-card">
            <div class="ppage-footer-card-name">Lakebase</div>
            <div class="ppage-footer-card-tag">{{ t('PostgreSQL · BM25 内核 · 数据湖', 'PostgreSQL · BM25 kernel · data lake') }}</div>
          </router-link>
          <router-link to="/product/knowledge" class="ppage-footer-card">
            <div class="ppage-footer-card-name">Knowledge</div>
            <div class="ppage-footer-card-tag">{{ t('Agent 自己维护的活 wiki', 'A living wiki the agent maintains') }}</div>
          </router-link>
          <router-link to="/product/datalake" class="ppage-footer-card">
            <div class="ppage-footer-card-name">Datalake</div>
            <div class="ppage-footer-card-tag">{{ t('多模态算子 · DAG · zero-copy', 'Multi-modal operators · DAG · zero-copy') }}</div>
          </router-link>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()
</script>

<style scoped>
.mm-title-a,
.mm-title-b {
  display: block;
}

.mm-title-b {
  color: var(--c-accent-text);
}

.ppage-num-unit {
  font-size: 0.45em;
  color: var(--c-text-3);
  margin-left: 0.08em;
  font-weight: 400;
}
</style>
