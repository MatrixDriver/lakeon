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
            '不是一个被动的 KV 存储。DBay Memory 每晚 digest 最近的对话，提炼出你的开发偏好、架构倾向、决策风格。几天后打开 Memory 面板，你会看到它自动积累出的"你是谁"。跨 Agent 共享——无论你换到哪个 Agent，它都还记得。',
            "Not a passive KV store. DBay Memory digests your conversations every night and distills your coding preferences, architectural leanings, and decision style. A few days in, open the Memory panel and you'll see it's quietly written down who you are. Shared across agents — whichever one you switch to, it still knows you."
          ) }}
        </p>
        <p class="ppage-manifesto-lede">
          {{ t(
            '反思成立的前提是服务端能读你的对话——这件事我们不绕着说。想让 DBay 读不到的那部分，标记为"私密"：客户端加密、服务端只看到密文、不参与 digest。秘钥、密码、以及你说"记住一件私事"的内容会自动走这条路。选择权是你的。',
            "For reflection to work, the server has to read your conversations — we don't dance around that. The parts you don't want DBay to read, you mark as \"private\": encrypted on your device, ciphertext-only on the server, left out of the digest. Keys, passwords, and anything you say \"remember this privately\" about take that path automatically. The choice is yours."
          ) }}
        </p>
      </div>
    </section>

    <!-- 02 · Two kinds of memory -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('两种记忆', 'Two kinds of memory') }}</h2>

        <div class="mm-dual">
          <article class="mm-dual-card">
            <div class="mm-dual-num">01</div>
            <h3 class="mm-dual-title">{{ t('你和 DBay 都知道的', "The ones you and DBay both know") }}</h3>
            <p class="mm-dual-body">
              {{ t(
                '大部分记忆。DBay 每晚读它、整合它、提炼你的思维方式。存在你的隔离数据库里，不跨账户聚合，不做训练素材，不向第三方分发——但 DBay 能读到它，这是反思成立的前提。',
                "Most memories. DBay reads them every night, weaves them together, and distills how you think. They live in your isolated database — never aggregated across accounts, never used for training, never shared with third parties — but DBay can read them. That's what makes reflection possible."
              ) }}
            </p>
          </article>

          <article class="mm-dual-card">
            <div class="mm-dual-num">02</div>
            <h3 class="mm-dual-title">{{ t('你一个人知道的', 'The ones only you know') }}</h3>
            <p class="mm-dual-body">
              {{ t(
                '标记为"私密"的条目。客户端基于公私钥的信封加密，服务端只看到密文。embedding 在你本地算好一起上传，所以这些条目依然能被语义搜索，但服务端无法阅读内容，也不参与 digest。即便数据库被整库拖走，也还原不了。',
                'Entries marked "private." Encrypted on your device with envelope encryption over a public/private key pair — the server only ever sees ciphertext. Embeddings are computed locally and uploaded alongside, so private entries still show up in semantic search, but the server can\'t read their content and they don\'t participate in the digest. Even a full database exfiltration reveals nothing.'
              ) }}
            </p>
          </article>
        </div>

        <p class="mm-dual-note">
          {{ t(
            'dbay-mcp 会自动把秘钥类内容（API key · token · password · private key）识别为私密。你在对话里说"记住一件私事"也会触发——Agent 的 LLM 看到这类意图会主动带上私密标记。',
            'dbay-mcp automatically flags secrets (API keys, tokens, passwords, private keys) as private. Saying "remember this privately" in conversation also triggers it — the agent\'s LLM sees that intent and marks the entry on its way in.'
          ) }}
        </p>
        <p class="mm-dual-note mm-dual-tradeoff">
          <em>{{ t(
            'trade-off 明说：私密条目不进 digest。如果你把所有东西都标为私密，反思洞察会稀疏——选一个和自己的安全边界相匹配的比例。',
            "A trade-off we won't hide: private entries don't feed the digest. If you mark everything private, the reflections will be thin — pick a ratio that matches your own threshold."
          ) }}</em>
        </p>
      </div>
    </section>

    <!-- 03 · Scene -->
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
              <li>{{ t('每晚 digest 整合最近对话 · 抽出 traits', 'Nightly digest weaves recent conversations together and extracts traits') }}</li>
              <li>{{ t('几天后自动出现"反思洞察" · 你是谁', 'A few days in, "reflection insights" appear — who you are') }}</li>
              <li>{{ t('召回时带回条目，不是整段历史 · token 成本 50–70% ↓', 'Recall returns entries, not raw history — token cost drops 50–70%') }}</li>
              <li>{{ t('跨 Agent · 跨 session · 跨机器 · 跨项目', 'Across agents, sessions, machines, projects') }}</li>
              <li>{{ t('分级存储 · 私密条目客户端加密，服务端看不见', 'Tiered store · private entries are client-encrypted, invisible to the server') }}</li>
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
              <span class="ppage-stack-name">{{ t('③ 分级存储 · tiered', '③ Tiered store') }}</span>
              <span class="ppage-stack-note">{{ t('普通明文 · 私密客户端加密', 'Plain or client-encrypted') }}</span>
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
              <strong>{{ t('分级存储', 'Tiered store') }}</strong>
              {{ t(
                '。普通条目以明文进入你的隔离数据库——服务端读它、embed 它、每晚 digest 它。标记为',
                '. Normal entries land in your isolated database in plaintext — the server reads them, embeds them, and digests them every night. Entries marked'
              ) }}
              <strong>{{ t('私密', 'private') }}</strong>
              {{ t(
                '的条目走另一条路：基于公私钥的信封加密在你的设备上完成，服务端只看到密文，不阅读内容，不参与 digest。客户端在加密前算好 embedding 一起上传，所以私密条目依然能被语义搜索。秘钥类内容会被 dbay-mcp 自动识别为私密——这就是为什么你敢把 OPENAI_API_KEY 写进记忆层，它从头到尾没以明文的形态出现在服务端。',
                " take a different path: envelope encryption with a public/private key pair happens on your device. The server only ever sees ciphertext, can't read the content, and doesn't digest them. Embeddings are computed locally before encryption, so private entries still work in semantic search. dbay-mcp automatically marks secrets as private — which is why you can drop OPENAI_API_KEY into the memory layer without flinching. It never appears as plaintext on the server, start to finish."
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
                '你也可以把秘钥写进去。API key、数据库连接串、token —— dbay-mcp 自动识别为私密，客户端加密，服务端只看到密文：',
                "You can also drop secrets in. API keys, database URLs, tokens — dbay-mcp auto-marks them as private, encrypts them on your device, and the server only sees ciphertext:"
              ) }}
            </p>
            <code class="ppage-code">$ dbay memory set OPENAI_API_KEY sk-...
