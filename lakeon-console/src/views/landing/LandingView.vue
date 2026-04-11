<template>
  <div class="landing">
    <!-- ══════════════════════════════════════════
         Screen 01 · Manifesto
         ══════════════════════════════════════════ -->
    <section class="screen screen-01">
      <div class="screen-inner">
        <h1 class="hero-title">
          <span class="hero-line-1">{{ t('Agent 的数据层', 'The data layer for agents') }}</span>
          <span class="hero-line-2">{{ t('不应该是四个产品的拼图。', "shouldn't be a jigsaw of four products.") }}</span>
        </h1>

        <p class="hero-lede">
          {{ t(
            '今天构建一个"记得你、会学习"的 Agent，要拼装四套系统：Postgres 存状态、向量库做检索、批处理框架跑离线反思、再写一层自制记忆。DBay 把这四件事装进同一个 copy-on-write、serverless、多租户的 substrate。',
            'Building a "remembers you, keeps learning" agent today means gluing together four systems: Postgres for state, a vector store for retrieval, a batch framework for offline reflection, and a homegrown memory layer on top. DBay packs all four into a single copy-on-write, serverless, multi-tenant substrate.'
          ) }}
        </p>

        <div class="hero-ctas">
          <button class="cta-primary" @click="startTrial" :disabled="trialLoading">
            {{ trialLoading ? t('创建中…', 'Creating…') : t('开始使用', 'Get started') }}
          </button>
          <router-link to="/architecture" class="cta-ghost">
            {{ t('阅读架构', 'Read the architecture') }} →
          </router-link>
        </div>

        <div class="hero-shortcut">
          <span class="shortcut-label">{{ t('也可以直接把 MCP 加进 Claude Code', 'Or drop the MCP into Claude Code directly') }}</span>
          <code class="shortcut-code">$ claude mcp add --scope user dbay -- python -m dbay_mcp</code>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         Screen 02 · Working state / Learning state
         ══════════════════════════════════════════ -->
    <section class="screen screen-02">
      <div class="screen-inner screen-02-inner">
        <h2 class="section-title">
          {{ t('工作态与学习态', 'Working state and learning state') }}
        </h2>

        <!-- Diagram · text grid -->
        <div class="working-learning-diagram">
          <div class="wl-column wl-working">
            <div class="wl-phase">
              <span class="wl-phase-num">01</span>
              <span class="wl-phase-name">{{ t('Agent 执行时', 'When the agent runs') }}</span>
            </div>
            <div class="wl-nodes">
              <div class="wl-node">Lakebase</div>
              <div class="wl-node">Memory.recall</div>
              <div class="wl-node">Knowledge.search</div>
            </div>
            <div class="wl-caption">{{ t('读写事务 · 召回记忆 · 检索知识', 'Read/write state · Recall memory · Search knowledge') }}</div>
          </div>

          <div class="wl-arrow">
            <div class="wl-arrow-label">{{ t('完成任务后反刍', 'Reflect after each task') }}</div>
            <svg class="wl-arrow-svg" viewBox="0 0 24 80" fill="none" aria-hidden="true">
              <line x1="12" y1="0" x2="12" y2="72" stroke="currentColor" stroke-width="1" stroke-dasharray="3 4"/>
              <path d="M6 66 L12 78 L18 66" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>

          <div class="wl-column wl-learning">
            <div class="wl-phase">
              <span class="wl-phase-num">02</span>
              <span class="wl-phase-name">{{ t('Agent 学习时', 'When the agent learns') }}</span>
            </div>
            <div class="wl-nodes">
              <div class="wl-node">Memory.digest</div>
              <div class="wl-node">Datalake</div>
              <div class="wl-node">Knowledge.curate</div>
            </div>
            <div class="wl-caption">{{ t('反思记忆 · 批处理 · 沉淀知识', 'Digest memory · Batch compute · Curate knowledge') }}</div>
          </div>
        </div>

        <p class="section-prose">
          {{ t(
            'Agent 不是无状态的 API 调用。它们在时间维度上积累认知，又在每次执行时消费这些认知。DBay 让这两件事发生在同一套 substrate 上。',
            'Agents are not stateless API calls. They accumulate understanding over time and consume it on every run. DBay keeps both on a single substrate.'
          ) }}
        </p>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         Screen 03 · Claude Code 已能用 (Developer voice)
         ══════════════════════════════════════════ -->
    <section class="screen screen-03">
      <div class="screen-inner">
        <h2 class="section-title">
          <span class="cc-title-line">{{ t('你的 Claude Code', 'Your Claude Code') }}</span>
          <span class="cc-title-line">{{ t('已经在等 DBay 了。', 'has been waiting for DBay.') }}</span>
        </h2>

        <p class="section-lede dev-voice">
          {{ t(
            '一行命令装上 dbay 的 MCP 和 Skill，Claude Code 立刻获得跨项目长期记忆和你私有知识库的搜索能力。Cursor · Cline · Continue 同样能用。30 秒，不需要改项目代码。',
            'One command installs the dbay MCP and Skill — Claude Code gains cross-project long-term memory and search over your private knowledge. Cursor, Cline, Continue also work. 30 seconds, no project code to change.'
          ) }}
        </p>

        <!-- Scene 1 · remember -->
        <div class="cc-scene">
          <div class="cc-scene-media"><div class="cc-gif-slot">{{ t('GIF 位 · 跨项目召回', 'GIF · cross-project recall') }}</div></div>
          <div class="cc-scene-body">
            <div class="cc-scene-idx">①</div>
            <h3 class="cc-scene-title">{{ t('跨项目记住你的开发习惯', 'Remembers your habits across projects') }}</h3>
            <p class="cc-scene-p">
              <em>"{{ t('记住我在这个团队偏好 camelCase。', 'Remember I prefer camelCase on this team.') }}"</em>
            </p>
            <p class="cc-scene-p">
              {{ t(
                '下次你在另一个项目打开 Claude Code，它还知道。跨机器、跨 repo、跨 session。',
                'Next time you open Claude Code in another project, it still knows. Across machines, repos, sessions.'
              ) }}
            </p>
          </div>
        </div>

        <!-- Scene 2 · share -->
        <div class="cc-scene cc-scene-reverse">
          <div class="cc-scene-media"><div class="cc-gif-slot">{{ t('GIF 位 · 团队共享', 'GIF · team sharing') }}</div></div>
          <div class="cc-scene-body">
            <div class="cc-scene-idx">②</div>
            <h3 class="cc-scene-title">{{ t('团队共享一份开发约定', 'One team, one shared convention') }}</h3>
            <p class="cc-scene-p">
              {{ t(
                'Git commit 规范、code review 清单、命名习惯 — 写进一次，整个团队的 Claude Code 共享。',
                'Git commit rules, review checklists, naming conventions — write them once, the whole team’s Claude Code shares them.'
              ) }}
            </p>
            <p class="cc-scene-p cc-scene-aside">
              {{ t(
                '不再有"新人头两周反复被纠正"。',
                'No more "new hires getting corrected for two weeks straight."'
              ) }}
            </p>
          </div>
        </div>

        <!-- Scene 3 · encrypted secrets -->
        <div class="cc-scene">
          <div class="cc-scene-media"><div class="cc-gif-slot">{{ t('GIF 位 · 加密存储', 'GIF · encrypted vault') }}</div></div>
          <div class="cc-scene-body">
            <div class="cc-scene-idx">③</div>
            <h3 class="cc-scene-title">{{ t('API Key 也可以存进记忆层', 'API keys can live inside the memory layer') }}</h3>
            <p class="cc-scene-p">
              {{ t(
                'OPENAI_API_KEY、DATABASE_URL 这些秘钥常常出现在你和 Agent 的对话里。写进 DBay，三因素密钥派生、PBKDF2 本地加密、服务端永远只能看到密文。数据库被拖走也无法还原。',
                'OPENAI_API_KEY, DATABASE_URL — these secrets end up in your agent conversations anyway. Store them in DBay: three-factor key derivation, PBKDF2 local encryption, the server only ever sees ciphertext. Even a full database leak stays opaque.'
              ) }}
            </p>
            <p class="cc-scene-p cc-scene-aside">
              {{ t(
                '这就是为什么你敢把秘钥交给记忆层。',
                "That's why you'd trust a memory layer with a real secret."
              ) }}
            </p>
          </div>
        </div>

        <!-- Narrative divider · from "you teach it" to "it learns you" -->
        <div class="cc-pivot">
          <div class="cc-pivot-rule"></div>
          <span class="cc-pivot-label">{{ t('它也在学你', 'And it’s learning you') }}</span>
          <div class="cc-pivot-rule"></div>
        </div>

        <!-- Scene 4 · reflection insights (qualitative shift) -->
        <div class="cc-scene cc-scene-reflection cc-scene-reverse">
          <div class="cc-scene-media">
            <div class="cc-reflection-panel">
              <div class="cc-reflection-header">
                <span class="cc-reflection-tab">{{ t('反思洞察', 'Reflection insights') }}</span>
                <span class="cc-reflection-count">4</span>
              </div>
              <div class="cc-reflection-card">
                <p class="cc-reflection-text">
                  {{ t(
                    '频繁使用 DBay API 进行项目访问和管理，倾向于优先保证自动化而非手动文档维护。',
                    'Frequently uses the DBay API for project access and management, preferring automation over manual doc upkeep.'
                  ) }}
                </p>
                <div class="cc-reflection-meta">
                  <span class="cc-reflection-conf">90%</span>
                  <span class="cc-reflection-bar"><span class="cc-reflection-fill" style="width: 90%"></span></span>
                </div>
              </div>
              <div class="cc-reflection-card">
                <p class="cc-reflection-text">
                  {{ t(
                    '在架构设计上倾向于先完成规划再动手，重视成本优化而非堆资源。',
                    'Plans architecture before coding; prioritises cost optimisation over throwing resources at problems.'
                  ) }}
                </p>
                <div class="cc-reflection-meta">
                  <span class="cc-reflection-conf">80%</span>
                  <span class="cc-reflection-bar"><span class="cc-reflection-fill" style="width: 80%"></span></span>
                </div>
              </div>
            </div>
          </div>
          <div class="cc-scene-body">
            <div class="cc-scene-idx">④</div>
            <h3 class="cc-scene-title">{{ t('它还在观察你', "It's watching you, too") }}</h3>
            <p class="cc-scene-p">
              {{ t(
                '三天之后，Memory 面板多了一个「反思洞察」标签。DBay 自己从对话里抽出了你的开发模式。',
                'Three days in, a "Reflection Insights" tab appears in your Memory panel. DBay has pulled your development patterns out of the conversation history on its own.'
              ) }}
            </p>
            <p class="cc-scene-p">
              {{ t(
                '这些洞察不是你告诉它的。是它在每晚的 digest 里自己反思出来的。下一次你问 Claude Code 关于新项目的建议，它知道的不只是你说过什么，还有 — 你是谁。',
                'You didn\'t tell it these things. It reasoned them out during the nightly digest. Next time Claude Code answers a question about a new project, it knows more than what you said — it knows who you are.'
              ) }}
            </p>
            <p class="cc-scene-p cc-scene-aside">
              {{ t(
                '这就是我们说的「学习态」。工作态是你读写它，学习态是它在读你。',
                'This is the "learning state." Working state is you reading and writing. Learning state is the substrate reading you.'
              ) }}
            </p>
          </div>
        </div>

        <!-- Divider -->
        <div class="cc-divider"></div>

        <!-- Token punchline -->
        <div class="cc-punchline">
          <div class="cc-punchline-nums">
            <div class="cc-num cc-num-accent">
              <span class="cc-num-arrow">↓</span>
              <span class="cc-num-val">50–70%</span>
              <span class="cc-num-label">{{ t('输入 token', 'input tokens') }}</span>
            </div>
            <div class="cc-num cc-num-primary">
              <span class="cc-num-arrow">↑</span>
              <span class="cc-num-val">82%</span>
              <span class="cc-num-label">{{ t('召回准确率', 'recall accuracy') }}</span>
            </div>
          </div>
          <p class="cc-punchline-prose">
            {{ t(
              '同一个 LoCoMo 多轮对话基准：比起把完整会话历史塞进上下文，DBay 记忆层只召回相关片段。输入 token 减少 50–70%，召回准确率 82%。少花钱，更准。',
              'On the LoCoMo multi-session benchmark: instead of cramming the full conversation history into context, DBay recalls only the relevant fragments. Input tokens drop by 50–70%; recall accuracy stays at 82%. Spend less, get more right.'
            ) }}
          </p>
        </div>

        <!-- Shell install -->
        <div class="cc-shell">
          <code class="shortcut-code">$ claude mcp add --scope user dbay -- python -m dbay_mcp</code>
          <p class="cc-shell-note">
            {{ t('以 Claude Code 为例。同样支持任何 MCP 兼容的 Agent。', 'Claude Code shown here. Any MCP-compatible agent works the same way.') }}
          </p>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         Screen 04 · Four capabilities (zoom out)
         ══════════════════════════════════════════ -->
    <section class="screen screen-04">
      <div class="screen-inner">
        <div class="zoom-out-intro">
          <h2 class="section-title">
            {{ t('你刚看到了其中两个。', 'You just saw two of them.') }}
          </h2>
          <p class="section-prose">
            {{ t(
              'Memory 和 Knowledge 只是 DBay 四种能力中的两种。全部四种是同一个 substrate 的不同入口。',
              'Memory and Knowledge are two of DBay\'s four capabilities. All four are entry points into the same substrate.'
            ) }}
          </p>
        </div>

        <div class="capabilities-grid">
          <!-- Lakebase -->
          <div class="cap">
            <h3 class="cap-name">Lakebase</h3>
            <ul class="cap-list">
              <li>{{ t('在数据湖上跑的 serverless PostgreSQL', 'Serverless PostgreSQL running on a data lake') }}</li>
              <li>{{ t('Neon copy-on-write + BM25 内核增强', 'Neon copy-on-write + BM25 kernel') }}</li>
              <li>{{ t('时间旅行 · 分支 · 版本', 'Time travel · branching · versioning') }}</li>
            </ul>
            <p class="cap-reveal">
              {{ t('不是 wrapper。我们在 Neon PageServer 里加了 BM25 索引。', 'Not a wrapper. We added BM25 indexing inside the Neon PageServer.') }}
            </p>
            <code class="cap-code">psql ${LAKEBASE_URL}</code>
          </div>

          <!-- Knowledge -->
          <div class="cap">
            <h3 class="cap-name">Knowledge</h3>
            <ul class="cap-list">
              <li>{{ t('Agent 自己维护的活 wiki', 'A living wiki the agent maintains itself') }}</li>
              <li>{{ t('从源文档生成 · 与 wiki 对话', 'Generated from source docs · conversational') }}</li>
              <li>{{ t('知识持续沉淀，不是静态 RAG', 'Knowledge keeps compounding — not static RAG') }}</li>
            </ul>
            <p class="cap-reveal">
              {{ t('Agent 读 wiki 条目，不是 chunk。底层四路融合检索。', 'Agents read wiki entries, not chunks. Four-way hybrid retrieval underneath.') }}
            </p>
            <code class="cap-code">dbay knowledge ask "..."</code>
          </div>

          <!-- Memory -->
          <div class="cap">
            <h3 class="cap-name">Memory</h3>
            <ul class="cap-list">
              <li>{{ t('同内核融合检索 · LoCoMo 82%', 'Same kernel fusion retrieval · LoCoMo 82%') }}</li>
              <li>{{ t('反思洞察自动抽出用户模式', 'Reflection insights, automatically') }}</li>
              <li>{{ t('跨 Agent 共享 · 本地加密存储', 'Shared across agents · locally encrypted') }}</li>
            </ul>
            <p class="cap-reveal">
              {{ t('每晚 digest 反刍对话，几天后自己抽出你的开发模式。', 'Nightly digest walks the conversation; a few days in it knows your patterns.') }}
            </p>
            <code class="cap-code">memory.recall("...")</code>
          </div>

          <!-- Datalake -->
          <div class="cap">
            <h3 class="cap-name">Datalake</h3>
            <ul class="cap-list">
              <li>{{ t('多模态算子库 · DAG 编排', 'Multi-modal operators · DAG orchestration') }}</li>
              <li>{{ t('算子间 zero-copy · shared memory', 'Operators share memory · zero-copy') }}</li>
              <li>{{ t('文本 · 视频 · 音频都可处理', 'Text, video, audio all welcome') }}</li>
            </ul>
            <p class="cap-reveal">
              {{ t('DAG 编译成分布式 Python，算子间 shared memory 不落地。', 'The DAG compiles into a single distributed Python program; operators pass through shared memory, never disk.') }}
            </p>
            <code class="cap-code">ray.submit(job)</code>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         Screen 05 · Hardcore numbers
         ══════════════════════════════════════════ -->
    <section class="screen screen-05">
      <div class="screen-inner">
        <h2 class="section-title-small">
          {{ t('四个可以被验证的数字', 'Four numbers you can verify') }}
        </h2>

        <div class="hard-numbers">
          <div class="hn">
            <div class="hn-val">&lt;3<span class="hn-unit">s</span></div>
            <div class="hn-label">{{ t('冷启动唤醒', 'Cold start') }}</div>
            <p class="hn-note">
              {{ t('任意挂起的 compute pod 唤醒到可查询。', 'Any suspended compute pod wakes up to queryable.') }}
            </p>
          </div>
          <div class="hn">
            <div class="hn-val">82<span class="hn-unit">%</span></div>
            <div class="hn-label">LoCoMo</div>
            <p class="hn-note">
              {{ t('LoCoMo 长时记忆任务基准上的召回准确率。', 'Recall accuracy on the LoCoMo long-term memory benchmark.') }}
            </p>
          </div>
          <div class="hn">
            <div class="hn-val">4<span class="hn-unit">{{ t('路', '-way') }}</span></div>
            <div class="hn-label">{{ t('融合检索', 'Fusion retrieval') }}</div>
            <p class="hn-note">
              {{ t('同一次查询融合向量、全文、图、时序四种索引，内核级 BM25 增强。', 'A single query fuses vector, full-text, graph, and temporal indexes — kernel-level BM25.') }}
            </p>
          </div>
          <div class="hn">
            <div class="hn-val">zero-<br>copy</div>
            <div class="hn-label">{{ t('算子间', 'Between operators') }}</div>
            <p class="hn-note">
              {{ t('Datalake DAG 分布式作业算子间无需磁盘落地。', 'Datalake DAG jobs hand data between operators without touching disk.') }}
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         Screen 06 · Architecture kernel (whitepaper)
         ══════════════════════════════════════════ -->
    <section class="screen screen-06">
      <div class="screen-inner">
        <div class="arch-grid">
          <!-- Left: kernel diagram -->
          <div class="arch-stack">
            <h2 class="section-title-small">{{ t('架构内核', 'The kernel underneath') }}</h2>

            <div class="kernel-layer kernel-top">
              <span class="kernel-layer-name">{{ t('本地加密 compute', 'Locally encrypted compute') }}</span>
              <span class="kernel-layer-note">PBKDF2 · 三因素派生</span>
            </div>
            <div class="kernel-line">↓</div>
            <div class="kernel-layer">
              <span class="kernel-layer-name">Datalake</span>
              <span class="kernel-layer-note">OBS · shared memory</span>
            </div>
            <div class="kernel-line">↓</div>
            <div class="kernel-layer">
              <span class="kernel-layer-name">Memory / Knowledge</span>
              <span class="kernel-layer-note">{{ t('同 PageServer · 四路融合', 'Same PageServer · 4-way fusion') }}</span>
            </div>
            <div class="kernel-line">↓</div>
            <div class="kernel-layer kernel-bold">
              <span class="kernel-layer-name">Lakebase + BM25 {{ t('内核', 'kernel') }}</span>
              <span class="kernel-layer-note">{{ t('PageServer 级索引', 'PageServer-level index') }}</span>
            </div>
            <div class="kernel-line">↓</div>
            <div class="kernel-layer">
              <span class="kernel-layer-name">Neon copy-on-write</span>
              <span class="kernel-layer-note">{{ t('开源存算分离', 'Open-source storage/compute split') }}</span>
            </div>
          </div>

          <!-- Right: long prose -->
          <div class="arch-prose">
            <p>
              {{ t(
                'DBay 建立在 Neon 的存算分离之上，但在内核层做了三件 Neon 原生没有的事：',
                'DBay builds on Neon\'s storage/compute split, but the kernel does three things Neon doesn\'t:'
              ) }}
            </p>
            <ol class="arch-ol">
              <li>
                <strong>{{ t('BM25 索引进 PageServer', 'BM25 inside the PageServer') }}</strong>
                {{ t('— 全文不再需要外挂 Elasticsearch。', '— full-text no longer needs an Elasticsearch side-car.') }}
              </li>
              <li>
                <strong>{{ t('多模态索引路由', 'Multi-modal index routing') }}</strong>
                {{ t('— 向量、全文、图、时序在同一次查询里融合打分。', '— vector, full-text, graph, and temporal signals fuse in a single query.') }}
              </li>
              <li>
                <strong>{{ t('本地加密 compute', 'Locally encrypted compute') }}</strong>
                {{ t('— PBKDF2 派生密钥，服务端永远拿不到明文。即使数据库被拖走也无法还原。', '— PBKDF2-derived keys, ciphertext-only on the server. Even a full database leak stays opaque.') }}
              </li>
            </ol>
            <p>
              {{ t(
                '再往上，Datalake 基于 Ray 但改了调度：DAG 编译成一个分布式 Python 程序，算子间通过 shared memory 传递，不写磁盘。文本清洗 → 特征抽取 → embedding → 写入，四步只有一次 I/O。',
                'One layer up, Datalake runs on Ray but changes the scheduler: the DAG compiles into a single distributed Python program, and operators pass data through shared memory instead of disk. Text cleaning → feature extraction → embedding → writes runs with a single I/O pass.'
              ) }}
            </p>
            <router-link to="/architecture" class="arch-more">
              {{ t('继续阅读架构白皮书', 'Continue to the architecture note') }} →
            </router-link>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         Footer
         ══════════════════════════════════════════ -->
    <footer class="landing-footer">
      <div class="footer-inner">
        <div class="footer-line">
          <span class="footer-brand">DBay</span>
          <span class="footer-sep">·</span>
          <span class="footer-tag">{{ t('Agent 工作态和学习态的数据基础设施', 'Substrate for agent runtime and learning') }}</span>
        </div>
        <div class="footer-links">
          <router-link to="/architecture">{{ t('架构', 'Architecture') }}</router-link>
          <router-link to="/docs">{{ t('文档', 'Docs') }}</router-link>
          <router-link to="/login">{{ t('登录', 'Sign in') }}</router-link>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLocale } from '../../stores/locale'
