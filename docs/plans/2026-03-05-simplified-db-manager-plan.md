# 简化版数据库管理界面实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 简化数据库管理界面，保留树形结构 + 列定义 + 数据预览，移除索引/约束展示

**Architecture:** 修改 StructureView.vue 组件，移除索引和约束相关代码，添加数据预览功能。使用现有的 tableData API 获取前 100 行数据，上下布局展示列定义和数据预览。

**Tech Stack:** Vue 3, TypeScript, TinyVue, Axios

---

## Task 1: 备份和准备工作

**Files:**
- Read: `lakeon-console/src/components/StructureView.vue`
- Read: `lakeon-console/src/api/database.ts`

**Step 1: 检查当前实现**

阅读 StructureView.vue，确认需要移除的部分：
- indexes 相关代码
- constraints 相关代码
- 备注列

**Step 2: 确认 API 可用性**

检查 database.ts 中的 tableData API：
```typescript
tableData: (id: string, schema: string, table: string, params: { page?: number; size?: number; sort?: string; dir?: string }) =>
  client.get<DataPage>(`/databases/${id}/schemas/${schema}/tables/${table}/data`, { params })
```

确认返回类型 DataPage：
```typescript
export interface DataPage {
  columns: string[]
  rows: unknown[][]
  total_rows: number
  page: number
  page_size: number
}
```

**Step 3: 创建 git 分支**

```bash
git checkout -b feature/simplified-db-manager
```

---

## Task 2: 修改 StructureView.vue - 移除索引和约束

**Files:**
- Modify: `lakeon-console/src/components/StructureView.vue`

**Step 1: 移除索引和约束的响应式变量**

在 `<script setup>` 中，删除：
```typescript
const indexes = ref<IndexInfo[]>([])
const constraints = ref<ConstraintInfo[]>([])
```

**Step 2: 移除索引和约束的 API 调用**

修改 `loadStructure()` 函数，从：
```typescript
const [colRes, idxRes, conRes] = await Promise.all([
  databaseApi.listColumns(props.dbId, props.schema, props.table),
  databaseApi.listIndexes(props.dbId, props.schema, props.table),
  databaseApi.listConstraints(props.dbId, props.schema, props.table),
])
columns.value = colRes.data
indexes.value = idxRes.data
constraints.value = conRes.data
```

改为：
```typescript
const colRes = await databaseApi.listColumns(props.dbId, props.schema, props.table)
columns.value = colRes.data
```

**Step 3: 移除模板中的索引和约束区域**

删除以下两个 `<div class="struct-section">` 块：
- 索引部分（包含 `<h4>索引 ({{ indexes.length }})</h4>`）
- 约束部分（包含 `<h4>约束 ({{ constraints.length }})</h4>`）

**Step 4: 移除备注列**

在列定义表格的 `<thead>` 中，删除：
```html
<th>备注</th>
```

在 `<tbody>` 中，删除：
```html
<td class="col-comment">{{ col.comment || '-' }}</td>
```

**Step 5: 移除相关 CSS 样式**

删除以下 CSS 类：
```css
.col-comment { ... }
.constraint-type { ... }
.ct-PRIMARY_KEY { ... }
.ct-FOREIGN_KEY { ... }
.ct-UNIQUE { ... }
.ct-CHECK { ... }
```

**Step 6: 测试编译**

```bash
cd lakeon-console
npm run dev
```

预期：无编译错误，页面可以正常加载

**Step 7: 提交**

```bash
git add lakeon-console/src/components/StructureView.vue
git commit -m "refactor: remove indexes and constraints from StructureView"
```

---

## Task 3: 添加数据预览功能

**Files:**
- Modify: `lakeon-console/src/components/StructureView.vue`

**Step 1: 添加数据预览响应式变量**

在 `<script setup>` 中添加：
```typescript
const dataPreview = ref<{ columns: string[]; rows: unknown[][]; total_rows: number } | null>(null)
const dataLoading = ref(false)
const dataError = ref('')
```

**Step 2: 添加 loadDataPreview 方法**

```typescript
async function loadDataPreview() {
  if (!props.schema || !props.table) return
  dataLoading.value = true
  dataError.value = ''
  try {
    const res = await databaseApi.tableData(props.dbId, props.schema, props.table, {
      page: 1,
      size: 100
    })
    dataPreview.value = {
      columns: res.data.columns,
      rows: res.data.rows,
      total_rows: res.data.total_rows
    }
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } }; message?: string }
    dataError.value = err.response?.data?.message || err.message || '数据加载失败'
    console.error('Failed to load data preview', e)
  } finally {
    dataLoading.value = false
  }
}
```

