<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">记忆库</h1>
      <div class="page-header-actions">
        <ViewToggle v-model="viewMode" />
        <button class="btn btn-primary" @click="showCreate = true; createStep = 1; resetCreateForm()">创建记忆库</button>
      </div>
    </div>

    <!-- Create dialog — two-step wizard -->
    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate = false">
      <div class="dialog-box" style="max-width: 520px;">
        <div class="dialog-header">
          <h3>{{ createStep === 1 ? '选择应用场景' : '配置记忆库' }}</h3>
          <button class="dialog-close" @click="showCreate = false">&times;</button>
        </div>
        <div class="dialog-body">
          <!-- Step 1: Scene selection -->
          <template v-if="createStep === 1">
            <div style="display: flex; flex-direction: column; gap: 12px;">
              <div class="scene-card" :class="{ selected: createForm.scene === 'DEVELOPER_TOOL' }"
                   @click="createForm.scene = 'DEVELOPER_TOOL'; createStep = 2">
                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px;">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#64748b" stroke-width="2"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
                  <span style="font-weight: 600; font-size: 15px;">开发者工具</span>
                </div>
                <div style="font-size: 13px; color: #666; line-height: 1.6;">
                  适用于 Claude Code、Cursor 等编码助手。记录事实、流程、决策和教训，不记录对话情景，不自动衰减。
                </div>
              </div>
              <div class="scene-card" :class="{ selected: createForm.scene === 'CHAT_ASSISTANT' }"
                   @click="createForm.scene = 'CHAT_ASSISTANT'; createStep = 2">
                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px;">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#64748b" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                  <span style="font-weight: 600; font-size: 15px;">对话助理</span>
                </div>
                <div style="font-size: 13px; color: #666; line-height: 1.6;">
                  适用于聊天机器人、个人助理、客服等。记录完整对话情景，自动提炼用户特征，支持时间衰减。
                </div>
              </div>
            </div>
          </template>

          <!-- Step 2: Details -->
          <template v-else>
            <!-- Scene summary (clickable to go back) -->
            <div class="scene-summary" @click="createStep = 1">
              <span>{{ createForm.scene === 'DEVELOPER_TOOL' ? '开发者工具' : '对话助理' }}</span>
              <span style="color: #9a5b25; font-size: 12px;">更改</span>
            </div>

            <div class="form-group">
              <label class="form-label">名称 <span style="color:#e6393d">*</span></label>
              <input v-model="createForm.name" class="form-input" placeholder="例如：用户偏好记忆库" />
            </div>
            <div class="form-group">
              <label class="form-label">描述</label>
              <input v-model="createForm.description" class="form-input" placeholder="可选，描述记忆库用途" />
            </div>

            <!-- Advanced options (collapsed by default) -->
            <div v-if="createForm.type === 'BUILTIN'" style="margin-top: 4px;">
              <button type="button" @click="showAdvanced = !showAdvanced"
                      style="background: none; border: none; color: #9a5b25; cursor: pointer; font-size: 13px; padding: 0; display: flex; align-items: center; gap: 4px;">
                <span style="display: inline-block; transition: transform 0.15s;" :style="showAdvanced ? 'transform: rotate(90deg)' : ''">&#x25b6;</span>
                高级选项
              </button>

              <div v-if="showAdvanced" style="margin-top: 12px; padding-top: 12px; border-top: 1px solid #f0ece8;">
                <!-- Embedding model -->
                <div class="form-group">
                  <label class="form-label">嵌入模型</label>
                  <select v-model="createForm.embedding_model" class="form-input" style="cursor: pointer;">
                    <option value="BAAI/bge-m3">BAAI/bge-m3</option>
                    <option value="text-embedding-3-small">text-embedding-3-small</option>
                  </select>
                </div>

                <!-- Agent-Extract mode -->
                <div class="form-group">
                  <label class="form-label">提取模式</label>
                  <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px;">
                    <input type="checkbox" v-model="createForm.agent_extract" style="width: 16px; height: 16px;" />
                    Agent-Extract 模式
                  </label>
                  <p style="font-size: 12px; color: #999; margin-top: 4px;">
                    {{ createForm.agent_extract
                      ? '客户端（如 Claude Code）自行提取记忆，零服务端 LLM 成本。'
                      : '服务端自动提取记忆（默认）。' }}
                  </p>
                </div>

                <!-- Encryption -->
                <div class="form-group">
                  <label class="form-label">端到端加密</label>
                  <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px;">
                    <input type="checkbox" v-model="createForm.encrypted" style="width: 16px; height: 16px;" />
                    启用客户端加密
                  </label>
                  <p style="font-size: 12px; color: #999; margin-top: 4px;">
                    记忆内容在本地加密后上传，服务端无法查看明文。
                  </p>
                </div>

                <template v-if="createForm.encrypted">
                  <div class="form-group">
                    <label class="form-label">加密密码 <span style="color:#e6393d">*</span></label>
                    <input v-model="createForm.password" type="password" class="form-input" placeholder="设置加密密码" autocomplete="new-password" />
                  </div>
                  <div class="form-group">
                    <label class="form-label">确认密码 <span style="color:#e6393d">*</span></label>
                    <input v-model="createForm.passwordConfirm" type="password" class="form-input" placeholder="再次输入密码" autocomplete="new-password" />
                  </div>
                  <div style="padding: 8px 12px; background: #fff7ed; border-radius: 6px; font-size: 12px; color: #8c7a68; line-height: 1.6;">
                    &#x26a0; 密码丢失将无法恢复数据。请妥善保管密码。
                  </div>
                </template>
              </div>
            </div>
          </template>
        </div>
        <div v-if="createStep === 2" class="dialog-footer">
          <button class="btn btn-default" @click="createStep = 1">上一步</button>
          <button class="btn btn-primary" @click="handleCreate" :disabled="!createForm.name.trim() || creating || (createForm.encrypted && (!createForm.password || createForm.password !== createForm.passwordConfirm))">
            {{ creating ? '创建中...' : '创建' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Journey guide banner (collapsible) -->
    <div v-if="showGuide" style="margin-bottom: 20px; background: #faf8f5; border: 1px solid #e8e0d8; border-radius: 10px; padding: 20px 24px; position: relative;">
      <button @click="showGuide = false" style="position: absolute; right: 12px; top: 10px; background: none; border: none; color: #bbb; cursor: pointer; font-size: 16px; padding: 2px 6px;" title="收起">&times;</button>
      <div style="text-align: center; margin-bottom: 14px;">
        <span style="font-size: 15px; font-weight: 600; color: #2c2420;">记忆构建流程</span>
        <span style="font-size: 12px; color: #999; margin-left: 12px;">对话产生记忆，AI 自动提炼并持久化，下次对话更懂你</span>
      </div>
      <div style="display: flex; gap: 10px; max-width: 640px; margin: 0 auto;">
        <div class="guide-card">
          <div class="guide-num" style="background: #6b8e8a;">1</div>
          <div class="guide-title">对话</div>
          <div class="guide-desc">通过 MCP 接入，自然对话</div>
        </div>
        <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
        <div class="guide-card">
          <div class="guide-num" style="background: #7a9e7e;">2</div>
          <div class="guide-title">提取</div>
          <div class="guide-desc">AI 自动提取事实、偏好和决策</div>
        </div>
        <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
        <div class="guide-card">
          <div class="guide-num" style="background: #8c7a68;">3</div>
          <div class="guide-title">积累</div>
          <div class="guide-desc">记忆持久化，跨会话可用</div>
        </div>
        <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
        <div class="guide-card">
          <div class="guide-num" style="background: #a89080;">4</div>
          <div class="guide-title">回忆</div>
          <div class="guide-desc">下次对话自动召回相关记忆</div>
        </div>
      </div>
    </div>
    <div v-if="!showGuide" style="margin-bottom: 12px;">
      <button @click="showGuide = true" style="background: none; border: none; color: #9a5b25; cursor: pointer; font-size: 12px; padding: 0;">显示记忆构建流程引导</button>
    </div>

    <div style="border-bottom: 1px solid #e8e0d8; margin-bottom: 20px;"></div>

    <!-- Memory base list -->
    <!-- Card view -->
    <div v-if="viewMode === 'card' && memoryBases.length > 0" class="card-grid" style="margin-top: 20px;">
      <ResourceCard
        v-for="item in memoryBases"
        :key="item.id"
        :name="item.name"
        :status="item.status"
        :statusLabel="statusText(item.status)"
        :meta="[item.scene === 'DEVELOPER_TOOL' ? '开发者工具' : item.scene === 'CHAT_ASSISTANT' ? '对话助理' : '-', typeText(item.type), item.encrypted ? '端到端加密' : '', `${item.memory_count ?? 0} 记忆`].filter(Boolean)"
        @click="handleRowClick(item)"
      >
        <template #actions>
          <CardMenu @delete="handleDelete(item)" />
        </template>
      </ResourceCard>
      <div class="card-create" @click="showCreate = true; createStep = 1; resetCreateForm()">
        + 创建记忆库
      </div>
    </div>

    <!-- Table view -->
    <div v-if="viewMode === 'table' && memoryBases.length > 0" style="margin-top: 20px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>记忆数</th>
            <th>特征数</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in memoryBases" :key="item.id" style="cursor: pointer;" @click="handleRowClick(item)">
            <td style="font-weight: 500; color: #9a5b25;">
              {{ item.name }}
              <span v-if="item.scene" style="font-size: 11px; padding: 1px 6px; border-radius: 3px; margin-left: 8px;"
                    :style="item.scene === 'DEVELOPER_TOOL' ? 'background:#e8f5e9;color:#2e7d32' : 'background:#fdf5ed;color:#1565c0'">
                {{ item.scene === 'DEVELOPER_TOOL' ? '开发者工具' : '对话助理' }}
              </span>
              <span v-if="item.encrypted" style="font-size: 11px; padding: 1px 6px; border-radius: 3px; margin-left: 6px; background: #f0f0f0; color: #666;">
                &#x1f512; 加密
              </span>
            </td>
            <td>
              <span v-if="item.type === 'BUILTIN'" class="status-tag tag-orange">自研</span>
              <span v-else-if="item.type === 'MEM0'" class="status-tag tag-blue">mem0</span>
              <span v-else-if="item.type === 'HINDSIGHT'" class="status-tag tag-green">hindsight</span>
              <span v-else class="status-tag tag-gray">自定义</span>
            </td>
            <td>{{ item.memory_count ?? 0 }}</td>
            <td>{{ item.trait_count ?? 0 }}</td>
            <td>
              <span class="status-tag" :class="item.status === 'READY' ? (item.database_status === 'RUNNING' ? 'tag-green' : 'tag-gray') : item.status === 'FAILED' ? 'tag-red' : 'tag-gray'">
                {{ statusText(item.status) }}
              </span>
            </td>
            <td style="color: #999;">{{ formatTime(item.created_at) }}</td>
            <td @click.stop>
              <button class="btn btn-text btn-small btn-danger-text" @click="handleDelete(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-if="memoryBases.length === 0 && !loading" class="empty-state" style="margin-top: 64px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/>
        <circle cx="12" cy="12" r="10"/>
        <line x1="12" y1="17" x2="12.01" y2="17"/>
      </svg>
      <p style="color: #666; margin-top: 12px;">还没有记忆库</p>
      <p style="color: #999; font-size: 13px;">创建记忆库后，AI 将自动管理用户记忆与特征</p>
    </div>

    <!-- Quick tips -->
    <div v-if="!loading && memoryBases.length > 0 && memoryBases.length < 3" class="page-tips">
      <div class="page-tips-title">快速上手</div>
      <div class="page-tips-items">
        <span>通过 MCP 接入 Claude Code，自动积累开发记忆</span>
        <span class="tips-sep">·</span>
        <span>在"反思洞察"中查看 AI 提炼的用户特征</span>
        <span class="tips-sep">·</span>
        <router-link to="/docs#memory" class="tips-link">查看文档</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listMemoryBases, createMemoryBase, deleteMemoryBase, type MemoryBase } from '../../api/memory'
import ViewToggle from '../../components/ViewToggle.vue'
import ResourceCard from '../../components/ResourceCard.vue'
import CardMenu from '../../components/CardMenu.vue'

const router = useRouter()
const viewMode = ref<'card' | 'table'>('card')
const memoryBases = ref<MemoryBase[]>([])
const showGuide = ref(true)
const showCreate = ref(false)
const createStep = ref(1)
const showAdvanced = ref(false)
const loading = ref(false)

const creating = ref(false)

const createForm = ref({
  name: '',
  description: '',
  type: 'BUILTIN' as MemoryBase['type'],
  scene: '' as string,
  embedding_model: 'BAAI/bge-m3',
  agent_extract: false,
  encrypted: false,
  password: '',
  passwordConfirm: '',
})

function statusText(status: string) {
  const map: Record<string, string> = { READY: '就绪', CREATING: '创建中', FAILED: '失败' }
  return map[status] || status
}

function typeText(type: string) {
  const map: Record<string, string> = { BUILTIN: '自研', MEM0: 'mem0', HINDSIGHT: 'hindsight' }
  return map[type] || '自定义'
}

function formatTime(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

function resetCreateForm() {
  createForm.value = {
    name: '',
    description: '',
    type: 'BUILTIN',
    scene: '',
    embedding_model: 'BAAI/bge-m3',
    agent_extract: false,
    encrypted: false,
    password: '',
    passwordConfirm: '',
  }
}

// ---------------------------------------------------------------------------
// Browser-side encryption helpers (Web Crypto API)
// ---------------------------------------------------------------------------

function arrayBufferToBase64(buf: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(buf)))
}

