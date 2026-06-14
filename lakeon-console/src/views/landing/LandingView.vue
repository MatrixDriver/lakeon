<template>
  <div class="landing">
    <!-- ══════════════════════════════════════════
         Screen 01 · Manifesto
         ══════════════════════════════════════════ -->
    <section class="screen screen-01">
      <div class="screen-inner">
        <h1 class="hero-title">
          <span class="hero-line-1">{{ t('你的 Agent 越来越懂你。', 'Your agent is getting to know you.') }}</span>
          <span class="hero-line-2">{{ t('这份"懂"，应该归你自己。', 'That knowing should belong to you.') }}</span>
        </h1>

        <p class="hero-lede">
          {{ t(
            'OpenClaw 有一份 Memory，Claude 有一份 Projects，Cursor 有一份 Context —— 你每换一个 Agent，都得从零开始。DBay 反过来：它是你自己的记忆库和知识库，任何 Agent 都可以接上，但数据始终属于你。',
            'OpenClaw has its own Memory. Claude has its own Projects. Cursor has its own Context. Every time you switch agents, you start over. DBay flips it: your memory and knowledge live in your own place, any agent can plug in, and the data always belongs to you.'
          ) }}
        </p>

        <p class="hero-refrain">
          <em>{{ t('在你这边。', 'On your side.') }}</em>
        </p>

        <div class="hero-ctas">
          <router-link to="/login?register=1" class="cta-primary">
            {{ t('开始使用', 'Get started') }}
          </router-link>
          <router-link to="/product" class="cta-ghost">
            {{ t('了解方案', 'Explore the solution') }} →
          </router-link>
        </div>

        <div class="hero-shortcut">
          <span class="shortcut-label">{{ t('也可以直接接入你正在用的 Agent', 'Or plug it into the agent you already use') }}</span>
          <router-link to="/integrations" class="shortcut-link">
            Claude Code · Cursor · Cline · Gemini CLI — {{ t('集成指引', 'integration guide') }} →
          </router-link>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         Screen "Ownership" · 在你这边
         ══════════════════════════════════════════ -->
    <section class="screen screen-ownership">
      <div class="screen-inner">
        <div class="own-header">
          <span class="own-eyebrow">{{ t('立场', 'Our stance') }}</span>
          <h2 class="own-title">{{ t('在你这边。', 'On your side.') }}</h2>
          <p class="own-lede">
            {{ t(
              'Agent 厂家越来越大，他们在你身上学到的也越来越多。这些"学到的"应该在哪一边？DBay 选一边。',
              'Agent vendors keep getting bigger, and they keep learning more about you. Which side should all of that live on? DBay picked a side.'
            ) }}
          </p>
        </div>

        <div class="own-cards">
          <article class="own-card">
            <div class="own-card-num">01</div>
            <h3 class="own-card-title">{{ t('钥匙在你手里', 'You hold the key') }}</h3>
            <p class="own-card-body">
              {{ t(
                '你的记忆在离开你电脑之前就加密好了（基于公私钥的信封加密），私钥只在你手里。DBay 的服务器只看到一堆密文——就算数据库被人搬走，也还原不了。',
                "Your memory is encrypted before it ever leaves your machine (envelope encryption with a public/private key pair). The private key stays with you. DBay's servers only see ciphertext — a stolen database reveals nothing."
              ) }}
            </p>
          </article>

          <article class="own-card">
            <div class="own-card-num">02</div>
            <h3 class="own-card-title">{{ t('你的知识，不是我们的语料', 'Your knowledge is not our training data') }}</h3>
            <p class="own-card-body">
              {{ t(
                '你上传的文档、沉淀的 wiki、积累的记忆，都是你个人的东西。我们不拿去训模型，不跨账户汇总，不给第三方（你的数据存在你自己的隔离数据库里）。',
                'The documents you upload, the wiki you settle into, the memory you accumulate — they are yours alone. We do not train models on them, do not aggregate across accounts, do not share with third parties. Your data lives in its own isolated database.'
              ) }}
            </p>
          </article>

          <article class="own-card">
            <div class="own-card-num">03</div>
            <h3 class="own-card-title">{{ t('接谁由你决定', 'You decide which agents plug in') }}</h3>
            <p class="own-card-body">
              {{ t(
                'DBay 通过 MCP 被任何 Agent 接上 —— Claude Code · Cursor · Cline · OpenClaw。换 Agent 不丢数据，因为数据从来就不在 Agent 那边。',
                'DBay connects to any agent over MCP — Claude Code, Cursor, Cline, OpenClaw. Switch agents without losing anything, because the data was never on the agent side to begin with.'
              ) }}
            </p>
          </article>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         Screen "Team" · 拥有 → 分享
         ══════════════════════════════════════════ -->
    <section class="screen screen-team">
      <div class="screen-inner">
        <div class="team-header">
          <span class="team-eyebrow">{{ t('团队协作', 'For teams') }}</span>
          <h2 class="team-title">
            <span class="team-title-a">{{ t('拥有。', 'Ownership.') }}</span>
            <span class="team-title-b">{{ t('所以可以分享。', 'Which means sharing.') }}</span>
          </h2>
          <p class="team-lede">
            {{ t(
              '因为记忆和知识属于你，所以你来决定谁能看。把一个知识库分享给团队——团队里每个人的 Agent 都能读到同一本 wiki。把记忆库分享给销售团队——所有 Agent 召回同一份客户洞察。',
              "Because your memory and knowledge belong to you, you decide who else sees them. Share a knowledge base with the team — every teammate's agent reads the same wiki. Share a memory base with sales — every agent recalls the same customer insights."
            ) }}
          </p>
        </div>

        <div class="team-rows">
          <div class="team-row">
            <div class="team-row-num">01</div>
            <div class="team-row-body">
              <h3 class="team-row-title">{{ t('一次点击，不是一次集成', 'One click, not one integration') }}</h3>
              <p>{{ t(
                '在 Console 里选一个库，填人或团队的邮箱，确认。对方的 Agent 下一次 recall 就能读到。没有 webhook、没有 API key 交换、没有跨租户复制。',
                "Pick a base in the Console, type their email, confirm. Their agent reads it on the next recall. No webhooks, no API key exchange, no cross-tenant copies."
              ) }}</p>
            </div>
          </div>

          <div class="team-row">
            <div class="team-row-num">02</div>
            <div class="team-row-body">
              <h3 class="team-row-title">{{ t('权限分级 · 只读 / 可写 / 管理', 'Granular roles — read, write, admin') }}</h3>
              <p>{{ t(
                '把客户洞察开给 sales 只读、给 CSM 可写、给 manager 管理；把架构约定开给整个工程部可写。每一份库是独立的权限域，不会互相串。',
                "Hand customer insights to sales as read-only, to CSMs as write, to managers as admin; open engineering conventions to the whole team for write. Every base is its own permission domain, no cross-leaks."
              ) }}</p>
            </div>
          </div>

          <div class="team-row">
            <div class="team-row-num">03</div>
            <div class="team-row-body">
              <h3 class="team-row-title">{{ t('撤销也一次点击。人走了，数据不走', 'Revoke in one click. People leave, data stays') }}</h3>
              <p>{{ t(
                '员工离职 → 踢出团队库 → 他的 Agent 下一次 recall 就读不到了。团队成员写入的条目保留在团队库里，个人的那份记忆跟着个人走——他换公司，他的 Agent 还记得他是谁。',
                'An employee leaves → remove them from the team base → their agent stops recalling it on the next call. Entries they wrote stay in the team base; their own memory base travels with them — they switch companies, their agent still knows who they are.'
              ) }}</p>
            </div>
          </div>
        </div>

        <p class="team-refrain">
          <em>{{ t('你拥有，你分享，你决定谁看。', 'You own it. You share it. You decide who sees it.') }}</em>
        </p>
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
              <div class="wl-node">Memory.recall</div>
              <div class="wl-node">Knowledge.search</div>
              <div class="wl-node">Lakebase</div>
            </div>
            <div class="wl-caption">{{ t('读写事务 · 召回记忆 · 检索知识', 'Read/write state · Recall memory · Search knowledge') }}</div>
          </div>

          <div class="wl-arrow">
            <div class="wl-arrow-label">{{ t('数据双向同步', 'Data flows both ways') }}</div>
            <svg class="wl-arrow-svg" viewBox="0 0 80 24" fill="none" aria-hidden="true">
              <line x1="10" y1="12" x2="70" y2="12" stroke="currentColor" stroke-width="1" stroke-dasharray="3 4"/>
              <path d="M16 6 L4 12 L16 18" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M64 6 L76 12 L64 18" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>

          <div class="wl-column wl-learning">
            <div class="wl-phase">
              <span class="wl-phase-num">02</span>
              <span class="wl-phase-name">{{ t('Agent 学习时', 'When the agent learns') }}</span>
            </div>
            <div class="wl-nodes">
              <div class="wl-node">Memory.digest</div>
              <div class="wl-node">Knowledge.curate</div>
              <div class="wl-node">Datalake</div>
            </div>
            <div class="wl-caption">{{ t('反思记忆 · 批处理 · 沉淀知识', 'Digest memory · Batch compute · Curate knowledge') }}</div>
          </div>
        </div>

        <p class="section-prose">
          {{ t(
            'Agent 不是无状态的 API 调用。它们在时间维度上积累认知，又在每次执行时消费这些认知。DBay 让这两件事发生在同一套底座上。',
            'Agents are not stateless API calls. They accumulate understanding over time and consume it on every run. DBay keeps both on a single foundation.'
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

        <!-- Scene 1 · remember across projects -->
        <div class="cc-scene">
          <div class="cc-scene-media">
            <svg class="cc-svg" viewBox="0 0 360 240" role="img" aria-labelledby="svg1-title">
              <title id="svg1-title">{{ t('跨项目的记忆层', 'Memory shared across projects') }}</title>
              <!-- project-a window top-left -->
              <g class="svg-win">
                <rect x="20" y="24" width="120" height="60" rx="5" fill="#fff"/>
                <rect x="20" y="24" width="120" height="60" rx="5" fill="none" stroke="var(--c-primary)" stroke-width="1.2"/>
                <line x1="20" y1="38" x2="140" y2="38" stroke="var(--c-primary)" stroke-width="1"/>
                <circle cx="28" cy="31" r="1.5" fill="var(--c-primary)"/>
                <circle cx="34" cy="31" r="1.5" fill="var(--c-primary)"/>
                <circle cx="40" cy="31" r="1.5" fill="var(--c-primary)"/>
                <text x="80" y="58" text-anchor="middle" font-family="'JetBrains Mono', monospace" font-size="10" fill="var(--c-primary)">project-a</text>
                <text x="80" y="73" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="9" font-style="italic" fill="var(--c-text-3)">{{ t('周一', 'Monday') }}</text>
              </g>

              <!-- project-b window top-right -->
              <g class="svg-win">
                <rect x="220" y="24" width="120" height="60" rx="5" fill="#fff"/>
                <rect x="220" y="24" width="120" height="60" rx="5" fill="none" stroke="var(--c-primary)" stroke-width="1.2"/>
                <line x1="220" y1="38" x2="340" y2="38" stroke="var(--c-primary)" stroke-width="1"/>
                <circle cx="228" cy="31" r="1.5" fill="var(--c-primary)"/>
                <circle cx="234" cy="31" r="1.5" fill="var(--c-primary)"/>
                <circle cx="240" cy="31" r="1.5" fill="var(--c-primary)"/>
                <text x="280" y="58" text-anchor="middle" font-family="'JetBrains Mono', monospace" font-size="10" fill="var(--c-primary)">project-b</text>
                <text x="280" y="73" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="9" font-style="italic" fill="var(--c-text-3)">{{ t('一周后', 'a week later') }}</text>
              </g>

              <!-- Central memory node -->
              <g>
                <rect x="100" y="140" width="160" height="44" rx="22" fill="color-mix(in oklch, var(--c-accent) 8%, #fff)"/>
                <rect x="100" y="140" width="160" height="44" rx="22" fill="none" stroke="var(--c-accent)" stroke-width="1.3"/>
                <text x="180" y="159" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="13" font-weight="500" fill="var(--c-accent-text)">DBay memory</text>
                <text x="180" y="174" text-anchor="middle" font-family="'JetBrains Mono', monospace" font-size="9" fill="var(--c-accent-text)">camelCase · no semicolons</text>
              </g>

              <!-- Curves: project-a → memory (save), memory → project-b (recall) -->
              <path d="M 80 86 Q 80 130 120 142" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-dasharray="3 4"/>
              <path d="M 240 142 Q 280 130 280 86" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-dasharray="3 4"/>

              <!-- Arrow labels -->
              <text x="82" y="118" font-family="'Geist', sans-serif" font-size="9" font-weight="500" fill="var(--c-accent-text)">↓ save</text>
              <text x="252" y="118" font-family="'Geist', sans-serif" font-size="9" font-weight="500" fill="var(--c-accent-text)">↑ recall</text>

              <!-- Bottom caption -->
              <text x="180" y="216" text-anchor="middle" font-family="'Source Serif 4', serif" font-style="italic" font-size="11" fill="var(--c-text-2)">{{ t('一次说出口，跨项目都记得', 'Say it once, every project remembers') }}</text>
            </svg>
          </div>
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
          <div class="cc-scene-media">
            <svg class="cc-svg" viewBox="0 0 360 240" role="img" aria-labelledby="svg2-title">
              <title id="svg2-title">{{ t('团队共享的记忆层', 'A memory layer shared by the team') }}</title>

              <!-- Top shared memory bar -->
              <g>
                <rect x="60" y="28" width="240" height="40" rx="20" fill="color-mix(in oklch, var(--c-accent) 8%, #fff)"/>
                <rect x="60" y="28" width="240" height="40" rx="20" fill="none" stroke="var(--c-accent)" stroke-width="1.3"/>
                <text x="180" y="45" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="12" font-weight="500" fill="var(--c-accent-text)">{{ t('团队共享记忆', 'team-shared memory') }}</text>
                <text x="180" y="59" text-anchor="middle" font-family="'JetBrains Mono', monospace" font-size="9" fill="var(--c-accent-text)">convention · commit rules · naming</text>
              </g>

              <!-- Three people as small circles below -->
              <!-- Jacky (writer) -->
              <g>
                <circle cx="80" cy="160" r="22" fill="#fff" stroke="var(--c-primary)" stroke-width="1.2"/>
                <text x="80" y="164" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="16" font-weight="500" fill="var(--c-primary)">J</text>
                <text x="80" y="198" text-anchor="middle" font-family="'Geist', sans-serif" font-size="10" fill="var(--c-text-2)">Jacky</text>
                <text x="80" y="212" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="9" font-style="italic" fill="var(--c-text-3)">{{ t('写规范', 'writes it') }}</text>
              </g>

              <!-- Alice (reader · new hire) -->
              <g>
                <circle cx="180" cy="160" r="22" fill="#fff" stroke="var(--c-primary)" stroke-width="1.2"/>
                <text x="180" y="164" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="16" font-weight="500" fill="var(--c-primary)">A</text>
                <text x="180" y="198" text-anchor="middle" font-family="'Geist', sans-serif" font-size="10" fill="var(--c-text-2)">Alice</text>
                <text x="180" y="212" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="9" font-style="italic" fill="var(--c-text-3)">{{ t('新人', 'new hire') }}</text>
              </g>

              <!-- Bob -->
              <g>
                <circle cx="280" cy="160" r="22" fill="#fff" stroke="var(--c-primary)" stroke-width="1.2"/>
                <text x="280" y="164" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="16" font-weight="500" fill="var(--c-primary)">B</text>
                <text x="280" y="198" text-anchor="middle" font-family="'Geist', sans-serif" font-size="10" fill="var(--c-text-2)">Bob</text>
                <text x="280" y="212" text-anchor="middle" font-family="'Source Serif 4', serif" font-size="9" font-style="italic" fill="var(--c-text-3)">{{ t('老员工', 'veteran') }}</text>
              </g>

              <!-- Arrow: Jacky → memory (save, solid up) -->
              <path d="M 80 138 L 80 90 L 90 90" fill="none" stroke="var(--c-accent)" stroke-width="1.3"/>
              <path d="M 86 84 L 92 90 L 86 96" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
              <text x="66" y="112" font-family="'Geist', sans-serif" font-size="9" font-weight="500" fill="var(--c-accent-text)">save</text>

              <!-- Arrow: memory → Alice (recall, dashed down) -->
              <path d="M 180 68 L 180 138" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-dasharray="3 4"/>
              <path d="M 174 132 L 180 138 L 186 132" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
              <text x="188" y="106" font-family="'Geist', sans-serif" font-size="9" font-weight="500" fill="var(--c-accent-text)">recall</text>

              <!-- Arrow: memory → Bob (recall, dashed down) -->
              <path d="M 280 68 L 280 138" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-dasharray="3 4"/>
              <path d="M 274 132 L 280 138 L 286 132" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
              <text x="288" y="106" font-family="'Geist', sans-serif" font-size="9" font-weight="500" fill="var(--c-accent-text)">recall</text>
            </svg>
          </div>
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
          <div class="cc-scene-media">
            <svg class="cc-svg" viewBox="0 0 360 240" role="img" aria-labelledby="svg3-title">
              <title id="svg3-title">{{ t('本地加密后才上云', 'Encrypted locally before it leaves') }}</title>

              <!-- Left · plaintext key -->
              <g>
                <rect x="20" y="70" width="96" height="60" rx="4" fill="#fff" stroke="var(--c-primary)" stroke-width="1.2"/>
                <text x="68" y="90" text-anchor="middle" font-family="'Geist', sans-serif" font-size="9" font-weight="500" fill="var(--c-text-3)">{{ t('你的机器', 'Your machine') }}</text>
                <text x="68" y="108" text-anchor="middle" font-family="'JetBrains Mono', monospace" font-size="10" fill="var(--c-primary)">OPENAI_API_KEY</text>
                <text x="68" y="120" text-anchor="middle" font-family="'JetBrains Mono', monospace" font-size="9" fill="var(--c-text-2)">sk-proj-a7f3…</text>
              </g>

              <!-- Arrow · key → lock -->
              <line x1="116" y1="100" x2="144" y2="100" stroke="var(--c-accent)" stroke-width="1.3"/>
              <path d="M 138 94 L 144 100 L 138 106" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>

              <!-- Middle · local encrypt lock -->
              <g>
                <rect x="144" y="60" width="72" height="80" rx="6" fill="color-mix(in oklch, var(--c-accent) 8%, #fff)"/>
                <rect x="144" y="60" width="72" height="80" rx="6" fill="none" stroke="var(--c-accent)" stroke-width="1.3"/>
                <!-- padlock glyph -->
                <g transform="translate(180, 92)">
                  <path d="M -7 -5 A 7 7 0 0 1 7 -5 L 7 0" fill="none" stroke="var(--c-accent-text)" stroke-width="1.4" stroke-linecap="round"/>
                  <rect x="-10" y="0" width="20" height="14" rx="2" fill="none" stroke="var(--c-accent-text)" stroke-width="1.4"/>
                  <circle cx="0" cy="7" r="1.5" fill="var(--c-accent-text)"/>
                </g>
                <text x="180" y="125" text-anchor="middle" font-family="'Geist', sans-serif" font-size="9" font-weight="500" fill="var(--c-accent-text)">PBKDF2</text>
                <text x="180" y="135" text-anchor="middle" font-family="'Geist', sans-serif" font-size="8" fill="var(--c-accent-text)">3-factor</text>
              </g>

              <!-- Arrow · lock → cloud -->
              <line x1="216" y1="100" x2="244" y2="100" stroke="var(--c-accent)" stroke-width="1.3"/>
              <path d="M 238 94 L 244 100 L 238 106" fill="none" stroke="var(--c-accent)" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>

              <!-- Right · cloud (DBay server) -->
              <g>
                <path d="M 254 110 Q 248 110 248 102 Q 248 92 258 92 Q 262 82 274 82 Q 286 80 290 90 Q 300 88 302 100 Q 308 102 306 112 Q 310 122 298 122 L 260 122 Q 252 122 254 110 Z" fill="#fff" stroke="var(--c-primary)" stroke-width="1.2"/>
                <text x="278" y="108" text-anchor="middle" font-family="'Geist', sans-serif" font-size="9" font-weight="500" fill="var(--c-text-3)">DBay</text>
                <text x="278" y="140" text-anchor="middle" font-family="'JetBrains Mono', monospace" font-size="9" fill="var(--c-primary)">enc_v1:7a3f…9c1b</text>
              </g>

              <!-- Horizontal divider mid caption -->
              <line x1="40" y1="180" x2="320" y2="180" stroke="var(--c-border)" stroke-width="1"/>

              <!-- Caption below -->
              <text x="180" y="202" text-anchor="middle" font-family="'Source Serif 4', serif" font-style="italic" font-size="11" fill="var(--c-text-2)">
                <tspan>{{ t('服务端只看到密文。', 'The server only sees ciphertext.') }}</tspan>
              </text>
              <text x="180" y="220" text-anchor="middle" font-family="'Source Serif 4', serif" font-style="italic" font-size="11" fill="var(--c-text-2)">
                <tspan>{{ t('数据库被拖走，也还原不了。', 'Even a full database leak stays opaque.') }}</tspan>
              </text>
            </svg>
          </div>
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
                'This is the "learning state." Working state is you reading and writing. Learning state is the foundation reading you.'
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

        <!-- Shell install + full guide link -->
        <div class="cc-shell">
          <code class="shortcut-code">$ claude mcp add --scope user dbay -- python -m dbay_mcp</code>
          <p class="cc-shell-note">
            {{ t('需要先装 dbay-cli。', 'Requires dbay-cli first.') }}
            <router-link to="/integrations" class="cc-shell-link">{{ t('查看完整接入指引', 'See the full integration guide') }} →</router-link>
          </p>
          <p class="cc-shell-note cc-shell-note-tiny">
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
              'Memory 和 Knowledge 只是 DBay 四种能力中的两种。全部四种，长在同一套底座上——而这套底座属于你自己。',
              'Memory and Knowledge are two of DBay\'s four capabilities. All four sit on the same foundation — and that foundation is yours.'
            ) }}
          </p>
        </div>

        <div class="capabilities-grid">
          <!-- Lakebase -->
          <router-link to="/product/lakebase" class="cap">
            <h3 class="cap-name">Lakebase</h3>
            <ul class="cap-list">
              <li>{{ t('在数据湖上跑的 serverless PostgreSQL', 'Serverless PostgreSQL running on a data lake') }}</li>
              <li>{{ t('Neon copy-on-write + BM25 内核增强', 'Neon copy-on-write + BM25 kernel') }}</li>
              <li>{{ t('时间旅行 · 分支 · 版本', 'Time travel · branching · versioning') }}</li>
            </ul>
            <p class="cap-reveal">
              {{ t('不是 wrapper。我们在 Neon PageServer 里加了 BM25 索引。', 'Not a wrapper. We added BM25 indexing inside the Neon PageServer.') }}
            </p>
            <span class="cap-more">{{ t('继续读 Lakebase', 'Read more') }} →</span>
          </router-link>

          <!-- Knowledge -->
          <router-link to="/product/knowledge" class="cap">
            <h3 class="cap-name">Knowledge</h3>
            <ul class="cap-list">
              <li>{{ t('Agent 自己维护的活 wiki', 'A living wiki the agent maintains itself') }}</li>
              <li>{{ t('从源文档生成 · 与 wiki 对话', 'Generated from source docs · conversational') }}</li>
              <li>{{ t('知识持续沉淀，不是静态 RAG', 'Knowledge keeps compounding — not static RAG') }}</li>
            </ul>
            <p class="cap-reveal">
              {{ t('Agent 读 wiki 条目，不是 chunk。底层四路融合检索。', 'Agents read wiki entries, not chunks. Four-way hybrid retrieval underneath.') }}
            </p>
            <span class="cap-more">{{ t('继续读 Knowledge', 'Read more') }} →</span>
          </router-link>

          <!-- Memory -->
          <router-link to="/product/memory" class="cap">
            <h3 class="cap-name">Memory</h3>
            <ul class="cap-list">
              <li>{{ t('同内核融合检索 · LoCoMo 82%', 'Same kernel fusion retrieval · LoCoMo 82%') }}</li>
              <li>{{ t('反思洞察自动抽出用户模式', 'Reflection insights, automatically') }}</li>
              <li>{{ t('跨 Agent 共享 · 本地加密存储', 'Shared across agents · locally encrypted') }}</li>
            </ul>
            <p class="cap-reveal">
              {{ t('每晚 digest 反刍对话，几天后自己抽出你的开发模式。', 'Nightly digest walks the conversation; a few days in it knows your patterns.') }}
            </p>
            <span class="cap-more">{{ t('继续读 Memory', 'Read more') }} →</span>
          </router-link>

          <!-- Datalake -->
          <router-link to="/product/datalake" class="cap">
            <h3 class="cap-name">Datalake</h3>
            <ul class="cap-list">
              <li>{{ t('多模态算子库 · DAG 编排', 'Multi-modal operators · DAG orchestration') }}</li>
              <li>{{ t('算子间 zero-copy · shared memory', 'Operators share memory · zero-copy') }}</li>
              <li>{{ t('文本 · 视频 · 音频都可处理', 'Text, video, audio all welcome') }}</li>
            </ul>
            <p class="cap-reveal">
              {{ t('DAG 编译成分布式 Python，算子间 shared memory 不落地。', 'The DAG compiles into a single distributed Python program; operators pass through shared memory, never disk.') }}
            </p>
            <span class="cap-more">{{ t('继续读 Datalake', 'Read more') }} →</span>
          </router-link>
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
import { useLocale } from '../../stores/locale'

