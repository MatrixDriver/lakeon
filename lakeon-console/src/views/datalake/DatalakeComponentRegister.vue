<template>
  <div class="page-container">
    <div class="page-header">
      <div class="breadcrumb">
        <router-link to="/datalake/components" class="breadcrumb-link">组件库</router-link>
        <span class="breadcrumb-sep"> / </span>
        <span>注册组件</span>
      </div>
    </div>

    <div class="register-form">
      <div class="form-section">
        <h3>基本信息</h3>
        <div class="form-row">
          <label>组件名称 <span class="required">*</span></label>
          <input v-model="form.name" placeholder="video_scene_split（英文标识符）" />
          <div class="field-help">Python 模块名格式，小写字母 + 下划线</div>
        </div>
        <div class="form-row">
          <label>显示名称 <span class="required">*</span></label>
          <input v-model="form.display_name" placeholder="视频镜头切分" />
        </div>
        <div class="form-row-pair">
          <div class="form-row">
            <label>类别 <span class="required">*</span></label>
            <select v-model="form.category">
              <option value="">请选择</option>
              <option value="DATA_PREP">数据准备</option>
              <option value="EXTRACT">提取</option>
              <option value="CLEAN">清洗</option>
              <option value="FILTER">过滤</option>
              <option value="QC">质检</option>
              <option value="LABEL">标注</option>
              <option value="PUBLISH">发布</option>
            </select>
          </div>
          <div class="form-row">
            <label>数据类型 <span class="required">*</span></label>
            <select v-model="form.data_type">
              <option value="">请选择</option>
              <option value="TEXT">文本</option>
              <option value="VIDEO">视频</option>
              <option value="IMAGE">图片</option>
              <option value="AUDIO">音频</option>
              <option value="DOCUMENT">文档</option>
              <option value="UNIVERSAL">通用</option>
            </select>
          </div>
        </div>
        <div class="form-row">
          <label>描述</label>
          <textarea v-model="form.description" placeholder="组件功能描述..." rows="3"></textarea>
        </div>
      </div>

      <div class="form-section">
        <h3>执行配置</h3>
        <div class="form-row">
          <label>入口函数 <span class="required">*</span></label>
          <input v-model="form.entrypoint" placeholder="lakeon.components.video.scene_split" />
          <div class="field-help">Python 模块路径，指向 @Component 装饰的函数</div>
        </div>
        <div class="form-row-pair">
          <div class="form-row">
            <label>执行模式</label>
            <select v-model="form.execution_mode">
              <option value="FUNCTION">函数执行</option>
              <option value="HUMAN_REVIEW">人工审核</option>
            </select>
          </div>
          <div class="form-row">
            <label>GPU 要求</label>
            <label class="toggle-label">
              <input type="checkbox" v-model="form.requires_gpu" />
              <span>需要 GPU</span>
            </label>
          </div>
        </div>
        <div class="form-row" v-if="form.requires_gpu">
          <label>模型标识</label>
          <input v-model="form.requires_model" placeholder="pyscenedetect (可选)" />
        </div>
      </div>

      <div class="form-section">
        <h3>Schema 配置</h3>
        <div class="form-row">
          <label>参数 Schema (JSON)</label>
          <textarea v-model="form.params_schema" placeholder='{"threshold": {"type": "number", "default": 27, "description": "切分灵敏度"}}' rows="5" class="mono-textarea"></textarea>
          <div class="field-help">JSON Schema 格式，定义组件参数。前端 DAG 编辑器据此自动渲染表单。</div>
        </div>
        <div class="form-row">
          <label>输出分支 (逗号分隔)</label>
          <input v-model="branchesInput" placeholder="passed, needs_crop, dropped" />
          <div class="field-help">条件分支组件声明多个输出端口，留空表示单输出。</div>
        </div>
      </div>

      <div class="form-section">
        <h3>代码文件</h3>
        <div class="form-row">
          <label>上传 .py 文件</label>
          <div class="upload-area" @click="fileInput?.click()" @drop.prevent="onFileDrop" @dragover.prevent>
            <div v-if="!uploadedFile">
              <div class="upload-icon">+</div>
              <div class="upload-text">点击或拖拽上传 Python 文件</div>
            </div>
            <div v-else class="uploaded-file">
              <span>{{ uploadedFile.name }}</span>
              <span class="file-size">{{ (uploadedFile.size / 1024).toFixed(1) }} KB</span>
            </div>
          </div>
          <input ref="fileInput" type="file" accept=".py" style="display: none;" @change="onFileSelect" />
          <div class="field-help">组件代码文件将上传到 OBS 存储。</div>
        </div>
      </div>

      <!-- 提交 -->
      <div class="form-actions">
        <button class="btn btn-secondary" @click="router.push('/datalake/components')">取消</button>
        <button class="btn btn-primary" @click="handleSubmit" :disabled="submitting || !isValid">
          {{ submitting ? '注册中...' : '注册组件' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { registerComponent, type RegisterComponentRequest, type ComponentCategory, type ComponentDataType } from '@/api/pipeline'

const router = useRouter()
const submitting = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const uploadedFile = ref<File | null>(null)
const branchesInput = ref('')

const form = ref<{
  name: string
  display_name: string
  category: ComponentCategory | ''
  data_type: ComponentDataType | ''
  description: string
  entrypoint: string
  execution_mode: 'FUNCTION' | 'HUMAN_REVIEW'
  requires_gpu: boolean
  requires_model: string
  params_schema: string
}>({
  name: '',
  display_name: '',
  category: '',
  data_type: '',
  description: '',
  entrypoint: '',
  execution_mode: 'FUNCTION',
  requires_gpu: false,
  requires_model: '',
  params_schema: '',
})

const isValid = computed(() =>
  form.value.name &&
  form.value.display_name &&
  form.value.category &&
  form.value.data_type &&
  form.value.entrypoint
)

function onFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.[0]) uploadedFile.value = input.files[0]
}

