---
description: "测试策略: serverless-pg"
status: completed
created_at: 2026-03-03T00:00:00
updated_at: 2026-03-03T00:00:00
archived_at: null
---

# LakeOn Serverless PostgreSQL 测试策略

## 1. 测试总览

本文档定义 LakeOn Serverless PostgreSQL 项目的全面测试策略，覆盖单元测试、集成测试和验收测试三个层次。测试目标是确保管控面 API、CLI 工具和 Neon 集成的正确性、可靠性与安全性。

### 测试金字塔

```
         ╱  验收测试  ╲          ← 端到端场景验证（少量、高价值）
        ╱  集成测试    ╲         ← API 端到端 + 外部依赖 mock（中等数量）
       ╱  单元测试      ╲        ← Service/Repository/CLI 逻辑（大量、快速）
```

### 测试覆盖率目标

| 层次 | 行覆盖率目标 | 分支覆盖率目标 |
|------|-------------|---------------|
| Service 层 | ≥ 90% | ≥ 85% |
| Repository 层 | ≥ 85% | ≥ 80% |
| Controller 层 | ≥ 85% | ≥ 80% |
| CLI 命令层 | ≥ 85% | ≥ 80% |

---

## 2. 单元测试策略

### 2.1 Spring Boot Service 层测试

**测试范围**：所有 `com.lakeon.service` 包下的业务逻辑类。

**Mock 策略**：
- 使用 Mockito mock `NeonApiClient`（Pageserver/Safekeeper API 调用）
- 使用 Mockito mock `KubernetesClient`（Fabric8 K8s 操作）
- 使用 Mockito mock `Repository` 层（数据访问）

**关键测试类和场景**：

#### DatabaseService 测试

| 测试用例 ID | 场景 | 预期结果 |
|------------|------|----------|
| UT-SVC-DB-001 | 创建实例 — 正常流程 | 调用 Neon API 创建 tenant + timeline，调用 K8s 创建 Pod，保存元数据，返回连接串 |
| UT-SVC-DB-002 | 创建实例 — Neon API 创建 tenant 失败 | 抛出异常，不创建 K8s Pod，不保存元数据 |
| UT-SVC-DB-003 | 创建实例 — K8s Pod 创建失败 | 回滚 Neon tenant，抛出异常 |
| UT-SVC-DB-004 | 创建实例 — 名称重复 | 抛出 ConflictException |
| UT-SVC-DB-005 | 删除实例 — 正常流程 | 销毁 K8s Pod，删除 Neon tenant，清除元数据 |
| UT-SVC-DB-006 | 删除实例 — 实例不存在 | 抛出 NotFoundException |
| UT-SVC-DB-007 | 查询实例详情 — 正常 | 返回完整实例信息（含状态、连接串、存储用量） |
| UT-SVC-DB-008 | 列出实例 — 租户隔离 | 仅返回当前租户的实例 |
| UT-SVC-DB-009 | 启动 compute — 实例已休眠 | 创建 K8s Pod，更新状态为 running |
| UT-SVC-DB-010 | 启动 compute — 实例已运行 | 幂等处理，不重复创建 Pod |
| UT-SVC-DB-011 | 停止 compute — 正常流程 | 销毁 K8s Pod，更新状态为 suspended |
| UT-SVC-DB-012 | 更新配置 — 修改 compute 规格 | 更新元数据，触发 compute 重启 |
| UT-SVC-DB-013 | 更新配置 — 修改休眠超时 | 更新元数据，不重启 compute |

#### BranchService 测试

| 测试用例 ID | 场景 | 预期结果 |
|------------|------|----------|
| UT-SVC-BR-001 | 创建分支 — 正常流程 | 调用 Neon API 创建 timeline，保存元数据 |
| UT-SVC-BR-002 | 创建分支 — 带 start_compute | 创建 timeline 后额外创建 K8s Pod |
| UT-SVC-BR-003 | 创建分支 — 父实例不存在 | 抛出 NotFoundException |
| UT-SVC-BR-004 | 删除分支 — 正常流程 | 销毁分支 compute，删除 Neon timeline，清除元数据 |
| UT-SVC-BR-005 | 删除分支 — 删除默认分支 | 拒绝操作，抛出 BadRequestException |
| UT-SVC-BR-006 | 列出分支 — 正常 | 返回实例下所有分支及状态 |

#### TenantService 测试