import { useAuthStore } from '../../stores/auth'
import client from '../../api/client'

const { t } = useLocale()
const router = useRouter()
const authStore = useAuthStore()

const trialLoading = ref(false)

async function startTrial() {
  trialLoading.value = true
  try {
    localStorage.removeItem('lakeon_api_key')
    authStore.apiKey = ''
    const { data } = await client.post('/trial', null, { timeout: 10000 })
    localStorage.setItem('lakeon_api_key', data.api_key)
    authStore.apiKey = data.api_key
    authStore.setTenant(data.tenant_id, data.username || 'trial')
    authStore.setTrialState(true, data.expires_at)
    router.push('/dashboard')
  } catch {
    router.push('/login?register=1')
  } finally {
    trialLoading.value = false
  }
}
</script>

<style scoped>
/* ══════════════════════════════════════════
   Landing · Harbor Editorial (public site tuning)
   ══════════════════════════════════════════ */
.landing {
  background: var(--c-bg-alt);
  color: var(--c-text);
  font-family: var(--font-sans);
}

.screen {
  width: 100%;
  padding: clamp(72px, 10vh, 140px) clamp(24px, 4vw, 56px);
  border-bottom: 1px solid var(--c-border-light);
}

.screen:last-of-type {
  border-bottom: none;
}

