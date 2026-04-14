<template>
  <div class="storage-panel">
    <!-- Summary Cards -->
    <div class="storage-summary">
      <div class="summary-card primary">
        <div class="summary-value">{{ formatBytes(summary?.totalObsBytes ?? 0) }}</div>
        <div class="summary-label">OBS 总用量</div>
      </div>
      <div class="summary-card blue">
        <div class="summary-value">{{ formatBytes(summary?.totalDbBytes ?? 0) }}</div>
        <div class="summary-label">数据库存储</div>
      </div>
      <div class="summary-card green">
        <div class="summary-value">{{ formatBytes(summary?.totalKbDocBytes ?? 0) }}</div>
        <div class="summary-label">知识库文档</div>
      </div>
      <div class="summary-card red" v-if="summary && summary.orphanBytes > 0">
        <div class="summary-value">{{ formatBytes(summary.orphanBytes) }}</div>
        <div class="summary-label">孤儿对象</div>
      </div>
    </div>

    <!-- Toolbar -->
    <div class="storage-toolbar">
      <h3 class="toolbar-title">租户存储明细</h3>
      <button class="scan-btn" :disabled="scanning" @click="runScan">
        <span v-if="scanning" class="spinner"></span>
        {{ scanning ? '扫描中...' : '刷新 OBS 扫描' }}
      </button>
    </div>

    <!-- Tenant Table -->
    <div class="section-card" style="margin-top: 12px;">
      <div v-if="loading" class="loading-text">加载中...</div>
      <div v-else-if="!tenants.length && !orphans.length" class="empty-text">暂无存储数据，请先执行 OBS 扫描</div>
      <div class="table-wrapper" v-else>
        <table class="data-table storage-table">
          <thead>
            <tr>
              <th style="width: 32px;"></th>
              <th>租户</th>
              <th>数据库</th>
              <th>知识库</th>
              <th>记忆库</th>
              <th>总量</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <!-- Normal tenants -->
            <template v-for="t in tenants" :key="t.tenantId">
              <tr class="tenant-row" @click="toggleExpand(t.tenantId)">
                <td class="expand-cell">
                  <span class="expand-arrow" :class="{ expanded: expandedTenants.has(t.tenantId) }">&#9654;</span>
                </td>
                <td class="tenant-name">{{ t.tenantName || t.tenantId }}</td>
                <td>{{ formatBytes(t.dbBytes) }}</td>
                <td>{{ formatBytes(t.kbDocBytes) }}</td>
                <td>{{ formatBytes(t.memoryBytes) }}</td>
                <td class="total-cell">{{ formatBytes(t.totalBytes) }}</td>
                <td><span class="status-tag" :class="t.status">{{ t.status }}</span></td>
              </tr>
              <!-- Sub items -->
              <template v-if="expandedTenants.has(t.tenantId)">
                <tr v-for="item in t.items" :key="item.id" class="sub-row">
                  <td></td>
                  <td colspan="1" class="sub-name">
                    <span class="type-badge" :class="item.type">{{ typeLabel(item.type) }}</span>
                    {{ item.name || item.id }}
                  </td>
                  <td>{{ formatBytes(item.dbBytes) }}</td>
                  <td>{{ formatBytes(item.kbDocBytes) }}</td>
                  <td>{{ formatBytes(item.memoryBytes) }}</td>
                  <td>{{ formatBytes((item.dbBytes ?? 0) + (item.kbDocBytes ?? 0) + (item.memoryBytes ?? 0)) }}</td>
                  <td><span class="status-tag" :class="item.status">{{ item.status }}</span></td>
                </tr>
              </template>
            </template>

            <!-- Orphan rows -->
            <tr v-for="o in orphans" :key="'orphan-' + o.tenantId" class="orphan-row">
              <td></td>
              <td class="tenant-name">
                {{ o.tenantId }}
                <span class="deleted-tag">已删除</span>
              </td>
              <td colspan="3">--</td>
              <td class="total-cell">{{ formatBytes(o.bytes) }}</td>
              <td>
                <button class="cleanup-btn" :disabled="cleaningUp === o.tenantId" @click.stop="startCleanup(o.tenantId)">
                  {{ cleaningUp === o.tenantId ? '清理中...' : '清理' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Footer -->
    <div class="storage-footer">
      <span class="footer-note">数据来源: OBS 对象扫描 + 数据库元数据</span>
      <span class="footer-time" v-if="summary?.lastScanTime">
        上次扫描: {{ formatTime(summary.lastScanTime) }}
      </span>
      <span class="footer-time" v-else>尚未执行过扫描</span>
    </div>

    <!-- Confirm Dialog -->
    <div v-if="confirmDialog" class="dialog-overlay" @click.self="confirmDialog = null">
      <div class="dialog-box">
        <h4>确认清理</h4>
        <p>将删除租户 <strong>{{ confirmDialog.tenantId }}</strong> 的 <strong>{{ confirmDialog.count }}</strong> 个孤儿对象，共 <strong>{{ formatBytes(confirmDialog.bytes) }}</strong>。</p>
        <p style="color: #e07070; font-size: 13px;">此操作不可撤销。</p>
        <div class="dialog-actions">
          <button class="dialog-cancel" @click="confirmDialog = null">取消</button>
          <button class="dialog-confirm" @click="doCleanup">确认清理</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

interface StorageItem {
  type: string
  id: string
  name: string
  status: string
  dbBytes: number
  kbDocBytes: number
  memoryBytes: number
}

interface TenantStorage {
  tenantId: string
  tenantName: string
  status: string
  dbBytes: number
  kbDocBytes: number
  memoryBytes: number
  totalBytes: number
  items: StorageItem[]
}

interface OrphanEntry {
  tenantId: string
  bytes: number
}

interface StorageSummary {
  totalObsBytes: number
  totalDbBytes: number
  totalKbDocBytes: number
  orphanBytes: number
  tenants: TenantStorage[]
  orphans: OrphanEntry[]
  lastScanTime: string | null
}

const summary = ref<StorageSummary | null>(null)
const tenants = ref<TenantStorage[]>([])
const orphans = ref<OrphanEntry[]>([])
const expandedTenants = ref(new Set<string>())
const loading = ref(true)
const scanning = ref(false)
const cleaningUp = ref<string | null>(null)
const confirmDialog = ref<{ tenantId: string; count: number; bytes: number } | null>(null)

function normalizeTenant(t: any): TenantStorage {
  const items: StorageItem[] = []
  for (const db of t.databases ?? []) {
    items.push({
      type: 'database', id: db.id, name: db.name,
      status: db.status ?? '-',
      dbBytes: db.logical_size ?? 0, kbDocBytes: 0, memoryBytes: 0,
    })
  }
  for (const kb of t.knowledge_bases ?? []) {
    items.push({
      type: 'knowledge', id: kb.id, name: kb.name,
      status: kb.status ?? '-',
      dbBytes: 0, kbDocBytes: kb.obs_size ?? 0, memoryBytes: 0,
    })
  }
  for (const mb of t.memory_bases ?? []) {
    items.push({
      type: 'memory', id: mb.id, name: mb.name,
      status: mb.status ?? '-',
      dbBytes: 0, kbDocBytes: 0, memoryBytes: mb.db_logical_size ?? 0,
    })
  }
  return {
    tenantId: t.tenant_id,
    tenantName: t.tenant_name,
    status: t.status ?? 'active',
    dbBytes: t.db_total_size ?? 0,
    kbDocBytes: t.kb_total_obs_size ?? 0,
    memoryBytes: t.mem_total_size ?? 0,
    totalBytes: t.total_size ?? 0,
    items,
  }
}

async function loadData() {
  loading.value = true
  try {
    const res = await adminApi.storageSummary()
    const raw = res.data as any
    summary.value = {
      totalObsBytes: raw.total_doc_obs_size ?? 0,
      totalDbBytes: (raw.total_db_size ?? 0) + (raw.total_mem_size ?? 0),
      totalKbDocBytes: raw.total_doc_obs_size ?? 0,
      orphanBytes: raw.orphan_bytes ?? 0,
      tenants: [],
      orphans: raw.orphans ?? [],
      lastScanTime: raw.last_scan_time ?? null,
    }
    tenants.value = (raw.tenants ?? []).map(normalizeTenant)
    orphans.value = (raw.orphans ?? []).map((o: any) => ({
      tenantId: o.tenant_id ?? o.tenantId,
      bytes: o.bytes ?? 0,
    }))
  } catch (e) {
    console.error('Failed to load storage summary', e)
  } finally {
    loading.value = false
  }
}

async function runScan() {
  scanning.value = true
  try {
    await adminApi.storageScan()
    await loadData()
  } catch (e) {
    console.error('Storage scan failed', e)
  } finally {
    scanning.value = false
  }
}

function toggleExpand(tenantId: string) {
  const s = new Set(expandedTenants.value)
  if (s.has(tenantId)) s.delete(tenantId)
  else s.add(tenantId)
  expandedTenants.value = s
}

async function startCleanup(tenantId: string) {
  cleaningUp.value = tenantId
  try {
    const res = await adminApi.storageCleanup(tenantId, true)
    const dryResult = res.data as { deletedCount: number; deletedBytes: number }
    confirmDialog.value = {
      tenantId,
      count: dryResult.deletedCount ?? 0,
      bytes: dryResult.deletedBytes ?? 0,
    }
  } catch (e) {
    console.error('Dry-run cleanup failed', e)
  } finally {
    cleaningUp.value = null
  }
}

async function doCleanup() {
  if (!confirmDialog.value) return
  const tid = confirmDialog.value.tenantId
  confirmDialog.value = null
  cleaningUp.value = tid
  try {
    await adminApi.storageCleanup(tid, false)
    await loadData()
  } catch (e) {
    console.error('Cleanup failed', e)
  } finally {
    cleaningUp.value = null
  }
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  const val = bytes / Math.pow(1024, i)
  return val.toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch {
    return iso
  }
}

function typeLabel(type: string): string {
  const map: Record<string, string> = { database: '数据库', knowledge_base: '知识库', memory_base: '记忆库' }
  return map[type] ?? type
}

onMounted(loadData)
</script>

<style scoped>
.storage-panel {
  position: relative;
}

.storage-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.summary-card {
  background: #2a2520;
  border: 1px solid #3d3530;
  border-radius: 10px;
  padding: 20px;
  text-align: center;
}
.summary-card.primary {
  border-color: #c9a96e44;
  background: linear-gradient(135deg, #2a2520, #33291e);
}
.summary-card.blue {
  border-color: #7eb8da44;
}
.summary-card.green {
  border-color: #a8d5a244;
}
.summary-card.red {
  border-color: #e0707044;
  background: linear-gradient(135deg, #2a2520, #33201e);
}
.summary-value {
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 4px;
}
.summary-card.primary .summary-value { color: #c9a96e; }
.summary-card.blue .summary-value { color: #7eb8da; }
.summary-card.green .summary-value { color: #a8d5a2; }
.summary-card.red .summary-value { color: #e07070; }
.summary-label {
  font-size: 13px;
  color: #a09080;
}

.storage-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.toolbar-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #e8d8c8;
}
.scan-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #c9a96e22;
  color: #c9a96e;
  border: 1px solid #c9a96e44;
  border-radius: 6px;
  padding: 6px 14px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.2s;
}
.scan-btn:hover:not(:disabled) {
  background: #c9a96e33;
}
.scan-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid #c9a96e44;
  border-top-color: #c9a96e;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.loading-text, .empty-text {
  padding: 32px;
  text-align: center;
  color: #a09080;
  font-size: 14px;
}

.storage-table th {
  text-align: left;
}

.tenant-row {
  cursor: pointer;
}
.tenant-row:hover {
  background: #332d2544 !important;
}
.expand-cell {
  text-align: center;
  width: 32px;
}
.expand-arrow {
  display: inline-block;
  font-size: 10px;
  color: #a09080;
  transition: transform 0.2s;
}
.expand-arrow.expanded {
  transform: rotate(90deg);
}
.tenant-name {
  font-weight: 500;
}
.total-cell {
  font-weight: 600;
  color: #c9a96e;
}

.sub-row {
  background: #1e1b1844;
}
.sub-row td {
  font-size: 13px;
  color: #b0a090;
}
.sub-name {
  padding-left: 12px !important;
}
.type-badge {
  display: inline-block;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  margin-right: 6px;
}
.type-badge.database { background: #7eb8da22; color: #7eb8da; }
.type-badge.knowledge_base { background: #a8d5a222; color: #a8d5a2; }
.type-badge.memory_base { background: #d4a8d522; color: #d4a8d5; }

.status-tag {
  display: inline-block;
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 4px;
  background: #a0908022;
  color: #a09080;
}
.status-tag.active { background: #a8d5a222; color: #a8d5a2; }
.status-tag.deleted { background: #e0707022; color: #e07070; }

.orphan-row {
  background: #e070701a !important;
}
.orphan-row td {
  color: #e0a0a0;
}
.deleted-tag {
  display: inline-block;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #e0707033;
  color: #e07070;
  margin-left: 8px;
}
.cleanup-btn {
  background: #e0707022;
  color: #e07070;
  border: 1px solid #e0707044;
  border-radius: 5px;
  padding: 3px 12px;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.2s;
}
.cleanup-btn:hover:not(:disabled) {
  background: #e0707044;
}
.cleanup-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.storage-footer {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
  font-size: 12px;
  color: #807060;
}
.footer-note, .footer-time {
  opacity: 0.8;
}

/* Confirm Dialog */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.dialog-box {
  background: #2a2520;
  border: 1px solid #3d3530;
  border-radius: 12px;
  padding: 24px;
  max-width: 420px;
  width: 90%;
}
.dialog-box h4 {
  margin: 0 0 12px 0;
  font-size: 16px;
  color: #e8d8c8;
}
.dialog-box p {
  font-size: 14px;
  color: #b0a090;
  margin: 8px 0;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}
.dialog-cancel {
  background: transparent;
  color: #a09080;
  border: 1px solid #3d3530;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
  font-size: 13px;
}
.dialog-cancel:hover {
  background: #3d353022;
}
.dialog-confirm {
  background: #e0707022;
  color: #e07070;
  border: 1px solid #e0707044;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
  font-size: 13px;
}
.dialog-confirm:hover {
  background: #e0707044;
}
</style>
