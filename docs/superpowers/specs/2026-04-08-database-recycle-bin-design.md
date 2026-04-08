# Database Recycle Bin (回收站)

## Background

SRE 和用户都可能误删数据库。当前删除操作会立即清除 Neon tenant/timeline 和 metadata 记录，恢复需要手动从 OBS 远程存储重建（复杂且不保证成功）。

## Design

### 核心行为

- **软删除**：删除数据库时标记为 `DELETED`，设置 `deleted_at` 时间戳，删除 compute pod（释放资源），**保留 Neon tenant/timeline 数据**
- **恢复**：从回收站恢复为 `SUSPENDED` 状态，清除 `deleted_at`，下次访问自动 wake
- **硬删除**：7 天后定时任务执行真正删除（Neon tenant + timeline + branches + metadata 记录）
- **立即永久删除**：SRE 可通过 purge 接口跳过回收站

### 数据模型

**DatabaseStatus 枚举新增**：`DELETED`

**DatabaseEntity 新增字段**：
- `deletedAt` (Instant, nullable) — 软删除时间

### 查询过滤

- 用户侧查询默认排除 `DELETED`（`findByTenantIdAndStatusNot(tenantId, DELETED)`）
- Admin 侧可查看所有状态，支持按 `status=DELETED` 过滤
- Compute summary 排除 DELETED 数据库

### API

| Method | Endpoint | 说明 | 角色 |
|--------|----------|------|------|
| DELETE | `/databases/{id}` | 软删除（改为标记而非真删） | 用户 |
| POST | `/databases/{id}/restore` | 从回收站恢复 | 用户 |
| DELETE | `/admin/databases/{id}/purge` | 立即永久删除 | SRE |

### 定时清理

- `@Scheduled(cron = "0 0 * * * *")` 每小时执行
- 查找 `status=DELETED AND deletedAt < now() - 7 days`
- 对每条记录执行现有 hardDelete 逻辑
- 记录审计日志

### 软删除逻辑（改造 DatabaseService.delete）

```
1. 删除 compute pod（释放资源）
2. 清除 compute 引用（podName, host, port）
3. 设置 status = DELETED, deletedAt = now()
4. 保存（不删除 Neon tenant/timeline）
```

### 恢复逻辑（新增 DatabaseService.restore）

```
1. 验证 status == DELETED
2. 验证 Neon tenant 仍存在（调 pageserver GET /v1/tenant/{id}）
3. 设置 status = SUSPENDED, deletedAt = null
4. 保存
```

### 硬删除逻辑（新增 DatabaseService.purge）

```
复用现有 delete() 中的 Neon 清理逻辑：
1. 删除 branches 的 compute pods + timelines
2. 删除主 compute pod
3. 删除 Neon tenant
4. 删除 metadata 记录
```

### 前端

**用户控制台** — 左侧导航"账户"分组下新增"回收站"入口：
```
账户
  资源用量
  回收站        ← 新增
```

回收站页面：
- 表格：名称、类型、删除时间、剩余天数、操作（恢复 / 永久删除）
- 空状态提示："回收站为空"
- 恢复按钮确认对话框
- 永久删除按钮二次确认

**SRE 控制台** — 数据库列表页现有 status 筛选器新增 `DELETED` 选项，数据库详情页增加 purge 按钮。

### 涉及文件

**后端**：
1. `DatabaseStatus.java` — 新增 `DELETED`
2. `DatabaseEntity.java` — 新增 `deletedAt` 字段
3. `DatabaseService.java` — `delete()` 改为软删除，新增 `restore()`、`purge()`、`cleanupExpiredDeleted()`
4. `DatabaseRepository.java` — 新增 `findByStatusAndDeletedAtBefore`，修改用户查询排除 DELETED
5. `DatabaseController.java` — 新增 `POST /databases/{id}/restore`
6. `AdminController.java` — 新增 `DELETE /admin/databases/{id}/purge`

**用户控制台**：
7. `lakeon-console/src/views/account/RecycleBin.vue` — 回收站页面
8. `lakeon-console/src/router/index.ts` — 新增路由
9. `lakeon-console/src/layouts/ConsoleLayout.vue` — 导航新增回收站入口
10. `lakeon-console/src/api/databases.ts` — 新增 restore API

**SRE 控制台**：
11. `lakeon-admin/src/api/admin.ts` — 新增 purge API
12. `lakeon-admin/src/views/databases/DatabaseList.vue` — DELETED 筛选 + purge 按钮
