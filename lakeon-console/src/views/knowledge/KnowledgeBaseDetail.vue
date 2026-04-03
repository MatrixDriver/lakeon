<template>
  <div class="page-container">
    <!-- Breadcrumb -->
    <div class="breadcrumb" style="margin-bottom: 16px;">
      <router-link to="/knowledge" style="color: #9a5b25; text-decoration: none;">知识库</router-link>
      <span style="margin: 0 8px; color: #ccc;">/</span>
      <span style="color: #333;">{{ kb?.name || '...' }}</span>
    </div>

    <div class="page-header">
      <h1 class="page-title">{{ kb?.name || '加载中...' }}</h1>
    </div>

    <!-- TABLE type KB: delegate to TableKbDetail -->
    <TableKbDetail v-if="kb && kb.type === 'TABLE'" :kb="kb" />

    <!-- DOCUMENT type KB (or legacy without type) -->
    <template v-if="!kb || kb.type !== 'TABLE'">

    <!-- Tabs -->
    <div class="tab-bar" style="margin-top: 20px; border-bottom: 1px solid #e5e5e5; display: flex; gap: 0;">
      <div v-for="tab in tabs" :key="tab.key"
           class="tab-item"
           :class="{ active: activeTab === tab.key }"
           @click="activeTab = tab.key">
        {{ tab.label }}
      </div>
    </div>

    <!-- Overview Tab -->
    <div v-if="activeTab === 'overview'" style="margin-top: 24px;">
      <div class="section-card" style="max-width: 600px;">
        <div class="section-header">概览</div>
        <div style="padding: 16px; display: grid; grid-template-columns: 120px 1fr; gap: 12px; font-size: 14px;">
          <span style="color: #999;">名称</span><span>{{ kb?.name }}</span>
          <span style="color: #999;">描述</span><span>{{ kb?.description || '-' }}</span>
          <span style="color: #999;">文档数</span><span>{{ kb?.document_count ?? 0 }}</span>
          <span style="color: #999;">Embedding 模型</span><span>BGE-M3 (1024维)</span>
          <span style="color: #999;">切片策略</span><span>结构化切片 (400 tokens)</span>
          <span style="color: #999;">状态</span>
          <span><span class="status-tag" :class="'tag-' + (kb?.status === 'READY' ? 'green' : 'blue')">{{ kb?.status === 'READY' ? '就绪' : kb?.status }}</span></span>
          <span style="color: #999;">创建时间</span><span>{{ kb?.created_at ? new Date(kb.created_at).toLocaleString('zh-CN') : '-' }}</span>
          <span style="color: #999;">底层数据库</span>
          <span v-if="kb?.database_id">
            <router-link :to="'/databases/' + kb.database_id" style="color: #2563eb; text-decoration: none;">{{ kb.database_id }}</router-link>
          </span>
          <span v-else>-</span>
        </div>
      </div>
      <div v-if="kb?.summary" class="section-card" style="max-width: 600px; margin-top: 16px;">
        <div class="section-header">知识库概览</div>
        <div style="padding: 16px; font-size: 14px; line-height: 1.8; color: #333; white-space: pre-wrap;">{{ kb.summary }}</div>
      </div>
      <!-- Danger zone -->
      <div class="section-card" style="max-width: 600px; margin-top: 32px; border-color: #f0d0d0;">
        <div class="section-header" style="color: #e6393d;">危险操作</div>
        <div style="padding: 16px; display: flex; justify-content: space-between; align-items: center;">
          <div>
            <div style="font-size: 14px; font-weight: 500;">删除知识库</div>
            <div style="font-size: 12px; color: #999; margin-top: 2px;">删除后所有文档、切片和索引数据将被永久清除，不可恢复。</div>
          </div>
          <button class="btn btn-danger" style="flex-shrink: 0;" @click="handleDeleteKb">删除知识库</button>
        </div>
      </div>
    </div>

    <!-- Documents Tab -->
    <div v-if="activeTab === 'documents'" style="margin-top: 24px;">
      <!-- Status filter tabs -->
      <div v-if="docStats.total > 0" style="display: flex; gap: 0; margin-bottom: 14px; border-bottom: 1px solid #e8e8e8;">
        <div v-for="tab in [
          { key: undefined, label: '全部', count: docStats.total },
          { key: 'PROCESSING', label: '处理中', count: docStats.processing },
          { key: 'READY', label: '已就绪', count: docStats.ready },
          { key: 'FAILED', label: '失败', count: docStats.failed },
        ]" :key="tab.label"
          style="padding: 8px 16px; font-size: 13px; cursor: pointer; transition: color 0.2s;"
          :style="{
            color: docStatusFilter === tab.key ? '#1890ff' : '#666',
            borderBottom: docStatusFilter === tab.key ? '2px solid #1890ff' : '2px solid transparent',
            fontWeight: docStatusFilter === tab.key ? 500 : 400,
          }"
          @click="setStatusFilter(tab.key)">
          {{ tab.label }} ({{ tab.count }})
        </div>
        <div style="flex: 1;"></div>
        <div style="padding: 8px 0; font-size: 12px; color: #999; align-self: center;">
          共 {{ docTotal }} 条
        </div>
      </div>

      <div style="margin-bottom: 16px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
        <label class="btn btn-primary" style="cursor: pointer;" :class="{ disabled: uploading || kb?.status !== 'READY' }">
          上传文件
          <input type="file" accept=".pdf,.docx,.doc,.xlsx,.xls,.xlsm,.pptx,.epub,.html,.htm,.md,.markdown,.txt" multiple style="display: none;" :disabled="uploading || kb?.status !== 'READY'" @change="handleUpload" />
        </label>
        <label class="btn btn-secondary" style="cursor: pointer;" :class="{ disabled: uploading || kb?.status !== 'READY' }">
          上传目录
          <input type="file" style="display: none;" :disabled="uploading || kb?.status !== 'READY'" webkitdirectory @change="handleDirectoryUpload" />
        </label>
        <span v-if="kb?.status === 'CREATING'" style="color: #c87a20; font-size: 13px;">知识库正在创建中，请稍候...</span>
        <span v-else style="color: #999; font-size: 13px;">支持 PDF、DOCX、DOC、XLSX、XLS、PPTX、EPUB、HTML、Markdown、TXT，最多 20 个/批</span>
      </div>

      <!-- Combined progress card -->
      <div v-if="uploading || uploadJustFinished || docStats.processing > 0 || docStats.pending > 0" style="background: #fff; border: 1px solid #e8e8e8; border-radius: 8px; padding: 14px 18px; margin-bottom: 16px;">
        <div v-if="uploading || uploadJustFinished" style="display: flex; align-items: center; gap: 10px; margin-bottom: 10px;">
          <span style="font-size: 12px; color: #666; width: 56px; flex-shrink: 0;">上传</span>
          <div style="flex: 1; height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden;">
            <div :style="{ width: (uploadProgress.length > 0 ? Math.round(uploadProgress.filter(f => f.status === 'done' || f.status === 'processing' || f.status === 'error').length / uploadProgress.length * 100) : 0) + '%', height: '100%', background: '#1890ff', borderRadius: '3px', transition: 'width 0.3s' }"></div>
          </div>
          <span style="font-size: 13px; color: #333; min-width: 75px; text-align: right;">
            {{ uploadProgress.filter(f => f.status === 'done' || f.status === 'processing').length }}/{{ uploadProgress.length }}
          </span>
          <span v-if="uploading && uploadStats.speed > 0" style="font-size: 11px; color: #999; min-width: 160px;">
            {{ formatSpeed(uploadStats.speed) }} &middot; 预计还需 {{ formatEta(uploadStats.eta) }}
          </span>
          <span v-else-if="!uploading" style="font-size: 11px; color: #52c41a;">上传完成</span>
        </div>
        <div v-if="docStats.processing > 0 || docStats.pending > 0" style="display: flex; align-items: center; gap: 10px;">
          <span style="font-size: 12px; color: #666; width: 56px; flex-shrink: 0;">处理</span>
          <div style="flex: 1; height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden;">
            <div :style="{ width: (docStats.total > 0 ? Math.round((docStats.ready + docStats.failed) / docStats.total * 100) : 0) + '%', height: '100%', background: '#52c41a', borderRadius: '3px', transition: 'width 0.3s' }"></div>
          </div>
          <span style="font-size: 13px; color: #333; min-width: 75px; text-align: right;">
            {{ docStats.ready + docStats.failed }}/{{ docStats.total }} ({{ docStats.total > 0 ? Math.round((docStats.ready + docStats.failed) / docStats.total * 100) : 0 }}%)
          </span>
          <span style="font-size: 11px; color: #999; min-width: 160px;">
            <span v-if="docStats.failed > 0" style="color: #e6393d;">{{ docStats.failed }} 失败</span>
          </span>
        </div>
        <div v-if="docStats.processing > 0 || docStats.pending > 0" style="font-size: 11px; color: #999; margin-top: 6px; padding-left: 66px;">
          排队 {{ docStats.pending }} &middot; 解析中 {{ docStats.processing - embeddingCount }} &middot; Embedding {{ embeddingCount }} &middot; 已完成 {{ docStats.ready }} &middot; 失败 {{ docStats.failed }}
        </div>
      </div>

      <TableToolbar v-model="docSearch" placeholder="搜索文件名" :loading="docLoading" @refresh="loadDocuments">
        <template #extra>
          <button v-if="docStats.failed > 0" style="background: #fff; color: #1890ff; border: 1px solid #1890ff; border-radius: 4px; padding: 4px 12px; cursor: pointer; font-size: 12px; white-space: nowrap;" :disabled="retryingFailed" @click="handleRetryAllFailed">
            {{ retryingFailed ? '重试中...' : `重试全部失败 (${docStats.failed})` }}
          </button>
          <button v-if="selectedDocIds.size > 0" style="background: #e6393d; color: #fff; border: none; border-radius: 4px; padding: 4px 12px; cursor: pointer; font-size: 12px; white-space: nowrap;" @click="handleBatchDelete">
            删除选中 ({{ selectedDocIds.size }})
          </button>
          <button v-if="documents.length > 0" style="background: #fff; color: #e6393d; border: 1px solid #e6393d; border-radius: 4px; padding: 4px 12px; cursor: pointer; font-size: 12px; white-space: nowrap;" @click="handleClearAll">
            清空文档
          </button>
        </template>
      </TableToolbar>
      <!-- Breadcrumb navigation -->
      <div v-if="currentFolder" style="margin-bottom: 12px; display: flex; align-items: center; gap: 4px; font-size: 14px;">
        <span class="breadcrumb-link" @click="navigateToFolder('')">全部文档</span>
        <template v-for="(segment, i) in folderPath" :key="i">
          <span style="color: #999; margin: 0 2px;">/</span>
          <span v-if="i < folderPath.length - 1" class="breadcrumb-link"
                @click="navigateToFolder(folderPath.slice(0, i + 1).join('/'))">
            {{ segment }}
          </span>
          <span v-else style="color: #333; font-weight: 500;">{{ segment }}</span>
        </template>
      </div>

      <!-- Folder grid -->
      <div v-if="folders.length > 0" style="display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; margin-bottom: 16px;">
        <div v-for="folder in folders" :key="folder.path"
             class="folder-card"
             @click="navigateToFolder(folder.path)">
          <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#e6a23c" stroke-width="2">
              <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
            </svg>
            <span style="font-weight: 500; font-size: 14px;">{{ folder.name }}</span>
          </div>
          <div style="font-size: 12px; color: #999;">
            {{ folder.document_count }} 个文档 · {{ formatSize(folder.total_size) }}
          </div>
        </div>
      </div>

      <div v-if="filteredDocs.length > 0" class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 36px; text-align: center;">
                <input type="checkbox" ref="selectAllCheckbox" :checked="isAllSelected" @change="toggleSelectAll" style="cursor: pointer;">
              </th>
              <th>文件名</th>
              <th>格式</th>
              <th style="cursor: pointer; user-select: none;" @click="setSort('size')">大小 {{ sortIcon('size') }}</th>
              <th style="cursor: pointer; user-select: none;" @click="setSort('chunks')">Chunks {{ sortIcon('chunks') }}</th>
              <th style="cursor: pointer; user-select: none;" @click="setSort('status')">状态 {{ sortIcon('status') }}</th>
              <th style="cursor: pointer; user-select: none;" @click="setSort('upload_time')">上传时间 {{ sortIcon('upload_time') }}</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="doc in filteredDocs" :key="doc.id" class="clickable-row" @click="router.push({ name: 'DocumentDetail', params: { kbId: route.params.kbId, docId: doc.id } })">
              <td style="text-align: center;" @click.stop>
                <input type="checkbox" :checked="selectedDocIds.has(doc.id)" @change="toggleSelect(doc.id)" style="cursor: pointer;">
              </td>
              <td>
                <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
                  <span style="font-weight: 500;">{{ doc.filename }}</span>
                  <span v-for="tag in (doc.tags || [])" :key="tag" class="tag-badge">{{ tag }}</span>
                  <button class="btn-icon" title="编辑标签" @click.stop="openTagDialog(doc)">
                    <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                    </svg>
                  </button>
                </div>
              </td>
              <td><span class="tag-blue" style="font-size: 11px; padding: 1px 6px; border-radius: 3px;">{{ doc.format }}</span></td>
              <td style="color: #666;">{{ formatSize(doc.size_bytes) }}</td>
              <td>{{ doc.chunks_count ?? '-' }}</td>
              <td>
                <div style="display: flex; flex-direction: column; gap: 4px;">
                  <div style="display: flex; align-items: center; gap: 6px;">
                    <span class="status-dot" :style="{ background: docStatusColor(doc.status) }"></span>
                    <span>{{ docStatusText(doc.status) }}</span>
                  </div>
                  <!-- Progress bar for PROCESSING -->
                  <div v-if="doc.status === 'PROCESSING' && doc.progress != null" style="display: flex; align-items: center; gap: 8px;">
                    <div style="flex: 1; height: 4px; background: #e5e5e5; border-radius: 4px; max-width: 120px;">
                      <div :style="{ width: Math.round(doc.progress * 100) + '%', height: '100%', background: '#1890ff', borderRadius: '2px', transition: 'width 0.3s' }"></div>
                    </div>
                    <span style="color: #1890ff; font-size: 12px; white-space: nowrap;">{{ Math.round(doc.progress * 100) }}%</span>
                  </div>
                  <div v-if="doc.status === 'PROCESSING' && doc.progress_message" style="color: #999; font-size: 11px;">
                    {{ doc.progress_message }}
                  </div>
                  <!-- Error with click to expand -->
                  <div v-if="doc.status === 'FAILED' && doc.error"
                       style="color: #e6393d; font-size: 12px; cursor: pointer; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                       :title="doc.error"
                       @click.stop="showErrorDetail(doc)">
                    {{ doc.error }}
                  </div>
                </div>
              </td>
              <td style="color: #999;">{{ doc.created_at ? new Date(doc.created_at).toLocaleString('zh-CN') : '-' }}</td>
              <td @click.stop>
                <button v-if="doc.status === 'FAILED'" class="btn btn-text btn-small" style="color: #1890ff;" @click="handleRetryDoc(doc)">重试</button>
                <button class="btn btn-text btn-small btn-danger-text" @click="handleDeleteDoc(doc)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Pagination -->
      <div v-if="docTotal > docPageSize" style="display: flex; justify-content: space-between; align-items: center; margin-top: 14px; font-size: 12px; color: #999;">
        <div>每页 {{ docPageSize }} 条</div>
        <div style="display: flex; gap: 4px; align-items: center;">
          <button class="page-btn" :disabled="docPage <= 1" @click="setPage(docPage - 1)">&lsaquo;</button>
          <template v-for="p in paginationPages" :key="p">
            <span v-if="p === '...'" style="padding: 3px 6px; color: #999;">...</span>
            <button v-else class="page-btn" :class="{ active: p === docPage }" @click="setPage(p as number)">{{ p }}</button>
          </template>
          <button class="page-btn" :disabled="docPage >= totalPages" @click="setPage(docPage + 1)">&rsaquo;</button>
        </div>
      </div>

      <div v-if="filteredDocs.length === 0 && !docLoading" class="empty-state" style="margin-top: 48px; text-align: center;">
        <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="#ccc" stroke-width="1.5">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
          <polyline points="14 2 14 8 20 8"/>
        </svg>
        <p style="color: #999; margin-top: 12px;">还没有文档，点击"上传文档"开始</p>
      </div>
    </div>

    <!-- Datasources Tab -->
    <div v-if="activeTab === 'datasources'" style="margin-top: 24px;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
        <h3 style="margin: 0; font-size: 16px;">OBS 数据源</h3>
        <button class="btn btn-primary" @click="dsCreateDialog = true">添加数据源</button>
      </div>

      <div v-if="datasources.length === 0 && !dsLoading" class="empty-state" style="padding: 40px; text-align: center; color: #999;">
        暂无数据源。点击"添加数据源"创建一个 OBS 目录，将文件批量上传后同步到知识库。
      </div>

      <div v-for="ds in datasources" :key="ds.id" class="section-card" style="margin-bottom: 16px; padding: 16px;">
        <div style="display: flex; justify-content: space-between; align-items: flex-start;">
          <div>
            <div style="font-weight: 600; font-size: 15px;">{{ ds.name }}</div>
            <div style="color: #999; font-size: 12px; margin-top: 4px;">
              <code style="background: #f5f5f5; padding: 2px 6px; border-radius: 3px;">obs://{{ ds.obs_prefix }}</code>
            </div>
            <div style="display: flex; gap: 16px; margin-top: 8px; font-size: 13px; color: #666;">
              <span>{{ ds.file_count }} 个文件</span>
              <span v-if="ds.last_synced_at">上次同步: {{ new Date(ds.last_synced_at).toLocaleString('zh-CN') }}</span>
              <span v-else>未同步</span>
              <span :style="{ color: ds.status === 'SYNCING' ? '#1890ff' : ds.status === 'ERROR' ? '#e6393d' : '#52c41a' }">
                {{ ds.status === 'ACTIVE' ? '正常' : ds.status === 'SYNCING' ? '同步中...' : '错误' }}
              </span>
            </div>
            <div v-if="ds.last_sync_stats" style="margin-top: 6px; font-size: 12px; color: #999;">
              新增 {{ ds.last_sync_stats.added }} / 修改 {{ ds.last_sync_stats.modified }} / 删除 {{ ds.last_sync_stats.deleted }} / 跳过 {{ ds.last_sync_stats.skipped }}
            </div>
            <div v-if="ds.error" style="margin-top: 6px; font-size: 12px; color: #e6393d;">{{ ds.error }}</div>
          </div>
          <div style="display: flex; gap: 8px; flex-shrink: 0;">
            <button class="btn btn-text" @click="handleGetCredentials(ds.id)">上传凭据</button>
            <button class="btn btn-primary" :disabled="dsSyncing.has(ds.id)" @click="handleSyncDs(ds.id)">
              {{ dsSyncing.has(ds.id) ? '同步中...' : '同步' }}
            </button>
            <button class="btn btn-danger-text" @click="handleDeleteDs(ds.id)">删除</button>
          </div>
        </div>

        <!-- Credentials panel -->
        <div v-if="dsCredentials && dsCredentials.dsId === ds.id" style="margin-top: 16px; padding: 16px; background: #f8f9fa; border-radius: 6px; font-size: 13px;">
          <div style="font-weight: 600; margin-bottom: 12px;">上传指引</div>
          <p style="margin-bottom: 8px;">将文件上传到以下 OBS 目录，然后点击"同步"将文件导入知识库：</p>
          <code style="display: block; background: #fff; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; word-break: break-all;">
            obs://{{ dsCredentials.creds.bucket }}/{{ dsCredentials.creds.prefix }}
          </code>
          <div style="margin-bottom: 8px; font-weight: 500;">支持格式：PDF、DOCX、DOC、XLSX、XLS、PPTX、EPUB、HTML、Markdown、TXT（支持子目录）</div>
          <div style="margin-bottom: 8px; font-weight: 500;">方式一：hcloud CLI</div>
          <pre style="background: #fff; padding: 8px 12px; border-radius: 4px; overflow-x: auto; font-size: 12px; margin-bottom: 12px; white-space: pre-wrap; word-break: break-all;">{{ dsCredentials.creds.upload_commands.hcloud }}</pre>
          <div style="margin-bottom: 8px; font-weight: 500;">方式二：obsutil</div>
          <pre style="background: #fff; padding: 8px 12px; border-radius: 4px; overflow-x: auto; font-size: 12px; margin-bottom: 12px; white-space: pre-wrap; word-break: break-all;">{{ dsCredentials.creds.upload_commands.obsutil }}</pre>
          <div style="margin-bottom: 8px; font-weight: 500;">方式三：华为云 OBS Console 网页端</div>
          <p style="margin: 0; color: #666;">登录华为云 Console → 对象存储服务 → {{ dsCredentials.creds.bucket }} → 进入 {{ dsCredentials.creds.prefix }} 目录 → 上传</p>
          <div style="margin-top: 12px; color: #faad14; font-size: 12px;">
            凭据有效期至 {{ new Date(dsCredentials.creds.expires_at).toLocaleString('zh-CN') }}，过期后重新获取。
          </div>
          <button class="btn btn-text" style="margin-top: 8px;" @click="dsCredentials = null">收起</button>
        </div>
      </div>

      <!-- Create dialog -->
      <div v-if="dsCreateDialog" class="modal-overlay" @click.self="dsCreateDialog = false">
        <div class="modal-box" style="max-width: 400px;">
          <div class="modal-header">
            <span>添加数据源</span>
            <button class="btn-icon" @click="dsCreateDialog = false">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          <div class="modal-body">
            <label class="form-label">数据源名称</label>
            <input v-model="dsCreateName" class="form-input" placeholder="例如：产品文档" @keyup.enter="handleCreateDs" />
          </div>
          <div class="modal-footer">
            <button class="btn btn-text" @click="dsCreateDialog = false">取消</button>
            <button class="btn btn-primary" :disabled="!dsCreateName.trim()" @click="handleCreateDs">创建</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Chunks Tab -->
    <div v-if="activeTab === 'chunks'" style="margin-top: 8px;">
      <ChunkStats :kb-id="(route.params.kbId as string)" />
    </div>

    <!-- Search Tab -->
    <div v-if="activeTab === 'search'" style="margin-top: 24px;">
      <!-- Search input -->
      <div style="display: flex; gap: 8px; max-width: 600px;">
        <input
          ref="chatInput"
          v-model="searchQuery"
          class="form-input"
          placeholder="输入查询语句检索分片..."
          :disabled="isSearching"
          @keyup.enter="handleSearch"
          style="flex: 1;"
        />
        <button class="btn btn-primary" style="flex-shrink: 0;" :disabled="!searchQuery.trim() || isSearching" @click="handleSearch">
          {{ isSearching ? '检索中...' : '检索' }}
        </button>
      </div>

      <!-- Tag filter -->
      <div v-if="allTags.length > 0" style="margin-top: 12px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center;">
        <span style="font-size: 12px; color: #999; margin-right: 4px;">标签过滤:</span>
        <span v-for="tag in allTags" :key="tag"
              class="tag-badge tag-filter"
              :class="{ 'tag-filter-active': searchFilterTags.includes(tag) }"
              @click="toggleFilterTag(tag)">
          {{ tag }}
        </span>
      </div>

      <!-- Results -->
      <div style="margin-top: 20px;">
        <!-- Empty state -->
        <div v-if="!searchResults && !isSearching" style="text-align: center; padding: 48px 0; color: #bbb;">
          <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="currentColor" stroke-width="1.5" style="margin: 0 auto;">
            <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <p style="margin-top: 10px; font-size: 13px;">语义检索 + 全文检索（RRF 融合排序）</p>
        </div>

        <!-- Loading -->
        <div v-if="isSearching" style="text-align: center; padding: 32px 0; color: #999; font-size: 13px;">检索中...</div>

        <!-- No results -->
        <div v-if="searchResults && searchResults.length === 0 && !isSearching" style="text-align: center; padding: 32px 0; color: #999; font-size: 13px;">
          未找到相关分片
        </div>

        <!-- Result info bar -->
        <div v-if="searchResults && searchResults.length > 0" style="font-size: 12px; color: #999; margin-bottom: 12px;">
          找到 {{ searchResults.length }} 个分片
          <span v-if="searchRewrittenQuery" style="margin-left: 12px; font-style: italic;">查询改写: <span style="color: #9a5b25;">{{ searchRewrittenQuery }}</span></span>
        </div>

        <!-- Chunk result cards -->
        <div v-if="searchResults && searchResults.length > 0" style="display: flex; flex-direction: column; gap: 8px;">
          <div v-for="(r, ri) in searchResults" :key="ri" class="search-chunk-card">
            <div class="search-chunk-header">
              <span class="search-chunk-index">#{{ ri + 1 }}</span>
              <span class="search-chunk-score">{{ r.score?.toFixed(3) }}</span>
              <span v-if="r.level === 1" class="search-chunk-level1">摘要</span>
              <span v-if="r.metadata?.filename" class="search-chunk-source">{{ r.metadata.filename }}</span>
              <span v-if="r.metadata?.section" class="search-chunk-section">{{ r.metadata.section }}</span>
            </div>
            <div class="search-chunk-content">{{ r.content }}</div>
          </div>
        </div>
      </div>
    </div>

    </template><!-- end DOCUMENT type -->

    <!-- Error Detail Dialog -->
    <div v-if="errorDetail.open" class="modal-overlay" @click.self="errorDetail.open = false">
      <div class="modal-box" style="max-width: 600px;">
        <div class="modal-header">
          <span>处理失败详情</span>
          <button class="btn-icon" @click="errorDetail.open = false">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <p style="font-size: 13px; color: #666; margin-bottom: 8px;">
            文档: <strong>{{ errorDetail.filename }}</strong>
          </p>
          <pre style="font-size: 13px; color: #e6393d; background: #fff5f5; border: 1px solid #fee; border-radius: 4px; padding: 12px; white-space: pre-wrap; word-break: break-word; max-height: 300px; overflow-y: auto;">{{ errorDetail.error }}</pre>
        </div>
        <div class="modal-footer">
          <button class="btn btn-text" @click="errorDetail.open = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- Tag Edit Dialog -->
    <div v-if="tagDialog.open" class="modal-overlay" @click.self="tagDialog.open = false">
      <div class="modal-box">
        <div class="modal-header">
          <span>编辑标签</span>
          <button class="btn-icon" @click="tagDialog.open = false">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <p style="font-size: 13px; color: #666; margin-bottom: 10px;">
            文档: <strong>{{ tagDialog.doc?.filename }}</strong>
          </p>
          <label style="font-size: 13px; color: #555; display: block; margin-bottom: 6px;">
            标签（逗号分隔）
          </label>
          <input
            v-model="tagDialog.input"
            class="form-input"
            placeholder="例如: 技术文档, 2024, 重要"
            @keyup.enter="saveDocTags"
          />
          <p style="font-size: 12px; color: #aaa; margin-top: 6px;">多个标签用英文逗号分隔</p>
        </div>
        <div class="modal-footer">
          <button class="btn btn-text" @click="tagDialog.open = false">取消</button>
          <button class="btn btn-primary" :disabled="tagDialog.saving" @click="saveDocTags">
            {{ tagDialog.saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getKnowledgeBase, deleteKnowledgeBase, listDocuments, listFolders, getDocumentStats, deleteDocument, clearAllDocuments, searchKnowledge, setDocumentTags, batchGetUploadUrls, batchProcessDocuments, ingestDocuments, listDataSources, createDataSource, deleteDataSource, syncDataSource, getDataSourceCredentials, type KnowledgeBase as KBType, type Document, type DocumentStats, type SearchResult, type DataSource, type DataSourceCredentials, type Folder } from '../../api/knowledge'
import ChunkStats from '../../components/knowledge/ChunkStats.vue'
import TableKbDetail from '../../components/knowledge/TableKbDetail.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { formatSize } from '../../utils/format'

const route = useRoute()
const router = useRouter()

const kb = ref<KBType | null>(null)
const documents = ref<Document[]>([])
const activeTab = ref('documents')
const searchQuery = ref('')
const searchFilterTags = ref<string[]>([])

// Chat-style search state
const searchResults = ref<SearchResult[] | null>(null)
const searchRewrittenQuery = ref<string | null>(null)
const isSearching = ref(false)
const uploading = ref(false)
const uploadJustFinished = ref(false)
const docLoading = ref(false)

interface UploadFileState {
  filename: string
  status: 'pending' | 'uploading' | 'processing' | 'done' | 'error'
  error?: string
}
const uploadProgress = ref<UploadFileState[]>([])
const docSearch = ref('')

const docPage = ref(1)
const docPageSize = ref(50)
const docTotal = ref(0)
const docStatusFilter = ref<string | undefined>(undefined)
const docSortBy = ref('upload_time')
const docSortOrder = ref<'asc' | 'desc'>('desc')
const docStats = ref<DocumentStats>({ total: 0, processing: 0, ready: 0, failed: 0, pending: 0 })
const embeddingCount = computed(() =>
  documents.value.filter(d => d.status === 'PROCESSING' && d.progress_message && /embedding/i.test(d.progress_message)).length
)
const chatInput = ref<HTMLInputElement | null>(null)

// Folder navigation
const currentFolder = ref('')
const folders = ref<Folder[]>([])
const foldersLoading = ref(false)

const folderPath = computed(() => currentFolder.value ? currentFolder.value.split('/') : [])

const errorDetail = ref<{ open: boolean; filename: string; error: string }>({
  open: false, filename: '', error: ''
})

function showErrorDetail(doc: Document) {
  errorDetail.value = { open: true, filename: doc.filename, error: doc.error || '未知错误' }
}

const tagDialog = ref<{
  open: boolean
  doc: Document | null
  input: string
  saving: boolean
}>({ open: false, doc: null, input: '', saving: false })

const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'documents', label: '文档' },
  { key: 'datasources', label: '数据源' },
  { key: 'search', label: '搜索' },
  { key: 'chunks', label: '切片' },
]

