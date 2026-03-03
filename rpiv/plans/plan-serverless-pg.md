---
description: "实施计划: serverless-pg - 基于 Neon 的华为云 Serverless PostgreSQL 服务"
status: pending
created_at: 2026-03-03T00:00:00
updated_at: 2026-03-03T00:00:00
archived_at: null
related_files:
  - rpiv/requirements/prd-serverless-pg.md
---

# LakeOn Serverless PostgreSQL 实施计划

## 功能描述

基于 Neon 开源项目构建 Serverless PostgreSQL 云服务，部署在华为云上。包含：
1. Spring Boot 管控面 API（租户/实例/分支 CRUD、compute 生命周期管理）
2. Python CLI 工具（命令行封装管控 API）
3. Neon Proxy 适配（实现 `wake_compute` 和 `get_endpoint_access_control` 接口供 Proxy 调用）
4. Helm Charts 部署配置（Neon 组件 + 管控面 + 监控）

## 问题陈述

团队需要一个零运维、按需启动的 PostgreSQL 服务。当前传统数据库申请流程长、运维成本高、开发测试环境资源浪费严重。

## 解决方案陈述

利用 Neon 的存算分离架构，将 compute 节点作为 K8s Pod 动态创建/销毁，数据持久化到华为云 OBS。通过自研管控面 API 管理实例生命周期，Neon Proxy 负责连接路由和自动唤醒。

## 功能元数据

**功能类型**: 新功能
**估计复杂度**: 高
**主要受影响的系统**: 管控面 API、CLI、K8s 部署、Neon Proxy 适配
**依赖项**: Neon 开源组件、华为云 CCE、华为云 OBS、PostgreSQL 17

---

## 上下文参考

### 关键 Neon 源码文件（必读）

**Proxy 与 Control Plane 交互**:
- `/Users/jacky/code/neon/proxy/src/control_plane/mod.rs` - `ControlPlaneApi` trait 定义，包含 `wake_compute`、`get_role_access_control`、`get_endpoint_access_control` 三个核心接口
- `/Users/jacky/code/neon/proxy/src/control_plane/messages.rs` - Proxy 期望的 API 响应结构：`WakeCompute`（address + aux）、`GetEndpointAccessControl`（role_secret + allowed_ips）、`MetricsAuxInfo`（endpoint_id, project_id, branch_id, compute_id）
- `/Users/jacky/code/neon/proxy/src/control_plane/client/cplane_proxy_v1.rs` - 生产环境 Proxy 如何调用 Control Plane：`GET {endpoint}/wake_compute?session_id=&application_name=&endpointish=`、`GET {endpoint}/get_endpoint_access_control?session_id=&application_name=&endpointish=&role=`
- `/Users/jacky/code/neon/proxy/src/control_plane/client/mock.rs` - Mock 实现参考，展示 `NodeInfo` 和 `ConnectInfo` 的构造方式
- `/Users/jacky/code/neon/proxy/src/binary/proxy.rs` (第 779-836 行) - Proxy 启动时如何配置 `--auth-backend control-plane --auth-endpoint <url> --control-plane-token <jwt>`

**Pageserver HTTP API**:
- `/Users/jacky/code/neon/pageserver/src/http/openapi_spec.yml` - Pageserver REST API 规范
- `/Users/jacky/code/neon/pageserver/client/src/mgmt_api.rs` - Pageserver 客户端，关键方法：
  - `location_config(tenant_shard_id, config, flush_ms, lazy)` - 创建/配置 tenant（PUT `/v1/tenant/{id}/location_config`）
  - `timeline_create(tenant_shard_id, req)` - 创建 timeline（POST `/v1/tenant/{id}/timeline`）
  - `tenant_delete(tenant_shard_id)` - 删除 tenant（DELETE `/v1/tenant/{id}`）
  - `timeline_delete(tenant_shard_id, timeline_id)` - 删除 timeline
- `/Users/jacky/code/neon/pageserver/src/http/routes.rs` (第 4025-4141 行) - 完整路由表

**Compute 配置与启动**:
- `/Users/jacky/code/neon/libs/compute_api/src/spec.rs` - `ComputeSpec` 结构：包含 tenant_id、timeline_id、cluster（roles/databases/settings）、pageserver_connection_info、safekeeper_connstrings
- `/Users/jacky/code/neon/libs/compute_api/src/responses.rs` - `ComputeConfig`（spec + compute_ctl_config）、`ComputeStatus` 枚举
- `/Users/jacky/code/neon/docker-compose/compute_wrapper/shell/compute.sh` - Compute 启动脚本参考：创建 tenant 用 `PUT /v1/tenant/{id}/location_config`，创建 timeline 用 `POST /v1/tenant/{id}/timeline/`
- `/Users/jacky/code/neon/docker-compose/compute_wrapper/var/db/postgres/configs/config.json` - compute_ctl 配置文件样例

**Docker Compose 部署参考**:
- `/Users/jacky/code/neon/docker-compose/docker-compose.yml` - 完整的 Neon 组件部署编排
- `/Users/jacky/code/neon/docker-compose/pageserver_config/pageserver.toml` - Pageserver 配置文件

**Neon 数据模型**:
- `/Users/jacky/code/neon/libs/pageserver_api/src/models.rs` - `TimelineCreateRequest`（new_timeline_id + mode: Branch/Bootstrap）、`LocationConfig`、`TenantInfo`、`TimelineInfo`
- `/Users/jacky/code/neon/libs/pageserver_api/src/shard.rs` - `TenantShardId` 类型

### PRD 文件

- `/Users/jacky/code/lakeon/rpiv/requirements/prd-serverless-pg.md`

---

## 模块分工

### Dev-1: Spring Boot API 项目（Java 部分）

**负责范围**: `lakeon-api/` 目录下所有 Java 代码
- 管控面 REST API
- JPA 实体和 Repository
- Neon Pageserver/Safekeeper HTTP 客户端
- Fabric8 K8s 客户端（compute Pod 管理）
- Proxy 适配 API（wake_compute、get_endpoint_access_control）
- 自动休眠调度器

### Dev-2: Python CLI + 部署配置

**负责范围**: `lakeon-cli/` 和 `deploy/` 目录
- Python CLI 工具
- Helm Charts（Neon 组件 + 管控面 + 监控）
- Prometheus/Grafana 监控配置
- K8s manifests

**文件不重叠保证**: Dev-1 只操作 `lakeon-api/`，Dev-2 只操作 `lakeon-cli/` 和 `deploy/`。

---

## Dev-1 实施计划：Spring Boot API 项目

### 阶段 1：项目骨架与数据模型

#### 任务 1.1: CREATE `lakeon-api/pom.xml`

Maven 项目配置文件。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativeTo/>
    </parent>

    <groupId>com.lakeon</groupId>
    <artifactId>lakeon-api</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>lakeon-api</name>
    <description>LakeOn Serverless PostgreSQL Control Plane API</description>

    <properties>
        <java.version>21</java.version>
        <fabric8.version>6.13.4</fabric8.version>
    </properties>

    <dependencies>
        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- PostgreSQL Driver -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Fabric8 Kubernetes Client -->
        <dependency>
            <groupId>io.fabric8</groupId>
            <artifactId>kubernetes-client</artifactId>
            <version>${fabric8.version}</version>
        </dependency>

        <!-- Micrometer Prometheus -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- **VALIDATE**: `cd lakeon-api && mvn validate`

#### 任务 1.2: CREATE `lakeon-api/src/main/java/com/lakeon/LakeonApplication.java`

```java
package com.lakeon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LakeonApplication {
    public static void main(String[] args) {
        SpringApplication.run(LakeonApplication.class, args);
    }
}
```

#### 任务 1.3: CREATE `lakeon-api/src/main/resources/application.yml`

```yaml
server:
  port: ${LAKEON_API_PORT:8080}

spring:
  datasource:
    url: ${LAKEON_DB_DSN:jdbc:postgresql://localhost:5432/lakeon}
    username: ${LAKEON_DB_USER:lakeon}
    password: ${LAKEON_DB_PASSWORD:lakeon}
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

lakeon:
  neon:
    pageserver-url: ${LAKEON_NEON_PAGESERVER_URL:http://localhost:9898}
    safekeeper-urls: ${LAKEON_NEON_SAFEKEEPER_URLS:safekeeper1:5454,safekeeper2:5454,safekeeper3:5454}
    storage-broker-url: ${LAKEON_NEON_STORAGE_BROKER_URL:http://localhost:50051}
  obs:
    endpoint: ${LAKEON_OBS_ENDPOINT:}
    bucket: ${LAKEON_OBS_BUCKET:neon}
    access-key: ${LAKEON_OBS_ACCESS_KEY:}
    secret-key: ${LAKEON_OBS_SECRET_KEY:}
  k8s:
    namespace: ${LAKEON_K8S_NAMESPACE:lakeon-compute}
    compute-image: ${LAKEON_COMPUTE_IMAGE:ghcr.io/neondatabase/compute-node-v17:latest}
  defaults:
    compute-size: ${LAKEON_DEFAULT_COMPUTE_SIZE:1cu}
    suspend-timeout: ${LAKEON_DEFAULT_SUSPEND_TIMEOUT:5m}
    storage-limit-gb: ${LAKEON_DEFAULT_STORAGE_LIMIT:10}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

#### 任务 1.4: CREATE JPA 实体

创建以下文件，包路径 `com.lakeon.model`:

**`lakeon-api/src/main/java/com/lakeon/model/Tenant.java`**:
```java
package com.lakeon.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {
    @Id
    @Column(name = "id", length = 64)
    private String id;  // 格式: "tenant_" + UUID 前8位

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "api_key", nullable = false, unique = true, length = 64)
    private String apiKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "tenant_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (apiKey == null) {
            apiKey = "lk_" + UUID.randomUUID().toString().replace("-", "");
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters for all fields
    // ... (标准 getter/setter)
}
```

**`lakeon-api/src/main/java/com/lakeon/model/DatabaseInstance.java`**:
```java
package com.lakeon.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "database_instances",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
public class DatabaseInstance {
    @Id
    @Column(name = "id", length = 64)
    private String id;  // 格式: "db_" + UUID 前8位

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "neon_tenant_id", length = 64)
    private String neonTenantId;  // Neon TenantId (32 hex chars)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InstanceStatus status;

    @Column(name = "compute_size", nullable = false, length = 16)
    private String computeSize;  // "1cu", "2cu", "4cu", "8cu"

    @Column(name = "suspend_timeout", nullable = false, length = 16)
    private String suspendTimeout;  // "5m", "10m", "30m"

    @Column(name = "storage_limit_gb", nullable = false)
    private Integer storageLimitGb;

    @Column(name = "db_user", length = 64)
    private String dbUser;

    @Column(name = "db_password", length = 128)
    private String dbPassword;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "db_" + UUID.randomUUID().toString().substring(0, 8);
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
}
```

**`lakeon-api/src/main/java/com/lakeon/model/Branch.java`**:
```java
package com.lakeon.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "branches",
       uniqueConstraints = @UniqueConstraint(columnNames = {"database_instance_id", "name"}))