.screen-inner {
  max-width: 1120px;
  margin: 0 auto;
}

/* ══════════════════════════════════════════
   Screen 01 · Hero manifesto
   ══════════════════════════════════════════ */
.screen-01 {
  min-height: 92vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: #fff;
  position: relative;
  overflow: hidden;
}

.screen-01::before {
  content: '';
  position: absolute;
  top: -140px;
  right: -140px;
  width: 520px;
  height: 520px;
  background: radial-gradient(ellipse at center, color-mix(in oklch, var(--c-accent) 8%, transparent), transparent 70%);
  pointer-events: none;
}

.hero-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(40px, 5.5vw, 80px);
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--c-primary);
  margin: 0 0 clamp(20px, 3vw, 40px);
  position: relative;
}

.hero-line-2 {
  white-space: nowrap;
}

.hero-line-1,
.hero-line-2 {
  display: block;
}

.hero-line-2 {
  color: var(--c-text);
}

.hero-lede {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(18px, 2vw, 26px);
  line-height: 1.5;
  color: var(--c-text-2);
  margin: 0 0 clamp(32px, 4vw, 56px);
  max-width: 62ch;
  position: relative;
}

.hero-ctas {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xl);
  margin-bottom: clamp(32px, 4vw, 56px);
  position: relative;
}