const { t } = useLocale()
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

.hero-line-1,
.hero-line-2 {
  display: block;
}

.hero-line-2 {
  color: var(--c-accent-text);
}

.hero-lede {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(18px, 2vw, 26px);
  line-height: 1.55;
  color: var(--c-text-2);
  margin: 0 0 clamp(20px, 2.5vw, 32px);
  max-width: 62ch;
  position: relative;
}

.hero-refrain {
  font-family: var(--font-display);
  font-size: clamp(15px, 1.5vw, 18px);
  line-height: 1;
  color: var(--c-accent-text);
  margin: 0 0 clamp(32px, 4vw, 56px);
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.hero-refrain::before {
  content: '';
  width: clamp(24px, 3vw, 40px);
  height: 1px;
  background: var(--c-accent);
}

.hero-refrain em {
  font-style: italic;
  font-weight: 400;
  letter-spacing: 0.02em;
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

.shortcut-link {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--c-accent-text);
  text-decoration: none;
  border-bottom: 1px solid currentColor;
  padding-bottom: 2px;
  transition: color 160ms ease-out;
}

.shortcut-link:hover {
  color: var(--c-accent-hover);
}

/* ══════════════════════════════════════════
   Screen Ownership · 在你这边
   ══════════════════════════════════════════ */
.screen-ownership {
  background: #fff;
}

.screen-ownership .screen-inner {
  max-width: 1200px;
}

.own-header {
  max-width: 720px;
  margin: 0 0 clamp(56px, 7vw, 96px);
}

.own-eyebrow {
  display: inline-block;
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--c-accent-text);
  margin-bottom: var(--space-lg);
}

