# Lakeon Console - 数据库管理器测试指南

## 构建状态

✅ **前端构建成功** (2026-03-05)
- 构建时间: 7.30s
- 输出目录: `dist/`
- 无错误或警告

## 环境状态

### 后端服务
- **lakeon-api pod**: ⚠️ ImagePullBackOff (需要修复)
- **lakeon-api service**: ✅ ClusterIP 10.96.32.242:8080
- **端口转发**: ✅ 已运行 (localhost:18080 → service:8080)

### 前置条件
在开始测试前，请确保：
1. lakeon-api pod 正常运行
2. 端口转发已启动: `kubectl port-forward svc/lakeon-api 18080:8080 -n lakeon`
3. 已创建至少一个数据库实例

## 测试数据准备

连接到你的测试数据库，执行以下 SQL 创建测试表：

```sql
-- 小表（用于测试基本显示）
CREATE TABLE test_small (
    id int,
    name text,
    email text,
    created_at timestamp
);
INSERT INTO test_small VALUES
    (1, 'Alice', 'alice@example.com', now()),
    (2, 'Bob', 'bob@example.com', now()),
    (3, 'Charlie', 'charlie@example.com', now());

-- 大表（用于测试分页提示）
CREATE TABLE test_large (
    id int,
    data text,
    value numeric
);
INSERT INTO test_large
SELECT
    generate_series(1, 500) as id,
    'data_' || generate_series(1, 500) as data,
    random() * 1000 as value;

-- 空表（用于测试空状态）
CREATE TABLE test_empty (
    id int,
    description text
);

-- 宽表（用于测试横向滚动）
CREATE TABLE test_wide (
    col1 text, col2 text, col3 text, col4 text, col5 text,
    col6 text, col7 text, col8 text, col9 text, col10 text
);
INSERT INTO test_wide VALUES
    ('a1','a2','a3','a4','a5','a6','a7','a8','a9','a10');
```

## 测试场景

### 场景 1: Schema 展开和表列表
**步骤:**
1. 打开浏览器访问 `http://localhost:5173`（或你的开发服务器地址）
2. 登录并进入数据库详情页
3. 点击"数据库管理器"标签
4. 展开 `public` schema

**预期结果:**
- ✅ 显示所有表（test_small, test_large, test_empty, test_wide）
- ✅ 表名按字母顺序排列
- ✅ 展开/收起动画流畅

### 场景 2: 查看表结构（小表）
**步骤:**
1. 点击 `test_small` 表

**预期结果:**
- ✅ 右侧显示"表结构"标签
- ✅ 显示列定义表格，包含：列名、数据类型、可空性、默认值
- ✅ **不显示**索引、约束、备注列
- ✅ 表格样式清晰，边框正确

### 场景 3: 数据预览（小表）
**步骤:**
1. 在 `test_small` 表结构下方查看数据预览区域

**预期结果:**
- ✅ 显示"数据预览"标题
- ✅ 显示 3 行数据
- ✅ 列标题正确（id, name, email, created_at）
- ✅ 数据正确显示
- ✅ 无分页提示（因为 < 100 行）

### 场景 4: 数据预览（大表）
**步骤:**
1. 点击 `test_large` 表
2. 查看数据预览区域

**预期结果:**
- ✅ 显示前 100 行数据
- ✅ 显示提示信息："显示前 100 行，共 500 行"
- ✅ 提示样式为浅蓝色背景

### 场景 5: 数据预览（空表）
**步骤:**
1. 点击 `test_empty` 表
2. 查看数据预览区域

**预期结果:**
- ✅ 显示"无数据"提示
- ✅ 提示样式为灰色文字，居中显示

### 场景 6: 横向滚动（宽表）
**步骤:**
1. 点击 `test_wide` 表
2. 尝试横向滚动数据预览表格

**预期结果:**
- ✅ 表格可以横向滚动
- ✅ 所有 10 列都可见
- ✅ 滚动流畅，无布局错乱

### 场景 7: 浏览器控制台检查
**步骤:**
1. 打开浏览器开发者工具（F12）
2. 切换到 Console 标签
3. 执行上述所有操作

**预期结果:**
- ✅ 无 JavaScript 错误
- ✅ 无 API 请求失败（除非后端未运行）
- ✅ 无 Vue 警告

### 场景 8: 响应式测试
**步骤:**
1. 调整浏览器窗口大小
2. 测试不同屏幕宽度下的显示

**预期结果:**
- ✅ 布局自适应
- ✅ 表格在小屏幕下可滚动
- ✅ 无内容溢出或遮挡

## 测试检查清单

- [ ] Schema 展开显示表列表
- [ ] 点击表显示列定义（无索引、约束、备注列）
- [ ] 数据预览正常显示
- [ ] 空表显示"无数据"
- [ ] 大表显示"显示前 100 行，共 X 行"提示
- [ ] 浏览器控制台无错误
- [ ] 表格横向滚动正常
- [ ] 响应式布局正常

## 已知问题

1. **lakeon-api pod ImagePullBackOff**: 需要修复镜像拉取问题
   - 解决方案: 检查镜像仓库配置或使用本地构建的镜像

## 测试完成后

如果所有测试场景通过，请：
1. 在检查清单中标记完成项
2. 截图关键功能（可选）
3. 记录任何发现的问题
4. 准备进入下一阶段：构建和部署

## 联系信息

如有问题，请检查：
- 前端开发服务器: `npm run dev`
- 后端 API 日志: `kubectl logs -n lakeon -l app=lakeon-api`
- 端口转发状态: `ps aux | grep port-forward`