**Step 3: 修改 loadStructure 方法**

将数据预览加载并行化：
```typescript
async function loadStructure() {
  if (!props.schema || !props.table) return
  loading.value = true
  try {
    await Promise.all([
      (async () => {
        const colRes = await databaseApi.listColumns(props.dbId, props.schema, props.table)
        columns.value = colRes.data
      })(),
      loadDataPreview()
    ])
  } catch (e) {
    console.error('Failed to load structure', e)
  } finally {
    loading.value = false
  }
}
```

**Step 4: 测试编译**

```bash
npm run dev
```

预期：无编译错误

**Step 5: 提交**

```bash
git add lakeon-console/src/components/StructureView.vue
git commit -m "feat: add data preview loading logic"
```

---

## Task 4: 添加数据预览模板

**Files:**
- Modify: `lakeon-console/src/components/StructureView.vue`

**Step 1: 在列定义表格后添加数据预览区域**

在 `</div>` (struct-section 结束) 之后，`</template>` 之前添加：

```html
<!-- Data Preview -->
<div class="struct-section">
  <h4 class="struct-title">
    数据预览
    <span v-if="dataPreview && dataPreview.total_rows > 100" class="preview-hint">
      (显示前 100 行，共 {{ dataPreview.total_rows }} 行)
    </span>
  </h4>

  <div v-if="dataLoading" class="preview-loading">加载中...</div>
  <div v-else-if="dataError" class="preview-error">{{ dataError }}</div>
  <div v-else-if="!dataPreview || dataPreview.rows.length === 0" class="preview-empty">无数据</div>

  <div v-else class="preview-table-wrapper">
    <table class="data-table preview-table">
      <thead>
        <tr>
          <th v-for="col in dataPreview.columns" :key="col">{{ col }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, idx) in dataPreview.rows" :key="idx">
          <td v-for="(cell, cellIdx) in row" :key="cellIdx">
            {{ cell === null ? 'NULL' : cell }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</div>
```

**Step 2: 测试编译**

```bash
npm run dev
```

预期：无编译错误，页面可以渲染

**Step 3: 提交**

```bash
git add lakeon-console/src/components/StructureView.vue
git commit -m "feat: add data preview template"
```

---

## Task 5: 添加数据预览样式

**Files:**
- Modify: `lakeon-console/src/components/StructureView.vue`

**Step 1: 添加数据预览相关 CSS**

在 `<style scoped>` 中添加：

```css
.preview-hint {
  font-size: 12px;
  font-weight: 400;
  color: #8a8e99;
  margin-left: 8px;
}

.preview-loading,
.preview-error,
.preview-empty {
  padding: 20px 12px;
  text-align: center;
  font-size: 13px;
}

.preview-loading {
  color: #8a8e99;
}

.preview-error {
  color: #d4380d;
}

.preview-empty {
  color: #8a8e99;
}

.preview-table-wrapper {
  overflow-x: auto;
  max-height: 400px;
  overflow-y: auto;
  border: 1px solid #ebebeb;
  border-radius: 2px;
}

.preview-table {
  min-width: 100%;
}

.preview-table thead {
  position: sticky;
  top: 0;
  z-index: 1;
}

.preview-table td {
  font-family: monospace;
  font-size: 12px;
  color: #191919;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```

**Step 2: 测试编译和样式**

```bash
npm run dev
```

在浏览器中检查：
- 数据预览区域是否正确显示
- 表格是否支持横向和纵向滚动
- 表头是否固定

**Step 3: 提交**

```bash
git add lakeon-console/src/components/StructureView.vue
git commit -m "style: add data preview styles"
```

---

## Task 6: 本地测试验证

**Files:**
- Test: `lakeon-console/src/components/StructureView.vue`

**Step 1: 构建前端**

```bash
cd lakeon-console
npm run build
```

预期：构建成功，无错误

**Step 2: 启动本地环境**

```bash
# 确保 lakeon-api 和数据库正在运行
kubectl port-forward -n lakeon svc/lakeon-api 8080:8080 &
```

**Step 3: 创建测试数据**