| 测试用例 ID | 场景 | 预期结果 |
|------------|------|----------|
| UT-SVC-TN-001 | 创建租户 | 生成唯一 API Key，保存元数据 |
| UT-SVC-TN-002 | 查看租户信息 | 返回租户详情和实例列表 |
| UT-SVC-TN-003 | 租户不存在 | 抛出 NotFoundException |

#### ComputeLifecycleService 测试

| 测试用例 ID | 场景 | 预期结果 |
|------------|------|----------|
| UT-SVC-CL-001 | wake_compute — 正常唤醒 | 创建 Pod，等待 Ready，返回 compute 地址 |
| UT-SVC-CL-002 | wake_compute — Pod 启动超时 | 抛出 WakeComputeTimeoutException |
| UT-SVC-CL-003 | 自动休眠检测 — 超时触发 | 调度器检测无活动超时，触发 Pod 销毁 |

**测试模式示例**：

```java
@ExtendWith(MockitoExtension.class)
class DatabaseServiceTest {

    @Mock private DatabaseRepository databaseRepository;
    @Mock private NeonApiClient neonApiClient;
    @Mock private KubernetesClient kubernetesClient;
    @InjectMocks private DatabaseService databaseService;

    @Test
    void createDatabase_success() {
        // Given
        var request = new CreateDatabaseRequest("my-db", "1cu", "5m", 10);
        when(neonApiClient.createTenant(any())).thenReturn(new NeonTenant("tenant-id"));
        when(neonApiClient.createTimeline(any(), any())).thenReturn(new NeonTimeline("timeline-id"));
        when(kubernetesClient.pods().create(any())).thenReturn(mockPod());

        // When
        var result = databaseService.create(tenantContext, request);

        // Then
        assertThat(result.getConnectionUri()).isNotBlank();
        verify(databaseRepository).save(any(DatabaseEntity.class));
        verify(neonApiClient).createTenant(any());
        verify(kubernetesClient.pods()).create(any());
    }

    @Test
    void createDatabase_neonApiFails_rollback() {
        // Given
        when(neonApiClient.createTenant(any())).thenThrow(new NeonApiException("failed"));

        // When/Then
        assertThrows(ServiceException.class, () ->
            databaseService.create(tenantContext, request));
        verify(kubernetesClient, never()).pods();
        verify(databaseRepository, never()).save(any());
    }
}
```

### 2.2 Repository 层测试

**测试方案**：使用 Testcontainers 启动 PostgreSQL 17 容器，确保与生产环境一致。

**备选方案**：若 CI 环境不支持 Docker，退回 H2 数据库（需维护兼容 schema）。

**关键测试场景**：

| 测试用例 ID | 场景 | 预期结果 |
|------------|------|----------|
| UT-REPO-001 | DatabaseRepository.save — 正常保存 | 数据正确持久化 |
| UT-REPO-002 | DatabaseRepository.findByTenantIdAndName — 存在 | 返回匹配实体 |
| UT-REPO-003 | DatabaseRepository.findByTenantIdAndName — 不存在 | 返回 Optional.empty |
| UT-REPO-004 | DatabaseRepository.findAllByTenantId — 多条 | 返回该租户所有实例，不返回其他租户数据 |
| UT-REPO-005 | BranchRepository.findAllByDatabaseId — 正常 | 返回该实例所有分支 |
| UT-REPO-006 | TenantRepository.findByApiKey — 正常 | 根据 API Key 查找租户 |
| UT-REPO-007 | 唯一性约束 — 同租户同名实例 | 抛出 DataIntegrityViolationException |

**测试模式示例**：

```java
@DataJpaTest
@Testcontainers
class DatabaseRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DatabaseRepository databaseRepository;

    @Test
    void findByTenantIdAndName_found() {
        // Given
        var entity = new DatabaseEntity("tenant-1", "my-db", ...);
        databaseRepository.save(entity);

        // When
        var result = databaseRepository.findByTenantIdAndName("tenant-1", "my-db");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("my-db");
    }
}
```

### 2.3 Python CLI 命令测试

**测试框架**：pytest + pytest-httpx（mock HTTP 调用）

**关键测试场景**：