.cta-primary {
  background: var(--c-accent);
  color: #fff;
  border: none;
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.02em;
  padding: 14px 32px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 160ms ease-out;
}

.cta-primary:hover:not(:disabled) {
  background: var(--c-accent-hover);
}

.cta-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.cta-primary:focus-visible {
  outline: 2px solid var(--c-accent);
  outline-offset: 3px;
}

.cta-ghost {
  color: var(--c-primary);
  text-decoration: none;
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.02em;
  padding: 14px 0;
  border-bottom: 1px solid currentColor;
  transition: color 160ms ease-out;
}

.cta-ghost:hover {
  color: var(--c-accent-text);
}

.hero-shortcut {
  position: relative;
  padding-top: var(--space-xl);
  border-top: 1px solid var(--c-border-light);
  max-width: 720px;
}

.shortcut-label {
  display: block;
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--c-text-3);
  margin-bottom: var(--space-sm);
}

.shortcut-code {
  display: inline-block;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--c-text);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border);
  border-radius: 4px;
  padding: 8px 14px;
  user-select: all;
}

/* ══════════════════════════════════════════
   Screen 02 · Working / Learning state
   ══════════════════════════════════════════ */
.screen-02 {
  background: var(--c-bg-alt);
}

.screen-02-inner {
  max-width: 1040px;
}