使用现有数据库或创建新的测试数据库，执行：

```sql
-- 小表
CREATE TABLE test_small (id int, name text);
INSERT INTO test_small VALUES (1, 'Alice'), (2, 'Bob');

-- 大表
CREATE TABLE test_large (id int, data text);
INSERT INTO test_large SELECT generate_series(1, 500), 'data';

-- 空表
CREATE TABLE test_empty (id int);
```

**Step 4: 手动测试场景**

在浏览器中打开控制台，进入"管理数据库"：

1. ✓ 展开 public schema，显示表列表
2. ✓ 点击 test_small，��侧显示列定义和 2 行数据
3. ✓ 点击 test_large，显示"显示前 100 行，共 500 行"提示
4. ✓ 点击 test_empty，数据预览显示"无数据"
5. ✓ 检查浏览器控制台无错误
6. ✓ 检查表格横向滚动正常（如果列多）

**Step 5: 记录测试结果**

创建测试验证文档（如果所有测试通过）

---

## Task 7: 构建和部署

**Files:**
- Modify: `lakeon-console/Dockerfile`
- Modify: `deploy/helm/lakeon/values.yaml`

**Step 1: 更新版本号**

在 `lakeon-console/package.json` 中更新版本（如果需要）

**Step 2: 构建 Docker 镜像**

```bash
cd lakeon-console
docker build -t lakeon-console:0.1.4 .
```

**Step 3: 推送镜像（如果部署到 CCE）**

```bash
# 根据实际情况调整
docker tag lakeon-console:0.1.4 swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-console:0.1.4
docker push swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-console:0.1.4
```

**Step 4: 更新 Helm values**

在 `deploy/helm/lakeon/values.yaml` 或 `deploy/cce/values-cce.yaml` 中：

```yaml
console:
  image:
    tag: "0.1.4"
```

**Step 5: 部署到集群**

```bash
helm upgrade --install lakeon deploy/helm/lakeon \
  -f deploy/cce/values-cce.yaml \
  -n lakeon
```

**Step 6: 验证部署**

```bash
kubectl get pods -n lakeon | grep console
kubectl logs -n lakeon deployment/lakeon-console
```

预期：Pod 运行正常，无错误日志

---

## Task 8: 最终验证和文档

**Files:**
- Create: `docs/verification/simplified-db-manager.md`

**Step 1: 在部署环境中重新测试**

重复 Task 6 的测试场景，确保所有功能正常

**Step 2: 创建验证报告**

```markdown
# 简化版数据库管理界面验证报告

## 验证环境
- 环境: [本地/CCE]
- 版本: lakeon-console:0.1.4
- 日期: 2026-03-05

## 验证结果

| 场景 | 状态 | 备注 |
|------|------|------|
| 树形结构展开 | PASS | Schema 正常展开 |
| 点击表显示列定义 | PASS | 列信息正确显示 |
| 数据预览（小表） | PASS | 显示 2 行数据 |
| 数据预览（大表） | PASS | 显示前 100 行提示 |
| 数据预览（空表） | PASS | 显示"无数据" |
| 横向滚动 | PASS | 多列表格滚动正常 |
| 错误处理 | PASS | 错误信息正确显示 |

## 截图
[可选：添加关键界面截图]

## 结论
所有功能验证通过，可以发布。
```

**Step 3: 合并代码**

```bash
git checkout main
git merge feature/simplified-db-manager
git push origin main
```

**Step 4: 更新 README**

在 `README.md` 的阶段 6 部分更新状态（如果需要）

**Step 5: 最终提交**

```bash
git add docs/verification/simplified-db-manager.md README.md
git commit -m "docs: add simplified db manager verification report"
git push origin main
```

---

## 验收标准

- [ ] StructureView.vue 不再显示索引和约束
- [ ] 列定义表格移除备注列
- [ ] 数据预览显示前 100 行数据
- [ ] 空表显示"无数据"提示
- [ ] 大表显示行数提示
- [ ] 表格支持横向和纵向滚动
- [ ] 浏览器控制台无错误
- [ ] 所有测试场景通过
- [ ] 代码已提交并部署

## 预计时间

- Task 1-2: 30 分钟（移除旧代码）
- Task 3-5: 45 分钟（添加数据预览）
- Task 6: 30 分钟（本地测试）
- Task 7-8: 30 分钟（部署和文档）
- **总计: 约 2 小时**
