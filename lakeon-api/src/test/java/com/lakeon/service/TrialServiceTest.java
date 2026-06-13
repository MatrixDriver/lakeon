package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.CreateDatabaseRequest;
import com.lakeon.model.dto.DatabaseResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TrialService 单元测试")
class TrialServiceTest {

    @Mock
    private LakeonProperties props;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @Test
    @DisplayName("创建 trial 时同步创建一个小规格数据库并返回连接信息")
    void createTrial_createsDatabase() {
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(inv -> {
            TenantEntity tenant = inv.getArgument(0);
            tenant.prePersist();
            return tenant;
        });
        when(databaseService.create(any(TenantEntity.class), any(CreateDatabaseRequest.class)))
            .thenReturn(DatabaseResponse.builder()
                .id("db_trial001")
                .name("trial-abcd1234")
                .status(DatabaseStatus.CREATING)
                .connectionUri("postgres://user@pg.dbay.cloud:5432/trial-abcd1234")
                .branches(List.of())
                .build());

        TrialService service = new TrialService(props, databaseService, tenantRepository, databaseRepository);

        var result = service.createTrial();

        assertThat(result.get("api_key")).isNotNull();
        assertThat(result.get("database_id")).isEqualTo("db_trial001");
        assertThat(result.get("database")).isInstanceOfSatisfying(java.util.Map.class, db -> {
            assertThat(db.get("id")).isEqualTo("db_trial001");
            assertThat(db.get("connection_uri")).isNotNull();
        });

        ArgumentCaptor<TenantEntity> tenantCaptor = ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).save(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getMaxDatabases()).isEqualTo(1);
        assertThat(tenantCaptor.getValue().getMaxComputeCu()).isEqualTo(1);

        ArgumentCaptor<CreateDatabaseRequest> requestCaptor = ArgumentCaptor.forClass(CreateDatabaseRequest.class);
        verify(databaseService).create(any(TenantEntity.class), requestCaptor.capture());
        assertThat(requestCaptor.getValue().computeSize()).isEqualTo("1cu");
        assertThat(requestCaptor.getValue().storageLimitGb()).isEqualTo(1);
    }
}