public class Branch {
    @Id
    @Column(name = "id", length = 64)
    private String id;  // 格式: "br_" + UUID 前8位

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "database_instance_id", nullable = false)
    private DatabaseInstance databaseInstance;

    @Column(name = "neon_timeline_id", length = 64)
    private String neonTimelineId;  // Neon TimelineId (32 hex chars)

    @Column(name = "parent_branch_id", length = 64)
    private String parentBranchId;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BranchStatus status;

    @Column(name = "compute_status")
    @Enumerated(EnumType.STRING)
    private ComputeStatus computeStatus;

    @Column(name = "compute_pod_name", length = 128)
    private String computePodName;

    @Column(name = "compute_host", length = 256)
    private String computeHost;

    @Column(name = "compute_port")
    private Integer computePort;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "br_" + UUID.randomUUID().toString().substring(0, 8);
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
}
```

**枚举类**:

`lakeon-api/src/main/java/com/lakeon/model/InstanceStatus.java`:
```java
package com.lakeon.model;
public enum InstanceStatus {
    CREATING, RUNNING, SUSPENDED, ERROR, DELETING
}
```

`lakeon-api/src/main/java/com/lakeon/model/BranchStatus.java`:
```java
package com.lakeon.model;
public enum BranchStatus {
    CREATING, ACTIVE, DELETING, ERROR
}
```

`lakeon-api/src/main/java/com/lakeon/model/ComputeStatus.java`:
```java
package com.lakeon.model;
public enum ComputeStatus {
    RUNNING, SUSPENDED, STARTING, STOPPING, ERROR
}
```

#### 任务 1.5: CREATE SQL Schema

**`lakeon-api/src/main/resources/schema.sql`**:
```sql
CREATE TABLE IF NOT EXISTS tenants (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    api_key VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS database_instances (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    neon_tenant_id VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'CREATING',
    compute_size VARCHAR(16) NOT NULL DEFAULT '1cu',
    suspend_timeout VARCHAR(16) NOT NULL DEFAULT '5m',
    storage_limit_gb INTEGER NOT NULL DEFAULT 10,
    db_user VARCHAR(64),
    db_password VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

CREATE TABLE IF NOT EXISTS branches (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    database_instance_id VARCHAR(64) NOT NULL REFERENCES database_instances(id),
    neon_timeline_id VARCHAR(64),
    parent_branch_id VARCHAR(64),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATING',
    compute_status VARCHAR(32),
    compute_pod_name VARCHAR(128),
    compute_host VARCHAR(256),
    compute_port INTEGER,
    last_active_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(database_instance_id, name)
);

CREATE INDEX idx_branches_neon_timeline ON branches(neon_timeline_id);
CREATE INDEX idx_instances_neon_tenant ON database_instances(neon_tenant_id);
CREATE INDEX idx_instances_tenant ON database_instances(tenant_id);
```

- **VALIDATE**: `cd lakeon-api && mvn compile`

#### 任务 1.6: CREATE DTO 类

包路径 `com.lakeon.model.dto`:

**`CreateDatabaseRequest.java`**:
```java
package com.lakeon.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateDatabaseRequest(
    @NotBlank String name,
    @Pattern(regexp = "^[1248]cu$") String computeSize,
    @Pattern(regexp = "^\\d+m$") String suspendTimeout,
    Integer storageLimitGb
) {}
```

**`UpdateDatabaseRequest.java`**:
```java
package com.lakeon.model.dto;

public record UpdateDatabaseRequest(
    String computeSize,
    String suspendTimeout,
    Integer storageLimitGb
) {}
```

**`CreateBranchRequest.java`**:
```java
package com.lakeon.model.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateBranchRequest(
    @NotBlank String name,
    Boolean startCompute
) {}
```

**`CreateTenantRequest.java`**:
```java
package com.lakeon.model.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(
    @NotBlank String name
) {}
```

**`DatabaseResponse.java`**:
```java
package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record DatabaseResponse(
    String id,
    String name,
    String status,
    @JsonProperty("connection_uri") String connectionUri,
    @JsonProperty("compute_size") String computeSize,
    @JsonProperty("suspend_timeout") String suspendTimeout,
    @JsonProperty("storage_limit_gb") Integer storageLimitGb,
    @JsonProperty("storage_used_gb") Double storageUsedGb,
    List<BranchResponse> branches,
    @JsonProperty("created_at") Instant createdAt
) {}
```

**`BranchResponse.java`**:
```java
package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record BranchResponse(
    String id,
    String name,
    @JsonProperty("parent_branch") String parentBranch,
    @JsonProperty("is_default") boolean isDefault,
    String status,
    @JsonProperty("compute_status") String computeStatus,
    @JsonProperty("connection_uri") String connectionUri,
    @JsonProperty("created_at") Instant createdAt
) {}
```

**`TenantResponse.java`**:
```java
package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record TenantResponse(
    String id,
    String name,
    @JsonProperty("api_key") String apiKey,
    @JsonProperty("created_at") Instant createdAt
) {}
```

**`ErrorResponse.java`**:
```java
package com.lakeon.model.dto;

public record ErrorResponse(
    ErrorBody error
) {
    public record ErrorBody(String code, String message) {}

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorBody(code, message));
    }
}
```

#### 任务 1.7: CREATE Repository 接口

包路径 `com.lakeon.repository`:

**`TenantRepository.java`**:
```java
package com.lakeon.repository;

import com.lakeon.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, String> {
    Optional<Tenant> findByApiKey(String apiKey);
    Optional<Tenant> findByName(String name);
}
```

**`DatabaseInstanceRepository.java`**:
```java
package com.lakeon.repository;

import com.lakeon.model.DatabaseInstance;
import com.lakeon.model.InstanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DatabaseInstanceRepository extends JpaRepository<DatabaseInstance, String> {
    List<DatabaseInstance> findByTenantId(String tenantId);
    Optional<DatabaseInstance> findByTenantIdAndName(String tenantId, String name);
    Optional<DatabaseInstance> findByTenantIdAndId(String tenantId, String id);
    Optional<DatabaseInstance> findByNeonTenantId(String neonTenantId);
    List<DatabaseInstance> findByStatus(InstanceStatus status);
}
```

**`BranchRepository.java`**:
```java
package com.lakeon.repository;

import com.lakeon.model.Branch;
import com.lakeon.model.ComputeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, String> {
    List<Branch> findByDatabaseInstanceId(String databaseInstanceId);
    Optional<Branch> findByDatabaseInstanceIdAndName(String databaseInstanceId, String name);
    Optional<Branch> findByDatabaseInstanceIdAndIsDefaultTrue(String databaseInstanceId);
    Optional<Branch> findByNeonTimelineId(String neonTimelineId);
    List<Branch> findByComputeStatus(ComputeStatus computeStatus);
    Optional<Branch> findByComputePodName(String podName);
}
```

- **VALIDATE**: `cd lakeon-api && mvn compile`

### 阶段 2：Neon 集成层

#### 任务 2.1: CREATE 配置类

**`lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`**:
```java
package com.lakeon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lakeon")
public class LakeonProperties {
    private NeonConfig neon = new NeonConfig();
    private ObsConfig obs = new ObsConfig();
    private K8sConfig k8s = new K8sConfig();
    private DefaultsConfig defaults = new DefaultsConfig();

    // Nested config classes with getters/setters
    public static class NeonConfig {
        private String pageserverUrl;
        private String safekeeperUrls;  // comma-separated
        private String storageBrokerUrl;
        // getters/setters
    }

    public static class ObsConfig {
        private String endpoint;
        private String bucket;
        private String accessKey;
        private String secretKey;
        // getters/setters
    }

    public static class K8sConfig {
        private String namespace;
        private String computeImage;
        // getters/setters
    }

    public static class DefaultsConfig {
        private String computeSize;
        private String suspendTimeout;
        private Integer storageLimitGb;
        // getters/setters
    }

    // getters/setters for top-level
}
```

#### 任务 2.2: CREATE Neon Pageserver 客户端

**`lakeon-api/src/main/java/com/lakeon/neon/PageserverClient.java`**:

此客户端封装对 Neon Pageserver HTTP API 的调用。

关键方法和对应的 Pageserver API（参考 `/Users/jacky/code/neon/pageserver/client/src/mgmt_api.rs`）：

```java
package com.lakeon.neon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

