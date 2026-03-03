package com.lakeon.repository;

import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("DatabaseRepository 数据访问层测试")
class DatabaseRepositoryTest {

    @Autowired
    private DatabaseRepository databaseRepository;

    @Test
    @DisplayName("UT-REPO-001: save — 正常保存实体")
    void save_success() {
        // Given
        var entity = createEntity("tn_repo001", "test-db-1");

        // When
        var saved = databaseRepository.save(entity);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("test-db-1");
        assertThat(saved.getTenantId()).isEqualTo("tn_repo001");
    }

    @Test
    @DisplayName("UT-REPO-002: findByTenantIdAndName — 存在，返回匹配实体")
    void findByTenantIdAndName_found() {
        // Given
        var entity = createEntity("tn_repo002", "my-db");
        databaseRepository.save(entity);

        // When
        var result = databaseRepository.findByTenantIdAndName("tn_repo002", "my-db");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("my-db");
        assertThat(result.get().getTenantId()).isEqualTo("tn_repo002");
    }

    @Test
    @DisplayName("UT-REPO-003: findByTenantIdAndName — 不存在，返回 empty")
    void findByTenantIdAndName_notFound() {
        // When
        var result = databaseRepository.findByTenantIdAndName("tn_nonexist", "no-db");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("UT-REPO-004: findAllByTenantId — 返回该租户所有实例，不含其他租户")
    void findAllByTenantId_tenantIsolation() {
        // Given
        databaseRepository.save(createEntity("tn_a", "db-a1"));
        databaseRepository.save(createEntity("tn_a", "db-a2"));
        databaseRepository.save(createEntity("tn_b", "db-b1"));

        // When
        var resultA = databaseRepository.findAllByTenantId("tn_a");
        var resultB = databaseRepository.findAllByTenantId("tn_b");

        // Then
        assertThat(resultA).hasSize(2);
        assertThat(resultA).extracting("name").containsExactlyInAnyOrder("db-a1", "db-a2");
        assertThat(resultB).hasSize(1);
        assertThat(resultB).extracting("name").containsExactly("db-b1");
    }

    private DatabaseEntity createEntity(String tenantId, String name) {
        var entity = new DatabaseEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setStatus(DatabaseStatus.RUNNING);
        entity.setComputeSize("1cu");
        entity.setSuspendTimeout("5m");
        entity.setStorageLimitGb(10);
        entity.setNeonTenantId("neon-" + tenantId);
        entity.setNeonTimelineId("timeline-" + name);
        entity.setConnectionUri("postgres://user:pass@proxy/" + name);
        return entity;
    }
}
