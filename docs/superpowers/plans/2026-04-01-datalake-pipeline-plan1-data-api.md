# 数据生产线 Plan 1: 数据基础 + API

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 Pipeline 系统的数据模型和 CRUD API 层，为 Orchestrator 和前端提供基础。

**Architecture:** 扩展 lakeon-api，新增 `com.lakeon.pipeline` 包。数据集版本化扩展现有 `com.lakeon.dataset` 包。所有 API 遵循现有模式：`Map<String, Object>` 响应、`HttpServletRequest` 租户隔离、构造函数注入。

**Tech Stack:** Spring Boot 3.3.5, Jakarta Persistence, PostgreSQL, Flyway, JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-04-01-datalake-pipeline-design.md`

---

## File Structure

```
lakeon-api/src/main/
├── java/com/lakeon/
│   ├── dataset/
│   │   ├── DatasetEntity.java              (修改: 添加 sourceType, latestVersion)
│   │   ├── DatasetSourceType.java          (修改: 添加 PIPELINE_OUTPUT)
│   │   ├── DatasetService.java             (修改: 版本化方法)
│   │   ├── DatasetController.java          (修改: 版本化 API)
│   │   ├── DatasetVersionEntity.java       (新建)
│   │   └── DatasetVersionRepository.java   (新建)
│   └── pipeline/
│       ├── PipelineEntity.java             (新建)
│       ├── PipelineVersionEntity.java      (新建)
│       ├── PipelineComponentEntity.java    (新建)
│       ├── PipelineComponentVersionEntity.java (新建)
│       ├── PipelineRunEntity.java          (新建)
│       ├── PipelineStepRunEntity.java      (新建)
│       ├── PipelineRepository.java         (新建)
│       ├── PipelineVersionRepository.java  (新建)
│       ├── PipelineComponentRepository.java (新建)
│       ├── PipelineComponentVersionRepository.java (新建)
│       ├── PipelineRunRepository.java      (新建)
│       ├── PipelineStepRunRepository.java  (新建)
│       ├── PipelineService.java            (新建)
│       ├── PipelineComponentService.java   (新建)
│       ├── PipelineRunService.java         (新建)
│       ├── PipelineController.java         (新建)
│       ├── PipelineComponentController.java (新建)
│       ├── PipelineRunController.java      (新建)
│       ├── PipelineStatus.java             (新建: 枚举)
│       ├── PipelineRunStatus.java          (新建: 枚举)
│       └── PipelineStepRunStatus.java      (新建: 枚举)
└── resources/db/migration/
    ├── V25__dataset_versioning.sql          (新建)
    ├── V26__create_pipeline_components.sql  (新建)
    ├── V27__create_pipelines.sql            (新建)
    └── V28__create_pipeline_runs.sql        (新建)

lakeon-api/src/test/java/com/lakeon/
├── dataset/
│   └── DatasetVersionRepositoryTest.java   (新建)
├── pipeline/
│   ├── PipelineRepositoryTest.java         (新建)
│   ├── PipelineServiceTest.java            (新建)
│   ├── PipelineControllerTest.java         (新建)
│   ├── PipelineComponentServiceTest.java   (新建)
│   ├── PipelineComponentControllerTest.java (新建)
│   ├── PipelineRunServiceTest.java         (新建)
│   └── PipelineRunControllerTest.java      (新建)
```

---

## Task 1: 数据集版本化 — 数据库迁移

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V25__dataset_versioning.sql`

- [ ] **Step 1: 编写迁移脚本**

```sql
-- V25__dataset_versioning.sql

-- 扩展 datasets 表
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS source_type VARCHAR(20) DEFAULT 'DB_EXPORT';
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS latest_version INTEGER DEFAULT 1;

-- 新建 dataset_versions 表
CREATE TABLE dataset_versions (
    id VARCHAR(64) PRIMARY KEY,
    dataset_id VARCHAR(64) NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    format VARCHAR(16) NOT NULL DEFAULT 'PARQUET',
    obs_path VARCHAR(512),
    row_count BIGINT,
    file_size BIGINT,
    schema_json TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATING',
    source_pipeline_run_id VARCHAR(64),
    source_job_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(dataset_id, version)
);
CREATE INDEX idx_dsv_dataset ON dataset_versions(dataset_id);
CREATE INDEX idx_dsv_status ON dataset_versions(status);

-- 为现有数据集创建 v1 版本记录
INSERT INTO dataset_versions (id, dataset_id, version, format, obs_path, row_count, file_size, schema_json, status, source_job_id, created_at)
SELECT
    'dsv_' || substring(replace(gen_random_uuid()::text, '-', '') from 1 for 12),
    id, 1, 'PARQUET', obs_path, row_count, file_size, schema_json, 'READY', job_id, created_at
FROM datasets
WHERE obs_path IS NOT NULL;
```

- [ ] **Step 2: 验证迁移**

Run: `cd lakeon-api && mvn flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/lakeon -Dflyway.user=lakeon -Dflyway.password=lakeon`

如果本地无 DB，跳过此步，后续通过 `@DataJpaTest` + H2 验证 schema。

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V25__dataset_versioning.sql
git commit -m "feat(pipeline): add dataset versioning migration V25"
```

---

## Task 2: 数据集版本化 — Entity + Repository

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetVersionEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetVersionRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetSourceType.java`

- [ ] **Step 1: 编写 Repository 测试**

Create: `lakeon-api/src/test/java/com/lakeon/dataset/DatasetVersionRepositoryTest.java`

```java
package com.lakeon.dataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("DatasetVersionRepository 数据访问层测试")
class DatasetVersionRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private DatasetVersionRepository versionRepo;

    @Autowired
    private DatasetRepository datasetRepo;

    private DatasetEntity createDataset(String tenantId, String name) {
        DatasetEntity ds = new DatasetEntity();
        ds.setTenantId(tenantId);
        ds.setName(name);
        ds.setSourceType(DatasetSourceType.DB_EXPORT);
        ds.setStatus(DatasetStatus.READY);
        ds.setLatestVersion(1);
        return datasetRepo.save(ds);
    }

    @Test
    @DisplayName("UT-DSV-001: save — 正常保存版本记录")
    void save_success() {
        DatasetEntity ds = createDataset("tn_dsv001", "test-ds");

        DatasetVersionEntity v = new DatasetVersionEntity();
        v.setDatasetId(ds.getId());
        v.setVersion(1);
        v.setFormat("PARQUET");
        v.setStatus("READY");

        DatasetVersionEntity saved = versionRepo.save(v);
        assertThat(saved.getId()).startsWith("dsv_");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("UT-DSV-002: findByDatasetIdOrderByVersionDesc — 按版本倒序")
    void findByDatasetId_orderedByVersionDesc() {
        DatasetEntity ds = createDataset("tn_dsv002", "test-ds-2");

        for (int i = 1; i <= 3; i++) {
            DatasetVersionEntity v = new DatasetVersionEntity();
            v.setDatasetId(ds.getId());
            v.setVersion(i);
            v.setFormat("PARQUET");
            v.setStatus("READY");
            versionRepo.save(v);
        }

        List<DatasetVersionEntity> versions = versionRepo.findByDatasetIdOrderByVersionDesc(ds.getId());
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("UT-DSV-003: findByDatasetIdAndVersion — 精确查询")
    void findByDatasetIdAndVersion_found() {
        DatasetEntity ds = createDataset("tn_dsv003", "test-ds-3");

        DatasetVersionEntity v = new DatasetVersionEntity();
        v.setDatasetId(ds.getId());
        v.setVersion(1);
        v.setFormat("LANCE");
        v.setStatus("READY");
        versionRepo.save(v);

        var found = versionRepo.findByDatasetIdAndVersion(ds.getId(), 1);
        assertThat(found).isPresent();
        assertThat(found.get().getFormat()).isEqualTo("LANCE");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-api && mvn test -pl . -Dtest=DatasetVersionRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 编译失败，`DatasetVersionEntity` 和 `DatasetVersionRepository` 不存在。

- [ ] **Step 3: 修改 DatasetSourceType 枚举**

File: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetSourceType.java`

```java
package com.lakeon.dataset;

public enum DatasetSourceType {
    DB_EXPORT,
    UPLOAD,
    PIPELINE_OUTPUT
}
```