@Component
public class PageserverClient {
    private static final Logger log = LoggerFactory.getLogger(PageserverClient.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public PageserverClient(LakeonProperties props, ObjectMapper objectMapper) {
        this.baseUrl = props.getNeon().getPageserverUrl();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * 创建 Tenant（通过 location_config）
     *
     * 对应 Neon API: PUT /v1/tenant/{tenant_shard_id}/location_config
     * 请求体: {"mode": "AttachedSingle", "generation": 1, "tenant_conf": {}}
     *
     * 参考: compute.sh 第 47-54 行
     */
    public void createTenant(String tenantId) throws Exception {
        String url = baseUrl + "/v1/tenant/" + tenantId + "/location_config";
        String body = objectMapper.writeValueAsString(Map.of(
            "mode", "AttachedSingle",
            "generation", 1,
            "tenant_conf", Map.of()
        ));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to create tenant: " + response.body());
        }
        log.info("Created Neon tenant: {}", tenantId);
    }

    /**
     * 创建 Timeline（分支）
     *
     * 对应 Neon API: POST /v1/tenant/{tenant_shard_id}/timeline
     *
     * Bootstrap 模式请求体: {"new_timeline_id": "<id>", "pg_version": 17}
     * Branch 模式请求体: {"new_timeline_id": "<id>", "ancestor_timeline_id": "<parent_id>"}
     *
     * 参考: TimelineCreateRequest 和 TimelineCreateRequestMode (pageserver_api/src/models.rs)
     */
    public TimelineInfo createTimeline(String tenantId, String timelineId, Integer pgVersion) throws Exception {
        String url = baseUrl + "/v1/tenant/" + tenantId + "/timeline";
        String body = objectMapper.writeValueAsString(Map.of(
            "new_timeline_id", timelineId,
            "pg_version", pgVersion
        ));
        // POST, parse response as TimelineInfo
    }

    /**
     * 创建分支 Timeline（从已有 timeline fork）
     */
    public TimelineInfo createBranchTimeline(String tenantId, String newTimelineId, String ancestorTimelineId) throws Exception {
        String url = baseUrl + "/v1/tenant/" + tenantId + "/timeline";
        String body = objectMapper.writeValueAsString(Map.of(
            "new_timeline_id", newTimelineId,
            "ancestor_timeline_id", ancestorTimelineId
        ));
        // POST, parse response
    }

    /**
     * 删除 Tenant
     * 对应: DELETE /v1/tenant/{tenant_shard_id}
     * 注意: 返回 200 表示成功, 503 表示需要重试
     */
    public void deleteTenant(String tenantId) throws Exception { ... }

    /**
     * 删除 Timeline
     * 对应: DELETE /v1/tenant/{tenant_shard_id}/timeline/{timeline_id}
     */
    public void deleteTimeline(String tenantId, String timelineId) throws Exception { ... }

    /**
     * 列出 Timelines
     * 对应: GET /v1/tenant/{tenant_shard_id}/timeline
     */
    public List<TimelineInfo> listTimelines(String tenantId) throws Exception { ... }

    /**
     * 获取 Tenant 信息
     * 对应: GET /v1/tenant/{tenant_shard_id}
     */
    public TenantInfo getTenant(String tenantId) throws Exception { ... }
}
```

**`lakeon-api/src/main/java/com/lakeon/neon/TimelineInfo.java`** (Neon API 响应 DTO):
```java
package com.lakeon.neon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TimelineInfo(
    @JsonProperty("timeline_id") String timelineId,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("ancestor_timeline_id") String ancestorTimelineId,
    @JsonProperty("current_logical_size") Long currentLogicalSize,
    @JsonProperty("last_record_lsn") String lastRecordLsn
) {}
```

#### 任务 2.3: CREATE K8s Compute Pod 管理器

**`lakeon-api/src/main/java/com/lakeon/k8s/ComputePodManager.java`**:

使用 Fabric8 K8s Client 管理 compute Pod。关键点：
- 每个 compute 对应一个 Pod，包含 compute_ctl 容器
- Pod 启动参数参考 `compute.sh`：需要传入 tenant_id、timeline_id、pageserver 地址、safekeeper 地址
- compute_ctl 启动命令: `/usr/local/bin/compute_ctl --pgdata /var/db/postgres/compute -C "postgresql://..." -b /usr/local/bin/postgres --compute-id <id> --config <config_file>`

```java
package com.lakeon.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.Branch;
import com.lakeon.model.DatabaseInstance;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ComputePodManager {
    private static final Logger log = LoggerFactory.getLogger(ComputePodManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;

    // Compute 规格映射 (参考 PRD 第 731-738 行)
    private static final Map<String, ComputeResources> COMPUTE_SIZES = Map.of(
        "1cu", new ComputeResources("1", "2Gi"),
        "2cu", new ComputeResources("2", "4Gi"),
        "4cu", new ComputeResources("4", "8Gi"),
        "8cu", new ComputeResources("8", "16Gi")
    );

    record ComputeResources(String cpu, String memory) {}

    public ComputePodManager(KubernetesClient k8sClient, LakeonProperties props, ObjectMapper objectMapper) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 compute Pod
     *
     * Pod 包含一个 InitContainer（生成 config.json）和一个主容器（运行 compute_ctl）。
     *
     * compute_ctl 需要的关键配置（参考 compute_api/src/spec.rs 的 ComputeSpec）:
     * - tenant_id: Neon tenant ID
     * - timeline_id: Neon timeline ID
     * - pageserver_connstring: "postgresql://pageserver_host:6400"
     * - safekeeper_connstrings: ["sk1:5454", "sk2:5454", "sk3:5454"]
     * - cluster.roles: [{"name": "<db_user>", "encrypted_password": "<scram_hash>"}]
     * - cluster.databases: [{"name": "<db_name>", "owner": "<db_user>"}]
     * - cluster.settings: PG 配置参数
     *
     * 参考: /Users/jacky/code/neon/docker-compose/compute_wrapper/var/db/postgres/configs/config.json
     */
    public String createComputePod(DatabaseInstance instance, Branch branch) {
        String podName = "compute-" + branch.getId().replace("_", "-");
        String namespace = props.getK8s().getNamespace();
        ComputeResources resources = COMPUTE_SIZES.getOrDefault(instance.getComputeSize(), COMPUTE_SIZES.get("1cu"));

        // 生成 ComputeSpec JSON (config.json)
        String configJson = generateComputeConfig(instance, branch);

        // 构建 Pod
        Pod pod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-compute",
                    "lakeon.io/instance-id", instance.getId(),
                    "lakeon.io/branch-id", branch.getId(),
                    "lakeon.io/tenant-id", instance.getTenant().getId()
                ))
            .endMetadata()
            .withNewSpec()
                .addNewInitContainer()
                    .withName("config-writer")
                    .withImage("busybox:1.36")
                    .withCommand("sh", "-c",
                        "echo '" + configJson.replace("'", "'\"'\"'") + "' > /config/config.json")
                    .addNewVolumeMount()
                        .withName("config-volume")
                        .withMountPath("/config")
                    .endVolumeMount()
                .endInitContainer()
                .addNewContainer()
                    .withName("compute")
                    .withImage(props.getK8s().getComputeImage())
                    .withCommand("/usr/local/bin/compute_ctl")
                    .withArgs(
                        "--pgdata", "/var/db/postgres/compute",
                        "-C", "postgresql://" + instance.getDbUser() + "@localhost:55433/postgres",
                        "-b", "/usr/local/bin/postgres",
                        "--compute-id", podName,
                        "--config", "/config/config.json"
                    )
                    .addNewPort()
                        .withContainerPort(55433)
                        .withName("pg")
                    .endPort()
                    .addNewPort()
                        .withContainerPort(3080)
                        .withName("http")
                    .endPort()
                    .withNewResources()
                        .withRequests(Map.of(
                            "cpu", new Quantity(resources.cpu()),
                            "memory", new Quantity(resources.memory())
                        ))
                        .withLimits(Map.of(
                            "cpu", new Quantity(resources.cpu()),
                            "memory", new Quantity(resources.memory())
                        ))
                    .endResources()
                    .addNewVolumeMount()
                        .withName("config-volume")
                        .withMountPath("/config")
                    .endVolumeMount()
                    .withNewReadinessProbe()
                        .withNewTcpSocket()
                            .withNewPort(55433)
                        .endTcpSocket()
                        .withInitialDelaySeconds(5)
                        .withPeriodSeconds(2)
                    .endReadinessProbe()
                .endContainer()
                .addNewVolume()
                    .withName("config-volume")
                    .withNewEmptyDir().endEmptyDir()
                .endVolume()
            .endSpec()
            .build();

        k8sClient.pods().inNamespace(namespace).resource(pod).create();
        log.info("Created compute Pod: {}/{}", namespace, podName);
        return podName;
    }

    /**
     * 删除 compute Pod
     */
    public void deleteComputePod(String podName) {
        String namespace = props.getK8s().getNamespace();
        k8sClient.pods().inNamespace(namespace).withName(podName).delete();
        log.info("Deleted compute Pod: {}/{}", namespace, podName);
    }

    /**
     * 检查 Pod 是否就绪
     */
    public boolean isPodReady(String podName) {
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) return false;
        return pod.getStatus() != null
            && pod.getStatus().getConditions() != null
            && pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /**
     * 获取 Pod IP
     */
    public String getPodIp(String podName) {
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        return pod != null && pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
    }

    /**
     * 等待 Pod 就绪（最多等待 timeoutSeconds 秒）
     */
    public boolean waitForPodReady(String podName, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isPodReady(podName)) return true;
            Thread.sleep(1000);
        }
        return false;
    }

    /**
     * 生成 compute_ctl 的 config.json
     *
     * 结构参考: ComputeSpec (compute_api/src/spec.rs)
     * 和 docker-compose/compute_wrapper/var/db/postgres/configs/config.json
     */
    private String generateComputeConfig(DatabaseInstance instance, Branch branch) {
        // 构建 ComputeSpec 兼容的 JSON
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("format_version", 1.0);
        spec.put("operation_uuid", UUID.randomUUID().toString());
        spec.put("tenant_id", instance.getNeonTenantId());
        spec.put("timeline_id", branch.getNeonTimelineId());
        spec.put("pageserver_connstring", "postgresql://pageserver_host:6400");  // 从配置读取实际地址
        spec.put("safekeeper_connstrings", parseSafekeeperUrls());
        spec.put("mode", "Primary");

        // Cluster 配置
        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("cluster_id", "lakeon_" + instance.getId());
        cluster.put("name", instance.getName());
        cluster.put("state", "restarted");
        cluster.put("roles", List.of(Map.of(
            "name", instance.getDbUser(),
            "encrypted_password", instance.getDbPassword(),  // SCRAM hash
            "options", (Object) null
        )));
        cluster.put("databases", List.of(Map.of(
            "name", instance.getName(),
            "owner", instance.getDbUser()
        )));
        cluster.put("settings", getDefaultPgSettings());
        spec.put("cluster", cluster);

        Map<String, Object> config = Map.of(
            "spec", spec,
            "compute_ctl_config", Map.of()
        );
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate compute config", e);
        }
    }

    private List<String> parseSafekeeperUrls() {
        return Arrays.asList(props.getNeon().getSafekeeperUrls().split(","));
    }

    private List<Map<String, String>> getDefaultPgSettings() {
        return List.of(
            Map.of("name", "fsync", "value", "off", "vartype", "bool"),
            Map.of("name", "wal_level", "value", "logical", "vartype", "enum"),
            Map.of("name", "wal_log_hints", "value", "on", "vartype", "bool"),
            Map.of("name", "log_connections", "value", "on", "vartype", "bool"),
            Map.of("name", "port", "value", "55433", "vartype", "integer"),
            Map.of("name", "shared_buffers", "value", "128MB", "vartype", "string"),
            Map.of("name", "max_connections", "value", "100", "vartype", "integer"),
            Map.of("name", "listen_addresses", "value", "0.0.0.0", "vartype", "string")
        );
    }
}
```

### 阶段 3：业务逻辑层

#### 任务 3.1: CREATE `TenantService.java`

**`lakeon-api/src/main/java/com/lakeon/service/TenantService.java`**:
```java
package com.lakeon.service;