.own-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(40px, 5vw, 72px);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--c-primary);
  margin: 0 0 var(--space-xl);
}

.own-lede {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(18px, 2vw, 24px);
  line-height: 1.55;
  color: var(--c-text-2);
  max-width: 62ch;
  margin: 0;
}

.own-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: clamp(24px, 3vw, 48px);
}

.own-card {
  display: flex;
  flex-direction: column;
  padding: clamp(28px, 3vw, 40px) 0 0;
  border-top: 1px solid var(--c-border);
}

.own-card-num {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.12em;
  color: var(--c-accent-text);
  margin-bottom: var(--space-lg);
}

.own-card-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(22px, 2.2vw, 28px);
  line-height: 1.25;
  letter-spacing: -0.01em;
  color: var(--c-primary);
  margin: 0 0 var(--space-md);
}

.own-card-body {
  font-family: var(--font-sans);
  font-size: 15px;
  line-height: 1.7;
  color: var(--c-text-2);
  margin: 0;
}

/* ══════════════════════════════════════════
   Screen Team · 拥有 → 分享
   ══════════════════════════════════════════ */
.screen-team {
  background: var(--c-bg-alt);
}

.screen-team .screen-inner {
  max-width: 1080px;
}

.team-header {
  max-width: 720px;
  margin: 0 0 clamp(56px, 7vw, 96px);
}