.section-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(32px, 5vw, 64px);
  line-height: 1.05;
  letter-spacing: -0.015em;
  color: var(--c-primary);
  margin: 0 0 clamp(32px, 4vw, 56px);
  max-width: 22ch;
}

.section-title-small {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(28px, 3.5vw, 44px);
  line-height: 1.1;
  letter-spacing: -0.01em;
  color: var(--c-primary);
  margin: 0 0 clamp(32px, 4vw, 48px);
}

.section-prose {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(17px, 1.8vw, 22px);
  line-height: 1.55;
  color: var(--c-text-2);
  max-width: 60ch;
  margin: 0;
}

.working-learning-diagram {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  gap: clamp(24px, 3vw, 48px);
  align-items: stretch;
  margin-bottom: clamp(48px, 6vw, 80px);
}

.wl-column {
  background: #fff;
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  padding: var(--space-xl) var(--space-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.wl-phase {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
}

.wl-phase-num {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: 28px;
  color: var(--c-accent);
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.wl-phase-name {
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--c-text-3);
}

.wl-nodes {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin: var(--space-md) 0;
}

.wl-node {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--c-text);
  padding: 6px 12px;
  background: var(--c-bg-alt);
  border-radius: 4px;
  letter-spacing: -0.01em;
}

.wl-caption {
  font-size: 12px;
  color: var(--c-text-3);
  line-height: 1.5;
  border-top: 1px solid var(--c-border-light);
  padding-top: var(--space-sm);
  margin-top: auto;
}

.wl-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--c-text-3);
  min-width: 64px;
}