| 测试用例 ID | 场景 | 预期结果 |
|------------|------|----------|
| UT-CLI-001 | `lakeon db create --name test` | 发送 POST /api/v1/databases，输出连接串 |
| UT-CLI-002 | `lakeon db list` | 发送 GET /api/v1/databases，输出表格 |
| UT-CLI-003 | `lakeon db status --name test` | 发送 GET /api/v1/databases/{id}，输出状态详情 |
| UT-CLI-004 | `lakeon db suspend --name test` | 发送 POST .../suspend |
| UT-CLI-005 | `lakeon db resume --name test` | 发送 POST .../resume |
| UT-CLI-006 | `lakeon db update --name test --compute-size 2cu` | 发送 PATCH .../databases/{id} |
| UT-CLI-007 | `lakeon db delete --name test --force` | 发送 DELETE .../databases/{id} |
| UT-CLI-008 | `lakeon branch create --db test --name feat` | 发送 POST .../branches |
| UT-CLI-009 | `lakeon branch list --db test` | 发送 GET .../branches，输出分支列表 |
| UT-CLI-010 | `lakeon branch delete --db test --name feat` | 发送 DELETE .../branches/{id} |
| UT-CLI-011 | `lakeon config set --api-url ... --api-key ...` | 保存配置到本地文件 |
| UT-CLI-012 | API 返回 401 | 输出认证失败错误信息 |
| UT-CLI-013 | API 返回 404 | 输出资源不存在错误信息 |
| UT-CLI-014 | API 超时/网络错误 | 输出网络错误信息 |
| UT-CLI-015 | 缺少必填参数 | 输出参数提示信息 |

**测试模式示例**：

```python
from typer.testing import CliRunner
from lakeon_cli.main import app

runner = CliRunner()

def test_db_create_success(httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:8080/api/v1/databases",
        json={
            "id": "db_abc123",
            "name": "test-db",
            "status": "creating",
            "connection_uri": "postgres://user:pass@proxy/test-db",
        },
    )
    result = runner.invoke(app, ["db", "create", "--name", "test-db"])
    assert result.exit_code == 0
    assert "postgres://user:pass@proxy/test-db" in result.stdout

def test_db_create_api_error(httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:8080/api/v1/databases",
        status_code=409,
        json={"error": {"code": "CONFLICT", "message": "already exists"}},
    )
    result = runner.invoke(app, ["db", "create", "--name", "test-db"])
    assert result.exit_code != 0
    assert "already exists" in result.stdout
```

---

## 3. 集成测试策略

### 3.1 Controller 层 API 端到端测试

**测试方案**：使用 Spring Boot `@WebMvcTest` 或 `@SpringBootTest` + `MockMvc`/`WebTestClient`

**依赖处理**：
- Service 层使用真实实现
- Neon API 使用 WireMock mock
- K8s Client 使用 Mockito mock（或 fabric8 的 mock server）
- 数据库使用 Testcontainers PostgreSQL 17

**关键测试场景**：

#### 实例管理 API

| 测试用例 ID | 场景 | HTTP 方法 | 路径 | 预期状态码 |
|------------|------|-----------|------|-----------|
| IT-API-DB-001 | 创建实例 — 正常 | POST | /api/v1/databases | 201 |
| IT-API-DB-002 | 创建实例 — 缺少 name | POST | /api/v1/databases | 400 |
| IT-API-DB-003 | 创建实例 — 名称重复 | POST | /api/v1/databases | 409 |
| IT-API-DB-004 | 创建实例 — 无 API Key | POST | /api/v1/databases | 401 |
| IT-API-DB-005 | 创建实例 — 无效 API Key | POST | /api/v1/databases | 401 |
| IT-API-DB-006 | 列出实例 — 空列表 | GET | /api/v1/databases | 200 |
| IT-API-DB-007 | 列出实例 — 多个实例 | GET | /api/v1/databases | 200 |
| IT-API-DB-008 | 查看实例 — 存在 | GET | /api/v1/databases/{id} | 200 |
| IT-API-DB-009 | 查看实例 — 不存在 | GET | /api/v1/databases/{id} | 404 |
| IT-API-DB-010 | 查看实例 — 其他租户 | GET | /api/v1/databases/{id} | 404 |
| IT-API-DB-011 | 更新配置 — 正常 | PATCH | /api/v1/databases/{id} | 200 |
| IT-API-DB-012 | 删除实例 — 正常 | DELETE | /api/v1/databases/{id} | 204 |
| IT-API-DB-013 | 删除实例 — 不存在 | DELETE | /api/v1/databases/{id} | 404 |
| IT-API-DB-014 | 休眠 compute — 正常 | POST | /api/v1/databases/{id}/suspend | 200 |
| IT-API-DB-015 | 唤醒 compute — 正常 | POST | /api/v1/databases/{id}/resume | 200 |

#### 分支管理 API