- [ ] **Step 4: 修改 DatasetEntity — 添加字段**

在 `DatasetEntity.java` 中添加 `latestVersion` 字段（`sourceType` 已有对应枚举）：

在 `private String schemaJson;` 之后添加：

```java
    @Column(name = "latest_version")
    private Integer latestVersion = 1;
```

在现有 getter/setter 之后添加：

```java
    public Integer getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(Integer latestVersion) {
        this.latestVersion = latestVersion;
    }
```

- [ ] **Step 5: 创建 DatasetVersionEntity**

Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetVersionEntity.java`

```java
package com.lakeon.dataset;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dataset_versions", indexes = {
    @Index(name = "idx_dsv_dataset", columnList = "dataset_id"),
    @Index(name = "idx_dsv_status", columnList = "status")
})
public class DatasetVersionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "dataset_id", nullable = false, length = 64)
    private String datasetId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 16)
    private String format = "PARQUET";

    @Column(name = "obs_path", length = 512)
    private String obsPath;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "schema_json", columnDefinition = "text")
    private String schemaJson;

    @Column(nullable = false, length = 16)
    private String status = "CREATING";

    @Column(name = "source_pipeline_run_id", length = 64)
    private String sourcePipelineRunId;

    @Column(name = "source_job_id", length = 64)
    private String sourceJobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "dsv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // --- Getters and Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getObsPath() { return obsPath; }
    public void setObsPath(String obsPath) { this.obsPath = obsPath; }

    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getSchemaJson() { return schemaJson; }
    public void setSchemaJson(String schemaJson) { this.schemaJson = schemaJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSourcePipelineRunId() { return sourcePipelineRunId; }
    public void setSourcePipelineRunId(String s) { this.sourcePipelineRunId = s; }

    public String getSourceJobId() { return sourceJobId; }
    public void setSourceJobId(String sourceJobId) { this.sourceJobId = sourceJobId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 6: 创建 DatasetVersionRepository**

Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetVersionRepository.java`

```java
package com.lakeon.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DatasetVersionRepository extends JpaRepository<DatasetVersionEntity, String> {
    List<DatasetVersionEntity> findByDatasetIdOrderByVersionDesc(String datasetId);
    Optional<DatasetVersionEntity> findByDatasetIdAndVersion(String datasetId, Integer version);
}
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `cd lakeon-api && mvn test -pl . -Dtest=DatasetVersionRepositoryTest`

Expected: 3 tests PASS

- [ ] **Step 8: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/dataset/DatasetVersionEntity.java \
        lakeon-api/src/main/java/com/lakeon/dataset/DatasetVersionRepository.java \
        lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java \
        lakeon-api/src/main/java/com/lakeon/dataset/DatasetSourceType.java \
        lakeon-api/src/test/java/com/lakeon/dataset/DatasetVersionRepositoryTest.java
git commit -m "feat(pipeline): add DatasetVersionEntity and repository with tests"
```

---

## Task 3: Pipeline 组件 — 数据库迁移 + Entity + Repository

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V26__create_pipeline_components.sql`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentVersionEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentRepository.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentVersionRepository.java`
- Test: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineComponentRepositoryTest.java`

- [ ] **Step 1: 编写迁移脚本**

Create: `lakeon-api/src/main/resources/db/migration/V26__create_pipeline_components.sql`

```sql
CREATE TABLE pipeline_components (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64),
    name VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    category VARCHAR(20) NOT NULL,
    data_type VARCHAR(20) NOT NULL,
    description TEXT,
    latest_version INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_comp_tenant ON pipeline_components(tenant_id);
CREATE INDEX idx_comp_category ON pipeline_components(category);
CREATE INDEX idx_comp_data_type ON pipeline_components(data_type);

CREATE TABLE pipeline_component_versions (
    id VARCHAR(64) PRIMARY KEY,
    component_id VARCHAR(64) NOT NULL REFERENCES pipeline_components(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    entrypoint VARCHAR(256) NOT NULL,
    params_schema TEXT,
    input_schema TEXT,
    output_schema TEXT,
    output_branches TEXT,
    requires_gpu BOOLEAN DEFAULT FALSE,
    requires_model VARCHAR(128),
    execution_mode VARCHAR(20) DEFAULT 'FUNCTION',
    status VARCHAR(16) DEFAULT 'DRAFT',
    changelog TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(component_id, version)
);
CREATE INDEX idx_compv_component ON pipeline_component_versions(component_id);
CREATE INDEX idx_compv_status ON pipeline_component_versions(status);
```

- [ ] **Step 2: 编写 Repository 测试**

Create: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineComponentRepositoryTest.java`

```java
package com.lakeon.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("PipelineComponentRepository 数据访问层测试")
class PipelineComponentRepositoryTest {

    @Autowired
    private PipelineComponentRepository compRepo;

    @Autowired
    private PipelineComponentVersionRepository compVersionRepo;

    private PipelineComponentEntity createComponent(String tenantId, String name, String category, String dataType) {
        PipelineComponentEntity c = new PipelineComponentEntity();
        c.setTenantId(tenantId);
        c.setName(name);
        c.setDisplayName(name);
        c.setCategory(category);
        c.setDataType(dataType);
        return compRepo.save(c);
    }

    @Test
    @DisplayName("UT-COMP-001: save — 正常保存组件")
    void save_success() {
        PipelineComponentEntity saved = createComponent(null, "video_scene_split", "EXTRACT", "VIDEO");
        assertThat(saved.getId()).startsWith("comp_");
        assertThat(saved.getTenantId()).isNull();
    }

    @Test
    @DisplayName("UT-COMP-002: 查询平台内置组件 — tenant_id IS NULL")
    void findBuiltinComponents() {
        createComponent(null, "video_normalize", "DATA_PREP", "VIDEO");
        createComponent(null, "text_dedup", "CLEAN", "TEXT");
        createComponent("tn_002", "custom_filter", "FILTER", "VIDEO");

        List<PipelineComponentEntity> builtins = compRepo.findByTenantIdIsNull();
        assertThat(builtins).hasSize(2);
    }

    @Test
    @DisplayName("UT-COMP-003: 查询租户可用组件 — 内置 + 本租户")
    void findAvailableForTenant() {
        createComponent(null, "builtin_1", "DATA_PREP", "VIDEO");
        createComponent("tn_003", "custom_1", "FILTER", "VIDEO");
        createComponent("tn_other", "other_custom", "FILTER", "VIDEO");

        List<PipelineComponentEntity> available = compRepo.findByTenantIdIsNullOrTenantId("tn_003");
        assertThat(available).hasSize(2);
    }

    @Test
    @DisplayName("UT-COMP-004: 保存组件版本")
    void saveComponentVersion() {
        PipelineComponentEntity comp = createComponent(null, "rule_filter", "FILTER", "VIDEO");

        PipelineComponentVersionEntity v = new PipelineComponentVersionEntity();
        v.setComponentId(comp.getId());
        v.setVersion(1);
        v.setEntrypoint("lakeon.components.video.rule_filter");
        v.setParamsSchema("{\"min_duration\":{\"type\":\"number\",\"default\":3}}");
        v.setOutputBranches("[\"passed\",\"needs_crop\",\"dropped\"]");
        v.setExecutionMode("FUNCTION");
        v.setStatus("PUBLISHED");

        PipelineComponentVersionEntity saved = compVersionRepo.save(v);
        assertThat(saved.getId()).startsWith("compv_");

        var found = compVersionRepo.findByComponentIdAndVersion(comp.getId(), 1);
        assertThat(found).isPresent();
        assertThat(found.get().getEntrypoint()).isEqualTo("lakeon.components.video.rule_filter");
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineComponentRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 编译失败

- [ ] **Step 4: 创建 PipelineComponentEntity**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentEntity.java`

```java
package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_components", indexes = {
    @Index(name = "idx_comp_tenant", columnList = "tenant_id"),
    @Index(name = "idx_comp_category", columnList = "category"),
    @Index(name = "idx_comp_data_type", columnList = "data_type")
})
public class PipelineComponentEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(name = "data_type", nullable = false, length = 20)
    private String dataType;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "latest_version")
    private Integer latestVersion = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "comp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getLatestVersion() { return latestVersion; }
    public void setLatestVersion(Integer latestVersion) { this.latestVersion = latestVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 5: 创建 PipelineComponentVersionEntity**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentVersionEntity.java`

```java
package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_component_versions", indexes = {
    @Index(name = "idx_compv_component", columnList = "component_id"),
    @Index(name = "idx_compv_status", columnList = "status")
})
public class PipelineComponentVersionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "component_id", nullable = false, length = 64)
    private String componentId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 256)
    private String entrypoint;

    @Column(name = "params_schema", columnDefinition = "text")
    private String paramsSchema;

    @Column(name = "input_schema", columnDefinition = "text")
    private String inputSchema;

    @Column(name = "output_schema", columnDefinition = "text")
    private String outputSchema;

    @Column(name = "output_branches", columnDefinition = "text")
    private String outputBranches;

    @Column(name = "requires_gpu")
    private Boolean requiresGpu = false;

    @Column(name = "requires_model", length = 128)
    private String requiresModel;

    @Column(name = "execution_mode", length = 20)
    private String executionMode = "FUNCTION";

    @Column(length = 16)
    private String status = "DRAFT";

    @Column(columnDefinition = "text")
    private String changelog;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "compv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        }
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getComponentId() { return componentId; }
    public void setComponentId(String componentId) { this.componentId = componentId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getEntrypoint() { return entrypoint; }
    public void setEntrypoint(String entrypoint) { this.entrypoint = entrypoint; }
    public String getParamsSchema() { return paramsSchema; }
    public void setParamsSchema(String paramsSchema) { this.paramsSchema = paramsSchema; }
    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public void setOutputSchema(String outputSchema) { this.outputSchema = outputSchema; }
    public String getOutputBranches() { return outputBranches; }
    public void setOutputBranches(String outputBranches) { this.outputBranches = outputBranches; }
    public Boolean getRequiresGpu() { return requiresGpu; }
    public void setRequiresGpu(Boolean requiresGpu) { this.requiresGpu = requiresGpu; }
    public String getRequiresModel() { return requiresModel; }
    public void setRequiresModel(String requiresModel) { this.requiresModel = requiresModel; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChangelog() { return changelog; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 6: 创建 Repository 接口**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentRepository.java`

```java
package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineComponentRepository extends JpaRepository<PipelineComponentEntity, String> {
    List<PipelineComponentEntity> findByTenantIdIsNull();
    List<PipelineComponentEntity> findByTenantIdIsNullOrTenantId(String tenantId);
    Optional<PipelineComponentEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<PipelineComponentEntity> findByNameAndTenantId(String name, String tenantId);
    Optional<PipelineComponentEntity> findByNameAndTenantIdIsNull(String name);
}
```

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentVersionRepository.java`

```java
package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineComponentVersionRepository extends JpaRepository<PipelineComponentVersionEntity, String> {
    List<PipelineComponentVersionEntity> findByComponentIdOrderByVersionDesc(String componentId);
    Optional<PipelineComponentVersionEntity> findByComponentIdAndVersion(String componentId, Integer version);
}
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineComponentRepositoryTest`

Expected: 4 tests PASS

- [ ] **Step 8: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V26__create_pipeline_components.sql \
        lakeon-api/src/main/java/com/lakeon/pipeline/ \
        lakeon-api/src/test/java/com/lakeon/pipeline/PipelineComponentRepositoryTest.java
git commit -m "feat(pipeline): add pipeline component entities and repositories"
```

---

## Task 4: Pipeline 定义 — 数据库迁移 + Entity + Repository

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V27__create_pipelines.sql`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineVersionEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRepository.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineVersionRepository.java`
- Test: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineRepositoryTest.java`

- [ ] **Step 1: 编写迁移脚本**

Create: `lakeon-api/src/main/resources/db/migration/V27__create_pipelines.sql`

```sql
CREATE TABLE pipelines (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    data_type VARCHAR(20),
    is_template BOOLEAN DEFAULT FALSE,
    source_template_id VARCHAR(64),
    latest_version INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pipe_tenant ON pipelines(tenant_id);
CREATE INDEX idx_pipe_template ON pipelines(is_template);

CREATE TABLE pipeline_versions (
    id VARCHAR(64) PRIMARY KEY,
    pipeline_id VARCHAR(64) NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    dag_yaml TEXT NOT NULL,
    status VARCHAR(16) DEFAULT 'DRAFT',
    changelog TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(pipeline_id, version)
);
CREATE INDEX idx_pipev_pipeline ON pipeline_versions(pipeline_id);
```

- [ ] **Step 2: 编写 Repository 测试**

Create: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineRepositoryTest.java`

```java
package com.lakeon.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("PipelineRepository 数据访问层测试")
class PipelineRepositoryTest {

    @Autowired
    private PipelineRepository pipelineRepo;

    @Autowired
    private PipelineVersionRepository versionRepo;

    private PipelineEntity createPipeline(String tenantId, String name, boolean isTemplate) {
        PipelineEntity p = new PipelineEntity();
        p.setTenantId(tenantId);
        p.setName(name);
        p.setDataType("VIDEO");
        p.setIsTemplate(isTemplate);
        return pipelineRepo.save(p);
    }

    @Test
    @DisplayName("UT-PIPE-001: save — 正常保存 pipeline")
    void save_success() {
        PipelineEntity saved = createPipeline("tn_pipe001", "视频清洗流水线", false);
        assertThat(saved.getId()).startsWith("pipe_");
    }

    @Test
    @DisplayName("UT-PIPE-002: 查询租户的 pipeline 列表")
    void findByTenantId() {
        createPipeline("tn_pipe002", "pipeline-1", false);
        createPipeline("tn_pipe002", "pipeline-2", false);
        createPipeline("tn_other", "other-pipeline", false);

        var list = pipelineRepo.findByTenantIdAndIsTemplateFalseOrderByCreatedAtDesc("tn_pipe002");
        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("UT-PIPE-003: 查询平台模板列表")
    void findTemplates() {
        createPipeline("system", "视频模板", true);
        createPipeline("system", "文本模板", true);
        createPipeline("tn_003", "用户 pipeline", false);

        var templates = pipelineRepo.findByIsTemplateTrue();
        assertThat(templates).hasSize(2);
    }

    @Test
    @DisplayName("UT-PIPE-004: 保存 pipeline 版本")
    void savePipelineVersion() {
        PipelineEntity pipe = createPipeline("tn_pipe004", "test-pipe", false);

        PipelineVersionEntity v = new PipelineVersionEntity();
        v.setPipelineId(pipe.getId());
        v.setVersion(1);
        v.setDagYaml("steps:\n  - id: normalize\n    component: video_normalize");
        v.setStatus("DRAFT");

        PipelineVersionEntity saved = versionRepo.save(v);
        assertThat(saved.getId()).startsWith("pipev_");

        var found = versionRepo.findByPipelineIdAndVersion(pipe.getId(), 1);
        assertThat(found).isPresent();
        assertThat(found.get().getDagYaml()).contains("video_normalize");
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 编译失败

- [ ] **Step 4: 创建 PipelineStatus 枚举**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStatus.java`

```java
package com.lakeon.pipeline;

public enum PipelineStatus {
    DRAFT,
    PUBLISHED,
    DEPRECATED
}
```

- [ ] **Step 5: 创建 PipelineEntity**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineEntity.java`

```java
package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipelines", indexes = {
    @Index(name = "idx_pipe_tenant", columnList = "tenant_id"),
    @Index(name = "idx_pipe_template", columnList = "is_template")
})
public class PipelineEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "data_type", length = 20)
    private String dataType;

    @Column(name = "is_template")
    private Boolean isTemplate = false;

    @Column(name = "source_template_id", length = 64)
    private String sourceTemplateId;

    @Column(name = "latest_version")
    private Integer latestVersion = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "pipe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Boolean getIsTemplate() { return isTemplate; }
    public void setIsTemplate(Boolean isTemplate) { this.isTemplate = isTemplate; }
    public String getSourceTemplateId() { return sourceTemplateId; }
    public void setSourceTemplateId(String s) { this.sourceTemplateId = s; }
    public Integer getLatestVersion() { return latestVersion; }
    public void setLatestVersion(Integer latestVersion) { this.latestVersion = latestVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 6: 创建 PipelineVersionEntity**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineVersionEntity.java`

```java
package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_versions", indexes = {
    @Index(name = "idx_pipev_pipeline", columnList = "pipeline_id")
})
public class PipelineVersionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "pipeline_id", nullable = false, length = 64)
    private String pipelineId;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "dag_yaml", nullable = false, columnDefinition = "text")
    private String dagYaml;

    @Column(length = 16)
    private String status = "DRAFT";

    @Column(columnDefinition = "text")
    private String changelog;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "pipev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        }
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDagYaml() { return dagYaml; }
    public void setDagYaml(String dagYaml) { this.dagYaml = dagYaml; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChangelog() { return changelog; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 7: 创建 Repository 接口**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRepository.java`

```java
package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineRepository extends JpaRepository<PipelineEntity, String> {
    List<PipelineEntity> findByTenantIdAndIsTemplateFalseOrderByCreatedAtDesc(String tenantId);
    List<PipelineEntity> findByIsTemplateTrue();
    Optional<PipelineEntity> findByIdAndTenantId(String id, String tenantId);
}
```

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineVersionRepository.java`

```java
package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineVersionRepository extends JpaRepository<PipelineVersionEntity, String> {
    List<PipelineVersionEntity> findByPipelineIdOrderByVersionDesc(String pipelineId);
    Optional<PipelineVersionEntity> findByPipelineIdAndVersion(String pipelineId, Integer version);
}
```

- [ ] **Step 8: 运行测试，确认通过**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineRepositoryTest`

Expected: 4 tests PASS

- [ ] **Step 9: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V27__create_pipelines.sql \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineEntity.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineVersionEntity.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStatus.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRepository.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineVersionRepository.java \
        lakeon-api/src/test/java/com/lakeon/pipeline/PipelineRepositoryTest.java
git commit -m "feat(pipeline): add pipeline and pipeline version entities"
```

---

## Task 5: Pipeline 运行记录 — 数据库迁移 + Entity + Repository

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V28__create_pipeline_runs.sql`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunRepository.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunRepository.java`

- [ ] **Step 1: 编写迁移脚本**

Create: `lakeon-api/src/main/resources/db/migration/V28__create_pipeline_runs.sql`

```sql
CREATE TABLE pipeline_runs (
    id VARCHAR(64) PRIMARY KEY,
    pipeline_id VARCHAR(64) NOT NULL REFERENCES pipelines(id),
    pipeline_version INTEGER NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    input_dataset_id VARCHAR(64),
    input_dataset_version INTEGER,
    output_dataset_version_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_run_pipeline ON pipeline_runs(pipeline_id);
CREATE INDEX idx_run_tenant ON pipeline_runs(tenant_id);
CREATE INDEX idx_run_status ON pipeline_runs(status);

CREATE TABLE pipeline_step_runs (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    step_id VARCHAR(128) NOT NULL,
    component_id VARCHAR(64),
    component_version INTEGER,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    input_ref TEXT,
    output_ref TEXT,
    checkpoint_path VARCHAR(512),
    metrics TEXT,
    error TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sr_run ON pipeline_step_runs(run_id);
CREATE INDEX idx_sr_status ON pipeline_step_runs(status);
```

- [ ] **Step 2: 创建枚举**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunStatus.java`

```java
package com.lakeon.pipeline;

public enum PipelineRunStatus {
    PENDING, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED
}
```

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunStatus.java`

```java
package com.lakeon.pipeline;

public enum PipelineStepRunStatus {
    PENDING, RUNNING, PAUSED, SUCCEEDED, FAILED, SKIPPED
}
```

- [ ] **Step 3: 创建 PipelineRunEntity**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunEntity.java`

```java
package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_runs", indexes = {
    @Index(name = "idx_run_pipeline", columnList = "pipeline_id"),
    @Index(name = "idx_run_tenant", columnList = "tenant_id"),
    @Index(name = "idx_run_status", columnList = "status")
})
public class PipelineRunEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "pipeline_id", nullable = false, length = 64)
    private String pipelineId;

    @Column(name = "pipeline_version", nullable = false)
    private Integer pipelineVersion;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "input_dataset_id", length = 64)
    private String inputDatasetId;

    @Column(name = "input_dataset_version")
    private Integer inputDatasetVersion;

    @Column(name = "output_dataset_version_id", length = 64)
    private String outputDatasetVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PipelineRunStatus status = PipelineRunStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public Integer getPipelineVersion() { return pipelineVersion; }
    public void setPipelineVersion(Integer pipelineVersion) { this.pipelineVersion = pipelineVersion; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getInputDatasetId() { return inputDatasetId; }
    public void setInputDatasetId(String s) { this.inputDatasetId = s; }
    public Integer getInputDatasetVersion() { return inputDatasetVersion; }
    public void setInputDatasetVersion(Integer v) { this.inputDatasetVersion = v; }
    public String getOutputDatasetVersionId() { return outputDatasetVersionId; }
    public void setOutputDatasetVersionId(String s) { this.outputDatasetVersionId = s; }
    public PipelineRunStatus getStatus() { return status; }
    public void setStatus(PipelineRunStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: 创建 PipelineStepRunEntity**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunEntity.java`

```java
package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_step_runs", indexes = {
    @Index(name = "idx_sr_run", columnList = "run_id"),
    @Index(name = "idx_sr_status", columnList = "status")
})
public class PipelineStepRunEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "step_id", nullable = false, length = 128)
    private String stepId;

    @Column(name = "component_id", length = 64)
    private String componentId;

    @Column(name = "component_version")
    private Integer componentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PipelineStepRunStatus status = PipelineStepRunStatus.PENDING;

    @Column(name = "input_ref", columnDefinition = "text")
    private String inputRef;

    @Column(name = "output_ref", columnDefinition = "text")
    private String outputRef;

    @Column(name = "checkpoint_path", length = 512)
    private String checkpointPath;

    @Column(columnDefinition = "text")
    private String metrics;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "sr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }
    public String getComponentId() { return componentId; }
    public void setComponentId(String componentId) { this.componentId = componentId; }
    public Integer getComponentVersion() { return componentVersion; }
    public void setComponentVersion(Integer componentVersion) { this.componentVersion = componentVersion; }
    public PipelineStepRunStatus getStatus() { return status; }
    public void setStatus(PipelineStepRunStatus status) { this.status = status; }
    public String getInputRef() { return inputRef; }
    public void setInputRef(String inputRef) { this.inputRef = inputRef; }
    public String getOutputRef() { return outputRef; }
    public void setOutputRef(String outputRef) { this.outputRef = outputRef; }
    public String getCheckpointPath() { return checkpointPath; }
    public void setCheckpointPath(String checkpointPath) { this.checkpointPath = checkpointPath; }
    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 5: 创建 Repository 接口**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunRepository.java`

```java
package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRunEntity, String> {
    List<PipelineRunEntity> findByPipelineIdOrderByCreatedAtDesc(String pipelineId);
    List<PipelineRunEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<PipelineRunEntity> findByStatus(PipelineRunStatus status);
    Optional<PipelineRunEntity> findByIdAndTenantId(String id, String tenantId);
}
```

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunRepository.java`

```java
package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PipelineStepRunRepository extends JpaRepository<PipelineStepRunEntity, String> {
    List<PipelineStepRunEntity> findByRunIdOrderByCreatedAtAsc(String runId);
    List<PipelineStepRunEntity> findByRunIdAndStatus(String runId, PipelineStepRunStatus status);
}
```

- [ ] **Step 6: 运行全部 pipeline 测试确认通过**

Run: `cd lakeon-api && mvn test -pl . -Dtest="com.lakeon.pipeline.*" -Dtest="com.lakeon.dataset.DatasetVersionRepositoryTest"`

Expected: 所有之前的测试 PASS（schema 兼容）

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V28__create_pipeline_runs.sql \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunEntity.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunEntity.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunStatus.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunStatus.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunRepository.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineStepRunRepository.java
git commit -m "feat(pipeline): add pipeline run and step run entities"
```

---

## Task 6: Pipeline CRUD Service + Controller

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineService.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineController.java`
- Test: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineServiceTest.java`
- Test: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineControllerTest.java`

- [ ] **Step 1: 编写 Service 单元测试**

Create: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineServiceTest.java`

```java
package com.lakeon.pipeline;

import com.lakeon.config.BadRequestException;
import com.lakeon.config.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineService 业务逻辑测试")
class PipelineServiceTest {

    @Mock private PipelineRepository pipelineRepo;
    @Mock private PipelineVersionRepository versionRepo;

    private PipelineService service;

    @BeforeEach
    void setUp() {
        service = new PipelineService(pipelineRepo, versionRepo);
    }

    @Test
    @DisplayName("UT-SVC-PIPE-001: create — 正常创建 pipeline + v1")
    void create_success() {
        when(pipelineRepo.save(any())).thenAnswer(inv -> {
            PipelineEntity e = inv.getArgument(0);
            e.setId("pipe_test123456");
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        when(versionRepo.save(any())).thenAnswer(inv -> {
            PipelineVersionEntity v = inv.getArgument(0);
            v.setId("pipev_test1234");
            v.setCreatedAt(Instant.now());
            return v;
        });

        PipelineEntity result = service.create(
            "tn_001", "视频清洗", "测试 pipeline", "VIDEO",
            null, "steps:\n  - id: normalize"
        );

        assertThat(result.getName()).isEqualTo("视频清洗");
        assertThat(result.getLatestVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("UT-SVC-PIPE-002: create — name 为空抛异常")
    void create_emptyName_throws() {
        assertThatThrownBy(() ->
            service.create("tn_001", "", "desc", "VIDEO", null, "steps:")
        ).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("UT-SVC-PIPE-003: create — dag_yaml 为空抛异常")
    void create_emptyYaml_throws() {
        assertThatThrownBy(() ->
            service.create("tn_001", "test", "desc", "VIDEO", null, "")
        ).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("UT-SVC-PIPE-004: get — 不存在抛 NotFoundException")
    void get_notFound_throws() {
        when(pipelineRepo.findByIdAndTenantId("pipe_xxx", "tn_001")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.get("tn_001", "pipe_xxx")
        ).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("UT-SVC-PIPE-005: createVersion — 版本号自增")
    void createVersion_incrementsVersion() {
        PipelineEntity pipe = new PipelineEntity();
        pipe.setId("pipe_test123456");
        pipe.setTenantId("tn_001");
        pipe.setLatestVersion(2);

        when(pipelineRepo.findByIdAndTenantId("pipe_test123456", "tn_001")).thenReturn(Optional.of(pipe));
        when(versionRepo.save(any())).thenAnswer(inv -> {
            PipelineVersionEntity v = inv.getArgument(0);
            v.setId("pipev_new12345");
            v.setCreatedAt(Instant.now());
            return v;
        });
        when(pipelineRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PipelineVersionEntity result = service.createVersion(
            "tn_001", "pipe_test123456", "steps:\n  - id: v3", "添加新步骤"
        );

        assertThat(result.getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("UT-SVC-PIPE-006: listTemplates — 返回模板列表")
    void listTemplates() {
        PipelineEntity t1 = new PipelineEntity();
        t1.setId("pipe_t1");
        t1.setIsTemplate(true);
        when(pipelineRepo.findByIsTemplateTrue()).thenReturn(List.of(t1));

        List<PipelineEntity> templates = service.listTemplates();
        assertThat(templates).hasSize(1);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 编译失败，`PipelineService` 不存在

- [ ] **Step 3: 实现 PipelineService**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineService.java`

```java
package com.lakeon.pipeline;

import com.lakeon.config.BadRequestException;
import com.lakeon.config.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineRepository pipelineRepo;
    private final PipelineVersionRepository versionRepo;

    public PipelineService(PipelineRepository pipelineRepo, PipelineVersionRepository versionRepo) {
        this.pipelineRepo = pipelineRepo;
        this.versionRepo = versionRepo;
    }

    @Transactional
    public PipelineEntity create(String tenantId, String name, String description,
                                  String dataType, String sourceTemplateId, String dagYaml) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Pipeline name is required");
        }
        if (dagYaml == null || dagYaml.isBlank()) {
            throw new BadRequestException("Pipeline DAG YAML is required");
        }

        PipelineEntity pipe = new PipelineEntity();
        pipe.setTenantId(tenantId);
        pipe.setName(name);
        pipe.setDescription(description);
        pipe.setDataType(dataType);
        pipe.setSourceTemplateId(sourceTemplateId);
        pipe.setLatestVersion(1);
        pipelineRepo.save(pipe);

        PipelineVersionEntity v = new PipelineVersionEntity();
        v.setPipelineId(pipe.getId());
        v.setVersion(1);
        v.setDagYaml(dagYaml);
        v.setStatus("DRAFT");
        versionRepo.save(v);

        log.info("Created pipeline {} v1 for tenant {}", pipe.getId(), tenantId);
        return pipe;
    }

    public PipelineEntity get(String tenantId, String pipelineId) {
        return pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
                .orElseThrow(() -> new NotFoundException("Pipeline not found: " + pipelineId));
    }

    public List<PipelineEntity> list(String tenantId) {
        return pipelineRepo.findByTenantIdAndIsTemplateFalseOrderByCreatedAtDesc(tenantId);
    }

    public List<PipelineEntity> listTemplates() {
        return pipelineRepo.findByIsTemplateTrue();
    }

    public List<PipelineVersionEntity> listVersions(String tenantId, String pipelineId) {
        get(tenantId, pipelineId);
        return versionRepo.findByPipelineIdOrderByVersionDesc(pipelineId);
    }

    public PipelineVersionEntity getVersion(String tenantId, String pipelineId, int version) {
        get(tenantId, pipelineId);
        return versionRepo.findByPipelineIdAndVersion(pipelineId, version)
                .orElseThrow(() -> new NotFoundException(
                    "Pipeline version not found: " + pipelineId + " v" + version));
    }

    @Transactional
    public PipelineVersionEntity createVersion(String tenantId, String pipelineId,
                                                String dagYaml, String changelog) {
        PipelineEntity pipe = get(tenantId, pipelineId);
        int nextVersion = pipe.getLatestVersion() + 1;

        PipelineVersionEntity v = new PipelineVersionEntity();
        v.setPipelineId(pipelineId);
        v.setVersion(nextVersion);
        v.setDagYaml(dagYaml);
        v.setStatus("DRAFT");
        v.setChangelog(changelog);
        versionRepo.save(v);

        pipe.setLatestVersion(nextVersion);
        pipelineRepo.save(pipe);

        log.info("Created pipeline {} v{} for tenant {}", pipelineId, nextVersion, tenantId);
        return v;
    }

    @Transactional
    public void delete(String tenantId, String pipelineId) {
        PipelineEntity pipe = get(tenantId, pipelineId);
        pipelineRepo.delete(pipe);
        log.info("Deleted pipeline {} for tenant {}", pipelineId, tenantId);
    }
}
```

- [ ] **Step 4: 运行 Service 测试，确认通过**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineServiceTest`

Expected: 6 tests PASS

- [ ] **Step 5: 编写 Controller 测试**

Create: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineControllerTest.java`

```java
package com.lakeon.pipeline;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PipelineController.class)
@Import(ApiKeyFilter.class)
@DisplayName("PipelineController API 集成测试")
class PipelineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PipelineService pipelineService;

    private PipelineEntity mockPipeline(String id, String name) {
        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId("tn_test");
        p.setName(name);
        p.setDataType("VIDEO");
        p.setLatestVersion(1);
        p.setIsTemplate(false);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    @Test
    @DisplayName("IT-API-PIPE-001: POST /api/v1/pipelines — 创建成功 201")
    void create_success() throws Exception {
        when(pipelineService.create(anyString(), eq("视频清洗"), any(), eq("VIDEO"), any(), anyString()))
            .thenReturn(mockPipeline("pipe_test123456", "视频清洗"));

        mockMvc.perform(post("/api/v1/pipelines")
                .requestAttr("tenant", TestHelper.mockTenant("tn_test"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "视频清洗",
                        "data_type": "VIDEO",
                        "dag_yaml": "steps:\\n  - id: normalize"
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("pipe_test123456"))
            .andExpect(jsonPath("$.name").value("视频清洗"));
    }

    @Test
    @DisplayName("IT-API-PIPE-002: GET /api/v1/pipelines — 列表查询")
    void list_success() throws Exception {
        when(pipelineService.list("tn_test"))
            .thenReturn(List.of(mockPipeline("pipe_1", "p1"), mockPipeline("pipe_2", "p2")));

        mockMvc.perform(get("/api/v1/pipelines")
                .requestAttr("tenant", TestHelper.mockTenant("tn_test")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("IT-API-PIPE-003: GET /api/v1/pipelines/{id} — 不存在返回 404")
    void get_notFound() throws Exception {
        when(pipelineService.get("tn_test", "pipe_xxx"))
            .thenThrow(new NotFoundException("Pipeline not found"));

        mockMvc.perform(get("/api/v1/pipelines/pipe_xxx")
                .requestAttr("tenant", TestHelper.mockTenant("tn_test")))
            .andExpect(status().isNotFound());
    }
}
```

Create: `lakeon-api/src/test/java/com/lakeon/pipeline/TestHelper.java`

```java
package com.lakeon.pipeline;

import com.lakeon.model.entity.TenantEntity;

class TestHelper {
    static TenantEntity mockTenant(String tenantId) {
        TenantEntity t = new TenantEntity();
        t.setId(tenantId);
        return t;
    }
}
```

- [ ] **Step 6: 实现 PipelineController**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineController.java`

```java
package com.lakeon.pipeline;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PipelineEntity pipe = pipelineService.create(
            tenant.getId(),
            (String) body.get("name"),
            (String) body.get("description"),
            (String) body.get("data_type"),
            (String) body.get("source_template_id"),
            (String) body.get("dag_yaml")
        );
        return toResponse(pipe);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return pipelineService.list(tenant.getId()).stream().map(this::toResponse).toList();
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> listTemplates() {
        return pipelineService.listTemplates().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return toResponse(pipelineService.get(tenant.getId(), id));
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> listVersions(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return pipelineService.listVersions(tenant.getId(), id).stream()
            .map(this::versionToResponse).toList();
    }

    @GetMapping("/{id}/versions/{version}")
    public Map<String, Object> getVersion(HttpServletRequest req,
                                           @PathVariable String id, @PathVariable int version) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return versionToResponse(pipelineService.getVersion(tenant.getId(), id, version));
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createVersion(HttpServletRequest req,
                                              @PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PipelineVersionEntity v = pipelineService.createVersion(
            tenant.getId(), id,
            (String) body.get("dag_yaml"),
            (String) body.get("changelog")
        );
        return versionToResponse(v);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        pipelineService.delete(tenant.getId(), id);
    }

    private Map<String, Object> toResponse(PipelineEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("data_type", p.getDataType());
        m.put("is_template", p.getIsTemplate());
        m.put("source_template_id", p.getSourceTemplateId());
        m.put("latest_version", p.getLatestVersion());
        m.put("created_at", p.getCreatedAt().toString());
        m.put("updated_at", p.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> versionToResponse(PipelineVersionEntity v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("pipeline_id", v.getPipelineId());
        m.put("version", v.getVersion());
        m.put("dag_yaml", v.getDagYaml());
        m.put("status", v.getStatus());
        m.put("changelog", v.getChangelog());
        m.put("created_at", v.getCreatedAt().toString());
        return m;
    }
}
```

- [ ] **Step 7: 运行 Controller 测试**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineControllerTest`

Expected: 3 tests PASS

- [ ] **Step 8: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/pipeline/PipelineService.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineController.java \
        lakeon-api/src/test/java/com/lakeon/pipeline/PipelineServiceTest.java \
        lakeon-api/src/test/java/com/lakeon/pipeline/PipelineControllerTest.java \
        lakeon-api/src/test/java/com/lakeon/pipeline/TestHelper.java
git commit -m "feat(pipeline): add pipeline CRUD service and controller with tests"
```

---

## Task 7: Component CRUD Service + Controller

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentService.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentController.java`
- Test: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineComponentServiceTest.java`
- Test: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineComponentControllerTest.java`

- [ ] **Step 1: 编写 Service 单元测试**

Create: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineComponentServiceTest.java`

```java
package com.lakeon.pipeline;

import com.lakeon.config.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineComponentService 业务逻辑测试")
class PipelineComponentServiceTest {

    @Mock private PipelineComponentRepository compRepo;
    @Mock private PipelineComponentVersionRepository compVersionRepo;

    private PipelineComponentService service;

    @BeforeEach
    void setUp() {
        service = new PipelineComponentService(compRepo, compVersionRepo);
    }

    @Test
    @DisplayName("UT-SVC-COMP-001: registerComponent — 正常注册")
    void register_success() {
        when(compRepo.save(any())).thenAnswer(inv -> {
            PipelineComponentEntity e = inv.getArgument(0);
            e.setId("comp_test123456");
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        when(compVersionRepo.save(any())).thenAnswer(inv -> {
            PipelineComponentVersionEntity v = inv.getArgument(0);
            v.setId("compv_test1234");
            v.setCreatedAt(Instant.now());
            return v;
        });

        PipelineComponentEntity result = service.register(
            "tn_001", "my_filter", "我的过滤器", "FILTER", "VIDEO",
            "测试", "lakeon.components.my_filter",
            "{}", "{}", "{}", null, false, null, "FUNCTION"
        );

        assertThat(result.getName()).isEqualTo("my_filter");
    }

    @Test
    @DisplayName("UT-SVC-COMP-002: listAvailable — 包含内置和本租户")
    void listAvailable_includesBuiltinAndTenant() {
        PipelineComponentEntity builtin = new PipelineComponentEntity();
        builtin.setId("comp_builtin");
        builtin.setName("video_normalize");
        PipelineComponentEntity custom = new PipelineComponentEntity();
        custom.setId("comp_custom");
        custom.setTenantId("tn_002");
        custom.setName("my_filter");

        when(compRepo.findByTenantIdIsNullOrTenantId("tn_002"))
            .thenReturn(List.of(builtin, custom));

        List<PipelineComponentEntity> result = service.listAvailable("tn_002");
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("UT-SVC-COMP-003: register — name 为空抛异常")
    void register_emptyName_throws() {
        assertThatThrownBy(() ->
            service.register("tn_001", "", "display", "FILTER", "VIDEO",
                "desc", "entry", "{}", "{}", "{}", null, false, null, "FUNCTION")
        ).isInstanceOf(BadRequestException.class);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineComponentServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 编译失败

- [ ] **Step 3: 实现 PipelineComponentService**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentService.java`

```java
package com.lakeon.pipeline;

import com.lakeon.config.BadRequestException;
import com.lakeon.config.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PipelineComponentService {

    private static final Logger log = LoggerFactory.getLogger(PipelineComponentService.class);

    private final PipelineComponentRepository compRepo;
    private final PipelineComponentVersionRepository compVersionRepo;

    public PipelineComponentService(PipelineComponentRepository compRepo,
                                     PipelineComponentVersionRepository compVersionRepo) {
        this.compRepo = compRepo;
        this.compVersionRepo = compVersionRepo;
    }

    @Transactional
    public PipelineComponentEntity register(String tenantId, String name, String displayName,
                                             String category, String dataType, String description,
                                             String entrypoint, String paramsSchema,
                                             String inputSchema, String outputSchema,
                                             String outputBranches, Boolean requiresGpu,
                                             String requiresModel, String executionMode) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Component name is required");
        }
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new BadRequestException("Component entrypoint is required");
        }

        PipelineComponentEntity comp = new PipelineComponentEntity();
        comp.setTenantId(tenantId);
        comp.setName(name);
        comp.setDisplayName(displayName);
        comp.setCategory(category);
        comp.setDataType(dataType);
        comp.setDescription(description);
        comp.setLatestVersion(1);
        compRepo.save(comp);

        PipelineComponentVersionEntity v = new PipelineComponentVersionEntity();
        v.setComponentId(comp.getId());
        v.setVersion(1);
        v.setEntrypoint(entrypoint);
        v.setParamsSchema(paramsSchema);
        v.setInputSchema(inputSchema);
        v.setOutputSchema(outputSchema);
        v.setOutputBranches(outputBranches);
        v.setRequiresGpu(requiresGpu != null ? requiresGpu : false);
        v.setRequiresModel(requiresModel);
        v.setExecutionMode(executionMode != null ? executionMode : "FUNCTION");
        v.setStatus("PUBLISHED");
        compVersionRepo.save(v);

        log.info("Registered component {} v1 for tenant {}", comp.getId(), tenantId);
        return comp;
    }

    public List<PipelineComponentEntity> listAvailable(String tenantId) {
        return compRepo.findByTenantIdIsNullOrTenantId(tenantId);
    }

    public List<PipelineComponentEntity> listBuiltin() {
        return compRepo.findByTenantIdIsNull();
    }

    public PipelineComponentEntity get(String componentId) {
        return compRepo.findById(componentId)
                .orElseThrow(() -> new NotFoundException("Component not found: " + componentId));
    }

    public List<PipelineComponentVersionEntity> listVersions(String componentId) {
        get(componentId);
        return compVersionRepo.findByComponentIdOrderByVersionDesc(componentId);
    }

    public PipelineComponentVersionEntity getVersion(String componentId, int version) {
        return compVersionRepo.findByComponentIdAndVersion(componentId, version)
                .orElseThrow(() -> new NotFoundException(
                    "Component version not found: " + componentId + " v" + version));
    }
}
```

- [ ] **Step 4: 运行 Service 测试，确认通过**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineComponentServiceTest`

Expected: 3 tests PASS

- [ ] **Step 5: 实现 PipelineComponentController**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentController.java`

```java
package com.lakeon.pipeline;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pipeline-components")
public class PipelineComponentController {

    private final PipelineComponentService componentService;

    public PipelineComponentController(PipelineComponentService componentService) {
        this.componentService = componentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PipelineComponentEntity comp = componentService.register(
            tenant.getId(),
            (String) body.get("name"),
            (String) body.get("display_name"),
            (String) body.get("category"),
            (String) body.get("data_type"),
            (String) body.get("description"),
            (String) body.get("entrypoint"),
            (String) body.get("params_schema"),
            (String) body.get("input_schema"),
            (String) body.get("output_schema"),
            (String) body.get("output_branches"),
            (Boolean) body.get("requires_gpu"),
            (String) body.get("requires_model"),
            (String) body.get("execution_mode")
        );
        return toResponse(comp);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return componentService.listAvailable(tenant.getId()).stream()
            .map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return toResponse(componentService.get(id));
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> listVersions(@PathVariable String id) {
        return componentService.listVersions(id).stream()
            .map(this::versionToResponse).toList();
    }

    @GetMapping("/{id}/versions/{version}")
    public Map<String, Object> getVersion(@PathVariable String id, @PathVariable int version) {
        return versionToResponse(componentService.getVersion(id, version));
    }

    private Map<String, Object> toResponse(PipelineComponentEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("tenant_id", c.getTenantId());
        m.put("name", c.getName());
        m.put("display_name", c.getDisplayName());
        m.put("category", c.getCategory());
        m.put("data_type", c.getDataType());
        m.put("description", c.getDescription());
        m.put("latest_version", c.getLatestVersion());
        m.put("created_at", c.getCreatedAt().toString());
        m.put("updated_at", c.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> versionToResponse(PipelineComponentVersionEntity v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("component_id", v.getComponentId());
        m.put("version", v.getVersion());
        m.put("entrypoint", v.getEntrypoint());
        m.put("params_schema", v.getParamsSchema());
        m.put("input_schema", v.getInputSchema());
        m.put("output_schema", v.getOutputSchema());
        m.put("output_branches", v.getOutputBranches());
        m.put("requires_gpu", v.getRequiresGpu());
        m.put("requires_model", v.getRequiresModel());
        m.put("execution_mode", v.getExecutionMode());
        m.put("status", v.getStatus());
        m.put("created_at", v.getCreatedAt().toString());
        return m;
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentService.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentController.java \
        lakeon-api/src/test/java/com/lakeon/pipeline/PipelineComponentServiceTest.java
git commit -m "feat(pipeline): add component CRUD service and controller with tests"
```

---

## Task 8: Pipeline Run Service + Controller

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunService.java`
- Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunController.java`
- Test: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineRunServiceTest.java`

- [ ] **Step 1: 编写 Service 单元测试**

Create: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineRunServiceTest.java`

```java
package com.lakeon.pipeline;

import com.lakeon.config.BadRequestException;
import com.lakeon.config.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineRunService 业务逻辑测试")
class PipelineRunServiceTest {

    @Mock private PipelineRunRepository runRepo;
    @Mock private PipelineStepRunRepository stepRunRepo;
    @Mock private PipelineRepository pipelineRepo;
    @Mock private PipelineVersionRepository versionRepo;

    private PipelineRunService service;

    @BeforeEach
    void setUp() {
        service = new PipelineRunService(runRepo, stepRunRepo, pipelineRepo, versionRepo);
    }

    private PipelineEntity mockPipeline() {
        PipelineEntity p = new PipelineEntity();
        p.setId("pipe_test123456");
        p.setTenantId("tn_001");
        p.setLatestVersion(1);
        return p;
    }

    private PipelineVersionEntity mockVersion() {
        PipelineVersionEntity v = new PipelineVersionEntity();
        v.setId("pipev_test1234");
        v.setPipelineId("pipe_test123456");
        v.setVersion(1);
        v.setDagYaml("steps:\n  - id: normalize\n    component: video_normalize\n  - id: split\n    component: video_scene_split");
        return v;
    }

    @Test
    @DisplayName("UT-SVC-RUN-001: trigger — 创建 run + step_runs")
    void trigger_success() {
        when(pipelineRepo.findByIdAndTenantId("pipe_test123456", "tn_001"))
            .thenReturn(Optional.of(mockPipeline()));
        when(versionRepo.findByPipelineIdAndVersion("pipe_test123456", 1))
            .thenReturn(Optional.of(mockVersion()));
        when(runRepo.save(any())).thenAnswer(inv -> {
            PipelineRunEntity r = inv.getArgument(0);
            r.setId("run_test123456");
            return r;
        });
        when(stepRunRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PipelineRunEntity result = service.trigger(
            "tn_001", "pipe_test123456", 1, "ds_input123", 1
        );

        assertThat(result.getStatus()).isEqualTo(PipelineRunStatus.PENDING);
        verify(stepRunRepo, times(2)).save(any());
    }

    @Test
    @DisplayName("UT-SVC-RUN-002: trigger — pipeline 不存在抛异常")
    void trigger_pipelineNotFound_throws() {
        when(pipelineRepo.findByIdAndTenantId("pipe_xxx", "tn_001"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.trigger("tn_001", "pipe_xxx", 1, null, null)
        ).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("UT-SVC-RUN-003: cancel — 正常取消")
    void cancel_success() {
        PipelineRunEntity run = new PipelineRunEntity();
        run.setId("run_test123456");
        run.setTenantId("tn_001");
        run.setStatus(PipelineRunStatus.RUNNING);

        when(runRepo.findByIdAndTenantId("run_test123456", "tn_001"))
            .thenReturn(Optional.of(run));
        when(runRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancel("tn_001", "run_test123456");

        ArgumentCaptor<PipelineRunEntity> captor = ArgumentCaptor.forClass(PipelineRunEntity.class);
        verify(runRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PipelineRunStatus.CANCELLED);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineRunServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 编译失败

- [ ] **Step 3: 实现 PipelineRunService**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunService.java`

```java
package com.lakeon.pipeline;

import com.lakeon.config.BadRequestException;
import com.lakeon.config.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PipelineRunService {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunService.class);

    private final PipelineRunRepository runRepo;
    private final PipelineStepRunRepository stepRunRepo;
    private final PipelineRepository pipelineRepo;
    private final PipelineVersionRepository versionRepo;

    public PipelineRunService(PipelineRunRepository runRepo,
                               PipelineStepRunRepository stepRunRepo,
                               PipelineRepository pipelineRepo,
                               PipelineVersionRepository versionRepo) {
        this.runRepo = runRepo;
        this.stepRunRepo = stepRunRepo;
        this.pipelineRepo = pipelineRepo;
        this.versionRepo = versionRepo;
    }

    @Transactional
    public PipelineRunEntity trigger(String tenantId, String pipelineId, int version,
                                      String inputDatasetId, Integer inputDatasetVersion) {
        pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
            .orElseThrow(() -> new NotFoundException("Pipeline not found: " + pipelineId));

        PipelineVersionEntity pipeVersion = versionRepo.findByPipelineIdAndVersion(pipelineId, version)
            .orElseThrow(() -> new NotFoundException(
                "Pipeline version not found: " + pipelineId + " v" + version));

        PipelineRunEntity run = new PipelineRunEntity();
        run.setPipelineId(pipelineId);
        run.setPipelineVersion(version);
        run.setTenantId(tenantId);
        run.setInputDatasetId(inputDatasetId);
        run.setInputDatasetVersion(inputDatasetVersion);
        run.setStatus(PipelineRunStatus.PENDING);
        runRepo.save(run);

        createStepRuns(run.getId(), pipeVersion.getDagYaml());

        log.info("Triggered pipeline run {} for {} v{}", run.getId(), pipelineId, version);
        return run;
    }

    private void createStepRuns(String runId, String dagYaml) {
        Yaml yaml = new Yaml();
        Map<String, Object> dag = yaml.load(dagYaml);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) dag.get("steps");
        if (steps == null) return;

        for (Map<String, Object> step : steps) {
            PipelineStepRunEntity sr = new PipelineStepRunEntity();
            sr.setRunId(runId);
            sr.setStepId((String) step.get("id"));
            sr.setComponentId((String) step.get("component"));
            Object cv = step.get("component_version");
            if (cv instanceof Integer) {
                sr.setComponentVersion((Integer) cv);
            } else if (cv instanceof Number) {
                sr.setComponentVersion(((Number) cv).intValue());
            }
            sr.setStatus(PipelineStepRunStatus.PENDING);
            stepRunRepo.save(sr);
        }
    }

    public PipelineRunEntity get(String tenantId, String runId) {
        return runRepo.findByIdAndTenantId(runId, tenantId)
            .orElseThrow(() -> new NotFoundException("Pipeline run not found: " + runId));
    }

    public List<PipelineRunEntity> listByPipeline(String pipelineId) {
        return runRepo.findByPipelineIdOrderByCreatedAtDesc(pipelineId);
    }

    public List<PipelineStepRunEntity> listStepRuns(String runId) {
        return stepRunRepo.findByRunIdOrderByCreatedAtAsc(runId);
    }

    @Transactional
    public void cancel(String tenantId, String runId) {
        PipelineRunEntity run = get(tenantId, runId);
        if (run.getStatus() == PipelineRunStatus.SUCCEEDED
            || run.getStatus() == PipelineRunStatus.CANCELLED) {
            throw new BadRequestException("Cannot cancel run in status: " + run.getStatus());
        }
        run.setStatus(PipelineRunStatus.CANCELLED);
        run.setFinishedAt(Instant.now());
        runRepo.save(run);
        log.info("Cancelled pipeline run {}", runId);
    }
}
```

- [ ] **Step 4: 运行 Service 测试，确认通过**

Run: `cd lakeon-api && mvn test -pl . -Dtest=PipelineRunServiceTest`

Expected: 3 tests PASS

- [ ] **Step 5: 实现 PipelineRunController**

Create: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunController.java`

```java
package com.lakeon.pipeline;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pipeline-runs")
public class PipelineRunController {

    private final PipelineRunService runService;

    public PipelineRunController(PipelineRunService runService) {
        this.runService = runService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> trigger(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PipelineRunEntity run = runService.trigger(
            tenant.getId(),
            (String) body.get("pipeline_id"),
            ((Number) body.get("pipeline_version")).intValue(),
            (String) body.get("input_dataset_id"),
            body.get("input_dataset_version") != null
                ? ((Number) body.get("input_dataset_version")).intValue() : null
        );
        return toResponse(run);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return toResponse(runService.get(tenant.getId(), id));
    }

    @GetMapping("/{id}/steps")
    public List<Map<String, Object>> listSteps(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        runService.get(tenant.getId(), id);
        return runService.listStepRuns(id).stream().map(this::stepToResponse).toList();
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        runService.cancel(tenant.getId(), id);
        return toResponse(runService.get(tenant.getId(), id));
    }

    private Map<String, Object> toResponse(PipelineRunEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("pipeline_id", r.getPipelineId());
        m.put("pipeline_version", r.getPipelineVersion());
        m.put("input_dataset_id", r.getInputDatasetId());
        m.put("input_dataset_version", r.getInputDatasetVersion());
        m.put("output_dataset_version_id", r.getOutputDatasetVersionId());
        m.put("status", r.getStatus().name());
        m.put("started_at", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
        m.put("finished_at", r.getFinishedAt() != null ? r.getFinishedAt().toString() : null);
        m.put("created_at", r.getCreatedAt().toString());
        return m;
    }

    private Map<String, Object> stepToResponse(PipelineStepRunEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("step_id", s.getStepId());
        m.put("component_id", s.getComponentId());
        m.put("component_version", s.getComponentVersion());
        m.put("status", s.getStatus().name());
        m.put("metrics", s.getMetrics());
        m.put("error", s.getError());
        m.put("checkpoint_path", s.getCheckpointPath());
        m.put("started_at", s.getStartedAt() != null ? s.getStartedAt().toString() : null);
        m.put("finished_at", s.getFinishedAt() != null ? s.getFinishedAt().toString() : null);
        return m;
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunService.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineRunController.java \
        lakeon-api/src/test/java/com/lakeon/pipeline/PipelineRunServiceTest.java
git commit -m "feat(pipeline): add pipeline run service and controller with tests"
```

---

## Task 9: 全量测试 + snakeyaml 依赖确认

**Files:**
- Modify: `lakeon-api/pom.xml` (确认 snakeyaml 依赖)

- [ ] **Step 1: 确认 snakeyaml 依赖**

snakeyaml 是 Spring Boot 的传递依赖，通常已包含。确认：

Run: `cd lakeon-api && mvn dependency:tree | grep snakeyaml`

Expected: 输出包含 `org.yaml:snakeyaml`。如果没有，在 pom.xml 中添加：

```xml
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
</dependency>
```

- [ ] **Step 2: 运行全量测试**

Run: `cd lakeon-api && mvn test`

Expected: 所有测试 PASS，包括之前的测试不被新代码破坏。

- [ ] **Step 3: 编译检查**

Run: `cd lakeon-api && mvn compile -q`

Expected: BUILD SUCCESS，无编译警告。

- [ ] **Step 4: Commit（如有 pom.xml 修改）**

```bash
git add lakeon-api/pom.xml
git commit -m "chore: confirm snakeyaml dependency for pipeline YAML parsing"
```

---

## API Summary

Plan 1 完成后可用的 API 端点：

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/pipelines` | 创建 pipeline（含 v1） |
| GET | `/api/v1/pipelines` | 列出租户的 pipeline |
| GET | `/api/v1/pipelines/templates` | 列出平台模板 |
| GET | `/api/v1/pipelines/{id}` | 获取 pipeline 详情 |
| DELETE | `/api/v1/pipelines/{id}` | 删除 pipeline |
| GET | `/api/v1/pipelines/{id}/versions` | 列出版本 |
| GET | `/api/v1/pipelines/{id}/versions/{v}` | 获取指定版本 |
| POST | `/api/v1/pipelines/{id}/versions` | 创建新版本 |
| POST | `/api/v1/pipeline-components` | 注册组件 |
| GET | `/api/v1/pipeline-components` | 列出可用组件 |
| GET | `/api/v1/pipeline-components/{id}` | 组件详情 |
| GET | `/api/v1/pipeline-components/{id}/versions` | 组件版本列表 |
| GET | `/api/v1/pipeline-components/{id}/versions/{v}` | 组件指定版本 |
| POST | `/api/v1/pipeline-runs` | 触发运行 |
| GET | `/api/v1/pipeline-runs/{id}` | 运行详情 |
| GET | `/api/v1/pipeline-runs/{id}/steps` | 步骤运行列表 |
| POST | `/api/v1/pipeline-runs/{id}/cancel` | 取消运行 |
