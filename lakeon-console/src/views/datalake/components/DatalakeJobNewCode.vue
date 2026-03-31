<template>
  <div>
    <div class="section-title">代码</div>
    <div class="section-desc">
      编写 Python 脚本。通过环境变量
      <code>DATASET_PATH</code> 读取输入，
      <code>OUTPUT_PATH</code> 写出结果。
    </div>

    <div class="source-tabs">
      <button class="source-tab" :class="{ active: tab === 'inline' }" @click="tab = 'inline'">内联编辑器</button>
      <button class="source-tab" :class="{ active: tab === 'obs' }" @click="tab = 'obs'">OBS 路径</button>
    </div>

    <div v-if="tab === 'inline'" class="editor-wrap">
      <div class="editor-toolbar">
        <span class="editor-filename">main.py</span>
      </div>
      <div ref="editorContainer" class="editor-container"></div>
    </div>

    <div v-else class="obs-stub">
      <div class="obs-stub-icon">🚧</div>
      <div class="obs-stub-title">OBS 路径模式即将推出</div>
      <div class="obs-stub-desc">将代码包上传到 OBS，填写路径后自动下载到容器执行。</div>
    </div>

    <!-- Requirements -->
    <div class="requirements-section">
      <label class="req-label">依赖包 <span class="req-hint">（可选，空格分隔，预装：pandas pyarrow boto3）</span></label>
      <input
        class="req-input"
        :value="requirements"
        @input="$emit('update:requirements', ($event.target as HTMLInputElement).value)"
        placeholder="例如：scikit-learn matplotlib seaborn"
      />
    </div>

    <!-- AI Panel -->
    <div class="ai-panel" :class="{ expanded: aiOpen }">
      <div class="ai-toggle" @click="aiOpen = !aiOpen">
        <strong>AI 辅助</strong>：描述你想做什么，AI 帮你生成脚本
        <span class="ai-toggle-arrow">{{ aiOpen ? '▼' : '▶' }}</span>
      </div>
      <div v-if="aiOpen" class="ai-body">
        <div class="ai-row">
          <label class="ai-label">模型</label>
          <select v-model="aiModel" class="ai-select">
            <option v-for="m in models" :key="m.id" :value="m.id">
              {{ m.name }} {{ m.input_price === 0 ? '(免费)' : `(¥${m.input_price}/${m.output_price} per M)` }}
            </option>
          </select>
        </div>
        <textarea
          v-model="aiPrompt"
          class="ai-prompt"
          rows="3"
          placeholder="例：过滤 score > 0.8 的行，按 category 分组统计数量"
          :disabled="aiLoading"
          @keydown.ctrl.enter="handleGenerate"
          @keydown.meta.enter="handleGenerate"
        ></textarea>
        <div class="ai-actions">
          <button class="ai-btn" :disabled="aiLoading || !aiPrompt.trim()" @click="handleGenerate">
            {{ aiLoading ? '生成中...' : '生成脚本' }}
          </button>
          <span v-if="aiTokens" class="ai-tokens">
            {{ aiTokens.input }} + {{ aiTokens.output }} tokens
            <template v-if="aiTokens.cost > 0"> · ¥{{ aiTokens.cost.toFixed(4) }}</template>
          </span>
        </div>
        <div v-if="aiError" class="ai-error">{{ aiError }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView, lineNumbers, highlightActiveLine } from '@codemirror/view'
import { python } from '@codemirror/lang-python'
import { oneDark } from '@codemirror/theme-one-dark'
import { generateDatalakeScript } from '../../../api/datalake'

const props = defineProps<{ script: string; requirements?: string; jobType?: string }>()
const emit = defineEmits<{
  'update:script': [value: string]
  'update:requirements': [value: string]
  'update:usedDatasetIds': [value: string[]]
}>()

const tab = ref<'inline' | 'obs'>('inline')
const editorContainer = ref<HTMLElement | null>(null)
let view: EditorView | null = null