async function generateEncryptionKeys(password: string) {
  // 1. Generate RSA-4096 key pair
  const keyPair = await crypto.subtle.generateKey(
    { name: 'RSA-OAEP', modulusLength: 4096, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true, ['encrypt', 'decrypt']
  )

  // 2. Export keys
  const publicKeySpki = await crypto.subtle.exportKey('spki', keyPair.publicKey)
  const privateKeyPkcs8 = await crypto.subtle.exportKey('pkcs8', keyPair.privateKey)

  // 3. Format as PEM
  const publicPem = `-----BEGIN PUBLIC KEY-----\n${arrayBufferToBase64(publicKeySpki).match(/.{1,64}/g)!.join('\n')}\n-----END PUBLIC KEY-----`
  const privatePem = `-----BEGIN PRIVATE KEY-----\n${arrayBufferToBase64(privateKeyPkcs8).match(/.{1,64}/g)!.join('\n')}\n-----END PRIVATE KEY-----`

  // 4. Generate DEK (256-bit) and salt (16-byte)
  const dek = crypto.getRandomValues(new Uint8Array(32))
  const salt = crypto.getRandomValues(new Uint8Array(16))

  // 5. Derive key from password using PBKDF2 (browser doesn't have scrypt, use PBKDF2 with high iterations)
  const enc = new TextEncoder()
  const passwordKey = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveKey'])
  const derivedKey = await crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations: 600000, hash: 'SHA-256' },
    passwordKey, { name: 'AES-GCM', length: 256 }, false, ['encrypt']
  )

  // 6. Encrypt private key with derived key (AES-256-GCM)
  const nonce = crypto.getRandomValues(new Uint8Array(12))
  const encryptedPrivateKey = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce }, derivedKey, new TextEncoder().encode(privatePem)
  )
  const encryptedPrivateKeyB64 = arrayBufferToBase64(new Uint8Array([...nonce, ...new Uint8Array(encryptedPrivateKey)]).buffer)

  // 7. Encrypt DEK with public key (RSA-OAEP)
  const encryptedDek = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, keyPair.publicKey, dek)
  const encryptedDekB64 = arrayBufferToBase64(encryptedDek)

  return {
    publicPem,
    encryptedPrivateKeyB64,
    encryptedDekB64,
    saltB64: arrayBufferToBase64(salt.buffer),
  }
}