import com.lakeon.model.Tenant;
import com.lakeon.model.dto.*;
import com.lakeon.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {
    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public TenantResponse createTenant(CreateTenantRequest request) {
        // 1. 检查名称唯一性
        // 2. 创建 Tenant 实体并保存
        // 3. 返回 TenantResponse（包含生成的 api_key）
    }

    public TenantResponse getTenant(String tenantId) {
        // 根据 ID 查找并返回
    }

    public Tenant authenticateByApiKey(String apiKey) {
        // 根据 API Key 查找 Tenant，用于认证中间件
    }
}
```

#### 任务 3.2: CREATE `DatabaseService.java`

**`lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java`**:

这是核心服务类，负责实例的完整生命周期管理。

```java
package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.*;
import com.lakeon.model.dto.*;
import com.lakeon.neon.PageserverClient;
import com.lakeon.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.util.*;

@Service
public class DatabaseService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);

    private final DatabaseInstanceRepository dbRepo;
    private final BranchRepository branchRepo;
    private final PageserverClient pageserverClient;
    private final ComputePodManager podManager;
    private final LakeonProperties props;

    /**
     * 创建数据库实例
     *
     * 完整流程:
     * 1. 生成 Neon tenant_id（32 hex chars）和 timeline_id
     * 2. 调用 Pageserver API 创建 tenant: PUT /v1/tenant/{id}/location_config
     * 3. 调用 Pageserver API 创建 timeline: POST /v1/tenant/{id}/timeline
     * 4. 生成 db_user 和 db_password
     * 5. 创建 K8s compute Pod
     * 6. 等待 Pod 就绪
     * 7. 更新状态为 RUNNING
     */
    @Transactional
    public DatabaseResponse createDatabase(String tenantId, CreateDatabaseRequest request) {
        // 参数默认值处理
        String computeSize = request.computeSize() != null ? request.computeSize() : props.getDefaults().getComputeSize();
        String suspendTimeout = request.suspendTimeout() != null ? request.suspendTimeout() : props.getDefaults().getSuspendTimeout();
        int storageLimitGb = request.storageLimitGb() != null ? request.storageLimitGb() : props.getDefaults().getStorageLimitGb();

        // 生成 Neon IDs
        String neonTenantId = generateHexId();
        String neonTimelineId = generateHexId();

        // 生成凭据
        String dbUser = "user_" + UUID.randomUUID().toString().substring(0, 8);
        String dbPassword = generatePassword();

        // 创建 DB 实体
        DatabaseInstance instance = new DatabaseInstance();
        instance.setName(request.name());
        instance.setTenant(/* lookup tenant */);
        instance.setNeonTenantId(neonTenantId);
        instance.setStatus(InstanceStatus.CREATING);
        instance.setComputeSize(computeSize);
        instance.setSuspendTimeout(suspendTimeout);
        instance.setStorageLimitGb(storageLimitGb);
        instance.setDbUser(dbUser);
        instance.setDbPassword(dbPassword);
        dbRepo.save(instance);

        // 创建默认分支
        Branch mainBranch = new Branch();
        mainBranch.setName("main");
        mainBranch.setDatabaseInstance(instance);
        mainBranch.setNeonTimelineId(neonTimelineId);
        mainBranch.setIsDefault(true);
        mainBranch.setStatus(BranchStatus.CREATING);
        branchRepo.save(mainBranch);

        // 异步执行 Neon 操作和 Pod 创建
        // （使用 @Async 或 CompletableFuture）
        provisionAsync(instance, mainBranch);

        return buildDatabaseResponse(instance, List.of(mainBranch));
    }

    /**
     * 异步 provisioning
     */
    private void provisionAsync(DatabaseInstance instance, Branch branch) {
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 创建 Neon tenant
                pageserverClient.createTenant(instance.getNeonTenantId());

                // 2. 创建 Neon timeline（PG 17）
                pageserverClient.createTimeline(instance.getNeonTenantId(), branch.getNeonTimelineId(), 17);

                // 3. 创建 compute Pod
                String podName = podManager.createComputePod(instance, branch);
                branch.setComputePodName(podName);
                branch.setComputeStatus(ComputeStatus.STARTING);
                branchRepo.save(branch);

                // 4. 等待就绪
                boolean ready = podManager.waitForPodReady(podName, 120);
                if (ready) {
                    String podIp = podManager.getPodIp(podName);
                    branch.setComputeHost(podIp);
                    branch.setComputePort(55433);
                    branch.setComputeStatus(ComputeStatus.RUNNING);
                    branch.setStatus(BranchStatus.ACTIVE);
                    branch.setLastActiveAt(Instant.now());
                    branchRepo.save(branch);

                    instance.setStatus(InstanceStatus.RUNNING);
                    dbRepo.save(instance);
                } else {
                    branch.setComputeStatus(ComputeStatus.ERROR);
                    branch.setStatus(BranchStatus.ERROR);
                    branchRepo.save(branch);
                    instance.setStatus(InstanceStatus.ERROR);
                    dbRepo.save(instance);
                }
            } catch (Exception e) {
                log.error("Failed to provision instance {}: {}", instance.getId(), e.getMessage(), e);
                instance.setStatus(InstanceStatus.ERROR);
                dbRepo.save(instance);
            }
        });
    }

    /**
     * 休眠 compute
     * 1. 删除 compute Pod
     * 2. 更新 branch.compute_status = SUSPENDED
     * 3. 更新 instance.status = SUSPENDED
     */
    @Transactional
    public void suspendDatabase(String tenantId, String dbId) { ... }

    /**
     * 唤醒 compute（也供 Proxy wake_compute 调用）
     * 1. 重新创建 compute Pod
     * 2. 等待就绪
     * 3. 更新状态
     * 4. 返回 compute 地址
     */
    @Transactional
    public String resumeDatabase(String tenantId, String dbId) { ... }

    /**
     * 唤醒指定分支的 compute（供 Proxy 调用）
     * 根据 endpoint（即 branch name 或 db name）查找对应分支
     */
    public ComputeAddress wakeCompute(String endpointId) { ... }

    /**
     * 删除实例
     * 1. 删除所有分支的 compute Pod
     * 2. 删除 Neon timelines
     * 3. 删除 Neon tenant
     * 4. 删除数据库记录
     */
    @Transactional
    public void deleteDatabase(String tenantId, String dbId) { ... }

    /**
     * 更新实例配置
     */
    @Transactional
    public DatabaseResponse updateDatabase(String tenantId, String dbId, UpdateDatabaseRequest request) { ... }

    /**
     * 生成 32 hex chars 的 ID（兼容 Neon TenantId/TimelineId 格式）
     */
    private String generateHexId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

**`ComputeAddress.java`**:
```java
package com.lakeon.service;

public record ComputeAddress(String host, int port) {}
```

#### 任务 3.3: CREATE `BranchService.java`

```java
package com.lakeon.service;

/**
 * 分支管理服务
 *
 * 创建分支流程:
 * 1. 查找父分支（默认为 main 分支）的 neon_timeline_id
 * 2. 生成新 timeline_id
 * 3. 调用 Pageserver: POST /v1/tenant/{tenant_id}/timeline
 *    body: {"new_timeline_id": "<new_id>", "ancestor_timeline_id": "<parent_id>"}
 *    (参考 TimelineCreateRequestMode::Branch in models.rs)
 * 4. 可选: 创建 compute Pod
 */
@Service
public class BranchService {
    // createBranch, deleteBranch, listBranches, getBranch
}
```

#### 任务 3.4: CREATE 自动休眠调度器

**`lakeon-api/src/main/java/com/lakeon/service/SuspendScheduler.java`**:
```java
package com.lakeon.service;

import com.lakeon.model.Branch;
import com.lakeon.model.ComputeStatus;
import com.lakeon.repository.BranchRepository;
import com.lakeon.k8s.ComputePodManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 定期检查运行中的 compute，超过 suspend_timeout 无活动则自动休眠。
 *
 * 检查逻辑:
 * 1. 查找所有 compute_status = RUNNING 的分支
 * 2. 对比 last_active_at 与当前时间
 * 3. 如果超过 suspend_timeout，删除 Pod 并更新状态
 */
@Component
public class SuspendScheduler {
    private final BranchRepository branchRepo;
    private final DatabaseInstanceRepository dbRepo;
    private final ComputePodManager podManager;

    @Scheduled(fixedDelay = 30000)  // 每 30 秒检查一次
    public void checkAndSuspend() {
        List<Branch> runningBranches = branchRepo.findByComputeStatus(ComputeStatus.RUNNING);
        for (Branch branch : runningBranches) {
            Duration timeout = parseDuration(branch.getDatabaseInstance().getSuspendTimeout());
            if (branch.getLastActiveAt() != null
                && Instant.now().isAfter(branch.getLastActiveAt().plus(timeout))) {
                // 休眠
                suspendBranch(branch);
            }
        }
    }

    private void suspendBranch(Branch branch) {
        if (branch.getComputePodName() != null) {
            podManager.deleteComputePod(branch.getComputePodName());
        }
        branch.setComputeStatus(ComputeStatus.SUSPENDED);
        branch.setComputePodName(null);
        branch.setComputeHost(null);
        branch.setComputePort(null);
        branchRepo.save(branch);

        // 如果所有分支都 SUSPENDED，更新实例状态
        // ...
    }

    private Duration parseDuration(String timeout) {
        // "5m" -> Duration.ofMinutes(5)
        int value = Integer.parseInt(timeout.replaceAll("[^0-9]", ""));
        if (timeout.endsWith("m")) return Duration.ofMinutes(value);
        if (timeout.endsWith("h")) return Duration.ofHours(value);
        return Duration.ofMinutes(5);
    }
}
```

### 阶段 4：REST API 控制器

#### 任务 4.1: CREATE API Key 认证过滤器

**`lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java`**:
```java
package com.lakeon.config;

import com.lakeon.model.Tenant;
import com.lakeon.service.TenantService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;

/**
 * API Key 认证过滤器
 *
 * 从 Authorization: Bearer <api-key> 头部提取 API Key
 * 验证后将 Tenant 放入 request attribute
 *
 * 排除的路径:
 * - /actuator/** (监控端点)
 * - /proxy/** (Proxy 适配接口, 使用内部 token 认证)
 * - POST /api/v1/tenants (创建租户, 无需认证)
 */
@Component
@Order(1)
public class ApiKeyFilter implements Filter {
    private final TenantService tenantService;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();

        // 排除不需要认证的路径
        if (path.startsWith("/actuator") || path.startsWith("/proxy/")) {
            chain.doFilter(req, res);
            return;
        }

        // 创建租户不需要认证
        if ("POST".equals(request.getMethod()) && "/api/v1/tenants".equals(path)) {
            chain.doFilter(req, res);
            return;
        }

        // 提取 API Key
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid Authorization header\"}}");
            return;
        }

        String apiKey = authHeader.substring(7);
        Tenant tenant = tenantService.authenticateByApiKey(apiKey);
        if (tenant == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid API key\"}}");
            return;
        }

        request.setAttribute("tenant", tenant);
        chain.doFilter(req, res);
    }
}
```