const PYTHON_STARTER = `import pandas as pd
import numpy as np
import os

# === DBay 数据湖 Python 演示 ===
# 生成一份销售数据并做统计分析

np.random.seed(42)
dates = pd.date_range("2025-01-01", periods=200, freq="D")
products = np.random.choice(["手机", "笔记本", "平板", "耳机"], 200)
quantities = np.random.randint(1, 50, 200)
prices = np.round(np.random.uniform(99, 9999, 200), 2)

df = pd.DataFrame({
    "日期": dates, "产品": products,
    "数量": quantities, "单价": prices,
})
df["销售额"] = df["数量"] * df["单价"]

# 按产品汇总
summary = df.groupby("产品").agg(
    订单数=("数量", "count"),
    总销量=("数量", "sum"),
    总销售额=("销售额", "sum"),
    平均单价=("单价", "mean"),
).round(2).sort_values("总销售额", ascending=False)

print("=" * 50)
print("  DBay 数据湖 — 销售数据分析演示")
print("=" * 50)
print(f"\\n总记录: {len(df)} 行")
print(f"日期范围: {df['日期'].min().date()} ~ {df['日期'].max().date()}")
print(f"总销售额: ¥{df['销售额'].sum():,.2f}")
print(f"\\n按产品汇总:")
print(summary.to_string())

# 输出结果（如果设置了 OUTPUT_PATH）
output = os.environ.get("OUTPUT_PATH")
if output:
    df.to_parquet(output, index=False)
    print(f"\\n✅ 已输出 {len(df)} 行到 {output}")
else:
    print("\\n✅ 演示完成")
`

const RAY_STARTER = `import ray
import time
import random

# === DBay 数据湖 Ray 分布式演示 ===
# 蒙特卡罗方法并行估算 π

ray.init()

@ray.remote
def estimate_pi(n_samples: int, task_id: int) -> dict:
    """单个 worker 的蒙特卡罗采样"""
    inside = 0
    for _ in range(n_samples):
        x, y = random.random(), random.random()
        if x * x + y * y <= 1.0:
            inside += 1
    return {"task_id": task_id, "inside": inside, "total": n_samples}

n_tasks = 10
samples_per_task = 1_000_000
total_samples = n_tasks * samples_per_task

print("=" * 50)
print("  DBay 数据湖 — Ray 分布式计算演示")
print("=" * 50)
print(f"\\n🚀 启动 {n_tasks} 个并行任务，每个采样 {samples_per_task:,} 次")
print(f"   总采样: {total_samples:,} 次")
print(f"   集群资源: {ray.cluster_resources()}")

start = time.time()
futures = [estimate_pi.remote(samples_per_task, i) for i in range(n_tasks)]
results = ray.get(futures)
elapsed = time.time() - start

total_inside = sum(r["inside"] for r in results)
pi_estimate = 4.0 * total_inside / total_samples
error = abs(pi_estimate - 3.141592653589793)

print(f"\\n计算结果:")
for r in results:
    pi_local = 4.0 * r["inside"] / r["total"]
    print(f"   Task {r['task_id']:2d}: π ≈ {pi_local:.6f}")

print(f"\\n   合并估算: π ≈ {pi_estimate:.8f}")
print(f"   实际误差: {error:.8f}")
print(f"   计算耗时: {elapsed:.2f}s")
print(f"\\n✅ 演示完成")
`

const STARTER = props.jobType === 'RAY' ? RAY_STARTER : PYTHON_STARTER

onMounted(() => {
  if (!editorContainer.value) return
  const doc = props.script || STARTER
  const state = EditorState.create({
    doc,
    extensions: [
      lineNumbers(),
      highlightActiveLine(),
      python(),
      oneDark,
      EditorView.updateListener.of(update => {
        if (update.docChanged) {
          emit('update:script', update.state.doc.toString())
        }
      }),
      EditorView.theme({ '&': { height: '340px' }, '.cm-scroller': { overflow: 'auto' } }),
    ],
  })
  view = new EditorView({ state, parent: editorContainer.value })
  if (!props.script) emit('update:script', STARTER)
})

onUnmounted(() => view?.destroy())

const aiOpen = ref(false)
const aiPrompt = ref('')
const aiModel = ref('deepseek-ai/DeepSeek-V3.2')
const aiLoading = ref(false)
const aiError = ref('')
const aiTokens = ref<{ input: number; output: number; cost: number } | null>(null)

const models = [
  { id: 'deepseek-ai/DeepSeek-V3.2', name: 'DeepSeek V3.2', input_price: 2.0, output_price: 3.0 },
  { id: 'Qwen/Qwen3-Coder-480B-A35B-Instruct', name: 'Qwen3 Coder 480B', input_price: 8.0, output_price: 16.0 },
  { id: 'Qwen/Qwen3-Coder-30B-A3B-Instruct', name: 'Qwen3 Coder 30B', input_price: 0.7, output_price: 2.8 },
]