| 测试用例 ID | 场景 | HTTP 方法 | 路径 | 预期状态码 |
|------------|------|-----------|------|-----------|
| IT-API-BR-001 | 创建分支 — 正常 | POST | /api/v1/databases/{id}/branches | 201 |
| IT-API-BR-002 | 创建分支 — 父实例不存在 | POST | /api/v1/databases/{id}/branches | 404 |
| IT-API-BR-003 | 列出分支 — 正常 | GET | /api/v1/databases/{id}/branches | 200 |
| IT-API-BR-004 | 删除分支 — 正常 | DELETE | .../branches/{bid} | 204 |
| IT-API-BR-005 | 删除分支 — 默认分支 | DELETE | .../branches/{bid} | 400 |

#### 租户管理 API

| 测试用例 ID | 场景 | HTTP 方法 | 路径 | 预期状态码 |
|------------|------|-----------|------|-----------|
| IT-API-TN-001 | 创建租户 | POST | /api/v1/tenants | 201 |
| IT-API-TN-002 | 查看租户 | GET | /api/v1/tenants/{id} | 200 |

### 3.2 Neon API 集成测试（WireMock）

**测试方案**：使用 WireMock 模拟 Neon Pageserver 和 Safekeeper API，验证管控面对 Neon API 的调用逻辑。

**关键测试场景**：

| 测试用例 ID | 场景 | 预期结果 |
|------------|------|----------|
| IT-NEON-001 | 创建 tenant — 正常 | POST /v1/tenant 被调用，tenant_id 正确记录 |
| IT-NEON-002 | 创建 tenant — Pageserver 返回 500 | 抛出异常，不继续后续操作 |
| IT-NEON-003 | 创建 timeline — 正常 | POST /v1/tenant/{id}/timeline 被调用 |
| IT-NEON-004 | 创建 timeline — 超时 | 超时处理，抛出异常 |
| IT-NEON-005 | 删除 tenant — 正常 | DELETE /v1/tenant/{id} 被调用 |
| IT-NEON-006 | 删除 tenant — Pageserver 返回 404 | 幂等处理，不抛出异常 |
| IT-NEON-007 | 列出 timelines — 正常 | GET /v1/tenant/{id}/timeline 返回列表 |
| IT-NEON-008 | Pageserver 连接失败 | 合理的超时和重试逻辑，最终抛出连接异常 |

**WireMock 配置示例**：

```java
@SpringBootTest
@WireMockTest(httpPort = 9090)
class NeonApiIntegrationTest {

    @Test
    void createTenant_success(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/v1/tenant"))
            .willReturn(aResponse()
                .withStatus(201)
                .withBody("{\"id\": \"tenant-abc\"}")));

        var result = neonApiClient.createTenant(new CreateTenantRequest(...));

        assertThat(result.getId()).isEqualTo("tenant-abc");
        verify(postRequestedFor(urlEqualTo("/v1/tenant")));
    }

    @Test
    void createTenant_serverError_throws() {
        stubFor(post(urlEqualTo("/v1/tenant"))
            .willReturn(aResponse().withStatus(500)));

        assertThrows(NeonApiException.class, () ->
            neonApiClient.createTenant(new CreateTenantRequest(...)));
    }
}
```

---

## 4. 验收测试场景

验收测试覆盖用户故事的端到端场景，验证系统作为整体的行为正确性。

### AT-001: 创建实例并获得连接串

**关联用户故事**：US-1

**前置条件**：租户已创建，API Key 有效

**测试步骤**：
1. 调用 `POST /api/v1/databases` 创建实例（name: "test-db", compute_size: "1cu"）
2. 验证响应状态码 201
3. 验证响应包含 `connection_uri`，格式为 `postgres://...`
4. 验证响应 `status` 为 `creating` 或 `running`
5. 轮询实例状态直到变为 `running`（超时 60s）

**验收标准**：
- [x] 返回 201 状态码
- [x] `connection_uri` 非空且格式正确
- [x] 60s 内实例变为 running 状态
- [x] 底层 Neon tenant 和 timeline 已创建
- [x] K8s compute Pod 已运行

### AT-002: 连接数据库并写入数据

**关联用户故事**：US-2

**前置条件**：AT-001 完成，实例 running

**测试步骤**：
1. 使用 `connection_uri` 通过 psql/libpq 连接数据库
2. 执行 `CREATE TABLE test_data (id SERIAL PRIMARY KEY, value TEXT)`
3. 执行 `INSERT INTO test_data (value) VALUES ('hello-lakeon')`
4. 执行 `SELECT * FROM test_data`
5. 验证查询结果