// ── Data sources state ──
const datasources = ref<DataSource[]>([])
const dsLoading = ref(false)
const dsCreateDialog = ref(false)
const dsCreateName = ref('')
const dsCredentials = ref<{ dsId: string; creds: DataSourceCredentials } | null>(null)
const dsSyncing = ref<Set<string>>(new Set())

async function loadDataSources() {
  const kbId = route.params.kbId as string
  dsLoading.value = true
  try {
    const res = await listDataSources(kbId)
    datasources.value = res.data
  } finally {
    dsLoading.value = false
  }
}

async function handleCreateDs() {
  const kbId = route.params.kbId as string
  if (!dsCreateName.value.trim()) return
  await createDataSource(kbId, dsCreateName.value.trim())
  dsCreateName.value = ''
  dsCreateDialog.value = false
  await loadDataSources()
}

async function handleDeleteDs(dsId: string) {
  if (!confirm('删除数据源将同时删除其关联的所有文档和切片，确定？')) return
  const kbId = route.params.kbId as string
  await deleteDataSource(kbId, dsId)
  await Promise.all([loadDataSources(), loadDocuments()])
}

async function handleSyncDs(dsId: string) {
  const kbId = route.params.kbId as string
  dsSyncing.value = new Set([...dsSyncing.value, dsId])
  try {
    await syncDataSource(kbId, dsId)
    await Promise.all([loadDataSources(), loadDocuments()])
  } finally {
    const next = new Set(dsSyncing.value)
    next.delete(dsId)
    dsSyncing.value = next
  }
}

