# 分支独立 Compute 设计

## 概述

将 Lakeon 从"单数据库单 compute"架构升级为"每个分支独立 compute（按需启动）"。用户连接指定分支时自动启动该分支的 compute，空闲超时自动挂起。兼容 Neon 的 proxy 回调机制。

## 连接路由

```
用户连接 postgres://user@pg.dbay.cloud:4432/mydb--dev
  → Neon Proxy 提取 endpointish = "mydb--dev"
  → 回调 GET /proxy/wake_compute?endpointish=mydb--dev
  → ProxyAdapterController 解析: db=mydb, branch=dev
  → 找到 dev 分支的 BranchEntity
     → compute 运行中: 返回地址
     → compute 已挂起: 唤醒，返回地址
     → 无 compute: 创建 compute pod，等待就绪，返回地址
  → Proxy 路由连接到 dev 的 compute

不指定分支时（endpointish=mydb），路由到默认分支（is_default=true）。
```

## 数据模型变更

### BranchEntity（已有字段启用 + 新增）

已有字段真正启用：
- `computePodName` — 分支的 compute pod 名称
- `computeHost` — compute pod IP
- `computePort` — compute pod 端口（55433）
- `computeStatus` — RUNNING / SUSPENDED / STARTING / ERROR

新增字段：
- `suspendTimeout` — 空闲挂起超时，默认继承数据库的值

### DatabaseEntity

- `computePodName`、`computeHost`、`computePort` — **废弃**，不再使用。Compute 生命周期完全在 BranchEntity 管理。
- `neonTimelineId` — 保留，通过默认分支关联。创建数据库时同步设置。

## 需要改的组件

### 1. ProxyAdapterController

`wake_compute(endpointish)` 改为：
1. 解析 `endpointish`：`dbName--branchName` 或 `dbName`（默认分支）
2. 查 BranchEntity（而非 DatabaseEntity）
3. 如果分支 compute 未运行，调用 ComputePodManager 创建/唤醒
4. 返回分支 compute 的地址

`get_endpoint_access_control(endpointish)` 同样解析分支。

### 2. ComputePodManager

新增方法：
- `createComputePodForBranch(DatabaseEntity db, BranchEntity branch)` — 为分支创建 compute pod
  - pod 名称：`compute-{branch_id}`（替换下划线为连字符）
  - config 使用分支的 `neonTimelineId`
  - 其余配置（tenant_id, pageserver, safekeeper 等）从 DatabaseEntity 取

现有 `createComputePod(DatabaseEntity)` 保留但标记 @Deprecated。

### 3. ComputeLifecycleService

当前：扫描 DatabaseEntity 的 compute 做自动挂起。
改为：扫描所有 BranchEntity 的 compute，按分支的 `suspendTimeout` 挂起。

- 遍历 `branchRepository.findByComputeStatus(ComputeStatus.RUNNING)`
- 检查分支的 `lastActiveAt` + `suspendTimeout`
- 超时则挂起（删除 compute pod，更新 computeStatus=SUSPENDED）

### 4. BranchService

- `create()` — 创建分支时**不启动 compute**，compute 在首次连接时按需启动
- 删除 `switchActive()` 方法（不再需要"切换"操作）
- `promote()` — 只改 `is_default` 标记，**不重建 compute**（每个分支有自己的 compute）
- `delete()` — 删除分支时同时删除其 compute pod

### 5. DatabaseService

- `create()` — 创建数据库后，compute 绑定到默认分支的 BranchEntity（而非 DatabaseEntity）
  - 创建默认分支 → 为默认分支创建 compute → 设置 BranchEntity 的 compute 字段
  - DatabaseEntity 的 compute 字段留空
- `suspend()` / `resume()` — 改为操作默认分支的 compute（或所有分支的 compute？）
  - 建议：suspend/resume 操作默认分支。其他分支各自管理。
- `delete()` — 删除数据库时删除所有分支的 compute pod

### 6. 前端变更

- **去掉"切换"按钮** — 不再需要
- **每个分支显示连接串** — `postgres://user@pg.dbay.cloud:4432/dbname--branchname`
- **每个分支显示 compute 状态** — 运行中/已挂起/未启动
- **"提升为默认"** — 只改默认标记，无需等待 compute 重建

### 7. 连接串生成

BranchEntity 的 `connectionUri` 生成规则：
- 默认分支：`postgres://user@pg.dbay.cloud:4432/dbname?options=endpoint%3Ddbname`
- 非默认分支：`postgres://user@pg.dbay.cloud:4432/dbname--branchname?options=endpoint%3Ddbname--branchname`

## 不变的部分

- 版本管理（创建/删除/squash/回滚）不受影响
- Neon pageserver / safekeeper / storage-broker 不需要改
- Neon proxy 本身不需要改（回调机制已有）
- 前端时间旅行页面结构不变
- API Key 认证机制不变

## 迁移策略

- DatabaseEntity 的 compute 字段保留但不再更新
- 现有数据库的 compute pod 迁移：将 compute 信息复制到默认分支的 BranchEntity
- 迁移脚本在启动时自动执行（检测 DatabaseEntity 有 compute 但默认分支没有 → 迁移）

## 设计决策

1. **分支 compute 按需启动** — 首次连接时才创建，节省资源
2. **空闲自动挂起** — 和数据库行为一致，用户不需要学新概念
3. **保留默认分支** — 不指定分支时连默认分支，兼容现有连接串
4. **去掉"切换"操作** — 每个分支独立 compute，不需要切换
5. **Promote 轻量化** — 只改 is_default 标记，不重建 compute
