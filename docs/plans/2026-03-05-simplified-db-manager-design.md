# 简化版数据库管理界面设计

## 概述

重新设计用户控制台的数据库管理功能，简化为：树形结构 + 列定义 + 数据预览。移除索引、约束展示和 SQL 编辑功能。

## 目标

- 用户能浏览 Database → Schema → Table 层级结构
- 查看表的列定义（名称、类型、可空、默认值）
- 预览表数据（前 100 行）
- 保持界面简洁，降低复杂度

## 架构设计

### 组件结构

保持现有三层结构：

```
DatabaseManager.vue (页面容器)
├── ObjectTree.vue (左侧树形结构，无改动)
└── StructureView.vue (右侧内容面板，简化)
```

### UI 布局

```
┌─────────────────────────────────────────────────┐
│ 面包屑: 数据库实例 / db_xxx / 管理               │
├──────────┬──────────────────────────────────────┤
│          │ schema.table_name                    │
│ Schema 1 │ ─────────────────────────────────────│
│  ├─ T t1 │ 列 (5)                               │
│  ├─ T t2 │ ┌────┬──────┬────────┬──────┬───────┐│
│  └─ V v1 │ │ #  │ 列名 │ 类型   │ 可空 │ 默认值││
│          │ ├────┼──────┼────────┼──────┼───────┤│
│ Schema 2 │ │ 1  │ id   │ int4   │ NO   │ -    ││
│  └─ T t3 │ │ 2  │ name │ varchar│ YES  │ -    ││
│          │ └────┴──────┴────────┴──────┴───────┘│
│          │                                       │
│          │ 数据预览 (前 100 行)                  │
│          │ ┌────┬──────────────────────────────┐│
│          │ │ id │ name                         ││
│          │ ├────┼──────────────────────────────┤│
│          │ │ 1  │ Alice                        ││
│          │ │ 2  │ Bob                          ││
│          │ └────┴──────────────────────────────┘│
└──────────┴──────────────────────────────────────┘
```

**左侧树形结构：**
- Schema 节点可展开/收起
- 表节点显示类型图标（T=表，V=视图）
- 自动展开 public schema
- 点击表节点，右侧显示详情

**右侧内容面板：**
- 上半部分：列定义表格
- 下半部分：数据预览表格
- 支持纵向滚动

## 功能设计

### 1. 树形结构（保持不变）

**功能：**
- 加载所有 Schema（排除 pg_catalog / information_schema）
- 展开 Schema 时加载其下的表和视图
- 点击表/视图，触发右侧内容更新

**API：**
- `GET /api/v1/databases/{id}/schemas`
- `GET /api/v1/databases/{id}/schemas/{schema}/tables`

### 2. 列定义展示（简化）

**显示字段：**
- 序号（ordinal_position）
- 列名（name）
- 数据类型（data_type）
- 可空性（nullable）
- 默认值（default_value）

**移除字段：**
- 备注（comment）— 简化界面

**API：**
- `GET /api/v1/databases/{id}/schemas/{schema}/tables/{table}/columns`

### 3. 数据预览（新增）

**功能：**
- 显示表的前 100 行数据
- 动态列头（根据表结构）
- 横向滚动支持（列多时）
- 空表提示："无数据"
- 大表提示："显示前 100 行，共 {total_rows} 行"

**API：**
- `GET /api/v1/databases/{id}/schemas/{schema}/tables/{table}/data?page=1&size=100`
- 返回：`{ columns: string[], rows: any[][], total_rows: number, page: number, page_size: number }`

**错误处理：**
- 数据加载失败时显示错误信息
- 不影响列定义的展示

## 数据流

```
用户点击表节点
    ↓
ObjectTree 触发 @select 事件
    ↓
DatabaseManager 更新 selectedSchema/selectedTable
    ↓
StructureView 监听 props 变化
    ↓
并行加载：
  - loadColumns() → 列定义
  - loadDataPreview() → 数据预览
    ↓
渲染：列定义表格 + 数据预览表格
```

## 代码改动

### 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `lakeon-console/src/components/StructureView.vue` | 修改 | 移除索引/约束，添加数据预览 |
| `lakeon-console/src/components/ObjectTree.vue` | 无改动 | 保持现有功能 |
| `lakeon-console/src/views/database/DatabaseManager.vue` | 无改动 | 保持现有布局 |
| `lakeon-console/src/api/database.ts` | 无改动 | API 已存在 |

### StructureView.vue 改动点

**移除：**
- `indexes` 和 `constraints` 响应式变量
- `listIndexes()` 和 `listConstraints()` API 调用
- 索引和约束的模板区域
- 相关 CSS 样式

**新增：**
- `dataPreview` 响应式变量：`{ columns: string[], rows: any[][], total_rows: number }`
- `dataLoading` 响应式变量：`boolean`
- `loadDataPreview()` 方法：调用 `tableData` API
- 数据预览模板区域
- 数据预览 CSS 样式

**修改：**
- `loadStructure()` 方法：并行加载列定义和数据预览
- 列定义表格：移除"备注"列

## 测试验证

### 测试场景

| 场景 | 验证点 |
|------|--------|
| 树形结构展开 | Schema 节点展开后显示表列表 |
| 点击表节点 | 右侧显示列定义和数据预览 |
| 空表 | 数据预览区域显示"无数据" |
| 大表（>100 行） | 显示提示："显示前 100 行，共 X 行" |
| 多列表 | 数据预览表格支持横向滚动 |
| 数据加载失败 | 显示错误信息，列定义正常展示 |
| 视图 | 与表相同的展示逻辑 |

### 测试数据准备

使用现有集成测试环境：

```sql
-- 创建测试表
CREATE TABLE test_small (id int, name text);
INSERT INTO test_small VALUES (1, 'Alice'), (2, 'Bob');

CREATE TABLE test_large (id int, data text);
INSERT INTO test_large SELECT generate_series(1, 500), 'data';

CREATE TABLE test_empty (id int);
```

### 验证步骤

1. 部署前端到本地/CCE
2. 创建测试数据库并执行上述 SQL
3. 在控制台打开"管理数据库"
4. 逐一验证测试场景
5. 检查浏览器控制台无错误

## 非功能需求

### 性能

- 数据预览限制 100 行，避免大数据量传输
- 列定义和数据预览并行加载，减少等待时间
- 表格使用虚拟滚动（如果列数 > 50）

### 兼容性

- 支持 Chrome / Firefox / Safari 最新版本
- 响应式布局（最小宽度 1280px）

### 安全

- 复用现有的 API Key 认证
- 后端已有 SQL 注入防护（PreparedStatement）
- 数据预览使用只读连接（cloud_admin 用户）

## 后续扩展（不在本次范围）

- 数据预览分页（翻页查看更多数据）
- 列排序（点击列头排序）
- 数据筛选（简单的 WHERE 条件）
- 数据导出（CSV/JSON）
- SQL 编辑器（独立功能）

## 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 大表数据加载慢 | 用户体验差 | 限制 100 行，添加加载提示 |
| 数据类型显示异常 | 显示错误 | 后端统一格式化（JSON/数组等） |
| 横向滚动不流畅 | 操作不便 | CSS `overflow-x: auto` + 固定表头 |

## 交付物

- 修改后的 `StructureView.vue`
- 更新的设计文档（本文档）
- 测试验证报告