async function handleGetCredentials(dsId: string) {
  const kbId = route.params.kbId as string
  const res = await getDataSourceCredentials(kbId, dsId)
  dsCredentials.value = { dsId, creds: res.data }
}

const allTags = computed(() => {
  const tagSet = new Set<string>()
  for (const d of documents.value) {
    for (const t of (d.tags || [])) {
      tagSet.add(t)
    }
  }
  return Array.from(tagSet).sort()
})

function docStatusColor(s: string) {
  if (s === 'READY') return '#52c41a'
  if (s === 'PROCESSING') return '#1890ff'
  if (s === 'FAILED') return '#e6393d'
  return '#d9d9d9'
}

function docStatusText(s: string) {
  const map: Record<string, string> = { PENDING: '等待中', PROCESSING: '处理中', READY: '就绪', FAILED: '失败' }
  return map[s] || s
}


function openTagDialog(doc: Document) {
  tagDialog.value = {
    open: true,
    doc,
    input: (doc.tags || []).join(', '),
    saving: false,
  }
}

async function saveDocTags() {
  if (!tagDialog.value.doc) return
  tagDialog.value.saving = true
  const tags = tagDialog.value.input
    .split(',')
    .map(t => t.trim())
    .filter(t => t.length > 0)
  try {
    await setDocumentTags(tagDialog.value.doc.id, tags)
    const doc = documents.value.find(d => d.id === tagDialog.value.doc!.id)
    if (doc) doc.tags = tags
    tagDialog.value.open = false
  } finally {
    tagDialog.value.saving = false
  }
}