.wl-arrow-label {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
  text-align: center;
  margin-bottom: var(--space-sm);
  white-space: nowrap;
}

.wl-arrow-svg {
  width: 24px;
  height: 80px;
}

/* ══════════════════════════════════════════
   Screen 03 · Claude Code (developer voice)
   ══════════════════════════════════════════ */
.screen-03 {
  background: #fff;
}

.screen-03 .section-title {
  max-width: 28ch;
}

.cc-title-line {
  display: block;
}

.cc-title-line:last-child {
  color: var(--c-text);
}

.section-lede {
  font-family: var(--font-display);
  font-size: clamp(17px, 1.8vw, 22px);
  line-height: 1.6;
  color: var(--c-text-2);
  max-width: 62ch;
  margin: 0 0 clamp(48px, 6vw, 88px);
}

.section-lede.dev-voice {
  color: var(--c-text);
}

.cc-scene {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(32px, 4vw, 72px);
  align-items: center;
  padding: clamp(32px, 4vw, 64px) 0;
  border-bottom: 1px solid var(--c-border-light);
}

.cc-scene:first-of-type {
  padding-top: 0;
}

.cc-scene-reverse {
  direction: rtl;
}

.cc-scene-reverse > * {
  direction: ltr;
}

.cc-scene-media {
  min-height: 240px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cc-gif-slot {
  width: 100%;
  aspect-ratio: 16 / 10;
  background: var(--c-bg-alt);
  border: 1px dashed var(--c-border);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-text-3);
  letter-spacing: 0.02em;
}

.cc-scene-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.cc-scene-idx {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: 32px;
  line-height: 1;
  color: var(--c-accent);
}

.cc-scene-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(22px, 2.4vw, 32px);
  line-height: 1.2;
  color: var(--c-text);
  margin: 0;
  letter-spacing: -0.005em;
}

.cc-scene-p {
  font-family: var(--font-sans);
  font-size: 15px;
  line-height: 1.7;
  color: var(--c-text-2);
  margin: 0;
  max-width: 54ch;
}