function onFileDrop(event: DragEvent) {
  const file = event.dataTransfer?.files[0]
  if (file && file.name.endsWith('.py')) uploadedFile.value = file
}

async function handleSubmit() {
  if (!isValid.value) return
  submitting.value = true

  try {
    const branches = branchesInput.value
      ? branchesInput.value.split(',').map(s => s.trim()).filter(Boolean)
      : undefined

    const body: RegisterComponentRequest = {
      name: form.value.name,
      display_name: form.value.display_name,
      category: form.value.category as ComponentCategory,
      data_type: form.value.data_type as ComponentDataType,
      description: form.value.description || undefined,
      entrypoint: form.value.entrypoint,
      execution_mode: form.value.execution_mode,
      requires_gpu: form.value.requires_gpu,
      requires_model: form.value.requires_model || undefined,
      params_schema: form.value.params_schema || undefined,
      output_branches: branches,
    }

    await registerComponent(body)
    // TODO: 上传 .py 文件到 OBS（需要 OBS STS 接口）
    router.push('/datalake/components')
  } catch (err) {
    console.error('Failed to register component', err)
    alert('注册失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.register-form { max-width: 640px; }
.form-section { margin-bottom: 24px; }
.form-section h3 { font-size: 14px; font-weight: 600; color: #2c3e50; margin: 0 0 12px; padding-bottom: 6px; border-bottom: 1px solid #f0ede8; }

.form-row { margin-bottom: 12px; }
.form-row label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
.required { color: #c6333a; }
.form-row input, .form-row select, .form-row textarea {
  width: 100%; padding: 6px 10px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 13px; outline: none; background: #fff;
}
.form-row input:focus, .form-row select:focus, .form-row textarea:focus { border-color: #2a4d6a; }
.mono-textarea { font-family: monospace; font-size: 12px; }
.field-help { font-size: 11px; color: #bbb; margin-top: 3px; }

.form-row-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

.toggle-label {
  display: flex; align-items: center; gap: 6px; font-size: 13px; color: #2c3e50;
  cursor: pointer; margin-top: 4px;
}

.upload-area {
  border: 2px dashed #e8e4df; border-radius: 8px; padding: 24px;
  text-align: center; cursor: pointer; transition: border-color 0.12s;
}
.upload-area:hover { border-color: #2a4d6a; }
.upload-icon { font-size: 24px; color: #ccc; }
.upload-text { font-size: 12px; color: #999; margin-top: 4px; }
.uploaded-file { display: flex; align-items: center; justify-content: center; gap: 8px; }
.uploaded-file span { font-size: 13px; color: #2c3e50; }
.file-size { color: #999; font-size: 11px; }

.form-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 16px; border-top: 1px solid #e8e4df; }

.breadcrumb { font-size: 13px; color: #999; }
.breadcrumb-link { color: #2a4d6a; text-decoration: none; }
.breadcrumb-link:hover { text-decoration: underline; }
.breadcrumb-sep { margin: 0 4px; }
</style>