function toggleFilterTag(tag: string) {
  const idx = searchFilterTags.value.indexOf(tag)
  if (idx === -1) {
    searchFilterTags.value.push(tag)
  } else {
    searchFilterTags.value.splice(idx, 1)
  }
}

const filteredDocs = computed(() => {
  if (!docSearch.value) return documents.value
  const q = docSearch.value.toLowerCase()
  return documents.value.filter(d => d.filename.toLowerCase().includes(q))
})

async function loadDocuments() {
  const kbId = route.params.kbId as string
  docLoading.value = true
  try {
    const resp = await listDocuments(kbId, {
      page: docPage.value,
      page_size: docPageSize.value,
      status: docStatusFilter.value,
      sort_by: docSortBy.value,
      sort_order: docSortOrder.value,
      folder: currentFolder.value || undefined,
    })
    documents.value = resp.data.documents
    docTotal.value = resp.data.total
  } finally {
    docLoading.value = false
  }
}

async function loadStats() {
  const kbId = route.params.kbId as string
  try {
    const resp = await getDocumentStats(kbId)
    docStats.value = resp.data
  } catch { /* ignore */ }
}

async function loadFolders() {
  const kbId = route.params.kbId as string
  foldersLoading.value = true
  try {
    const resp = await listFolders(kbId, currentFolder.value)
    folders.value = resp.data
  } finally {
    foldersLoading.value = false
  }
}

