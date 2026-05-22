package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.job.JobService;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.DatabaseResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.AiSqlService;
import com.lakeon.service.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link KnowledgeService#createKnowledgeBase} publishes a
 * {@link KnowledgeBaseCreatedEvent} so that {@link WikiSchemaSeeder} can seed
 * a default {@code schema.md} after the KB transaction commits.
 *
 * Uses a mock-based unit test rather than {@code @SpringBootTest} to avoid
 * external dependencies (OBS, MaaS, Postgres) and to keep the test fast.
 */
class KnowledgeServiceSchemaSeedTest {

    DocumentRepository documentRepository;
    KnowledgeBaseRepository knowledgeBaseRepository;
    JobService jobService;
    LakeonProperties props;
    DatabaseRepository databaseRepository;
    ComputePodManager computePodManager;
    ObjectMapper objectMapper;
    DatabaseService databaseService;
    KnowledgeDbHelper dbHelper;
    QueryRewriteService queryRewriteService;
    AiSqlService aiSqlService;
    KbWriteQueue kbWriteQueue;
    ChunkService chunkService;
    KbAccessService kbAccessService;
    KbShareRepository kbShareRepository;
    ApplicationEventPublisher eventPublisher;

    KnowledgeService knowledgeService;

    @BeforeEach
    void setup() throws Exception {
        documentRepository = mock(DocumentRepository.class);
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        jobService = mock(JobService.class);
        props = mock(LakeonProperties.class);
        databaseRepository = mock(DatabaseRepository.class);
        computePodManager = mock(ComputePodManager.class);
        objectMapper = new ObjectMapper();
        databaseService = mock(DatabaseService.class);
        dbHelper = mock(KnowledgeDbHelper.class);
        queryRewriteService = mock(QueryRewriteService.class);
        aiSqlService = mock(AiSqlService.class);
        kbWriteQueue = mock(KbWriteQueue.class);
        chunkService = mock(ChunkService.class);
        kbAccessService = mock(KbAccessService.class);
        kbShareRepository = mock(KbShareRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        // Stub props.getKnowledge().getEmbeddingModel() for embedding model resolution.
        LakeonProperties.KnowledgeConfig knowledgeCfg = mock(LakeonProperties.KnowledgeConfig.class);
        when(knowledgeCfg.getEmbeddingModel()).thenReturn("bge-m3");
        when(props.getKnowledge()).thenReturn(knowledgeCfg);

        // Stub props.getDefaults() so the kb-provision background thread doesn't NPE.
        LakeonProperties.DefaultsConfig defaults = mock(LakeonProperties.DefaultsConfig.class);
        when(defaults.getComputeSize()).thenReturn("nano");
        when(defaults.getSuspendTimeout()).thenReturn("300s");
        when(defaults.getStorageLimitGb()).thenReturn(10);
        when(props.getDefaults()).thenReturn(defaults);

        // Stub databaseService.create(...) so the kb-provision background thread can proceed.
        // Return a synthetic db id we control; the poll loop will see this db in RUNNING state
        // (via the databaseRepository.findById stub below) and publish the seed event.
        DatabaseResponse dbResp = mock(DatabaseResponse.class);
        when(dbResp.getId()).thenReturn("db-noop");
        when(dbResp.getPassword()).thenReturn("");
        when(databaseService.create(any(TenantEntity.class), any())).thenReturn(dbResp);

        // Provide a RUNNING DatabaseEntity for the synthetic db id so the background
        // provisioning thread completes successfully and publishes the seed event.
        // Any other id (e.g. src-db-1 used by the TABLE test) returns empty by default.
        DatabaseEntity provisionedDb = new DatabaseEntity();
        provisionedDb.setId("db-noop");
        provisionedDb.setStatus(DatabaseStatus.RUNNING);
        when(databaseRepository.findById("db-noop")).thenReturn(Optional.of(provisionedDb));

        // Simulate JPA assigning an id on save so the seeded schema can reference it,
        // and track saved KBs so findById can return them (the background thread calls
        // findById to flip status to READY and to read tenantId for the event).
        ConcurrentMap<String, KnowledgeBaseEntity> kbStore = new ConcurrentHashMap<>();
        when(knowledgeBaseRepository.save(any(KnowledgeBaseEntity.class))).thenAnswer(inv -> {
            KnowledgeBaseEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId("kb-test-seed-" + System.nanoTime());
            }
            kbStore.put(entity.getId(), entity);
            return entity;
        });
        when(knowledgeBaseRepository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(kbStore.get(inv.<String>getArgument(0))));

        knowledgeService = new KnowledgeService(
                documentRepository, knowledgeBaseRepository, jobService, props,
                databaseRepository, computePodManager, objectMapper, databaseService,
                dbHelper, queryRewriteService, aiSqlService, kbWriteQueue, chunkService,
                kbAccessService, kbShareRepository, eventPublisher);
    }

    @Test
    void createKnowledgeBasePublishesSeedEvent() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tenant-test-schema-seed");
        tenant.setName("test");

        KnowledgeBaseEntity kb = knowledgeService.createKnowledgeBase(
                tenant, "Seed Test KB", "desc",
                KnowledgeBaseType.DOCUMENT, null, null, null);

        assertNotNull(kb);
        assertNotNull(kb.getId());

        // For DOCUMENT-type KBs the schema-seed event is published asynchronously
        // from the kb-provision background thread, AFTER the synthetic database
        // is observed in RUNNING state (see KnowledgeService#provisionKbDatabase).
        // Wait up to 5s for the background publish before asserting payload.
        ArgumentCaptor<KnowledgeBaseCreatedEvent> captor =
                ArgumentCaptor.forClass(KnowledgeBaseCreatedEvent.class);
        verify(eventPublisher, timeout(5000)).publishEvent(captor.capture());
        assertEquals(tenant.getId(), captor.getValue().getTenantId());
        assertEquals(kb.getId(), captor.getValue().getKbId());
    }

    @Test
    void createTableKbAlsoPublishesSeedEvent() throws Exception {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tenant-test-schema-seed-table");
        tenant.setName("test");

        // Source database must exist for the tenant.
        DatabaseEntity srcDb = new DatabaseEntity();
        srcDb.setId("src-db-1");
        srcDb.setTenantId(tenant.getId());
        when(databaseRepository.findByIdAndTenantId("src-db-1", tenant.getId()))
                .thenReturn(Optional.of(srcDb));

        // Stub the JDBC chain used for table-name validation.
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        java.sql.Array sqlArr = mock(java.sql.Array.class);

        when(dbHelper.getComputeConnectionByDbId(tenant.getId(), "src-db-1")).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(conn.createArrayOf(anyString(), any(Object[].class))).thenReturn(sqlArr);
        when(ps.executeQuery()).thenReturn(rs);
        // Return the single table name we pass in, then terminate.
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("table_name")).thenReturn("orders");

        KnowledgeBaseEntity kb = knowledgeService.createKnowledgeBase(
                tenant, "Seed Table KB", "desc",
                KnowledgeBaseType.TABLE, "src-db-1", List.of("orders"), null);

        assertNotNull(kb);
        assertNotNull(kb.getId());

        ArgumentCaptor<KnowledgeBaseCreatedEvent> captor =
                ArgumentCaptor.forClass(KnowledgeBaseCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(tenant.getId(), captor.getValue().getTenantId());
        assertEquals(kb.getId(), captor.getValue().getKbId());
    }
}