#### 任务 4.2: CREATE REST 控制器

**`lakeon-api/src/main/java/com/lakeon/controller/TenantController.java`**:
```java
package com.lakeon.controller;

import com.lakeon.model.dto.*;
import com.lakeon.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {
    private final TenantService tenantService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return tenantService.createTenant(request);
    }

    @GetMapping("/{tenantId}")
    public TenantResponse getTenant(@PathVariable String tenantId) {
        return tenantService.getTenant(tenantId);
    }
}
```

**`lakeon-api/src/main/java/com/lakeon/controller/DatabaseController.java`**:
```java
package com.lakeon.controller;

import com.lakeon.model.Tenant;
import com.lakeon.model.dto.*;
import com.lakeon.service.DatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/databases")
public class DatabaseController {
    private final DatabaseService databaseService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DatabaseResponse createDatabase(HttpServletRequest req, @Valid @RequestBody CreateDatabaseRequest request) {
        Tenant tenant = (Tenant) req.getAttribute("tenant");
        return databaseService.createDatabase(tenant.getId(), request);
    }

    @GetMapping
    public List<DatabaseResponse> listDatabases(HttpServletRequest req) {
        Tenant tenant = (Tenant) req.getAttribute("tenant");
        return databaseService.listDatabases(tenant.getId());
    }

    @GetMapping("/{dbId}")
    public DatabaseResponse getDatabase(HttpServletRequest req, @PathVariable String dbId) {
        Tenant tenant = (Tenant) req.getAttribute("tenant");
        return databaseService.getDatabase(tenant.getId(), dbId);
    }

    @PatchMapping("/{dbId}")
    public DatabaseResponse updateDatabase(HttpServletRequest req, @PathVariable String dbId,
                                           @RequestBody UpdateDatabaseRequest request) {
        Tenant tenant = (Tenant) req.getAttribute("tenant");
        return databaseService.updateDatabase(tenant.getId(), dbId, request);
    }

    @DeleteMapping("/{dbId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDatabase(HttpServletRequest req, @PathVariable String dbId,
                               @RequestParam(defaultValue = "false") boolean force) {
        Tenant tenant = (Tenant) req.getAttribute("tenant");
        databaseService.deleteDatabase(tenant.getId(), dbId);
    }

    @PostMapping("/{dbId}/suspend")
    public void suspendDatabase(HttpServletRequest req, @PathVariable String dbId) {
        Tenant tenant = (Tenant) req.getAttribute("tenant");
        databaseService.suspendDatabase(tenant.getId(), dbId);
    }

    @PostMapping("/{dbId}/resume")
    public DatabaseResponse resumeDatabase(HttpServletRequest req, @PathVariable String dbId) {
        Tenant tenant = (Tenant) req.getAttribute("tenant");
        databaseService.resumeDatabase(tenant.getId(), dbId);
        return databaseService.getDatabase(tenant.getId(), dbId);
    }
}
```

**`lakeon-api/src/main/java/com/lakeon/controller/BranchController.java`**:
```java
package com.lakeon.controller;

@RestController
@RequestMapping("/api/v1/databases/{dbId}/branches")
public class BranchController {
    // POST /  - createBranch
    // GET /   - listBranches
    // GET /{branchId} - getBranch
    // DELETE /{branchId} - deleteBranch
}
```

#### 任务 4.3: CREATE Proxy 适配 API

**关键**: Neon Proxy 调用 Control Plane 的两个核心接口需要我们实现。

参考 Neon 源码:
- `proxy/src/control_plane/client/cplane_proxy_v1.rs` 第 282 行: `GET {endpoint}/wake_compute?session_id=&application_name=&endpointish=`
- `proxy/src/control_plane/client/cplane_proxy_v1.rs` 第 140 行: `GET {endpoint}/get_endpoint_access_control?session_id=&application_name=&endpointish=&role=`

Proxy 期望的响应格式（参考 `proxy/src/control_plane/messages.rs`）:

**wake_compute 响应**:
```json
{
    "address": "<compute_host>:<compute_port>",
    "aux": {
        "endpoint_id": "<branch_id>",
        "project_id": "<instance_id>",
        "branch_id": "<branch_id>",
        "compute_id": "<pod_name>",
        "cold_start_info": "warm" | "pool_miss"
    }
}
```

**get_endpoint_access_control 响应**:
```json
{
    "role_secret": "<scram_hash>",
    "project_id": "<instance_id>",
    "allowed_ips": null
}
```

**`lakeon-api/src/main/java/com/lakeon/controller/ProxyAdapterController.java`**:
```java
package com.lakeon.controller;

import com.lakeon.model.*;
import com.lakeon.repository.*;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.ComputeAddress;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Proxy 适配 API
 *
 * 这些端点是 Neon Proxy 调用的。Proxy 启动参数:
 *   --auth-backend control-plane
 *   --auth-endpoint http://lakeon-api:8080/proxy
 *   --control-plane-token <internal_jwt>
 *
 * Proxy 会调用:
 *   GET /proxy/wake_compute?endpointish=<db_name>&session_id=...
 *   GET /proxy/get_endpoint_access_control?endpointish=<db_name>&role=<role>&session_id=...
 */
@RestController
@RequestMapping("/proxy")
public class ProxyAdapterController {
    private final DatabaseService databaseService;
    private final DatabaseInstanceRepository dbRepo;
    private final BranchRepository branchRepo;

    /**
     * wake_compute - Proxy 在连接到来时调用此接口唤醒 compute
     *
     * endpointish 参数: 对应数据库实例名称或 "db_name/branch_name" 格式
     *
     * 响应格式参考: WakeCompute struct (proxy/src/control_plane/messages.rs 第 288-293 行)
     */
    @GetMapping("/wake_compute")
    public Map<String, Object> wakeCompute(
            @RequestParam("endpointish") String endpointish,
            @RequestParam(value = "session_id", required = false) String sessionId,
            @RequestParam(value = "application_name", required = false) String applicationName) {

        // 1. 解析 endpointish -> 找到对应的 DatabaseInstance 和 Branch
        // 2. 如果 compute 已运行，直接返回地址
        // 3. 如果 compute 已休眠，唤醒（创建 Pod，等待就绪）
        // 4. 返回 WakeCompute 格式响应

        // 解析逻辑:
        // endpointish 可能是 "my-app-db"（默认分支）或 "my-app-db--feature-test"（指定分支）
        String dbName;
        String branchName = "main";
        if (endpointish.contains("--")) {
            String[] parts = endpointish.split("--", 2);
            dbName = parts[0];
            branchName = parts[1];
        } else {
            dbName = endpointish;
        }

        // 查找实例和分支
        DatabaseInstance instance = dbRepo.findByName(dbName);  // 注意: 需跨租户查找或使用 endpoint_id
        Branch branch = branchRepo.findByDatabaseInstanceIdAndName(instance.getId(), branchName);

        // 确保 compute 运行中
        ComputeAddress address;
        String coldStartInfo;
        if (branch.getComputeStatus() == ComputeStatus.RUNNING && branch.getComputeHost() != null) {
            address = new ComputeAddress(branch.getComputeHost(), branch.getComputePort());
            coldStartInfo = "warm";
        } else {
            address = databaseService.wakeCompute(branch);
            coldStartInfo = "pool_miss";
        }

        return Map.of(
            "address", address.host() + ":" + address.port(),
            "aux", Map.of(
                "endpoint_id", branch.getId(),
                "project_id", instance.getId(),
                "branch_id", branch.getId(),
                "compute_id", branch.getComputePodName() != null ? branch.getComputePodName() : "",
                "cold_start_info", coldStartInfo
            )
        );
    }

    /**
     * get_endpoint_access_control - Proxy 认证连接时调用
     *
     * 返回 role 的密码 hash，供 Proxy 进行 SCRAM 认证
     *
     * 响应格式参考: GetEndpointAccessControl struct (proxy/src/control_plane/messages.rs 第 252-266 行)
     */
    @GetMapping("/get_endpoint_access_control")
    public Map<String, Object> getEndpointAccessControl(
            @RequestParam("endpointish") String endpointish,
            @RequestParam("role") String role,
            @RequestParam(value = "session_id", required = false) String sessionId) {

        // 解析 endpointish
        String dbName = endpointish.contains("--") ? endpointish.split("--")[0] : endpointish;

        DatabaseInstance instance = dbRepo.findByName(dbName);

        // 返回角色密码（SCRAM-SHA-256 格式）
        return Map.of(
            "role_secret", instance.getDbPassword(),  // 需要是 SCRAM-SHA-256 hash
            "project_id", instance.getId(),
            "allowed_ips", new Object[0]  // 不限制 IP
        );
    }
}
```

**GOTCHA**: `role_secret` 必须是 PostgreSQL SCRAM-SHA-256 格式的密码 hash，格式为 `SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:<ServerKey>`。需要实现 SCRAM hash 生成工具类。

#### 任务 4.4: CREATE SCRAM 密码工具

**`lakeon-api/src/main/java/com/lakeon/util/ScramUtils.java`**:
```java
package com.lakeon.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PostgreSQL SCRAM-SHA-256 密码 hash 生成工具
 *
 * 生成格式: SCRAM-SHA-256$4096:<salt_base64>$<StoredKey_base64>:<ServerKey_base64>
 *
 * 参考: https://www.postgresql.org/docs/17/protocol-auth.html
 */
public class ScramUtils {
    private static final int ITERATIONS = 4096;

    public static String generateScramHash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return generateScramHash(password, salt, ITERATIONS);
    }

    public static String generateScramHash(String password, byte[] salt, int iterations) {
        try {
            byte[] saltedPassword = hi(password.getBytes("UTF-8"), salt, iterations);

            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(saltedPassword, "HmacSHA256"));
            byte[] clientKey = hmac.doFinal("Client Key".getBytes("UTF-8"));

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] storedKey = md.digest(clientKey);

            hmac.init(new SecretKeySpec(saltedPassword, "HmacSHA256"));
            byte[] serverKey = hmac.doFinal("Server Key".getBytes("UTF-8"));

            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            String storedKeyBase64 = Base64.getEncoder().encodeToString(storedKey);
            String serverKeyBase64 = Base64.getEncoder().encodeToString(serverKey);

            return String.format("SCRAM-SHA-256$%d:%s$%s:%s",
                iterations, saltBase64, storedKeyBase64, serverKeyBase64);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate SCRAM hash", e);
        }
    }

    private static byte[] hi(byte[] password, byte[] salt, int iterations) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(password, "HmacSHA256"));

        byte[] u = new byte[salt.length + 4];
        System.arraycopy(salt, 0, u, 0, salt.length);
        u[u.length - 1] = 1;  // INT(1)

        byte[] prev = hmac.doFinal(u);
        byte[] result = prev.clone();

        for (int i = 2; i <= iterations; i++) {
            hmac.init(new SecretKeySpec(password, "HmacSHA256"));
            byte[] current = hmac.doFinal(prev);
            for (int j = 0; j < result.length; j++) {
                result[j] ^= current[j];
            }
            prev = current;
        }
        return result;
    }
}
```

