<template>
  <div class="ppage">
    <!-- 01 · Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <router-link to="/" class="ppage-back">← {{ t('回到首页', 'Back to home') }}</router-link>
        <div class="ppage-eyebrow">{{ t('能力 02 · 知识库', 'Capability 02 · Knowledge') }}</div>

        <h1 class="ppage-manifesto-title">
          <span class="kn-title-a">{{ t('不是 RAG。', "It isn't RAG.") }}</span>
          <span class="kn-title-b">{{ t('是 Agent 自己', "It's a wiki") }}</span>
          <span class="kn-title-c">{{ t('在维护的一本 wiki。', 'the agent maintains.') }}</span>
        </h1>

        <p class="ppage-manifesto-lede">
          {{ t(
            'LLM 擅长读 wiki 条目，不擅长拼 chunk。DBay Knowledge 把你的源文档提炼成一本结构化 wiki：Agent 读条目不读片段，用户问问题顺便反哺新条目，知识只增不减。',
            'LLMs are good at reading wiki entries and bad at stitching chunks. DBay Knowledge distills your source documents into a structured wiki: agents read entries, not fragments. Users ask questions; the answers become new entries. Knowledge only grows.'
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
              '你给项目装了 RAG。你把 docs/ 目录全传进去了。Agent 开始回答用户问题，但质量不稳定 —— 有时 cite 错章节，有时拼接不相关片段，有时幻觉出一条根本不存在的条款。',
              "You added RAG to the project. You uploaded docs/. The agent answers questions, but quality wobbles — sometimes it cites the wrong section, sometimes it stitches unrelated fragments, sometimes it hallucinates a clause that doesn't exist."
            ) }}
          </p>
          <p>
            {{ t(
              '你加了 rerank。你切小了 chunk。你装了 query rewriting。质量有点提升，但你心里知道问题的根源：',
              'You added a reranker. You shrank chunks. You bolted on query rewriting. Quality nudges up, but you know where the problem really sits:'
            ) }}
          </p>
          <p>
            <em>{{ t(
              'LLM 读的不应该是 chunk，是条目。',
              "LLMs shouldn't read chunks. They should read entries."
            ) }}</em>
          </p>
          <p>
            {{ t(
              '这也是 Karpathy 最近反复讲的：LLM consumable 的知识层形状是一本 wiki，不是一堆 embeddings。',
              "Karpathy has been making this point lately too: the LLM-consumable shape of knowledge is a wiki, not a pile of embeddings."
            ) }}
          </p>
        </div>
      </div>
    </section>

    <!-- 03 · Contrast -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('RAG 的天花板', 'Where RAG runs out of room') }}</h2>

        <div class="ppage-contrast">
          <div class="ppage-contrast-col is-faded">
            <h3>{{ t('朴素 RAG (chunk · embed · retrieve)', 'Naive RAG (chunk · embed · retrieve)') }}</h3>
            <ul>
              <li>{{ t('chunk 粒度永远不对：太粗丢细节，太细失语境', 'Chunk size is never right — too big loses detail, too small loses context') }}</li>
              <li>{{ t('检索出的是文档片段，不是被理解过的条目', 'What comes back is a fragment, not something that has been understood') }}</li>
              <li>{{ t('文档更新要重嵌入整篇，漂移没人管', 'Doc edits re-embed the whole thing; drift goes unmanaged') }}</li>
              <li>{{ t('用户新问题消失在会话日志里，知识不沉淀', 'New user questions disappear into session logs; knowledge never compounds') }}</li>
              <li>{{ t('rerank + query rewrite 是拼命补救，不是根治', 'Rerankers and query rewriters patch the symptoms, not the cause') }}</li>
            </ul>
          </div>
          <div class="ppage-contrast-col is-accent">
            <h3>DBay Knowledge</h3>
            <ul>
              <li>{{ t('LLM 从源文档抽出结构化条目，存进一本 wiki', 'An LLM distills source docs into structured entries inside a wiki') }}</li>
              <li>{{ t('Agent 读的是条目 · 带原文引用 · 不拼 chunk', 'The agent reads entries — with source citations — not chunks') }}</li>
              <li>{{ t('用户问题 → 变成新条目 · 知识持续生长', "User questions become new entries. Knowledge compounds.") }}</li>
              <li>{{ t('后台维护：源文档更新时同步 wiki · 冗余合并', 'Background curation: wiki stays in sync with sources; redundant entries merge') }}</li>
              <li>{{ t('底层仍然是四路融合检索 (vector · BM25 · 图 · 时序)', 'Four-way fusion retrieval underneath (vector · BM25 · graph · temporal)') }}</li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- 04 · Approach -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('wiki 怎么生长', 'How the wiki grows') }}</h2>

        <div class="ppage-approach">
          <div class="ppage-stack">
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">{{ t('① 源文档上传', '① Source docs uploaded') }}</span>
              <span class="ppage-stack-note">PDF · Markdown · Git repo</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer is-bold">
              <span class="ppage-stack-name">{{ t('② LLM 后台提炼 wiki', '② LLM distills into a wiki') }}</span>
              <span class="ppage-stack-note">{{ t('结构化条目 · 带源引用', 'Entries with source cites') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">{{ t('③ Agent 读 wiki 条目', '③ Agents read wiki entries') }}</span>
              <span class="ppage-stack-note">{{ t('四路融合检索', 'Fusion retrieval') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">{{ t('④ 新问题反哺 wiki', '④ New questions feed back') }}</span>
              <span class="ppage-stack-note">{{ t('wiki 增量生长', 'Incremental growth') }}</span>
            </div>
          </div>

          <div class="ppage-section-prose">
            <p>
              {{ t(
                '你上传一份 docs/，DBay 在后台用 LLM 把它解构成 wiki。每个条目有明确的标题、摘要、原文引用、交叉链接。',
                'You upload docs/. DBay spins up an LLM in the background that decomposes it into a wiki — each entry with a clear title, a summary, source citations, and cross-links.'
              ) }}
            </p>
            <p>
              {{ t(
                'Agent 问问题时，检索命中的是 wiki 条目，不是散落的 chunk。条目是被理解过的，所以 LLM 不再需要从一堆片段里拼答案，它只是',
                'When the agent asks a question, retrieval returns a wiki entry — not scattered chunks. The entry is already understood, so the LLM stops stitching fragments and starts'
              ) }}
              <strong>{{ t('读一段结构化的知识', 'reading structured knowledge') }}</strong>
              {{ t('。幻觉自然下降，token 成本自然下降。', '. Hallucinations drop. Token cost drops.') }}
            </p>
            <p>
              {{ t(
                '更重要的是：用户问新问题时，DBay 会把有价值的问答补进 wiki 作为新条目，带上原始上下文的引用。第二天，团队里另一个人再问相关问题，wiki 已经有答案。',
                'More importantly: when users ask new questions, DBay appends the useful Q&A as a new entry in the wiki, with the original context cited. The next day, someone else on the team asks something related — the wiki already has an answer.'
              ) }}
            </p>
            <p>
              {{ t(
                '这不是一个被动的检索层。这是一本',
                "This isn't a passive retrieval layer. It's a"
              ) }}
              <strong>{{ t('在生长的', 'living, growing') }}</strong>
              {{ t('知识库。', ' knowledge base.') }}
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
          <h2>{{ t('上传项目文档，问 Claude Code 问题', 'Upload your project docs, ask Claude Code questions') }}</h2>

          <div class="ppage-today-prose">
            <p>{{ t('用 CLI 把项目文档扔进 DBay：', 'Push your project docs into DBay:') }}</p>
            <code class="ppage-code">$ dbay knowledge upload ./docs --name my-project</code>

            <p>
              {{ t(
                '等几分钟到几十分钟，取决于文档量。DBay 后台在跑 LLM，把文档提炼成 wiki 条目。你在 console 可以看到生成进度。',
                'A few minutes to tens of minutes, depending on volume. An LLM runs in the background, distilling the docs into wiki entries. Progress shows in the console.'
              ) }}
            </p>

            <p>{{ t('把 dbay MCP 装进 Claude Code：', 'Drop the dbay MCP into Claude Code:') }}</p>
            <code class="ppage-code">$ claude mcp add --scope user dbay -- python -m dbay_mcp</code>

            <p>{{ t('然后在 Claude Code 里正常问问题：', 'Then just ask, like you would any other day:') }}</p>
            <p>
              <em>{{ t('"我们的 auth flow 是怎么处理 refresh token 的？"', '"How does our auth flow handle refresh tokens?"') }}</em>
            </p>
            <p>
              {{ t(
                'Claude Code 读 wiki 的对应条目，答案带原文引用 —— 来自你的 docs/，不是公网。',
                "Claude Code reads the matching wiki entry. The answer cites your docs/ — not the open web."
              ) }}
            </p>

            <p>
              {{ t(
                '接下来你再问的新问题会反哺 wiki。知识只增不减。',
                'Anything new you ask feeds back into the wiki. Knowledge only grows.'
              ) }}
            </p>
          </div>

          <p class="ppage-today-aside">
            {{ t(
              '顺便说一句：四路融合比朴素向量 RAG 拿回的不相关 chunk 少很多 —— 最终上下文更短，token 成本更低，准确率反而更高。这是致敬 Karpathy 最近讲的 "LLM-consumable knowledge should look like a wiki" 思路的落地。',
              'A side note: four-way fusion brings back far fewer irrelevant chunks than naive vector RAG — shorter final context, lower token cost, and higher accuracy. Think of this as the working implementation of Karpathy\'s recent "LLM-consumable knowledge should look like a wiki" framing.'
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
            <div class="ppage-num-val">4</div>
            <div class="ppage-num-label">{{ t('路融合检索', 'fusion dimensions') }}</div>
            <p class="ppage-num-note">
              {{ t('vector · BM25 · graph · temporal，同一次检索融合打分。', 'Vector · BM25 · graph · temporal, fused in a single retrieval.') }}
            </p>
          </div>
          <div class="ppage-num">
            <div class="ppage-num-val">50–70%<span class="ppage-num-arrow">↓</span></div>
            <div class="ppage-num-label">{{ t('输入 token', 'input tokens') }}</div>
            <p class="ppage-num-note">
              {{ t('比把全文 chunk 塞进上下文少得多，因为 wiki 条目已经被理解过。', 'Far below stuffing raw chunks into context — wiki entries come pre-understood.') }}
            </p>
          </div>
          <div class="ppage-num">
            <div class="ppage-num-val">∞</div>
            <div class="ppage-num-label">{{ t('知识沉淀', 'Knowledge compounds') }}</div>
            <p class="ppage-num-note">
              {{ t('每一个被问到的新问题都变成 wiki 的新条目。', 'Every new question becomes a new entry.') }}
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
            <div class="ppage-footer-card-tag">{{ t('PostgreSQL · BM25 内核 · 长在数据湖上', 'PostgreSQL · BM25 kernel · on a data lake') }}</div>
          </router-link>
          <router-link to="/product/memory" class="ppage-footer-card">
            <div class="ppage-footer-card-name">Memory</div>
            <div class="ppage-footer-card-tag">{{ t('长期记忆 · 反思 · 跨 Agent 共享', 'Long-term memory · reflection · cross-agent') }}</div>
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
.kn-title-a,
.kn-title-b,
.kn-title-c {
  display: block;
}

.kn-title-a {
  color: var(--c-text-3);
}

.kn-title-b {
  color: var(--c-primary);
}

.kn-title-c {
  color: var(--c-accent-text);
}

.ppage-num-arrow {
  font-size: 0.45em;
  color: var(--c-accent);
  margin-left: 0.1em;
  font-weight: 400;
}
</style>