function downloadConfigFile(memId: string, config: Record<string, any>) {
  const content = JSON.stringify({ [memId]: config }, null, 2)
  const blob = new Blob([content], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `encrypted_bases_${memId}.json`
  a.click()
  URL.revokeObjectURL(url)
}

async function loadMemoryBases() {
  loading.value = true
  try {
    const res = await listMemoryBases()
    memoryBases.value = res.data
  } catch (e: any) {
    console.error('Failed to load memory bases:', e)
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  creating.value = true
  try {
    const { name, description, type, scene, embedding_model, agent_extract, encrypted, password } = createForm.value
    const options: Record<string, any> = { type, scene }
    if (type === 'BUILTIN' && embedding_model) {
      options.embedding_model = embedding_model
    }
    if (type === 'BUILTIN' && agent_extract) {
      options.one_llm_mode = true
    }

    let configForDownload: Record<string, any> | null = null

    if (encrypted) {
      // Browser-side crypto
      const keys = await generateEncryptionKeys(password)
      options.encrypted = true
      options.encrypted_dek = keys.encryptedDekB64
      options.kdf_salt = keys.saltB64
      options.embedding_dim = 1024 // Default DBay embedding dim

      configForDownload = {
        public_key: keys.publicPem,
        encrypted_private_key: keys.encryptedPrivateKeyB64,
        kdf_salt: keys.saltB64,
        kdf_algorithm: 'pbkdf2',
        embedding_provider: 'dbay',
        embedding_dim: 1024,
      }
    }

    const res = await createMemoryBase(name, description || undefined, options)

    if (encrypted && configForDownload) {
      downloadConfigFile(res.data.id, configForDownload)
      alert(
        `加密记忆库已创建：${res.data.id}\n\n` +
        `请完成以下设置：\n` +
        `1. 将下载的配置文件内容合并到 ~/.dbay/encrypted_bases.json\n` +
        `2. 创建 ~/.dbay/secret 文件，写入：\n   DBAY_ENCRYPTION_PASSWORD=${password}\n` +
        `3. MCP 将自动加解密，无需其他操作`
      )
    }

    showCreate.value = false
    resetCreateForm()
    await loadMemoryBases()
  } catch (e: any) {
    alert('创建失败: ' + (e.response?.data?.error?.message || e.message))
  } finally {
    creating.value = false
  }
}

async function handleDelete(item: MemoryBase) {
  if (!confirm(`确认删除记忆库"${item.name}"？所有记忆和特征数据将被永久删除。`)) return
  try {
    await deleteMemoryBase(item.id)
    await loadMemoryBases()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

function handleRowClick(item: MemoryBase) {
  router.push('/memory/' + item.id)
}

onMounted(loadMemoryBases)
</script>

<style scoped>
.guide-card {
  flex: 1;
  background: #fff;
  border: 1px solid #e8e0d8;
  border-radius: 8px;
  padding: 14px 10px;
  text-align: center;
}
.guide-num {
  width: 28px; height: 28px; border-radius: 50%; color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 600; margin-bottom: 6px;
}
.guide-title { font-size: 14px; font-weight: 600; color: #3d3d3d; margin-bottom: 2px; }
.guide-desc { font-size: 11px; color: #8c7a68; line-height: 1.4; }
.type-radio {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #555;
  flex: 1;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
  user-select: none;
}
.type-radio:hover {
  border-color: #c67d3a;
  color: #9a5b25;
}
.type-radio.selected {
  border-color: #c67d3a;
  background: #fdf5ed;
  color: #9a5b25;
  font-weight: 500;
}
.scene-card {
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.scene-card:hover {
  border-color: #c67d3a;
}
.scene-card.selected {
  border-color: #c67d3a;
  background: #fdf5ed;
  box-shadow: 0 0 0 1px #c67d3a;
}
.scene-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #f7f8fa;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  margin-bottom: 16px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}
.scene-summary:hover {
  border-color: #c67d3a;
}
.page-tips {
  margin-top: 48px;
  padding: 16px 20px;
  background: color-mix(in oklch, var(--c-accent) 6%, #fff);
  border: 1px solid color-mix(in oklch, var(--c-accent) 20%, var(--c-border-light));
  border-radius: 6px;
}
.page-tips-title {
  font-size: 13px;
  font-weight: 600;
  color: #2c3e50;
  margin-bottom: 6px;
}
.page-tips-items {
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
}
.tips-sep {
  margin: 0 8px;
  color: #d5d0ca;
}
.tips-link {
  color: #9a5b25;
  text-decoration: none;
}
.tips-link:hover {
  text-decoration: underline;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.card-create {
  border: 1px dashed #d5d0ca;
  border-radius: 8px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.card-create:hover { border-color: #94a3b8; }
@media (max-width: 768px) {
  .card-grid { grid-template-columns: 1fr; }
}
</style>