function navigateToFolder(path: string) {
  currentFolder.value = path
  docPage.value = 1
  loadFolders()
  loadDocuments()
}

function setStatusFilter(status: string | undefined) {
  docStatusFilter.value = status
  docPage.value = 1
  loadDocuments()
  loadStats()
}

function setSort(field: string) {
  if (docSortBy.value === field) {
    if (docSortOrder.value === 'desc') {
      docSortOrder.value = 'asc'
    } else {
      docSortBy.value = 'upload_time'
      docSortOrder.value = 'desc'
    }
  } else {
    docSortBy.value = field
    docSortOrder.value = 'desc'
  }
  docPage.value = 1
  loadDocuments()
}

function sortIcon(field: string): string {
  if (docSortBy.value !== field) return '\u21D5'
  return docSortOrder.value === 'asc' ? '\u2191' : '\u2193'
}

function setPage(p: number) {
  docPage.value = p
  loadDocuments()
}

const totalPages = computed(() => Math.ceil(docTotal.value / docPageSize.value))

const paginationPages = computed(() => {
  const total = totalPages.value
  const current = docPage.value
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const pages: (number | string)[] = [1]
  if (current > 3) pages.push('...')
  for (let i = Math.max(2, current - 1); i <= Math.min(total - 1, current + 1); i++) {
    pages.push(i)
  }
  if (current < total - 2) pages.push('...')
  pages.push(total)
  return pages
})