.team-eyebrow {
  display: inline-block;
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--c-accent-text);
  margin-bottom: var(--space-lg);
}

.team-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(40px, 5vw, 72px);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--c-primary);
  margin: 0 0 var(--space-xl);
}

.team-title-a,
.team-title-b {
  display: block;
}

.team-title-b {
  color: var(--c-accent-text);
  font-style: italic;
  font-weight: 400;
}

.team-lede {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(18px, 2vw, 24px);
  line-height: 1.55;
  color: var(--c-text-2);
  max-width: 62ch;
  margin: 0;
}

.team-rows {
  display: flex;
  flex-direction: column;
  gap: clamp(32px, 4vw, 56px);
  margin-bottom: clamp(40px, 5vw, 64px);
}

.team-row {
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: clamp(16px, 2vw, 32px);
  align-items: baseline;
}

.team-row-num {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.12em;
  color: var(--c-accent-text);
}

.team-row-body {
  border-top: 1px solid var(--c-border);
  padding-top: var(--space-md);
}

.team-row-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(20px, 2vw, 24px);
  line-height: 1.25;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  margin: 0 0 var(--space-sm);
}

.team-row-body p {
  font-family: var(--font-sans);
  font-size: 15px;
  line-height: 1.7;
  color: var(--c-text-2);
  max-width: 72ch;
  margin: 0;
}