**验收标准**：
- [x] psql 连接成功
- [x] DDL 执行成功
- [x] DML 执行成功
- [x] SELECT 返回正确数据

### AT-003: 等待休眠并验证 compute Pod 销毁

**关联用户故事**：US-3

**前置条件**：AT-002 完成，实例 running，suspend_timeout 设为较短时间（如 1m 用于测试）

**测试步骤**：
1. 断开所有数据库连接
2. 等待超过 suspend_timeout 时间
3. 检查实例状态是否变为 `suspended`
4. 检查 K8s 中对应 compute Pod 是否已销毁

**验收标准**：
- [x] 超时后实例状态变为 suspended
- [x] K8s compute Pod 不再存在
- [x] Pageserver 和 Safekeeper 数据保持不变

### AT-004: 重新连接并自动唤醒，验证数据完整性

**关联用户故事**：US-4

**前置条件**：AT-003 完成，实例 suspended

**测试步骤**：
1. 使用原始 `connection_uri` 通过 psql 尝试连接
2. 等待连接建立（Proxy 触发唤醒）
3. 执行 `SELECT * FROM test_data`
4. 验证数据与 AT-002 写入一致（value = 'hello-lakeon'）
5. 检查实例状态变回 `running`

**验收标准**：
- [x] 连接在 30s 内建立成功
- [x] 查询返回之前写入的数据，数据零丢失
- [x] 实例状态变为 running
- [x] 新的 K8s compute Pod 已创建

### AT-005: 创建分支并验证数据隔离

**关联用户故事**：US-5

**前置条件**：AT-002 完成（主分支有数据）

**测试步骤**：
1. 调用 `POST /api/v1/databases/{id}/branches` 创建分支（name: "test-branch", start_compute: true）
2. 等待分支 compute 就绪
3. 连接到分支（使用分支连接串）
4. 验证分支包含主分支的数据（SELECT * FROM test_data 返回 'hello-lakeon'）
5. 在分支中写入新数据：`INSERT INTO test_data (value) VALUES ('branch-data')`
6. 在主分支中验证没有分支新写入的数据
7. 在分支中验证有两条数据

**验收标准**：
- [x] 分支创建成功，有独立连接串
- [x] 分支包含 fork 时刻的主分支数据
- [x] 分支写入的数据不影响主分支
- [x] 主分支和分支的数据完全隔离

### AT-006: 删除分支和实例，验证资源清理

**关联用户故事**：US-6

**前置条件**：AT-005 完成

**测试步骤**：
1. 删除分支：`DELETE /api/v1/databases/{id}/branches/{branch_id}`
2. 验证分支 compute Pod 已销毁
3. 验证分支 timeline 已从 Neon 删除
4. 验证主分支数据不受影响
5. 删除实例：`DELETE /api/v1/databases/{id}`
6. 验证 compute Pod 已销毁
7. 验证 Neon tenant 已清理
8. 验证元数据已清除

**验收标准**：
- [x] 分支删除后资源完全清理
- [x] 主分支不受分支删除影响
- [x] 实例删除后所有关联资源清理（K8s Pod、Neon tenant、元数据）
- [x] 删除后实例列表不再包含该实例

### AT-007: 异常场景 — 创建失败回滚

**测试步骤**：
1. 模拟 Neon Pageserver 不可用
2. 调用创建实例 API
3. 验证返回错误信息（500 或合理的错误码）
4. 验证没有遗留的 K8s Pod
5. 验证没有遗留的元数据记录

**验收标准**：
- [x] 返回明确的错误码和错误信息
- [x] 无资源泄露（无遗留 Pod、无遗留元数据）
- [x] 可重试创建

### AT-008: 异常场景 — 唤醒失败

**测试步骤**：
1. 休眠实例
2. 模拟 K8s Pod 创建失败（资源不足等）
3. 通过 Proxy 尝试连接
4. 验证返回 PostgreSQL 标准错误消息

**验收标准**：
- [x] Proxy 返回包含明确原因的错误
- [x] 实例状态准确反映错误
- [x] 后续重试（问题修复后）可以成功唤醒

### AT-009: 异常场景 — 存储上限

**测试步骤**：
1. 创建实例，设置 storage_limit_gb 为较小值
2. 写入大量数据直到接近上限
3. 验证存储用量查询准确
4. 继续写入超出上限
5. 验证系统正确处理（拒绝写入或告警）