.cc-scene-p em {
  font-family: var(--font-display);
  font-style: italic;
  color: var(--c-text);
  font-size: 17px;
}

.cc-scene-aside {
  font-style: italic;
  color: var(--c-text-3);
  font-size: 13px;
}

/* Pivot between "you teach it" scenes and "it learns you" */
.cc-pivot {
  display: flex;
  align-items: center;
  gap: var(--space-xl);
  margin: clamp(40px, 5vw, 72px) 0 clamp(24px, 3vw, 48px);
}

.cc-pivot-rule {
  flex: 1;
  height: 1px;
  background: var(--c-border);
}

.cc-pivot-label {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--c-accent-text);
  white-space: nowrap;
}

/* Scene 4 · static reflection panel (not GIF) */
.cc-scene-reflection .cc-scene-media {
  align-items: stretch;
}

.cc-reflection-panel {
  width: 100%;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.cc-reflection-header {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid var(--c-border-light);
}

.cc-reflection-tab {
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 600;
  color: var(--c-accent-text);
  letter-spacing: 0.02em;
  position: relative;
}

.cc-reflection-tab::after {
  content: '';
  position: absolute;
  left: -4px;
  right: -4px;
  bottom: -13px;
  height: 2px;
  background: var(--c-accent);
}

.cc-reflection-count {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-text-3);
  padding: 1px 8px;
  background: var(--c-bg-alt);
  border-radius: 10px;
}

.cc-reflection-card {
  padding: 14px 16px;
  border-bottom: 1px solid var(--c-border-light);
}

.cc-reflection-card:last-child {
  border-bottom: none;
}

.cc-reflection-text {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.55;
  color: var(--c-text);
  margin: 0 0 10px;
}

.cc-reflection-meta {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.cc-reflection-conf {
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 500;
  color: var(--c-accent-text);
  font-variant-numeric: tabular-nums;
}

.cc-reflection-bar {
  flex: 1;
  height: 3px;
  background: var(--c-border-light);
  border-radius: 2px;
  overflow: hidden;
}

.cc-reflection-fill {
  display: block;
  height: 100%;
  background: var(--c-accent);
  border-radius: 2px;
}

/* Divider + punchline */
.cc-divider {
  height: 1px;
  background: var(--c-border-light);
  margin: clamp(48px, 6vw, 88px) 0 clamp(48px, 6vw, 80px);
}

.cc-punchline {
  text-align: center;
  margin-bottom: clamp(48px, 6vw, 80px);
}

.cc-punchline-nums {
  display: flex;
  justify-content: center;
  align-items: flex-end;
  gap: clamp(48px, 6vw, 96px);
  margin-bottom: var(--space-xl);
  flex-wrap: wrap;
}

.cc-num {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.cc-num-arrow {
  font-family: var(--font-display);
  font-size: 28px;
  line-height: 1;
  margin-bottom: 4px;
}

.cc-num-accent .cc-num-arrow,
.cc-num-accent .cc-num-val {
  color: var(--c-accent);
}

.cc-num-primary .cc-num-arrow,
.cc-num-primary .cc-num-val {
  color: var(--c-primary);
}

.cc-num-val {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(56px, 8vw, 96px);
  line-height: 0.95;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}

.cc-num-label {
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--c-text-3);
  margin-top: var(--space-sm);
}

.cc-punchline-prose {
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.65;
  color: var(--c-text-2);
  max-width: 56ch;
  margin: 0 auto;
}

.cc-shell {
  text-align: center;
  margin-top: clamp(32px, 4vw, 48px);
}

.cc-shell-note {
  font-family: var(--font-sans);
  font-size: 11px;
  color: var(--c-text-3);
  margin: var(--space-sm) 0 0;
  letter-spacing: 0.02em;
}

/* ══════════════════════════════════════════
   Screen 04 · Four capabilities
   ══════════════════════════════════════════ */
.screen-04 {
  background: var(--c-bg-alt);
}

.zoom-out-intro {
  margin-bottom: clamp(48px, 6vw, 80px);
}

.zoom-out-intro .section-title {
  margin-bottom: var(--space-md);
}

.capabilities-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: clamp(24px, 3vw, 56px);
}

.cap {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.cap-name {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(22px, 2.2vw, 28px);
  color: var(--c-primary);
  margin: 0 0 var(--space-sm);
  letter-spacing: -0.01em;
  padding-bottom: var(--space-sm);
  border-bottom: 1px solid var(--c-border);
}

.cap-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.cap-list li {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.55;
  color: var(--c-text-2);
}

.cap-reveal {
  font-family: var(--font-sans);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-3);
  font-style: italic;
  margin: var(--space-md) 0 0;
  padding-top: var(--space-md);
  border-top: 1px solid var(--c-border-light);
  max-width: none;
}

.cap-code {
  display: block;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-accent-text);
  background: transparent;
  padding: 0;
  margin: 0;
  letter-spacing: -0.01em;
}

/* ══════════════════════════════════════════
   Screen 05 · Hard numbers
   ══════════════════════════════════════════ */
.screen-05 {
  background: #fff;
}

