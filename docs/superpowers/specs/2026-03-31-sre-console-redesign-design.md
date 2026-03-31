# SRE Console Redesign — 与用户 Console 统一港湾风格

**日期**: 2026-03-31
**目标**: 将 SRE 运维控制台(lakeon-admin)的视觉风格与用户控制台统一，采用相同的港湾暖色调设计体系

---

## 1. 现状问题

SRE 控制台与用户控制台存在完全相同的设计问题（改造前的用户 console）：
- HuaweiSans 字体 + #0073e6 冷蓝 + #e6393d 红色 primary 按钮
- 黑色 header + 虚假导航（备案/资源/费用/企业/工具/工单）+ 不可用搜索栏
- 区域选择器（华北-北京四）无实际功能
- border-radius: 2px 过于方正

## 2. 改造范围

### 2.1 色板暖化（与用户 console 完全一致）

| 变量 | 色值 | 用途 |
|------|------|------|
| --c-primary | #2a4d6a | 按钮、header、active 状态 |
| --c-primary-hover | #1e3a52 | hover |
| --c-accent | #c67d3a | 装饰、logo |
| --c-accent-text | #9a5b25 | 链接、交互文字 |
| --c-danger | #e6393d | 错误、删除 |
| --c-text | #2c3e50 | 主文字 |
| --c-text-2 | #64748b | 次要文字 |
| --c-border | #e8e4df | 边框 |
| --c-bg-alt | #faf8f5 | 表头背景 |
| --c-hover | #f8f5f1 | hover 背景 |

### 2.2 字体

HuaweiSans → DM Sans（Google Fonts），与用户 console 一致。

### 2.3 border-radius

2px → 4px（按钮/输入框），4px → 8px（dialog）。

## 3. AdminLayout 改造

### 3.1 Header

- 背景: navy #2a4d6a，无 border-bottom
- 左侧: 移除九宫格图标、"运维控制台"文字、区域选择器
- 改为: `DBay` (amber) + `SRE` (rgba(255,255,255,0.4)) tagline
- 右侧: 移除 6 个虚假导航 + 不可用搜索栏
- 改为: ⌘K 命令面板按钮 + Admin 用户名 + 退出

### 3.2 Sidebar

保持当前结构不变（已经是单层 sidebar），但更新样式：
- 背景: #fff，border-right: #e8e4df
- 标题: "DBay 运维" 保留，字体色改 #2c3e50
- 分组标题: 10px uppercase, #94a3b8, letter-spacing 0.8px
- 菜单项: 添加 SVG 图标（当前无图标），active 状态用 navy + 右边框
- 宽度: 保持 220px

### 3.3 导航结构（保持不变）

SRE 控制台的 6 个分组已经很合理，不需要调整：
```
总览
  仪表盘

租户
  租户管理

数据服务
  数据库 / 知识库 / 记忆库 / 数据湖

基础设施
  基础设施 / 华为云控制台 / 成本监控

日志审计
  操作日志 / 审计日志

运行时监控
  组件健康 / 应用指标 / 日志查看 / 告警管理
```

## 4. style.css 改造

与用户 console 的 style.css 做相同的变更：
- 添加 console theme CSS 变量（--c-primary 等）
- btn-primary: #e6393d → var(--c-primary) navy
- btn-danger: 保留 #e6393d
- btn-text/btn-default hover: #0073e6 → var(--c-accent-text)
- form focus: #0073e6 → var(--c-accent)
- 所有 #0073e6 → 对应暖色变量
- 所有 #191919 → var(--c-text)
- 所有 #575d6c → var(--c-text-2)
- border-radius: 2px → 4px
- 表格 hover: #f5f7fa → var(--c-hover)
- 表头背景: #fafafa → var(--c-bg-alt)

## 5. 各 View 页面

18 个页面中所有硬编码的 #0073e6、#191919、#575d6c、border-radius: 2px 替换为暖色调。

重点页面：
- DashboardView.vue (457行) — 仪表盘，指标卡片需要加色带
- InfraMonitor.vue (1332行) — 最大页面，大量状态色
- DatabaseList.vue (524行) — 数据库管理
- TenantList.vue (294行) — 租户管理

### 5.1 操作按钮层次（与用户 console 一致）

- btn-danger-text: 默认灰 #94a3b8，hover 变红
- btn-accent-text: 正向操作用 #9a5b25

## 6. ⌘K 命令面板

复用用户 console 的 CommandPalette 组件设计，但内容替换为 SRE 页面：

### 页面跳转
仪表盘、租户管理、数据库、知识库、记忆库、数据湖、基础设施、华为云控制台、成本监控、操作日志、审计日志、组件健康、应用指标、日志查看、告警管理

### 操作
（SRE 控制台主要是查看/监控，操作类较少，可以后续按需添加）

## 7. index.html

添加 DM Sans Google Fonts 引用。

## 8. 不变的部分

- 路由结构和 URL 不变
- API 层不变
- AiChatPanel 组件不变
- 登录页不变（独立样式）
- 各页面的功能逻辑不变

## 9. 实施策略

与用户 console 第一轮改造相同的批量替换策略：
1. style.css 加 CSS 变量 + 更新全局组件样式
2. AdminLayout.vue 重写 header + 更新 sidebar 样式
3. 创建 SRE 版 CommandPalette（或从用户 console 复制调整）
4. 批量替换 18 个 view 文件中的硬编码颜色
5. index.html 加 Google Fonts
6. 类型检查 + 提交
