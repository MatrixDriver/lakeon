<template>
  <div class="ppage">
    <!-- 01 · Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <router-link to="/" class="ppage-back">← {{ t('回到首页', 'Back to home') }}</router-link>
        <div class="ppage-eyebrow">{{ t('能力 04 · 数据湖', 'Capability 04 · Datalake') }}</div>

        <h1 class="ppage-manifesto-title">
          <span class="dl-title-a">{{ t('多模态算子，', 'Multi-modal operators.') }}</span>
          <span class="dl-title-b">{{ t('DAG 编排，', 'DAG orchestration.') }}</span>
          <span class="dl-title-c">{{ t('算子间 zero-copy。', 'Zero-copy in between.') }}</span>
        </h1>

        <p class="ppage-manifesto-lede">
          {{ t(
            '不是一个 Ray 的套壳。DBay Datalake 提供文本/图像/音频/视频/文档的多模态算子库，DAG 直接编译成一个分布式 Python 程序，算子间通过 shared memory 传递，不写磁盘。文本清洗 → 特征抽取 → embedding → 写入，四步只有一次 I/O。',
            "Not a Ray wrapper. DBay Datalake ships a multi-modal operator library — text, image, audio, video, documents — and the DAG compiles into a single distributed Python program. Operators hand each other data through shared memory, not disk. Text clean → feature extract → embedding → write: four steps, one I/O."
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
              '你有 10TB 视频。你要：提帧 → OCR → 过滤 → 生成 embedding → 写入知识库。这是一条典型的多模态数据处理流水线。',
              "You have 10TB of video. The job: extract frames → OCR → filter → embed → write to a knowledge base. A typical multi-modal data pipeline."
            ) }}
          </p>
          <p>
            {{ t(
              '你拿 Ray 写：算子间要落地盘存 parquet，然后下一步再读。Spark 也一样，而且 Spark 不擅长 Python-only 的 OCR/CV 算子。Airflow 根本不是数据处理框架，是任务编排器，跑不了 GPU 算子。',
              "You try Ray: each operator spills to disk for the next one to read. Spark is the same — and Spark isn't happy running Python-only OCR/CV ops. Airflow isn't a data processing framework at all; it's a scheduler, and it can't run GPU ops."
            ) }}
          </p>
          <p>
            <em>{{ t(
              '最后你写了 800 行 glue code。大部分是"把上一步的输出读回内存"。',
              "You end up with 800 lines of glue code. Most of it is \"read the previous step's output back into memory.\""
            ) }}</em>
          </p>
        </div>
      </div>
    </section>

    <!-- 03 · Contrast -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('为什么通用框架不够', "Why general-purpose frameworks don't fit") }}</h2>

        <div class="ppage-contrast">
          <div class="ppage-contrast-col is-faded">
            <h3>{{ t('通用分布式 Python (Ray · Spark · Airflow)', 'Generic distributed Python (Ray · Spark · Airflow)') }}</h3>
            <ul>
              <li>{{ t('算子间数据必须落地磁盘 · I/O 开销巨大', 'Operators hand off through disk — I/O dominates') }}</li>
              <li>{{ t('多模态算子自己写 · 没有现成的 OCR/CV/video frame 库', 'Multi-modal operators are DIY — no built-in OCR/CV/video ops') }}</li>
              <li>{{ t('热启动 · 集群调度 · GPU 分配都要自己处理', 'Warm pool, scheduling, GPU allocation — you build it') }}</li>
              <li>{{ t('每次跑 job 都要打 Docker 镜像 · 重启集群', 'Every run builds a Docker image, restarts the cluster') }}</li>
              <li>{{ t('写一堆 glue code 把 Ray/Spark/Airflow 拼起来', 'Glue code holds Ray/Spark/Airflow together by the seams') }}</li>
            </ul>
          </div>
          <div class="ppage-contrast-col is-accent">
            <h3>DBay Datalake</h3>
            <ul>
              <li>{{ t('算子间 shared memory · zero-copy · 不落地盘', 'Operators pass shared memory — zero-copy, no disk') }}</li>
              <li>{{ t('内置多模态算子：文本清洗 · OCR · 视频分帧 · 音频转写', 'Built-in multi-modal ops: text clean, OCR, video frame, audio transcribe') }}</li>
              <li>{{ t('Ray 热池 · 冷启动 ~13s · CCI Kata VM 隔离', 'Ray warm pool · ~13s cold start · CCI Kata VM isolation') }}</li>
              <li>{{ t('DAG 编译成一个分布式 Python 程序 · 无 Docker 镜像', 'The DAG compiles into one distributed Python program — no Docker image') }}</li>
              <li>{{ t('Notebook 交互式开发 · 算子可拖拽 · 可一键转 job', 'Notebook for interactive dev · drag-drop operators · promote to a job in one click') }}</li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- 04 · Approach -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('DAG 怎么变成一个分布式 Python 程序', 'How a DAG turns into a single distributed Python program') }}</h2>

        <div class="ppage-approach">
          <div class="ppage-stack">
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">{{ t('① DAG 描述', '① DAG description') }}</span>
              <span class="ppage-stack-note">{{ t('Notebook · 拖拽 · yaml', 'Notebook · drag-drop · yaml') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer is-bold">
              <span class="ppage-stack-name">{{ t('② 编译器', '② Compiler') }}</span>
              <span class="ppage-stack-note">{{ t('算子合并 · shared memory 规划', 'Op fusion · shared-memory plan') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">{{ t('③ 分布式 Python', '③ Distributed Python') }}</span>
              <span class="ppage-stack-note">{{ t('Ray 热池 · CCI Kata', 'Ray warm pool · CCI Kata') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">OBS / Lakebase</span>
              <span class="ppage-stack-note">{{ t('结果写入', 'Results land') }}</span>
            </div>
          </div>

          <div class="ppage-section-prose">
            <p>
              {{ t(
                '你用 Notebook 或者 DAG 描述文件画一个数据流：输入 → 一串算子 → 输出。每个算子是一个 Python 函数，可以是内置的 (OCR / video frame / audio transcribe / text clean / embed)，也可以是你自己写的。',
                "You draw a data flow in a Notebook or a DAG file: input → a chain of operators → output. Each operator is a Python function, either built-in (OCR, video frames, audio transcribe, text cleaning, embed) or yours."
              ) }}
            </p>
            <p>
              <strong>{{ t('编译器', 'The compiler') }}</strong>
              {{ t(
                '拿到 DAG 之后，做两件关键的事：合并可以融合的算子（避免不必要的中间层），规划 shared memory 缓冲区（让一个算子的输出直接是下一个算子的输入，不经过磁盘）。结果是一个分布式 Python 程序 —— 一个进程组，通过共享内存协作。',
                " takes the DAG and does two things that matter: it fuses operators that can be fused (to kill unnecessary intermediate materialization), and it plans the shared-memory buffers so one operator's output is the next operator's input without ever touching disk. The result is a single distributed Python program — a process group coordinating through shared memory."
              ) }}
            </p>
            <p>
              {{ t(
                '执行时走 Ray 热池 —— compute pod 始终预热着，新作业进来 ~13s 冷启动，不是几分钟。隔离级别是 CCI Kata VM，不同租户的作业真正隔离，不是 namespace 级别。',
                'At runtime it lands on a Ray warm pool — compute pods stay preheated, so a new job cold-starts in ~13 seconds, not minutes. Isolation is CCI Kata VM, so tenants are genuinely isolated, not merely namespaced.'
              ) }}
            </p>

            <code class="ppage-code"># {{ t('一个典型的视频处理 DAG', 'A typical video processing DAG') }}
from dbay.datalake import dag, ops

with dag("video-knowledge") as d:
    clips  = d.input("obs://bucket/clips/*.mp4")
    frames = ops.video_frames(clips, every="2s")
    text   = ops.ocr(frames)
    clean  = ops.text_clean(text)
    vec    = ops.embed(clean, model="bge-m3")
    d.output(vec, to="lakebase:docs")

d.submit()  # {{ t('编译 → 热池执行 → shared memory 传递', 'Compile → warm-pool execute → shared memory') }}</code>
          </div>
        </div>
      </div>
    </section>

    <!-- 05 · Today you can do -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <div class="ppage-today">
          <span class="ppage-today-eyebrow">{{ t('今天你就能做', 'You can do this today') }}</span>
          <h2>{{ t('从 Notebook 开始，一个小时跑出一条 pipeline', 'Start in a Notebook. Ship a pipeline in an hour.') }}</h2>

          <div class="ppage-today-prose">
            <p>{{ t('创建一个 Notebook，算子是内置的 Python 函数：', 'Create a Notebook. The operators are just Python functions:') }}</p>
            <code class="ppage-code">$ dbay datalake notebook new my-experiment
Opened at https://dbay.cloud/notebook/my-experiment</code>

            <p>
              {{ t(
                '在 Notebook 里交互式地调试算子：',
                'Iterate on operators interactively inside the Notebook:'
              ) }}
            </p>
            <code class="ppage-code">frames = ops.video_frames("obs://bucket/sample.mp4", every="2s")
text = ops.ocr(frames[:5])    # {{ t('先试 5 帧', 'Try 5 frames first') }}
print(text)</code>

            <p>{{ t('觉得流程跑通了，一键把 Notebook 转成定时作业：', 'When the flow works, promote the Notebook to a scheduled job:') }}</p>
            <code class="ppage-code">$ dbay datalake job new --from-notebook my-experiment --schedule "0 3 * * *"
Job queued. Warm pool cold-start ~13s.</code>

            <p>
              {{ t(
                '结果直接写进 Lakebase 的表。Knowledge 和 Memory 也用同一个 Lakebase 底座，于是这条 pipeline 跑出来的 embedding 立刻可以被 Agent 召回 —— 不需要再做一次 ETL。',
                "Results land directly in a Lakebase table. Because Knowledge and Memory also sit on Lakebase, the embeddings this pipeline produces are immediately recall-able by agents — no second ETL pass."
              ) }}
            </p>
          </div>

          <p class="ppage-today-aside">
            {{ t(
              '算子是 Python 函数，所以你可以写自己的。想混用 GPU 算子？声明一下资源，编译器会帮你路由到 GPU 节点，其他算子继续在 CPU 节点跑，依然是同一个 DAG。',
              "Operators are Python functions, so you can write your own. Need a GPU op? Declare resources and the compiler routes it to a GPU node — the rest of the operators stay on CPU, inside the same DAG."
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
            <div class="ppage-num-val">zero-<br>copy</div>
            <div class="ppage-num-label">{{ t('算子间', 'between operators') }}</div>
            <p class="ppage-num-note">
              {{ t('shared memory 传递 · 不落地盘 · 四步只一次 I/O。', 'Shared-memory handoff · no disk · one I/O per four steps.') }}
            </p>
          </div>
          <div class="ppage-num">
            <div class="ppage-num-val">~13<span class="ppage-num-unit">s</span></div>
            <div class="ppage-num-label">{{ t('冷启动', 'cold start') }}</div>
            <p class="ppage-num-note">
              {{ t('Ray 热池预热 · 从提交到执行的实测范围。', 'Ray warm pool pre-heats — measured submit-to-run range.') }}
            </p>
          </div>
          <div class="ppage-num">
            <div class="ppage-num-val">5</div>
            <div class="ppage-num-label">{{ t('模态算子库', 'modalities') }}</div>
            <p class="ppage-num-note">
              {{ t('文本 · 图像 · 音频 · 视频 · 文档。可以加自己的。', 'Text, image, audio, video, documents. Add your own.') }}
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
          <router-link to="/product/memory" class="ppage-footer-card">
            <div class="ppage-footer-card-name">Memory</div>
            <div class="ppage-footer-card-tag">{{ t('长期记忆 · 自我反思 · 加密存储', 'Long-term memory · reflection · encrypted') }}</div>
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
.dl-title-a,
.dl-title-b,
.dl-title-c {
  display: block;
}

.dl-title-b {
  color: var(--c-text);
}

.dl-title-c {
  color: var(--c-accent-text);
}

.ppage-num-unit {
  font-size: 0.45em;
  color: var(--c-text-3);
  margin-left: 0.08em;
  font-weight: 400;
}
</style>
