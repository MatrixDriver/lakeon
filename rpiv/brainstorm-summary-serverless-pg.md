---
description: "需求摘要: serverless-pg - 基于 Neon 的华为云 Serverless PostgreSQL 服务"
status: pending
created_at: 2026-03-03T00:00:00
updated_at: 2026-03-03T00:00:00
archived_at: null
---

## 需求摘要

### 产品愿景
- **核心问题**：需要在华为云上提供 Serverless PostgreSQL 服务，支持存算分离和按需弹性
- **价值主张**：基于 Neon 开源项目，提供存算分离、自动启停、按需使用的 PostgreSQL 数据库服务
- **目标用户**：先内部团队验证，后续面向外部客户商业化
- **产品形态**：RESTful API + CLI 工具，数据库连接使用标准 PostgreSQL 协议

### 核心场景（按优先级排序）
1. **创建数据库实例**：用户通过 API/CLI 创建 PG 实例，指定名称和可选配置（PG 版本、compute 规格、休眠超时）
2. **连接并读写数据**：用户通过标准 PG 客户端连接，proxy 层路由到对应 compute，正常 CRUD
3. **compute 自动休眠**：无查询活动超过配置时间后，compute 自动关闭，节省资源
4. **compute 自动唤醒**：新连接到来时 proxy 自动唤醒 compute，数据零丢失，用户无感（可接受等待时间较长）
5. **数据库分支管理**：创建/删除/列出分支，每个分支有独立 compute 和连接串
6. **实例生命周期管理**：查看实例列表/状态、手动启停 compute、调整配置、删除实例

### 产品边界
- **MVP 范围内**：
  - 基于 Neon 的存算分离架构（Compute + Pageserver + Safekeeper + OBS）
  - 管控 API（RESTful）+ CLI 工具
  - 基础多租户（多个租户，每个租户独立实例，API Key 认证）
  - Compute 自动休眠/唤醒（连接触发唤醒，无活动超时休眠）
  - 数据库分支（创建/删除/列出）
  - 实例配置管理（compute 规格、休眠超时）
  - 存储默认上限（如 10GB，可调整）
  - 仅支持 PG 17
  - Prometheus + Grafana 监控 + 告警
  - 单 AZ 部署，架构预留跨 AZ 扩展
- **明确不做**：
  - Web 控制台
  - 计费系统
  - 冷启动优化（100ms 目标推迟）
  - 基于时间点的分支恢复（point-in-time branch）
- **后续版本考虑**：
  - Web 控制台
  - 计费与计量
  - 跨 AZ 高可用
  - 冷启动优化
  - 多 PG 版本支持
  - Point-in-time 分支

### 技术栈
- 管控面 API：Java 21 + Spring Boot 3
- CLI 工具：Python 3.11+ + Typer
- Neon 组件：Rust（直接使用开源构建产物）
- 部署：华为云 CCE（K8s）+ Helm Charts

### 已知约束
- 部署在华为云上，依赖 OBS（对象存储）和 CCE（K8s）
- Neon 代码库以 Rust + C 为主，团队需要具备相应能力
- OBS 需兼容 S3 API（需验证）
- 单 AZ 部署，架构预留跨 AZ
- Neon 代码位于 /Users/jacky/code/neon

### 各场景功能要点

#### 场景1：创建数据库实例
- **功能点**：用户指定实例名称（必填），可选指定 compute 规格（CPU/内存）、休眠超时时间
- **关键交互**：API/CLI 发起请求 → 系统创建 Pageserver tenant + timeline → 创建 compute pod → 返回连接串和实例信息
- **异常处理**：资源不足时返回明确错误；名称冲突时提示重名

#### 场景2：连接并读写数据
- **功能点**：用户用 psql 或任意 PG 客户端连接，proxy 路由到正确的 compute
- **关键交互**：用户连接 proxy 地址 → proxy 解析 tenant/database → 路由到 compute → 建立连接
- **异常处理**：compute 不存在或已删除时返回明确错误

#### 场景3：compute 自动休眠
- **功能点**：无查询活动超过配置时间后自动关闭 compute pod
- **关键交互**：后台监控活动 → 超时触发 → 优雅关闭连接 → 销毁 compute pod
- **异常处理**：休眠过程中如有新请求进来，取消休眠

#### 场景4：compute 自动唤醒
- **功能点**：新连接到来时 proxy 检测 compute 状态，若已休眠则自动创建 compute pod
- **关键交互**：连接请求 → proxy 发现 compute 不在 → 通知管控面启动 → 等待就绪 → 建立连接
- **异常处理**：启动失败时返回明确错误信息（资源不足、存储异常等）；自动重试几次后超时报错

#### 场景5：数据库分支管理
- **功能点**：创建分支（从主分支 fork）、删除分支、列出分支，每个分支有独立 compute 和连接串
- **关键交互**：API/CLI 操作 → 创建新 timeline（Neon 概念）→ 可选启动新 compute → 返回分支信息
- **异常处理**：分支数量限制；删除时确认无活跃连接

#### 场景6：实例生命周期管理
- **功能点**：查看实例列表/状态、手动启停 compute、调整 compute 规格、修改休眠超时、删除实例
- **关键交互**：API/CLI 操作 → 管控面执行 → 返回结果
- **异常处理**：删除时需确认（或强制删除标志）；调整规格时可能需要重启 compute
