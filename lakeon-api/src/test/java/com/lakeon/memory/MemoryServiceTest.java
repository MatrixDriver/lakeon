package com.lakeon.memory;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.CreateDatabaseRequest;
import com.lakeon.model.dto.DatabaseResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private MemoryBaseRepository repository;

    @Mock
    private MemoryDbHelper dbHelper;

    @Mock
    private LakeonProperties props;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private TenantRepository tenantRepository;

    private MemoryService service;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        service = new MemoryService(repository, dbHelper, props, databaseService, tenantRepository);
        tenant = new TenantEntity();
        tenant.setId("tn_test001");
        tenant.setName("test tenant");
        tenant.setApiKey("lk_test");

        DatabaseResponse response = new DatabaseResponse();
        response.setId("db_test001");
        response.setPassword("raw-secret-password");
        when(databaseService.create(any(TenantEntity.class), any(CreateDatabaseRequest.class))).thenReturn(response);
        when(repository.save(any(MemoryBaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createBaseUsesProxySafeDatabaseSlug() {
        service.createBase(
                tenant,
                "project memory",
                null,
                MemoryBaseType.BUILTIN,
                null,
                true,
                null,
                false,
                null,
                null,
                null);

        ArgumentCaptor<CreateDatabaseRequest> request = ArgumentCaptor.forClass(CreateDatabaseRequest.class);
        verify(databaseService).create(any(TenantEntity.class), request.capture());

        assertThat(request.getValue().name())
                .startsWith("mem-")
                .matches("[A-Za-z0-9-]+")
                .doesNotContain("_");
    }

    @Test
    void createBaseStoresRawDatabasePasswordForMemoryServiceConnections() {
        service.createBase(
                tenant,
                "project memory",
                null,
                MemoryBaseType.BUILTIN,
                null,
                true,
                null,
                false,
                null,
                null,
                null);

        ArgumentCaptor<MemoryBaseEntity> entity = ArgumentCaptor.forClass(MemoryBaseEntity.class);
        verify(repository).save(entity.capture());

        assertThat(entity.getValue().getDbPassword()).isEqualTo("raw-secret-password");
    }
}
