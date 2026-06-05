package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.connector.ConnectorDtos.PostgresConnectionSnapshot;
import com.lakeon.connector.ConnectorService;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.k8s.ImportJobPodManager;
import com.lakeon.model.dto.CreateImportRequest;
import com.lakeon.model.dto.ImportTaskResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.ImportTaskEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.ConflictStrategy;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.ImportTableTaskRepository;
import com.lakeon.repository.ImportTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImportService connector-backed imports")
class ImportServiceConnectorTest {

    @Mock
    private ImportTaskRepository importTaskRepository;

    @Mock
    private ImportTableTaskRepository importTableTaskRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private ImportJobPodManager importJobPodManager;

    @Mock
    private ComputePodManager computePodManager;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private ConnectorService connectorService;

    private ImportService importService;
    private TenantEntity tenant;
    private DatabaseEntity database;

    @BeforeEach
    void setUp() {
        LakeonProperties props = new LakeonProperties();
        LakeonProperties.SyncConfig syncConfig = new LakeonProperties.SyncConfig();
        syncConfig.setMaxTasks(10);
        props.setSync(syncConfig);

        importService = new ImportService(
            importTaskRepository,
            importTableTaskRepository,
            databaseRepository,
            importJobPodManager,
            computePodManager,
            databaseService,
            operationLogService,
            props,
            connectorService
        );

        TransactionSynchronizationManager.initSynchronization();

        tenant = new TenantEntity();
        tenant.setId("tn_test001");

        database = new DatabaseEntity();
        database.setId("db_test001");
        database.setTenantId("tn_test001");
        database.setName("target-db");
        database.setStatus(DatabaseStatus.RUNNING);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("createImport resolves connector and snapshots connector source fields")
    void createImport_withConnectorIdUsesResolvedSnapshot() {
        when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
            .thenReturn(Optional.of(database));
        when(connectorService.resolvePostgres("tn_test001", "conn_pg001"))
            .thenReturn(new PostgresConnectionSnapshot(
                "conn_pg001",
                "Orders PostgreSQL",
                "resolved.example.com",
                6543,
                "orders",
                "resolved_user",
                "resolved_pass"
            ));
        OperationLogEntity opLog = new OperationLogEntity();
        opLog.setId("op_001");
        when(operationLogService.startOperation(any(), any(), any(), any())).thenReturn(opLog);
        when(importTaskRepository.save(any(ImportTaskEntity.class)))
            .thenAnswer(inv -> {
                ImportTaskEntity entity = inv.getArgument(0);
                entity.setId("imp_conn001");
                return entity;
            });

        var request = new CreateImportRequest(
            "conn_pg001",
            "manual.example.com", 5432, "manualdb", "manualuser", "manualpass",
            ImportMode.SELECTIVE, ConflictStrategy.REPLACE, List.of("public.orders")
        );

        ImportTaskResponse response = importService.createImport(tenant, "db_test001", request);

        verify(connectorService).resolvePostgres("tn_test001", "conn_pg001");

        ArgumentCaptor<ImportTaskEntity> taskCaptor = ArgumentCaptor.forClass(ImportTaskEntity.class);
        verify(importTaskRepository).save(taskCaptor.capture());
        ImportTaskEntity savedTask = taskCaptor.getValue();
        assertThat(savedTask.getConnectorId()).isEqualTo("conn_pg001");
        assertThat(savedTask.getSourceHost()).isEqualTo("resolved.example.com");
        assertThat(savedTask.getSourcePort()).isEqualTo(6543);
        assertThat(savedTask.getSourceDbname()).isEqualTo("orders");
        assertThat(savedTask.getSourceUser()).isEqualTo("resolved_user");
        assertThat(savedTask.getSourcePassword()).isEqualTo("resolved_pass");

        assertThat(response.connectorId()).isEqualTo("conn_pg001");
        assertThat(response.connectorName()).isEqualTo("Orders PostgreSQL");
    }
}