#### 任务 4.5: CREATE 全局异常处理

**`lakeon-api/src/main/java/com/lakeon/controller/GlobalExceptionHandler.java`**:
```java
package com.lakeon.controller;

import com.lakeon.model.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ResourceNotFoundException e) {
        return ErrorResponse.of("RESOURCE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicate(DuplicateResourceException e) {
        return ErrorResponse.of("DUPLICATE_RESOURCE", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception e) {
        return ErrorResponse.of("INTERNAL_ERROR", e.getMessage());
    }
}
```

### 阶段 5：测试

#### 任务 5.1: CREATE 单元测试

**`lakeon-api/src/test/java/com/lakeon/service/DatabaseServiceTest.java`**:
- 使用 H2 内存数据库
- Mock PageserverClient 和 ComputePodManager
- 测试创建实例、休眠、唤醒、删除流程
- 测试默认值填充逻辑

**`lakeon-api/src/test/java/com/lakeon/controller/ProxyAdapterControllerTest.java`**:
- 测试 wake_compute 端点的响应格式是否与 Neon Proxy 期望一致
- 测试 get_endpoint_access_control 响应格式
- 测试 endpointish 解析逻辑

**`lakeon-api/src/test/java/com/lakeon/util/ScramUtilsTest.java`**:
- 验证生成的 SCRAM hash 格式正确

#### 任务 5.2: CREATE 测试配置

**`lakeon-api/src/test/resources/application-test.yml`**:
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    database-platform: org.hibernate.dialect.H2Dialect

lakeon:
  neon:
    pageserver-url: http://localhost:9898
    safekeeper-urls: localhost:5454
    storage-broker-url: http://localhost:50051
  k8s:
    namespace: test-namespace
    compute-image: test-image:latest
  defaults:
    compute-size: 1cu
    suspend-timeout: 5m
    storage-limit-gb: 10
```

---

## Dev-2 实施计划：Python CLI + 部署配置

### 阶段 1：Python CLI 项目

#### 任务 1.1: CREATE `lakeon-cli/pyproject.toml`

```toml
[project]
name = "lakeon-cli"
version = "0.1.0"
description = "LakeOn Serverless PostgreSQL CLI"
requires-python = ">=3.11"
dependencies = [
    "typer[all]>=0.12.0",
    "httpx>=0.27.0",
    "rich>=13.0.0",
]

[project.scripts]
lakeon = "lakeon_cli.main:app"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["lakeon_cli"]
```

#### 任务 1.2: CREATE CLI 主入口

**`lakeon-cli/lakeon_cli/main.py`**:
```python
import typer

from lakeon_cli.commands import db, branch, tenant, config

app = typer.Typer(
    name="lakeon",
    help="LakeOn Serverless PostgreSQL CLI",
    no_args_is_help=True,
)

app.add_typer(db.app, name="db", help="数据库实例管理")
app.add_typer(branch.app, name="branch", help="分支管理")
app.add_typer(tenant.app, name="tenant", help="租户管理")
app.add_typer(config.app, name="config", help="CLI 配置")

if __name__ == "__main__":
    app()
```

#### 任务 1.3: CREATE API 客户端

**`lakeon-cli/lakeon_cli/client.py`**:
```python
import httpx
import json
from pathlib import Path
from typing import Any, Optional

CONFIG_FILE = Path.home() / ".lakeon" / "config.json"

class LakeonClient:
    def __init__(self):
        config = self._load_config()
        self.base_url = config.get("api_url", "http://localhost:8080")
        self.api_key = config.get("api_key", "")
        self._client = httpx.Client(
            base_url=self.base_url + "/api/v1",
            headers={"Authorization": f"Bearer {self.api_key}"},
            timeout=30.0,
        )

    def _load_config(self) -> dict:
        if CONFIG_FILE.exists():
            return json.loads(CONFIG_FILE.read_text())
        return {}

    @staticmethod
    def save_config(api_url: str, api_key: str):
        CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_FILE.write_text(json.dumps({"api_url": api_url, "api_key": api_key}, indent=2))

    def _handle_response(self, response: httpx.Response) -> dict:
        if response.status_code >= 400:
            error = response.json().get("error", {})
            raise Exception(f"API Error [{error.get('code')}]: {error.get('message')}")
        if response.status_code == 204:
            return {}
        return response.json()

    # Tenant
    def create_tenant(self, name: str) -> dict:
        return self._handle_response(self._client.post("/tenants", json={"name": name}))

    def get_tenant(self, tenant_id: str) -> dict:
        return self._handle_response(self._client.get(f"/tenants/{tenant_id}"))

    # Database
    def create_database(self, name: str, compute_size: Optional[str] = None,
                        suspend_timeout: Optional[str] = None, storage_limit_gb: Optional[int] = None) -> dict:
        body = {"name": name}
        if compute_size: body["compute_size"] = compute_size
        if suspend_timeout: body["suspend_timeout"] = suspend_timeout
        if storage_limit_gb: body["storage_limit_gb"] = storage_limit_gb
        return self._handle_response(self._client.post("/databases", json=body))

    def list_databases(self) -> list:
        return self._handle_response(self._client.get("/databases"))

    def get_database(self, db_id: str) -> dict:
        return self._handle_response(self._client.get(f"/databases/{db_id}"))

    def update_database(self, db_id: str, **kwargs) -> dict:
        return self._handle_response(self._client.patch(f"/databases/{db_id}", json=kwargs))

    def delete_database(self, db_id: str, force: bool = False) -> dict:
        return self._handle_response(self._client.delete(f"/databases/{db_id}", params={"force": force}))

    def suspend_database(self, db_id: str) -> dict:
        return self._handle_response(self._client.post(f"/databases/{db_id}/suspend"))

    def resume_database(self, db_id: str) -> dict:
        return self._handle_response(self._client.post(f"/databases/{db_id}/resume"))

    # Branch
    def create_branch(self, db_id: str, name: str, start_compute: bool = False) -> dict:
        return self._handle_response(self._client.post(
            f"/databases/{db_id}/branches",
            json={"name": name, "start_compute": start_compute}
        ))

    def list_branches(self, db_id: str) -> list:
        return self._handle_response(self._client.get(f"/databases/{db_id}/branches"))

    def delete_branch(self, db_id: str, branch_id: str) -> dict:
        return self._handle_response(self._client.delete(f"/databases/{db_id}/branches/{branch_id}"))
```

#### 任务 1.4: CREATE 命令模块

**`lakeon-cli/lakeon_cli/commands/__init__.py`**: 空文件

**`lakeon-cli/lakeon_cli/commands/config.py`**:
```python
import typer
from rich.console import Console

app = typer.Typer()
console = Console()

@app.command("set")
def config_set(
    api_url: str = typer.Option(..., help="API endpoint URL"),
    api_key: str = typer.Option(..., help="API Key"),
):
    """配置 CLI 连接参数"""
    from lakeon_cli.client import LakeonClient
    LakeonClient.save_config(api_url, api_key)
    console.print("[green]Configuration saved.[/green]")
```

**`lakeon-cli/lakeon_cli/commands/db.py`**:
```python
import typer
from rich.console import Console
from rich.table import Table
from lakeon_cli.client import LakeonClient

app = typer.Typer()
console = Console()

@app.command("create")
def create(
    name: str = typer.Option(..., help="数据库名称"),
    compute_size: str = typer.Option(None, help="Compute 规格 (1cu/2cu/4cu/8cu)"),
    suspend_timeout: str = typer.Option(None, help="休眠超时 (如 5m, 10m)"),
):
    """创建数据库实例"""
    client = LakeonClient()
    result = client.create_database(name, compute_size=compute_size, suspend_timeout=suspend_timeout)
    console.print(f"[green]Database created: {result['id']}[/green]")
    console.print(f"Connection URI: {result['connection_uri']}")

@app.command("list")
def list_dbs():
    """列出所有数据库实例"""
    client = LakeonClient()
    databases = client.list_databases()
    table = Table(title="Databases")
    table.add_column("ID")
    table.add_column("Name")
    table.add_column("Status")
    table.add_column("Compute Size")
    table.add_column("Created At")
    for db in databases:
        table.add_row(db["id"], db["name"], db["status"], db["compute_size"], db.get("created_at", ""))
    console.print(table)

@app.command("status")
def status(name: str = typer.Option(..., help="数据库名称")):
    """查看数据库实例状态"""
    client = LakeonClient()
    # 需要先通过 name 找到 id，或者 API 支持 name 查询
    databases = client.list_databases()
    db = next((d for d in databases if d["name"] == name), None)
    if not db:
        console.print(f"[red]Database '{name}' not found[/red]")
        raise typer.Exit(1)
    from rich.pretty import pprint
    pprint(db)

@app.command("suspend")
def suspend(name: str = typer.Option(..., help="数据库名称")):
    """休眠 compute"""
    client = LakeonClient()
    databases = client.list_databases()
    db = next((d for d in databases if d["name"] == name), None)
    if db:
        client.suspend_database(db["id"])
        console.print(f"[green]Database '{name}' suspended.[/green]")

@app.command("resume")
def resume(name: str = typer.Option(..., help="数据库名称")):
    """唤醒 compute"""
    client = LakeonClient()
    databases = client.list_databases()
    db = next((d for d in databases if d["name"] == name), None)
    if db:
        client.resume_database(db["id"])
        console.print(f"[green]Database '{name}' resumed.[/green]")

