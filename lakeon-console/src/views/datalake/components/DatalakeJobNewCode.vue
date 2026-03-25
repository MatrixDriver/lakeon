<template>
  <div>
    <div class="section-title">代码</div>
    <div class="section-desc">
      编写 Python 脚本。通过环境变量
      <code>DATASET_PATH</code> 读取输入，
      <code>OUTPUT_PATH</code> 写出结果。
    </div>

    <div class="source-tabs">
      <button class="source-tab" :class="{ active: tab === 'inline' }" @click="tab = 'inline'">✏️ 内联编辑器</button>
      <button class="source-tab" :class="{ active: tab === 'obs' }" @click="tab = 'obs'">📦 OBS 路径</button>
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

    <div class="ai-hint">
      ✨ <strong>AI 辅助（即将推出）</strong>：描述你想做什么，AI 帮你生成初始脚本
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView, lineNumbers, highlightActiveLine } from '@codemirror/view'
import { python } from '@codemirror/lang-python'
import { oneDark } from '@codemirror/theme-one-dark'

const props = defineProps<{ script: string }>()
const emit = defineEmits<{ 'update:script': [value: string] }>()

const tab = ref<'inline' | 'obs'>('inline')
const editorContainer = ref<HTMLElement | null>(null)
let view: EditorView | null = null

const STARTER = `import os
import pandas as pd

# 通过环境变量读取输入数据集和输出路径
input_path  = os.environ["DATASET_PATH"]
output_path = os.environ["OUTPUT_PATH"]

df = pd.read_parquet(input_path)

# 在此编写你的处理逻辑
# df = df[df["score"] > 0.8]

df.to_parquet(output_path, index=False)
print(f"输出 {len(df)} 行到 {output_path}")
`

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
.ai-hint { display: flex; align-items: center; gap: 8px; background: rgba(99,102,241,.08); border: 1px solid rgba(99,102,241,.2); border-radius: 6px; padding: 8px 12px; margin-top: 12px; font-size: 11px; color: #6366f1; cursor: pointer; }
</style>
