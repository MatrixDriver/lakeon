package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.job.JobService;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.AiSqlService;
import com.lakeon.service.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link KnowledgeService#createKnowledgeBase} seeds a default
 * {@code schema.md} wiki page via {@link WikiService#writeWikiDocument}.
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
    WikiService wikiService;

    KnowledgeService knowledgeService;

    @BeforeEach
    void setup() {
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
        wikiService = mock(WikiService.class);

        // Stub props.getKnowledge().getEmbeddingModel() for embedding model resolution.
        LakeonProperties.KnowledgeConfig knowledgeCfg = mock(LakeonProperties.KnowledgeConfig.class);
        when(knowledgeCfg.getEmbeddingModel()).thenReturn("bge-m3");
        when(props.getKnowledge()).thenReturn(knowledgeCfg);

        // Simulate JPA assigning an id on save so the seeded schema can reference it.
        when(knowledgeBaseRepository.save(any(KnowledgeBaseEntity.class))).thenAnswer(inv -> {
            KnowledgeBaseEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId("kb-test-seed-" + System.nanoTime());
            }
            return entity;
        });

        knowledgeService = new KnowledgeService(
                documentRepository, knowledgeBaseRepository, jobService, props,
                databaseRepository, computePodManager, objectMapper, databaseService,
                dbHelper, queryRewriteService, aiSqlService, kbWriteQueue, chunkService,
                kbAccessService, kbShareRepository, wikiService);
    }

    @Test
    void createKnowledgeBaseSeedsDefaultSchemaPage() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tenant-test-schema-seed");
        tenant.setName("test");

        KnowledgeBaseEntity kb = knowledgeService.createKnowledgeBase(
                tenant, "Seed Test KB", "desc",
                KnowledgeBaseType.DOCUMENT, null, null, null);

        assertNotNull(kb);
        assertNotNull(kb.getId());

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(wikiService).writeWikiDocument(
                eq(tenant.getId()),
                eq(kb.getId()),
                eq("schema.md"),
                eq("KB Schema"),
                contentCaptor.capture());

        String seeded = contentCaptor.getValue();
        assertNotNull(seeded);
        assertTrue(seeded.contains("KB Schema"),
                "seeded content should contain the 'KB Schema' title");
        assertTrue(seeded.contains("Create vs Update Budget"),
                "seeded content should contain the 'Create vs Update Budget' section");
        assertTrue(seeded.contains("Self-maintenance"),
                "seeded content should contain the 'Self-maintenance' section");
    }

    @Test
    void seedFailureIsNonFatal() {
        // If wikiService.writeWikiDocument throws, createKnowledgeBase should still
        // succeed and return the persisted KB.
        doThrow(new RuntimeException("simulated wiki failure"))
                .when(wikiService).writeWikiDocument(
                        any(), any(), eq("schema.md"), any(), any());

        TenantEntity tenant = new TenantEntity();
        tenant.setId("tenant-test-schema-seed-fail");
        tenant.setName("test");

        KnowledgeBaseEntity kb = knowledgeService.createKnowledgeBase(
                tenant, "Seed Fail KB", "desc",
                KnowledgeBaseType.DOCUMENT, null, null, null);

        assertNotNull(kb);
        assertNotNull(kb.getId());
        verify(wikiService).writeWikiDocument(
                any(), any(), eq("schema.md"), any(), any());
    }
}