@app.command("update")
def update(
    name: str = typer.Option(..., help="数据库名称"),
    compute_size: str = typer.Option(None, help="Compute 规格"),
    suspend_timeout: str = typer.Option(None, help="休眠超时"),
):
    """更新实例配置"""
    client = LakeonClient()
    databases = client.list_databases()
    db = next((d for d in databases if d["name"] == name), None)
    if db:
        kwargs = {}
        if compute_size: kwargs["compute_size"] = compute_size
        if suspend_timeout: kwargs["suspend_timeout"] = suspend_timeout
        client.update_database(db["id"], **kwargs)
        console.print(f"[green]Database '{name}' updated.[/green]")

@app.command("delete")
def delete(
    name: str = typer.Option(..., help="数据库名称"),
    force: bool = typer.Option(False, help="跳过确认"),
):
    """删除数据库实例"""
    client = LakeonClient()
    databases = client.list_databases()
    db = next((d for d in databases if d["name"] == name), None)
    if db:
        if not force:
            typer.confirm(f"确定要删除数据库 '{name}'?", abort=True)
        client.delete_database(db["id"], force=force)
        console.print(f"[green]Database '{name}' deleted.[/green]")
```

**`lakeon-cli/lakeon_cli/commands/branch.py`**:
```python
import typer
from rich.console import Console
from rich.table import Table
from lakeon_cli.client import LakeonClient

app = typer.Typer()
console = Console()

@app.command("create")
def create(
    db: str = typer.Option(..., help="数据库名称"),
    name: str = typer.Option(..., help="分支名称"),
):
    """创建分支"""
    client = LakeonClient()
    # 先找到 db_id
    databases = client.list_databases()
    db_obj = next((d for d in databases if d["name"] == db), None)
    if not db_obj:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    result = client.create_branch(db_obj["id"], name, start_compute=True)
    console.print(f"[green]Branch created: {result['id']}[/green]")
    if result.get("connection_uri"):
        console.print(f"Connection URI: {result['connection_uri']}")

@app.command("list")
def list_branches(db: str = typer.Option(..., help="数据库名称")):
    """列出分支"""
    client = LakeonClient()
    databases = client.list_databases()
    db_obj = next((d for d in databases if d["name"] == db), None)
    if not db_obj:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    branches = client.list_branches(db_obj["id"])
    table = Table(title=f"Branches of {db}")
    table.add_column("ID")
    table.add_column("Name")
    table.add_column("Status")
    table.add_column("Default")
    for br in branches:
        table.add_row(br["id"], br["name"], br["status"], str(br.get("is_default", False)))
    console.print(table)

@app.command("delete")
def delete(
    db: str = typer.Option(..., help="数据库名称"),
    name: str = typer.Option(..., help="分支名称"),
):
    """删除分支"""
    client = LakeonClient()
    databases = client.list_databases()
    db_obj = next((d for d in databases if d["name"] == db), None)
    if not db_obj:
        console.print(f"[red]Database '{db}' not found[/red]")
        raise typer.Exit(1)
    branches = client.list_branches(db_obj["id"])
    br = next((b for b in branches if b["name"] == name), None)
    if br:
        client.delete_branch(db_obj["id"], br["id"])
        console.print(f"[green]Branch '{name}' deleted.[/green]")
```

**`lakeon-cli/lakeon_cli/commands/tenant.py`**:
```python
import typer
from rich.console import Console
from lakeon_cli.client import LakeonClient

app = typer.Typer()
console = Console()

@app.command("create")
def create(name: str = typer.Option(..., help="租户名称")):
    """创建租户"""
    client = LakeonClient()
    result = client.create_tenant(name)
    console.print(f"[green]Tenant created: {result['id']}[/green]")
    console.print(f"API Key: {result['api_key']}")
```

- **VALIDATE**: `cd lakeon-cli && pip install -e . && lakeon --help`

### 阶段 2：Helm Charts 部署配置

#### 任务 2.1: CREATE Helm Chart 结构

```
deploy/helm/lakeon/
  Chart.yaml
  values.yaml
  templates/
    _helpers.tpl
    namespace.yaml
    configmap-pageserver.yaml
    deployment-pageserver.yaml
    service-pageserver.yaml
    statefulset-safekeeper.yaml
    service-safekeeper.yaml
    deployment-storage-broker.yaml
    service-storage-broker.yaml
    deployment-proxy.yaml
    service-proxy.yaml
    deployment-api.yaml
    service-api.yaml
    configmap-api.yaml
    secret-obs.yaml
    secret-api.yaml
```

**`deploy/helm/lakeon/Chart.yaml`**:
```yaml
apiVersion: v2
name: lakeon
description: LakeOn Serverless PostgreSQL on Huawei Cloud
version: 0.1.0
appVersion: "0.1.0"
```

**`deploy/helm/lakeon/values.yaml`**:
```yaml
global:
  namespace: lakeon

neon:
  image:
    repository: ghcr.io/neondatabase/neon
    tag: latest
    pullPolicy: IfNotPresent
  computeImage:
    repository: ghcr.io/neondatabase/compute-node-v17
    tag: latest

pageserver:
  replicas: 1
  httpPort: 9898
  pgPort: 6400
  resources:
    requests:
      cpu: "2"
      memory: "4Gi"
    limits:
      cpu: "4"
      memory: "8Gi"

safekeeper:
  replicas: 3
  pgPort: 5454
  httpPort: 7676
  resources:
    requests:
      cpu: "1"
      memory: "2Gi"
    limits:
      cpu: "2"
      memory: "4Gi"

storageBroker:
  replicas: 1
  port: 50051

proxy:
  replicas: 2
  listenPort: 4432
  httpPort: 7000
  authBackend: control-plane
  resources:
    requests:
      cpu: "1"
      memory: "1Gi"

api:
  replicas: 2
  port: 8080
  image:
    repository: lakeon/lakeon-api
    tag: "0.1.0"
  resources:
    requests:
      cpu: "1"
      memory: "1Gi"

obs:
  endpoint: ""
  bucket: "lakeon-neon"
  region: "cn-north-4"
  accessKey: ""
  secretKey: ""

metadataDb:
  host: ""
  port: 5432
  name: "lakeon"
  user: "lakeon"
  password: ""
```

#### 任务 2.2: CREATE Pageserver 部署模板

**`deploy/helm/lakeon/templates/deployment-pageserver.yaml`**:

关键配置项参考 `/Users/jacky/code/neon/docker-compose/pageserver_config/pageserver.toml`:
- `broker_endpoint`: Storage Broker gRPC 地址
- `listen_pg_addr`: PG 协议监听地址
- `listen_http_addr`: HTTP API 监听地址
- `remote_storage`: OBS 配置（S3 兼容）

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pageserver
  namespace: {{ .Values.global.namespace }}
spec:
  replicas: {{ .Values.pageserver.replicas }}
  selector:
    matchLabels:
      app: pageserver
  template:
    metadata:
      labels:
        app: pageserver
    spec:
      containers:
        - name: pageserver
          image: "{{ .Values.neon.image.repository }}:{{ .Values.neon.image.tag }}"
          command: ["pageserver"]
          args:
            - "-D"
            - "/data"
            - "-c"
            - "broker_endpoint='http://storage-broker:{{ .Values.storageBroker.port }}'"
            - "-c"
            - "listen_pg_addr='0.0.0.0:{{ .Values.pageserver.pgPort }}'"
            - "-c"
            - "listen_http_addr='0.0.0.0:{{ .Values.pageserver.httpPort }}'"
            - "-c"
            - "remote_storage={endpoint='{{ .Values.obs.endpoint }}', bucket_name='{{ .Values.obs.bucket }}', bucket_region='{{ .Values.obs.region }}', prefix_in_bucket='/pageserver'}"
            - "-c"
            - "control_plane_emergency_mode=true"
          env:
            - name: AWS_ACCESS_KEY_ID
              valueFrom:
                secretKeyRef:
                  name: obs-credentials
                  key: access-key
            - name: AWS_SECRET_ACCESS_KEY
              valueFrom:
                secretKeyRef:
                  name: obs-credentials
                  key: secret-key
          ports:
            - containerPort: {{ .Values.pageserver.httpPort }}
              name: http
            - containerPort: {{ .Values.pageserver.pgPort }}
              name: pg
          resources:
            {{- toYaml .Values.pageserver.resources | nindent 12 }}
          volumeMounts:
            - name: data
              mountPath: /data
      volumes:
        - name: data
          emptyDir: {}
```

#### 任务 2.3: CREATE Safekeeper StatefulSet

**`deploy/helm/lakeon/templates/statefulset-safekeeper.yaml`**:

Safekeeper 启动参数参考 `docker-compose.yml` 第 63-71 行:
- `--listen-pg`: PG 协议监听地址
- `--listen-http`: HTTP API 地址
- `--id`: Safekeeper 节点 ID
- `--broker-endpoint`: Storage Broker 地址
- `--remote-storage`: OBS 配置

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: safekeeper
  namespace: {{ .Values.global.namespace }}
spec:
  serviceName: safekeeper
  replicas: {{ .Values.safekeeper.replicas }}
  selector:
    matchLabels:
      app: safekeeper
  template:
    metadata:
      labels:
        app: safekeeper
    spec:
      containers:
        - name: safekeeper
          image: "{{ .Values.neon.image.repository }}:{{ .Values.neon.image.tag }}"
          command: ["safekeeper"]
          args:
            - "--listen-pg=0.0.0.0:{{ .Values.safekeeper.pgPort }}"
            - "--listen-http=0.0.0.0:{{ .Values.safekeeper.httpPort }}"
            - "--id=$(SAFEKEEPER_ID)"
            - "--broker-endpoint=http://storage-broker:{{ .Values.storageBroker.port }}"
            - "--advertise-url=$(POD_NAME).safekeeper.{{ .Values.global.namespace }}.svc.cluster.local:{{ .Values.safekeeper.pgPort }}"
            - "-D"
            - "/data"
            - "--remote-storage={endpoint='{{ .Values.obs.endpoint }}', bucket_name='{{ .Values.obs.bucket }}', bucket_region='{{ .Values.obs.region }}', prefix_in_bucket='/safekeeper/'}"
          env:
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: SAFEKEEPER_ID
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name  # 需要用 initContainer 转换为数字 ID
            - name: AWS_ACCESS_KEY_ID
              valueFrom:
                secretKeyRef:
                  name: obs-credentials
                  key: access-key
            - name: AWS_SECRET_ACCESS_KEY
              valueFrom:
                secretKeyRef:
                  name: obs-credentials
                  key: secret-key
          ports:
            - containerPort: {{ .Values.safekeeper.pgPort }}
              name: pg
            - containerPort: {{ .Values.safekeeper.httpPort }}
              name: http
          resources:
            {{- toYaml .Values.safekeeper.resources | nindent 12 }}
