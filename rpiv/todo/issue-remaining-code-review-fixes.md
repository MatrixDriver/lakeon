---
title: "修复剩余代码审查问题（7项）"
type: issue
status: open
priority: medium
created_at: 2026-03-03T12:00:00
updated_at: 2026-03-03T12:00:00
---

# 修复剩余代码审查问题（7项）

## 问题现象

代码审查报告（`rpiv/validation/code-review-serverless-pg.md`）共发现 21 个问题，已修复 14 个（3 CRITICAL + 5 HIGH 中的 4 个 + 6 MEDIUM + 1 LOW）。剩余 7 个未修复。

## 未修复项清单

### HIGH（1项）

**CR-HIGH-004: CreateTimelineRequest 构造函数语义不一致**
- 文件: `BranchService.java:53-54`, `CreateTimelineRequest.java`
- 描述: 两个构造函数 `(String, String)` 和 `(String, Integer)` 参数类型相似，容易混淆
- 建议: 使用 Builder 模式或静态工厂方法替代多构造函数

### MEDIUM（3项）

**CR-MED-002: HttpClient 生命周期管理**
- 文件: `NeonApiClient.java:33-35`
- 描述: `HttpClient` 在 `@Component` 单例中创建，应用关闭时不释放连接池资源
- 建议: 考虑使用 Spring `RestClient` 或实现 `DisposableBean` 清理资源

**CR-MED-004: waitForPodReady 阻塞事务**
- 文件: `ComputePodManager.java:155-167`
- 描述: busy-wait 最长阻塞 120 秒，在 `@Transactional` 方法中调用会长时间持有事务
- 建议: 将 Pod 等待逻辑移到事务外，或使用异步回调机制

**CR-MED-006: 密码字符集不含特殊字符**
- 文件: `DatabaseService.java:291`
- 描述: `generatePassword()` 仅用字母+数字（62字符集），可能不满足合规要求
- 建议: 添加特殊字符（如 `!@#$%^&*`），但需确保不破坏连接串 URL 编码

### LOW（3项）

**CR-LOW-001: DatabaseStatus 序列化风格不一致**
- 文件: `DatabaseStatus.java`, `DatabaseResponse.java`
- 描述: 枚举输出大写，但 Branch status 手动转小写，API 风格不统一
- 建议: 添加 `@JsonValue` 返回小写值

**CR-LOW-004: endpointish 参数缺少注释**
- 文件: `ProxyAdapterController.java`
- 描述: Neon 内部术语 `endpointish` 缺少 Javadoc 说明
- 建议: 补充参数含义和映射关系的注释

**CR-LOW-005: CLI 重复错误处理模式**
- 文件: `lakeon-cli/lakeon_cli/commands/db.py`, `branch.py`, `tenant.py`
- 描述: 每个命令都有相同的 try/except 模式，代码重复率高
- 建议: 提取公共错误处理装饰器

## 影响范围

- Java 后端服务: 5 项（HIGH-004, MED-002, MED-004, MED-006, LOW-001, LOW-004）
- Python CLI: 1 项（LOW-005）

## 已知 Workaround

当前代码功能正常，这些问题主要影响可维护性、健壮性和合规性，不影响核心功能。

## 已尝试的方案

无（这些项尚未开始修复）

## 参考

- 代码审查报告: `rpiv/validation/code-review-serverless-pg.md`
- 已修复的 14 项在上一轮会话中完成并通过全部 58 个测试