**验收标准**：
- [x] 存储用量查询返回准确值
- [x] 接近或达到上限时有合理的处理机制

### AT-010: 多租户隔离验证

**测试步骤**：
1. 创建两个不同租户（tenant-A, tenant-B）
2. tenant-A 创建实例 "app-db"
3. tenant-B 尝试访问 tenant-A 的实例
4. 验证 tenant-B 无法访问

**验收标准**：
- [x] tenant-B 查询 tenant-A 的实例返回 404
- [x] tenant-B 无法操作 tenant-A 的资源

---

## 5. 测试工具和框架

### 5.1 Java 测试栈

| 工具 | 版本 | 用途 |
|------|------|------|
| JUnit 5 | 5.10+ | 测试框架 |
| Mockito | 5.x | Mock 框架 |
| AssertJ | 3.25+ | 断言库 |
| Spring Boot Test | 3.3+ | Spring 测试支持 |
| MockMvc / WebTestClient | Spring 自带 | Controller 层测试 |
| WireMock | 3.x | HTTP API mock（模拟 Neon API） |
| Testcontainers | 1.19+ | PostgreSQL 容器（Repository 测试） |
| JaCoCo | 0.8+ | 代码覆盖率 |

**Maven 依赖配置**：

```xml
<dependencies>
    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.wiremock</groupId>
        <artifactId>wiremock-standalone</artifactId>
        <version>3.3.1</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 5.2 Python 测试栈

| 工具 | 版本 | 用途 |
|------|------|------|
| pytest | 8.x | 测试框架 |
| pytest-httpx | 0.30+ | httpx mock |
| pytest-cov | 5.x | 覆盖率 |
| typer.testing.CliRunner | Typer 自带 | CLI 命令测试 |

**pyproject.toml 测试配置**：

```toml
[tool.pytest.ini_options]
testpaths = ["tests"]
addopts = "--cov=lakeon_cli --cov-report=term-missing --cov-fail-under=85"

[project.optional-dependencies]
dev = [
    "pytest>=8.0",
    "pytest-httpx>=0.30",
    "pytest-cov>=5.0",
]
```

### 5.3 CI 集成

测试在 CI 流水线中的执行顺序：

```
1. 代码检查（lint/format）
   ├── Java: checkstyle / spotless
   └── Python: ruff / black

2. 单元测试（快速，< 2min）
   ├── Java: mvn test -Dgroups=unit
   └── Python: pytest tests/unit/

3. 集成测试（中速，< 5min）
   ├── Java: mvn test -Dgroups=integration（需要 Docker 运行 Testcontainers + WireMock）
   └── Python: pytest tests/integration/

4. 覆盖率报告
   ├── Java: JaCoCo report
   └── Python: pytest-cov report

5. 验收测试（慢，需要完整环境，可选触发）
   └── 针对部署环境运行 AT-001 ~ AT-010
```

---

## 6. 测试数据管理

### 测试数据策略

- **单元测试**：使用固定的测试数据工厂（Builder 模式或 Fixture），不依赖外部状态
- **集成测试**：每个测试方法通过 `@Transactional` 自动回滚，或使用 `@DirtiesContext` 隔离
- **验收测试**：每次测试运行前创建独立的租户和实例，测试后清理

### 测试命名规范

- Java：`{方法名}_{场景}_{预期结果}` 例如 `createDatabase_nameAlreadyExists_throwsConflict`
- Python：`test_{命令}_{场景}` 例如 `test_db_create_success`

---

## 7. 测试优先级

| 优先级 | 测试类型 | 说明 |
|--------|---------|------|
| P0 - 必须 | AT-001 ~ AT-004 | 核心流程：创建、连接、休眠、唤醒、数据完整性 |
| P0 - 必须 | UT-SVC-DB-001 ~ 003 | 创建实例的核心逻辑和错误处理 |
| P0 - 必须 | UT-SVC-CL-001 ~ 002 | compute 唤醒逻辑 |
| P1 - 重要 | AT-005 ~ AT-006 | 分支管理和资源清理 |
| P1 - 重要 | IT-API-DB-* | API 层端到端测试 |
| P1 - 重要 | IT-NEON-* | Neon API 集成正确性 |
| P2 - 一般 | AT-007 ~ AT-010 | 异常场景和边界条件 |
| P2 - 一般 | UT-REPO-* | Repository 层数据访问 |
| P2 - 一般 | UT-CLI-* | CLI 命令测试 |