.team-refrain {
  font-family: var(--font-display);
  font-size: clamp(18px, 2vw, 22px);
  color: var(--c-accent-text);
  margin: 0;
  padding-top: var(--space-xl);
  border-top: 1px solid var(--c-border-light);
}

.team-refrain em {
  font-style: italic;
  font-weight: 400;
  letter-spacing: 0.01em;
}

@media (max-width: 900px) {
  .team-row {
    grid-template-columns: 1fr;
    gap: var(--space-sm);
  }
}

/* ══════════════════════════════════════════
   Screen 02 · Working / Learning state
   ══════════════════════════════════════════ */
.screen-02 {
  background: #fff;
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
  width: 80px;
  height: 24px;
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

.cc-svg {
  width: 100%;
  height: auto;
  max-width: 440px;
  display: block;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  padding: var(--space-md);
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
  font-size: 12px;
  color: var(--c-text-2);
  margin: var(--space-md) 0 0;
  letter-spacing: 0.02em;
}

.cc-shell-note-tiny {
  font-size: 11px;
  color: var(--c-text-3);
  margin-top: var(--space-xs);
}

.cc-shell-link {
  color: var(--c-accent-text);
  text-decoration: none;
  border-bottom: 1px solid currentColor;
  padding-bottom: 1px;
  margin-left: 4px;
  transition: color 160ms ease-out;
}

.cc-shell-link:hover {
  color: var(--c-accent-hover);
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
  text-decoration: none;
  color: inherit;
  padding: var(--space-lg) var(--space-md);
  margin: calc(var(--space-lg) * -1) calc(var(--space-md) * -1);
  border-radius: 6px;
  transition: background 160ms ease-out, transform 160ms ease-out;
}

.cap:hover {
  background: #fff;
}

.cap:hover .cap-more {
  color: var(--c-accent-hover);
  transform: translateX(2px);
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
  flex: 1;
}

.cap-more {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  color: var(--c-accent-text);
  margin-top: var(--space-sm);
  transition: color 160ms ease-out, transform 160ms ease-out;
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
  .own-cards {
    grid-template-columns: 1fr;
  }

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