.hard-numbers {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: clamp(32px, 4vw, 64px);
}

.hn {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.hn-val {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(64px, 9vw, 116px);
  line-height: 0.92;
  letter-spacing: -0.03em;
  color: var(--c-primary);
  font-variant-numeric: tabular-nums;
}

.hn-unit {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: 0.45em;
  color: var(--c-text-3);
  margin-left: 0.05em;
}

.hn-label {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--c-text-3);
}

.hn-note {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.55;
  color: var(--c-text-2);
  margin: 0;
  max-width: 24ch;
}

/* ══════════════════════════════════════════
   Screen 06 · Kernel architecture
   ══════════════════════════════════════════ */
.screen-06 {
  background: var(--c-bg-alt);
}

.arch-grid {
  display: grid;
  grid-template-columns: minmax(280px, 360px) 1fr;
  gap: clamp(48px, 6vw, 88px);
  align-items: start;
}

.arch-stack {
  display: flex;
  flex-direction: column;
}

.arch-stack .section-title-small {
  margin-bottom: var(--space-xl);
}

.kernel-layer {
  background: #fff;
  border: 1px solid var(--c-border-light);
  border-radius: 4px;
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kernel-layer.kernel-top {
  border-color: color-mix(in oklch, var(--c-accent) 30%, var(--c-border));
}

.kernel-layer.kernel-bold {
  background: color-mix(in oklch, var(--c-accent) 6%, #fff);
  border-color: color-mix(in oklch, var(--c-accent) 30%, var(--c-border));
}

.kernel-layer-name {
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.kernel-layer-note {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-text-3);
  letter-spacing: -0.01em;
}

.kernel-line {
  text-align: center;
  font-family: var(--font-mono);
  font-size: 14px;
  color: var(--c-text-3);
  padding: 4px 0;
  line-height: 1;
}

.arch-prose {
  max-width: 62ch;
}

.arch-prose p {
  font-family: var(--font-sans);
  font-size: 16px;
  line-height: 1.75;
  color: var(--c-text);
  margin: 0 0 var(--space-xl);
}

.arch-ol {
  padding: 0;
  margin: 0 0 var(--space-xl);
  list-style: none;
  counter-reset: arch-count;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.arch-ol li {
  counter-increment: arch-count;
  position: relative;
  padding-left: 36px;
  font-family: var(--font-sans);
  font-size: 15px;
  line-height: 1.7;
  color: var(--c-text-2);
}

.arch-ol li::before {
  content: counter(arch-count);
  position: absolute;
  left: 0;
  top: 2px;
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 18px;
  color: var(--c-accent);
  width: 24px;
  text-align: right;
}

.arch-ol strong {
  color: var(--c-text);
  font-weight: 600;
}

.arch-more {
  display: inline-block;
  margin-top: var(--space-md);
  font-family: var(--font-sans);
  font-size: 14px;
  color: var(--c-accent-text);
  text-decoration: none;
  border-bottom: 1px solid currentColor;
  padding-bottom: 2px;
  transition: color 160ms ease-out;
}

.arch-more:hover {
  color: var(--c-accent-hover);
}

/* ══════════════════════════════════════════
   Footer
   ══════════════════════════════════════════ */
.landing-footer {
  background: #fff;
  border-top: 1px solid var(--c-border-light);
  padding: var(--space-2xl) clamp(24px, 4vw, 56px);
}

.footer-inner {
  max-width: 1120px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xl);
  flex-wrap: wrap;
}

.footer-line {
  display: flex;
  align-items: baseline;
  gap: var(--space-md);
  flex-wrap: wrap;
}

.footer-brand {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 18px;
  color: var(--c-primary);
  letter-spacing: -0.01em;
}

.footer-sep {
  color: var(--c-border);
}

.footer-tag {
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-text-3);
  letter-spacing: 0.02em;
}

.footer-links {
  display: flex;
  gap: var(--space-xl);
}

.footer-links a {
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--c-text-2);
  text-decoration: none;
  transition: color 160ms ease-out;
}

.footer-links a:hover {
  color: var(--c-accent-text);
}

/* ══════════════════════════════════════════
   Responsive
   ══════════════════════════════════════════ */
@media (max-width: 900px) {
  .working-learning-diagram {
    grid-template-columns: 1fr;
  }

  .wl-arrow {
    transform: rotate(90deg);
    align-self: center;
    min-height: 96px;
  }

  .wl-arrow-label {
    transform: rotate(-90deg);
  }

  .cc-scene,
  .cc-scene-reverse {
    grid-template-columns: 1fr;
    direction: ltr;
  }

  .capabilities-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .hard-numbers {
    grid-template-columns: repeat(2, 1fr);
  }

  .arch-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .capabilities-grid,
  .hard-numbers {
    grid-template-columns: 1fr;
  }

  .hero-ctas {
    flex-direction: column;
    align-items: flex-start;
  }

  .cta-primary,
  .cta-ghost {
    width: 100%;
    text-align: center;
  }

  .footer-inner {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .cta-primary,
  .cta-ghost,
  .footer-links a,
  .arch-more {
    transition: none;
  }
}
</style>