Marked private. Encrypted locally. Server stored ciphertext only.</code>
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
            <div class="ppage-num-label">{{ t('私密条目密钥', 'private-entry key') }}</div>
            <p class="ppage-num-note">
              {{ t('密码 + device salt + base salt，客户端派生。服务端拿不到。', 'Password + device salt + base salt, derived on your device. The server never has it.') }}
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

.mm-dual {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(24px, 3vw, 48px);
  margin-bottom: clamp(24px, 3vw, 40px);
}

.mm-dual-card {
  padding: clamp(28px, 3vw, 40px) 0 0;
  border-top: 1px solid var(--c-border);
}

.mm-dual-num {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.12em;
  color: var(--c-accent-text);
  margin-bottom: var(--space-lg);
}

.mm-dual-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(22px, 2.2vw, 28px);
  line-height: 1.2;
  letter-spacing: -0.01em;
  color: var(--c-primary);
  margin: 0 0 var(--space-md);
}

.mm-dual-body {
  font-family: var(--font-sans);
  font-size: 15px;
  line-height: 1.7;
  color: var(--c-text-2);
  margin: 0;
}

.mm-dual-note {
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.7;
  color: var(--c-text-2);
  max-width: 72ch;
  margin: 0 0 var(--space-md);
}

.mm-dual-tradeoff {
  color: var(--c-text-3);
  font-size: 13px;
}

.mm-dual-tradeoff em {
  font-style: italic;
  font-family: var(--font-display);
}

@media (max-width: 900px) {
  .mm-dual {
    grid-template-columns: 1fr;
  }
}

.ppage-num-unit {
  font-size: 0.45em;
  color: var(--c-text-3);
  margin-left: 0.08em;
  font-weight: 400;
}
</style>