async function handleGenerate() {
  if (!aiPrompt.value.trim() || aiLoading.value) return
  aiLoading.value = true
  aiError.value = ''
  aiTokens.value = null
  try {
    const res = await generateDatalakeScript(aiPrompt.value, aiModel.value)
    const data = res.data as any
    if (data.error) {
      aiError.value = data.error
      return
    }
    if (data.script && view) {
      // Replace editor content
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: data.script }
      })
      emit('update:script', data.script)
    }
    if (data.used_dataset_ids) {
      emit('update:usedDatasetIds', data.used_dataset_ids)
    }
    // Token usage + cost
    const m = models.find(x => x.id === aiModel.value)
    const inputCost = (data.input_tokens || 0) * (m?.input_price || 0) / 1_000_000
    const outputCost = (data.output_tokens || 0) * (m?.output_price || 0) / 1_000_000
    aiTokens.value = {
      input: data.input_tokens || 0,
      output: data.output_tokens || 0,
      cost: inputCost + outputCost
    }
  } catch (e: any) {
    aiError.value = e?.response?.data?.error?.message || e.message || 'Generation failed'
  } finally {
    aiLoading.value = false
  }
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 16px; line-height: 1.5; }
code { background: #f1f5f9; padding: 1px 5px; border-radius: 3px; font-size: 11px; }
.source-tabs { display: flex; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; width: fit-content; margin-bottom: 12px; }
.source-tab { padding: 7px 16px; font-size: 12px; font-weight: 600; color: #64748b; cursor: pointer; background: #f8fafc; border: none; }
.source-tab.active { background: #fff; color: #2563eb; border-bottom: 2px solid #2563eb; }
.editor-wrap { border: 1px solid #334155; border-radius: 8px; overflow: hidden; }
.editor-toolbar { background: #334155; padding: 6px 12px; }
.editor-filename { font-size: 11px; color: #94a3b8; font-family: monospace; }
.editor-container { min-height: 340px; }
.obs-stub { background: #f8fafc; border: 2px dashed #e2e8f0; border-radius: 8px; padding: 40px; text-align: center; }
.obs-stub-icon { font-size: 32px; margin-bottom: 8px; }
.obs-stub-title { font-size: 14px; font-weight: 700; color: #1e293b; margin-bottom: 6px; }
.obs-stub-desc { font-size: 12px; color: #64748b; }
.requirements-section { margin-top: 12px; }
.req-label { font-size: 12px; font-weight: 600; color: #374151; display: block; margin-bottom: 4px; }
.req-hint { font-weight: 400; color: #94a3b8; font-size: 11px; }
.req-input { width: 100%; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px 10px; font-size: 12px; font-family: monospace; color: #334155; outline: none; }
.req-input:focus { border-color: #2563eb; }
.ai-panel { margin-top: 12px; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; }
.ai-panel.expanded { border-color: rgba(99,102,241,.3); }
.ai-toggle { display: flex; align-items: center; gap: 8px; padding: 10px 14px; font-size: 12px; color: #6366f1; cursor: pointer; background: rgba(99,102,241,.04); }
.ai-toggle:hover { background: rgba(99,102,241,.08); }
.ai-toggle-arrow { margin-left: auto; font-size: 10px; }
.ai-body { padding: 12px 14px; border-top: 1px solid #e2e8f0; background: #fff; }
.ai-row { margin-bottom: 8px; }
.ai-label { font-size: 11px; font-weight: 600; color: #374151; margin-right: 8px; }
.ai-select { font-size: 12px; padding: 4px 8px; border: 1px solid #e2e8f0; border-radius: 4px; color: #334155; outline: none; }
.ai-prompt { width: 100%; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 10px; font-size: 12px; color: #334155; outline: none; resize: vertical; font-family: inherit; }
.ai-prompt:focus { border-color: #6366f1; }
.ai-actions { display: flex; align-items: center; gap: 12px; margin-top: 8px; }
.ai-btn { background: linear-gradient(135deg, #667eea, #764ba2); color: #fff; border: none; padding: 6px 16px; border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer; }
.ai-btn:disabled { opacity: 0.5; cursor: default; }
.ai-tokens { font-size: 11px; color: #94a3b8; }
.ai-error { margin-top: 8px; font-size: 11px; color: #ef4444; }
</style>