const uploadStats = reactive({
  totalBytes: 0,
  uploadedBytes: 0,
  startTime: 0,
  speed: 0,
  eta: 0,
})

function formatSpeed(bytesPerSec: number): string {
  if (bytesPerSec < 1024) return `${Math.round(bytesPerSec)} B/s`
  if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`
  return `${(bytesPerSec / 1024 / 1024).toFixed(1)} MB/s`
}

function formatEta(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)} 秒`
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`
  return `${(seconds / 3600).toFixed(1)} 小时`
}

const SUPPORTED_EXTENSIONS = ['.pdf', '.docx', '.doc', '.xlsx', '.xls', '.xlsm', '.pptx', '.epub', '.html', '.htm', '.md', '.markdown', '.txt']

function filterSupportedFiles(files: File[]): File[] {
  return files.filter(f => SUPPORTED_EXTENSIONS.some(ext => f.name.toLowerCase().endsWith(ext)))
}

async function handleUpload(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const files = filterSupportedFiles(Array.from(input.files))
  if (!files.length) {
    alert('没有支持的文件格式（支持 PDF、DOCX、DOC、XLSX、XLS、PPTX、EPUB、HTML、Markdown、TXT）')
    input.value = ''
    return
  }
  await runBatchUpload(files)
  input.value = ''
}

async function handleDirectoryUpload(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const files = filterSupportedFiles(Array.from(input.files))
  if (!files.length) {
    alert('目录中没有支持的文件格式（支持 PDF、DOCX、EPUB、Markdown、TXT）')
    input.value = ''
    return
  }
  await runBatchUpload(files)
  input.value = ''
}

async function runBatchUpload(files: File[]) {
  const kbId = route.params.kbId as string
  uploading.value = true
  uploadProgress.value = files.map(f => ({ filename: f.name, status: 'pending' as const }))

  // Init upload speed tracking
  uploadStats.totalBytes = files.reduce((sum, f) => sum + f.size, 0)
  uploadStats.uploadedBytes = 0
  uploadStats.startTime = Date.now()
  uploadStats.speed = 0
  uploadStats.eta = 0
  const speedInterval = setInterval(() => {
    const elapsed = (Date.now() - uploadStats.startTime) / 1000
    if (elapsed > 0 && uploadStats.uploadedBytes > 0) {
      uploadStats.speed = uploadStats.uploadedBytes / elapsed
      const remaining = uploadStats.totalBytes - uploadStats.uploadedBytes
      uploadStats.eta = remaining / uploadStats.speed
    }
  }, 500)

  try {
    // Split into chunks of 20 for presigned URL batching
    const BATCH_SIZE = 20
    const allDocumentIds: string[] = []

    for (let batchStart = 0; batchStart < files.length; batchStart += BATCH_SIZE) {
      const batchFiles = files.slice(batchStart, batchStart + BATCH_SIZE)
      const batchIndices = batchFiles.map((_, i) => batchStart + i)

      // Get presigned URLs for this batch, including folder from webkitRelativePath
      const fileSpecs = batchFiles.map(f => {
        const spec: { filename: string; folder?: string } = { filename: f.name }
        if ((f as any).webkitRelativePath) {
          const parts = (f as any).webkitRelativePath.split('/')
          if (parts.length > 1) {
            spec.folder = parts.slice(0, -1).join('/')
          }
        }
        return spec
      })
      const urlResp = await batchGetUploadUrls(kbId, fileSpecs)
      const docItems = urlResp.data.documents

      // Concurrent PUT uploads (3 at a time)
      const CONCURRENCY = 3
      const documentIds: string[] = new Array(docItems.length)
      const uploadTasks = docItems.map((item, i) => async () => {
        const idx = batchIndices[i]!
        uploadProgress.value[idx] = { filename: item.filename, status: 'uploading' }
        const uploadResp = await fetch(item.upload_url, { method: 'PUT', body: batchFiles[i] })
        if (!uploadResp.ok) {
          uploadProgress.value[idx] = { filename: item.filename, status: 'error', error: `HTTP ${uploadResp.status}` }
          return null
        }
        uploadStats.uploadedBytes += batchFiles[i]!.size
        documentIds[i] = item.document_id
        return item.document_id
      })

      // Run with concurrency limit
      const results: (string | null)[] = []
      for (let i = 0; i < uploadTasks.length; i += CONCURRENCY) {
        const chunk = uploadTasks.slice(i, i + CONCURRENCY)
        const chunkResults = await Promise.all(chunk.map(t => t()))
        results.push(...chunkResults)
      }

      // Collect successfully uploaded document IDs
      const successIds = results.filter((id): id is string => id !== null)
      if (successIds.length === 0) continue

      // Mark as processing (ingest will be called after all batches complete)
      batchIndices.forEach((idx, i) => {
        if (results[i] !== null) {
          uploadProgress.value[idx] = { filename: files[idx]!.name, status: 'processing' }
        }
      })

      allDocumentIds.push(...successIds)
    }

    // Call ingest once for all uploaded documents
    if (allDocumentIds.length > 0) {
      await ingestDocuments(allDocumentIds)
    }
  } catch (err: any) {
    const serverMsg = err.response?.data?.error?.message || err.response?.data?.message
    alert(`上传失败: ${serverMsg || err.message || err}`)
  } finally {
    clearInterval(speedInterval)
    uploading.value = false
    uploadJustFinished.value = true
    await Promise.all([loadDocuments(), loadStats()])
  }
}

// ── Retry failed documents ──
const retryingFailed = ref(false)

async function handleRetryDoc(doc: Document) {
  await batchProcessDocuments([doc.id])
  await Promise.all([loadDocuments(), loadStats()])
  startPollingIfNeeded()
}

async function handleRetryAllFailed() {
  if (!confirm(`确认重试全部 ${docStats.value.failed} 个失败文档？`)) return
  retryingFailed.value = true
  try {
    // Fetch all failed doc IDs (paginate through all)
    const failedIds: string[] = []
    let page = 1
    while (true) {
      const resp = await listDocuments(route.params.kbId as string, { status: 'FAILED', page, page_size: 200 })
      failedIds.push(...resp.data.documents.map(d => d.id))
      if (failedIds.length >= resp.data.total) break
      page++
    }
    // Submit in batches of 20
    for (let i = 0; i < failedIds.length; i += 20) {
      await batchProcessDocuments(failedIds.slice(i, i + 20))
    }
    await Promise.all([loadDocuments(), loadStats()])
    startPollingIfNeeded()
  } finally {
    retryingFailed.value = false
  }
}

async function handleDeleteDoc(doc: Document) {
  if (!confirm(`确认删除文档"${doc.filename}"？`)) return
  await deleteDocument(doc.id)
  selectedDocIds.value.delete(doc.id)
  await Promise.all([loadDocuments(), loadStats()])
}

// ── Batch selection ──
const selectedDocIds = ref<Set<string>>(new Set())
const selectAllCheckbox = ref<HTMLInputElement | null>(null)

const isAllSelected = computed(() =>
  filteredDocs.value.length > 0 && filteredDocs.value.every(d => selectedDocIds.value.has(d.id))
)
const isIndeterminate = computed(() =>
  !isAllSelected.value && filteredDocs.value.some(d => selectedDocIds.value.has(d.id))
)

watch(isIndeterminate, (val) => {
  if (selectAllCheckbox.value) selectAllCheckbox.value.indeterminate = val
})

function toggleSelect(docId: string) {
  const s = new Set(selectedDocIds.value)
  if (s.has(docId)) s.delete(docId); else s.add(docId)
  selectedDocIds.value = s
}

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedDocIds.value = new Set()
  } else {
    selectedDocIds.value = new Set(filteredDocs.value.map(d => d.id))
  }
}

async function handleBatchDelete() {
  const count = selectedDocIds.value.size
  if (!confirm(`确认删除选中的 ${count} 个文档？`)) return
  const ids = [...selectedDocIds.value]
  for (const id of ids) {
    try { await deleteDocument(id) } catch (e) { console.error('Failed to delete', id, e) }
  }
  selectedDocIds.value = new Set()
  await Promise.all([loadDocuments(), loadStats()])
}

async function handleClearAll() {
  const total = docStats.value.total
  if (total === 0) return
  if (!confirm(`确认清空全部 ${total} 个文档？此操作不可恢复。`)) return
  await clearAllDocuments(route.params.kbId as string)
  selectedDocIds.value = new Set()
  await Promise.all([loadDocuments(), loadStats()])
}

async function handleDeleteKb() {
  const name = kb.value?.name || ''
  if (!confirm(`确认删除知识库"${name}"？所有文档和索引数据将被永久删除。`)) return
  try {
    await deleteKnowledgeBase(route.params.kbId as string)
    router.push('/knowledge')
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

async function handleSearch() {
  const query = searchQuery.value.trim()
  if (!query || isSearching.value) return
  const kbId = route.params.kbId as string

  isSearching.value = true
  searchResults.value = null
  searchRewrittenQuery.value = null

  try {
    const options: { tags?: string[] } = {}
    if (searchFilterTags.value.length > 0) options.tags = searchFilterTags.value

    const resp = await searchKnowledge(kbId, query, 10, options)
    searchResults.value = resp.data.results
    searchRewrittenQuery.value = resp.data.rewritten_query || null
  } catch {
    searchResults.value = []
  } finally {
    isSearching.value = false
    chatInput.value?.focus()
  }
}

// ── Auto-poll PROCESSING documents for progress ────────────────
let pollTimer: ReturnType<typeof setInterval> | null = null

function startPollingIfNeeded() {
  const hasActive = docStats.value.processing > 0 || docStats.value.pending > 0
  if (hasActive && !pollTimer) {
    pollTimer = setInterval(async () => {
      try {
        await Promise.all([loadDocuments(), loadStats()])
        if (docStats.value.processing === 0 && docStats.value.pending === 0) {
          stopPolling()
          uploadJustFinished.value = false
        }
      } catch { /* ignore */ }
    }, 8000)
  } else if (!hasActive && pollTimer) {
    stopPolling()
    uploadJustFinished.value = false
  }
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch([() => docStats.value.processing, () => docStats.value.pending], ([proc, pend]) => {
  if (proc > 0 || pend > 0) startPollingIfNeeded()
  else {
    stopPolling()
    uploadJustFinished.value = false
  }
})
onUnmounted(stopPolling)

onMounted(async () => {
  const kbId = route.params.kbId as string
  const [kbResp] = await Promise.all([
    getKnowledgeBase(kbId),
    loadDocuments().catch(() => {}),
    loadStats(),
    loadFolders().catch(() => {}),
  ])
  kb.value = kbResp.data
  loadDataSources()
  startPollingIfNeeded()
  // Auto-poll KB status while CREATING
  if (kb.value?.status === 'CREATING') {
    const kbPollInterval = setInterval(async () => {
      try {
        const resp = await getKnowledgeBase(kbId)
        kb.value = resp.data
        if (resp.data.status !== 'CREATING') {
          clearInterval(kbPollInterval)
          await Promise.all([loadDocuments().catch(() => {}), loadStats()])
        }
      } catch { clearInterval(kbPollInterval) }
    }, 3000)
    onUnmounted(() => clearInterval(kbPollInterval))
  }
})
</script>

<style scoped>
.tab-bar {
  display: flex;
  gap: 0;
}
.tab-item {
  padding: 10px 20px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}
.tab-item:hover {
  color: #333;
}
.tab-item.active {
  color: #9a5b25;
  font-weight: 600;
  border-bottom-color: #c67d3a;
}
.clickable-row {
  cursor: pointer;
}
.tag-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  background: #e8f3ff;
  color: #9a5b25;
  border: 1px solid #b3d4f7;
  white-space: nowrap;
}
.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  background: transparent;
  color: #aaa;
  cursor: pointer;
  border-radius: 4px;
  padding: 0;
  transition: background 0.15s, color 0.15s;
}
.btn-icon:hover {
  background: #f0f0f0;
  color: #555;
}
.tag-filter {
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.tag-filter:hover {
  background: #cfe4fc;
}
.tag-filter-active {
  background: #9a5b25;
  color: #fff;
  border-color: #c67d3a;
}

/* Chat */
.chat-container {
  flex: 1;
  overflow-y: auto;
  border: 1px solid #e5e5e5;
  border-radius: 10px 10px 0 0;
  padding: 16px;
  background: #fafafa;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
}
.chat-message-row {
  display: flex;
}
.chat-message-row.user {
  justify-content: flex-end;
}
.chat-message-row.assistant,
.chat-message-row.loading {
  justify-content: flex-start;
}
.chat-bubble {
  max-width: 88%;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.5;
}
.user-bubble {
  background: #9a5b25;
  color: #fff;
  border-bottom-right-radius: 3px;
}
.assistant-bubble {
  background: #fff;
  border: 1px solid #e5e5e5;
  border-bottom-left-radius: 3px;
  width: 100%;
  max-width: 100%;
}
.loading-bubble {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 12px 16px;
}
.loading-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #bbb;
  animation: dot-bounce 1.2s infinite ease-in-out;
}
.loading-dot:nth-child(2) { animation-delay: 0.2s; }
.loading-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes dot-bounce {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.4; }
  40% { transform: translateY(-5px); opacity: 1; }
}
.result-card {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #fafafa;
}
.result-card:last-child {
  margin-bottom: 0;
}
.chat-input-row {
  display: flex;
  gap: 8px;
  border: 1px solid #e5e5e5;
  border-top: none;
  border-radius: 0 0 10px 10px;
  padding: 10px 12px;
  background: #fff;
}
.chat-input {
  flex: 1;
  border-radius: 6px;
}

/* Search chunk results */
.search-chunk-card {
  border: 1px solid #e8e4df;
  border-radius: 6px;
  padding: 12px 14px;
  background: #fff;
  transition: box-shadow 0.15s;
}
.search-chunk-card:hover {
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}
.search-chunk-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 12px;
  flex-wrap: wrap;
}
.search-chunk-index {
  font-weight: 600;
  color: #9a5b25;
}
.search-chunk-score {
  background: #f5f3f0;
  color: #666;
  padding: 1px 6px;
  border-radius: 3px;
}
.search-chunk-level1 {
  background: #eff6ff;
  color: #2563eb;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
}
.search-chunk-source {
  color: #999;
}
.search-chunk-section {
  color: #999;
}
.search-chunk-content {
  font-size: 13px;
  line-height: 1.7;
  color: #333;
  white-space: pre-wrap;
  max-height: 200px;
  overflow-y: auto;
}

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal-box {
  background: #fff;
  border-radius: 10px;
  width: 420px;
  max-width: 90vw;
  box-shadow: 0 8px 32px rgba(0,0,0,0.15);
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px 12px;
  font-size: 15px;
  font-weight: 600;
  border-bottom: 1px solid #f0f0f0;
}
.modal-body {
  padding: 16px 20px;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px 16px;
  border-top: 1px solid #f0f0f0;
}
.error-msg {
  color: #e6393d;
  font-size: 12px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  text-decoration: underline dashed #e6393d;
  text-underline-offset: 2px;
}
.error-msg:hover {
  opacity: 0.8;
}
.page-btn {
  padding: 3px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 3px;
  background: #fff;
  cursor: pointer;
  font-size: 12px;
  color: #333;
}
.page-btn:hover:not(:disabled) {
  border-color: #1890ff;
  color: #1890ff;
}
.page-btn.active {
  background: #1890ff;
  color: #fff;
  border-color: #1890ff;
}
.page-btn:disabled {
  color: #d9d9d9;
  cursor: not-allowed;
}
.folder-card {
  padding: 12px 16px;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
  background: #fafafa;
}
.folder-card:hover {
  border-color: var(--color-primary, #e6a23c);
  background: #fff7ed;
}
.breadcrumb-link {
  cursor: pointer;
  color: var(--color-primary, #e6a23c);
}
.breadcrumb-link:hover {
  text-decoration: underline;
}
</style>