```

#### 任务 2.4: CREATE Proxy 部署模板

**`deploy/helm/lakeon/templates/deployment-proxy.yaml`**:

Proxy 关键启动参数（参考 `proxy/src/binary/proxy.rs` 第 779-836 行）:
- `--auth-backend control-plane`: 使用 Control Plane 认证模式
- `--auth-endpoint http://lakeon-api:8080/proxy`: LakeOn API 的 Proxy 适配端点
- `--control-plane-token <jwt>`: 内部认证 token

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: proxy
  namespace: {{ .Values.global.namespace }}
spec:
  replicas: {{ .Values.proxy.replicas }}
  selector:
    matchLabels:
      app: proxy
  template:
    metadata:
      labels:
        app: proxy
    spec:
      containers:
        - name: proxy
          image: "{{ .Values.neon.image.repository }}:{{ .Values.neon.image.tag }}"
          command: ["proxy"]
          args:
            - "--proxy=0.0.0.0:{{ .Values.proxy.listenPort }}"
            - "--auth-backend=control-plane"
            - "--auth-endpoint=http://lakeon-api:{{ .Values.api.port }}/proxy"
            - "--wss=0.0.0.0:{{ .Values.proxy.httpPort }}"
          env:
            - name: NEON_PROXY_TO_CONTROLPLANE_TOKEN
              valueFrom:
                secretKeyRef:
                  name: api-credentials
                  key: proxy-token
          ports:
            - containerPort: {{ .Values.proxy.listenPort }}
              name: pg
            - containerPort: {{ .Values.proxy.httpPort }}
              name: http
          resources:
            {{- toYaml .Values.proxy.resources | nindent 12 }}
```

#### 任务 2.5: CREATE API 部署模板 + Service

**`deploy/helm/lakeon/templates/deployment-api.yaml`** 和 **`service-api.yaml`**:
标准 Spring Boot 应用 K8s 部署配置。

#### 任务 2.6: CREATE Storage Broker 部署模板

参考 `docker-compose.yml` 第 136-143 行: `storage_broker --listen-addr=0.0.0.0:50051`

#### 任务 2.7: CREATE Secret 和 ConfigMap 模板

- `secret-obs.yaml`: OBS 访问密钥
- `secret-api.yaml`: API 内部 token、元数据库密码
- `configmap-api.yaml`: API 配置（指向各组件地址）

### 阶段 3：监控配置

#### 任务 3.1: CREATE Prometheus 配置

**`deploy/monitoring/prometheus/prometheus.yml`**:
```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'lakeon-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['lakeon-api:8080']

  - job_name: 'pageserver'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['pageserver:9898']

  - job_name: 'safekeeper'
    metrics_path: '/metrics'
    static_configs:
      - targets:
          - 'safekeeper-0.safekeeper:7676'
          - 'safekeeper-1.safekeeper:7676'
          - 'safekeeper-2.safekeeper:7676'

  - job_name: 'proxy'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['proxy:7000']
```

#### 任务 3.2: CREATE Grafana Dashboard

**`deploy/monitoring/grafana/dashboards/lakeon-overview.json`**:

包含以下面板:
- 实例状态分布（running/suspended/error）
- Compute 唤醒延迟直方图
- 活跃连接数
- Pageserver 存储用量
- Safekeeper WAL 延迟
- API 请求延迟和错误率

#### 任务 3.3: CREATE 告警规则

**`deploy/monitoring/prometheus/alerts.yml`**:
```yaml
groups:
  - name: lakeon-alerts
    rules:
      - alert: ComputeWakeupFailed
        expr: rate(lakeon_compute_wakeup_failures_total[5m]) > 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Compute wakeup failures detected"

      - alert: PageserverDown
        expr: up{job="pageserver"} == 0
        for: 1m
        labels:
          severity: critical

      - alert: SafekeeperDown
        expr: count(up{job="safekeeper"} == 1) < 2
        for: 1m
        labels:
          severity: critical

      - alert: HighStorageUsage
        expr: lakeon_storage_used_bytes / lakeon_storage_limit_bytes > 0.9
        for: 5m
        labels:
          severity: warning

      - alert: APIHighLatency
        expr: histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{job="lakeon-api"}[5m])) > 2
        for: 5m
        labels:
          severity: warning
```

- **VALIDATE**: `helm lint deploy/helm/lakeon/`

---

## 测试策略

### 单元测试（Dev-1）

| 模块 | 测试文件 | 覆盖内容 |
|------|---------|---------|
| TenantService | TenantServiceTest.java | 创建租户、API Key 生成唯一性、名称重复检查 |
| DatabaseService | DatabaseServiceTest.java | 创建实例流程、默认值填充、状态转换、休眠/唤醒逻辑 |
| BranchService | BranchServiceTest.java | 创建分支、删除分支、默认分支保护 |
| PageserverClient | PageserverClientTest.java | HTTP 请求构造、响应解析、错误处理 |
| ComputePodManager | ComputePodManagerTest.java | Pod spec 生成、config.json 格式验证 |
| ProxyAdapterController | ProxyAdapterControllerTest.java | wake_compute 响应格式、endpointish 解析 |
| ScramUtils | ScramUtilsTest.java | SCRAM hash 格式正确性 |
| ApiKeyFilter | ApiKeyFilterTest.java | 认证通过/拒绝、路径排除 |

### 单元测试（Dev-2）

| 模块 | 测试文件 | 覆盖内容 |
|------|---------|---------|
| client.py | test_client.py | API 请求构造、错误处理、配置加载 |
| commands/db.py | test_db_commands.py | 各命令输出格式 |
| Helm Charts | helm lint | Chart 语法验证 |

### 集成测试

使用 docker-compose 环境（Neon 组件 + 管控面 + PostgreSQL 元数据库）进行端到端测试:

1. 创建租户 -> 获得 API Key
2. 创建数据库实例 -> 获得连接串
3. psql 连接 -> 执行 DDL/DML
4. 等待休眠 -> 验证 Pod 被删除
5. 重新连接 -> 验证自动唤醒 -> 数据完好
6. 创建分支 -> 验证数据隔离
7. 删除分支 -> 验证主分支不受影响
8. 删除实例 -> 验证 Neon 资源清理

---

## 验证命令

### 级别 1: 编译

```bash
# Java API
cd lakeon-api && mvn clean compile

# Python CLI
cd lakeon-cli && pip install -e . && lakeon --help
```

### 级别 2: 单元测试

```bash
# Java
cd lakeon-api && mvn test

# Python
cd lakeon-cli && python -m pytest tests/
```

### 级别 3: Helm 验证

```bash
helm lint deploy/helm/lakeon/
helm template lakeon deploy/helm/lakeon/ --values deploy/helm/lakeon/values.yaml
```

### 级别 4: 手动验证

```bash
# 启动 API（需要本地 PostgreSQL 作为元数据库）
cd lakeon-api && mvn spring-boot:run

# 创建租户
curl -X POST http://localhost:8080/api/v1/tenants -H "Content-Type: application/json" -d '{"name":"test-tenant"}'

# 创建实例
curl -X POST http://localhost:8080/api/v1/databases -H "Authorization: Bearer <api_key>" -H "Content-Type: application/json" -d '{"name":"test-db"}'

# 测试 Proxy 适配接口
curl "http://localhost:8080/proxy/wake_compute?endpointish=test-db&session_id=test"
curl "http://localhost:8080/proxy/get_endpoint_access_control?endpointish=test-db&role=user_xxx&session_id=test"
```

---

## 验收标准

- [ ] API 所有端点正常工作（租户 CRUD、实例 CRUD、分支 CRUD、suspend/resume）
- [ ] Proxy 适配接口（wake_compute、get_endpoint_access_control）返回正确格式
- [ ] CLI 所有命令正常工作
- [ ] Helm Charts 通过 lint 验证
- [ ] 单元测试覆盖率 > 80%
- [ ] compute Pod 能正确创建和销毁
- [ ] 自动休眠调度器正常工作
- [ ] Prometheus 指标端点可访问
- [ ] Grafana Dashboard JSON 有效

---

## 完成检查清单

- [ ] Dev-1: lakeon-api Maven 项目可编译运行
- [ ] Dev-1: 所有 JPA 实体和 Repository 正确
- [ ] Dev-1: PageserverClient 可调用 Neon API
- [ ] Dev-1: ComputePodManager 生成正确的 Pod spec
- [ ] Dev-1: Proxy 适配 API 格式与 Neon Proxy 兼容
- [ ] Dev-1: 单元测试通过
- [ ] Dev-2: lakeon-cli 可安装并执行所有命令
- [ ] Dev-2: Helm Charts 语法正确
- [ ] Dev-2: 监控配置完整
- [ ] 两个模块文件无重叠

---

## 备注

### 关键技术决策

1. **Tenant 创建方式**: 使用 `PUT /v1/tenant/{id}/location_config` 而非已废弃的 `POST /v1/tenant`。配置为 `AttachedSingle` 模式（参考 compute.sh 第 50 行）。

2. **Proxy 认证模式**: 使用 `--auth-backend control-plane` 模式，Proxy 会调用 `{auth_endpoint}/wake_compute` 和 `{auth_endpoint}/get_endpoint_access_control`。这两个接口的请求/响应格式必须严格匹配 Neon Proxy 的期望（参考 cplane_proxy_v1.rs）。

3. **endpointish 路由**: Neon Proxy 使用连接串中的 SNI 或数据库名作为 `endpointish` 参数。LakeOn 约定格式: `<db_name>` 访问默认分支，`<db_name>--<branch_name>` 访问指定分支。

4. **compute_ctl 配置**: 必须生成与 `ComputeSpec`（compute_api/src/spec.rs）兼容的 JSON 配置，包含 tenant_id、timeline_id、pageserver_connstring、safekeeper_connstrings、cluster（roles/databases/settings）。

5. **SCRAM 密码**: Proxy 认证使用 SCRAM-SHA-256。`get_endpoint_access_control` 返回的 `role_secret` 必须是 PostgreSQL SCRAM-SHA-256 格式。创建实例时需要: (a) 生成随机密码 (b) 计算 SCRAM hash (c) SCRAM hash 存入 DB 并传给 Proxy 和 compute 配置。

6. **华为云 OBS S3 兼容**: Neon 通过标准 AWS S3 SDK 访问远程存储。OBS 兼容 S3 API，只需配置 `endpoint` 为 OBS 的 S3 兼容端点。环境变量 `AWS_ACCESS_KEY_ID` 和 `AWS_SECRET_ACCESS_KEY` 用于认证。
