package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.job.JobEntity;
import com.lakeon.job.JobService;
import com.lakeon.job.JobStatus;
import com.lakeon.job.JobType;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.AiSqlService;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final JobService jobService;
    private final LakeonProperties props;
    private final DatabaseRepository databaseRepository;
    private final ComputePodManager computePodManager;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final DatabaseService databaseService;
    private final KnowledgeDbHelper dbHelper;
    private final HttpClient httpClient;
    private final QueryRewriteService queryRewriteService;
    private final AiSqlService aiSqlService;
    private final KbWriteQueue kbWriteQueue;

    public KnowledgeService(DocumentRepository documentRepository,
                            KnowledgeBaseRepository knowledgeBaseRepository,
                            JobService jobService,
                            LakeonProperties props,
                            DatabaseRepository databaseRepository,
                            ComputePodManager computePodManager,
                            ObjectMapper objectMapper,
                            DatabaseService databaseService,
                            KnowledgeDbHelper dbHelper,
                            QueryRewriteService queryRewriteService,
                            AiSqlService aiSqlService,
                            KbWriteQueue kbWriteQueue) {
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.jobService = jobService;
        this.props = props;
        this.databaseRepository = databaseRepository;
        this.computePodManager = computePodManager;
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.dbHelper = dbHelper;
        this.queryRewriteService = queryRewriteService;
        this.aiSqlService = aiSqlService;
        this.kbWriteQueue = kbWriteQueue;
        this.restTemplate = new RestTemplate();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Knowledge Base CRUD ──────────────────────────────────────────

    /**
     * Create a KnowledgeBase.
     * For DOCUMENT type: provisions a hidden database asynchronously.
     * For TABLE type: validates source database and table names, sets READY immediately.
     */
    @Transactional
    public KnowledgeBaseEntity createKnowledgeBase(TenantEntity tenant, String name, String description) {
        return createKnowledgeBase(tenant, name, description, KnowledgeBaseType.DOCUMENT, null, null, null);
    }

    @Transactional
    public KnowledgeBaseEntity createKnowledgeBase(TenantEntity tenant, String name, String description,
                                                    KnowledgeBaseType type, String sourceDatabaseId,
                                                    List<String> tableNames, String embeddingModel) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (type == null) {
            type = KnowledgeBaseType.DOCUMENT;
        }

        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setTenantId(tenant.getId());
        kb.setName(name);
        kb.setDescription(description);
        kb.setType(type);
        kb.setDocumentCount(0);

        // Set embedding model: use provided value or fall back to global default
        String resolvedModel = (embeddingModel != null && !embeddingModel.isBlank())
                ? embeddingModel.trim()
                : props.getKnowledge().getEmbeddingModel();
        kb.setEmbeddingModel(resolvedModel);

        if (type == KnowledgeBaseType.TABLE) {
            if (sourceDatabaseId == null || sourceDatabaseId.isBlank()) {
                throw new BadRequestException("source_database_id is required for TABLE type");
            }
            if (tableNames == null || tableNames.isEmpty()) {
                throw new BadRequestException("table_names is required for TABLE type");
            }

            // Validate source database exists and belongs to tenant
            databaseRepository.findByIdAndTenantId(sourceDatabaseId, tenant.getId())
                    .orElseThrow(() -> new BadRequestException("Source database not found: " + sourceDatabaseId));

            // Ensure compute is running for validation
            databaseService.ensureRunning(tenant, sourceDatabaseId);

            // Validate table names by connecting to compute and checking information_schema
            try (Connection conn = dbHelper.getComputeConnectionByDbId(tenant.getId(), sourceDatabaseId);
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ANY(?)")) {
                java.sql.Array arr = conn.createArrayOf("varchar", tableNames.toArray());
                ps.setArray(1, arr);
                Set<String> foundTables = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        foundTables.add(rs.getString("table_name"));
                    }
                }
                List<String> missing = tableNames.stream()
                        .filter(t -> !foundTables.contains(t))
                        .toList();
                if (!missing.isEmpty()) {
                    throw new BadRequestException("Tables not found in source database: " + String.join(", ", missing));
                }
            } catch (BadRequestException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to validate table names for source database {}: {}", sourceDatabaseId, e.getMessage(), e);
                throw new BadRequestException("Failed to validate tables: " + e.getMessage());
            }

            kb.setSourceDatabaseId(sourceDatabaseId);
            kb.setTableNames(tableNames);
            kb.setStatus(KnowledgeBaseStatus.READY);
            knowledgeBaseRepository.save(kb);
            return kb;
        }

        // DOCUMENT type: existing flow
        kb.setStatus(KnowledgeBaseStatus.CREATING);
        knowledgeBaseRepository.save(kb);

        String kbId = kb.getId();
        String internalDbName = kbId.replace("_", "-");

        Thread t = new Thread(() -> provisionKbDatabase(kbId, tenant, internalDbName),
                "kb-provision-" + kbId);
        t.setDaemon(true);
        t.start();

        return kb;
    }

    /**
     * Asynchronously provisions the database for a KB.
     * Uses standard DatabaseService.create (same as user databases).
     * Tags the database with kbId so it's identifiable in the database list.
     */
    private void provisionKbDatabase(String kbId, TenantEntity tenant, String dbName) {
        try {
            log.info("Provisioning database '{}' for knowledge base {}", dbName, kbId);

            com.lakeon.model.dto.CreateDatabaseRequest req = new com.lakeon.model.dto.CreateDatabaseRequest(
                    dbName,
                    props.getDefaults().getComputeSize(),
                    props.getDefaults().getSuspendTimeout(),
                    props.getDefaults().getStorageLimitGb()
            );
            com.lakeon.model.dto.DatabaseResponse dbResp = databaseService.create(tenant, req);
            String dbId = dbResp.getId();
            String dbPassword = dbResp.getPassword();  // plaintext, only available at creation

            // Tag the database with kbId and save password to KB
            databaseRepository.findById(dbId).ifPresent(dbEntity -> {
                dbEntity.setKbId(kbId);
                databaseRepository.save(dbEntity);
            });
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setDbPassword(dbPassword);
                knowledgeBaseRepository.save(kb);
            });

            // Poll until RUNNING (max 5 min)
            long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
            DatabaseStatus lastStatus = DatabaseStatus.CREATING;
            while (System.currentTimeMillis() < deadline) {
                DatabaseEntity dbEntity = databaseRepository.findById(dbId).orElse(null);
                if (dbEntity == null) break;
                lastStatus = dbEntity.getStatus();
                if (lastStatus == DatabaseStatus.RUNNING) {
                    knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                        kb.setDatabaseId(dbId);
                        kb.setStatus(KnowledgeBaseStatus.READY);
                        knowledgeBaseRepository.save(kb);
                    });
                    log.info("Knowledge base {} is READY (database {} host={} user={})",
                             kbId, dbId, dbEntity.getComputeHost(), dbEntity.getDbUser());
                    return;
                }
                if (lastStatus == DatabaseStatus.ERROR) break;
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            String errorMsg = "Database provisioning failed or timed out (last status: " + lastStatus + ")";
            log.error("Knowledge base {} failed: {}", kbId, errorMsg);
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setStatus(KnowledgeBaseStatus.FAILED);
                kb.setError(errorMsg);
                knowledgeBaseRepository.save(kb);
            });

        } catch (Exception e) {
            log.error("Failed to provision database for knowledge base {}: {}", kbId, e.getMessage(), e);
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setStatus(KnowledgeBaseStatus.FAILED);
                kb.setError(e.getMessage());
                knowledgeBaseRepository.save(kb);
            });
        }
    }

    public List<KnowledgeBaseEntity> listKnowledgeBases(String tenantId) {
        return knowledgeBaseRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public KnowledgeBaseEntity getKnowledgeBase(String tenantId, String kbId) {
        return knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
    }

    @Transactional
    public KnowledgeBaseEntity deleteKnowledgeBase(String tenantId, String kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));

        // Cancel any pending/running write tasks for this KB first
        kbWriteQueue.cancelTasksForKb(kbId);

        // Delete all documents in this KB (OBS files, jobs, chunks)
        List<DocumentEntity> docs = documentRepository.findAllByKbId(kbId);
        for (DocumentEntity doc : docs) {
            try {
                deleteDocument(tenantId, doc.getId());
            } catch (Exception e) {
                log.warn("Failed to delete document {} during KB deletion: {}", doc.getId(), e.getMessage());
            }
        }

        // Delete the hidden database (best-effort)
        if (kb.getDatabaseId() != null) {
            try {
                DatabaseEntity dbEntity = databaseRepository.findById(kb.getDatabaseId()).orElse(null);
                if (dbEntity != null) {
                    TenantEntity tenantRef = new TenantEntity();
                    tenantRef.setId(tenantId);
                    databaseService.delete(tenantRef, kb.getDatabaseId());
                }
            } catch (Exception e) {
                log.warn("Failed to delete hidden database {} for KB {}: {}", kb.getDatabaseId(), kbId, e.getMessage());
            }
        }

        knowledgeBaseRepository.delete(kb);
        return kb;
    }

    // ── Document operations (updated to use kbId) ───────────────────

    /**
     * Generate a presigned PUT URL for uploading a document to OBS.
     * Accepts kbId; resolves the underlying databaseId from the KB.
     */
    @Transactional
    public Map<String, Object> generateUploadUrl(TenantEntity tenant, String kbId, String filename, List<String> tags) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));

        if (kb.getStatus() != KnowledgeBaseStatus.READY) {
            throw new BadRequestException("Knowledge base is not ready. Current status: " + kb.getStatus());
        }

        String databaseId = kb.getDatabaseId();
        if (databaseId == null) {
            throw new BadRequestException("Knowledge base has no backing database");
        }

        // Detect format from extension
        String format = detectFormat(filename);
        if (format == null) {
            throw new BadRequestException("Unsupported file format. Supported: .pdf, .docx, .doc, .xlsx, .xls, .pptx, .epub, .html, .md, .txt");
        }

        // Create DocumentEntity in PENDING status
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId(tenant.getId());
        doc.setDatabaseId(databaseId);
        doc.setKbId(kbId);
        doc.setFilename(filename);
        doc.setFormat(format);
        doc.setStatus(DocumentStatus.PENDING);
        if (tags != null && !tags.isEmpty()) {
            doc.setTags(tags);
        }
        documentRepository.save(doc);

        // Generate OBS key
        String obsKey = "knowledge/" + tenant.getId() + "/" + kbId + "/" + doc.getId() + "/" + filename;

        // Generate presigned PUT URL
        int expireSeconds = props.getKnowledge().getPresignExpireSeconds();
        String uploadUrl;
        try (S3Presigner presigner = buildPresigner()) {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(obsKey)
                    .build();
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expireSeconds))
                    .putObjectRequest(putRequest)
                    .build();
            uploadUrl = presigner.presignPutObject(presignRequest).url().toString();
        }

        // Update doc with obsKey
        doc.setObsKey(obsKey);
        documentRepository.save(doc);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document_id", doc.getId());
        result.put("upload_url", uploadUrl);
        result.put("obs_key", obsKey);
        result.put("expires_in", expireSeconds);
        return result;
    }

    /**
     * Trigger document processing by submitting via KbWriteQueue.
     * The queue ensures the kb-write pod is ready and submits the job pod.
     */
    public DocumentEntity processDocument(TenantEntity tenant, String documentId) {
        DocumentEntity doc = documentRepository.findByIdAndTenantId(documentId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));

        if (doc.getStatus() != DocumentStatus.PENDING) {
            throw new BadRequestException("Document is not in PENDING status, current: " + doc.getStatus());
        }

        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(doc.getKbId(), tenant.getId())
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + doc.getKbId()));

        // Build job params — connstr will be overridden by KbWriteQueue to point to kb-write pod
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("document_id", doc.getId());
        params.put("tenant_id", tenant.getId());
        params.put("kb_id", doc.getKbId());
        params.put("obs_key", doc.getObsKey());
        params.put("format", doc.getFormat());
        params.put("filename", doc.getFilename());
        params.put("database_connstr", "placeholder"); // overridden by KbWriteQueue
        params.put("embedding_api_url", props.getKnowledge().getEmbeddingApiUrl());
        params.put("embedding_api_key", props.getKnowledge().getEmbeddingApiKey());
        params.put("embedding_model", kb.getEmbeddingModel() != null
                ? kb.getEmbeddingModel() : props.getKnowledge().getEmbeddingModel());

        KbWriteTaskEntity task = kbWriteQueue.submit(tenant.getId(), doc.getKbId(),
                kb.getDatabaseId(), KbWriteTaskType.DOCUMENT_PARSE, params);

        // Update doc status
        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);

        return doc;
    }

    /**
     * Batch generate presigned upload URLs for multiple files (max 20).
     * Returns list of {document_id, filename, upload_url, expires_in}.
     */
    @Transactional
    public List<Map<String, Object>> batchGenerateUploadUrls(TenantEntity tenant, String kbId,
            List<Map<String, Object>> files) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("files is required");
        }
        if (files.size() > 20) {
            throw new BadRequestException("Maximum 20 files per batch");
        }

        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        if (kb.getStatus() != KnowledgeBaseStatus.READY) {
            throw new BadRequestException("Knowledge base is not ready. Current status: " + kb.getStatus());
        }
        String databaseId = kb.getDatabaseId();
        if (databaseId == null) {
            throw new BadRequestException("Knowledge base has no backing database");
        }

        int expireSeconds = props.getKnowledge().getPresignExpireSeconds();
        List<Map<String, Object>> results = new ArrayList<>();

        try (S3Presigner presigner = buildPresigner()) {
            for (Map<String, Object> fileSpec : files) {
                String filename = (String) fileSpec.get("filename");
                if (filename == null || filename.isBlank()) {
                    throw new BadRequestException("filename is required for each file");
                }
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) fileSpec.get("tags");

                String format = detectFormat(filename);
                if (format == null) {
                    throw new BadRequestException("Unsupported format for file: " + filename);
                }

                DocumentEntity doc = new DocumentEntity();
                doc.setTenantId(tenant.getId());
                doc.setDatabaseId(databaseId);
                doc.setKbId(kbId);
                doc.setFilename(filename);
                doc.setFormat(format);
                doc.setStatus(DocumentStatus.PENDING);
                if (tags != null && !tags.isEmpty()) {
                    doc.setTags(tags);
                }
                documentRepository.save(doc);

                String obsKey = "knowledge/" + tenant.getId() + "/" + kbId + "/" + doc.getId() + "/" + filename;
                doc.setObsKey(obsKey);
                documentRepository.save(doc);

                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(props.getObs().getBucket())
                        .key(obsKey)
                        .build();
                PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(expireSeconds))
                        .putObjectRequest(putRequest)
                        .build();
                String uploadUrl = presigner.presignPutObject(presignRequest).url().toString();

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("document_id", doc.getId());
                item.put("filename", filename);
                item.put("upload_url", uploadUrl);
                item.put("expires_in", expireSeconds);
                results.add(item);
            }
        }

        return results;
    }

    /**
     * Batch process multiple documents with a single job pod (max 20).
     * All documents must be PENDING and belong to the same KB.
     */
    public Map<String, Object> batchProcessDocuments(TenantEntity tenant, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BadRequestException("document_ids is required");
        }
        if (documentIds.size() > 20) {
            throw new BadRequestException("Maximum 20 documents per batch");
        }

        List<DocumentEntity> docs = new ArrayList<>();
        String kbId = null;
        String databaseId = null;

        for (String docId : documentIds) {
            DocumentEntity doc = documentRepository.findByIdAndTenantId(docId, tenant.getId())
                    .orElseThrow(() -> new NotFoundException("Document not found: " + docId));
            if (doc.getStatus() != DocumentStatus.PENDING) {
                throw new BadRequestException("Document " + docId + " is not PENDING (status: " + doc.getStatus() + ")");
            }
            if (kbId == null) {
                kbId = doc.getKbId();
            } else if (!kbId.equals(doc.getKbId())) {
                throw new BadRequestException("All documents must belong to the same knowledge base");
            }
            docs.add(doc);
        }

        final String finalKbId = kbId;
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(finalKbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + finalKbId));
        databaseId = kb.getDatabaseId();

        // Build per-document params list
        List<Map<String, Object>> docParams = new ArrayList<>();
        for (DocumentEntity doc : docs) {
            Map<String, Object> dp = new LinkedHashMap<>();
            dp.put("document_id", doc.getId());
            dp.put("obs_key", doc.getObsKey());
            dp.put("format", doc.getFormat());
            dp.put("filename", doc.getFilename());
            dp.put("kb_id", doc.getKbId());
            dp.put("tenant_id", tenant.getId());
            docParams.add(dp);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("document_ids", documentIds);
        params.put("documents", docParams);
        params.put("tenant_id", tenant.getId());
        params.put("kb_id", kbId);
        params.put("database_connstr", "placeholder"); // overridden by KbWriteQueue
        params.put("embedding_api_url", props.getKnowledge().getEmbeddingApiUrl());
        params.put("embedding_api_key", props.getKnowledge().getEmbeddingApiKey());
        params.put("embedding_model", kb.getEmbeddingModel() != null
                ? kb.getEmbeddingModel() : props.getKnowledge().getEmbeddingModel());

        KbWriteTaskEntity task = kbWriteQueue.submit(tenant.getId(), kbId,
                databaseId, KbWriteTaskType.BATCH_DOCUMENT_PARSE, params);

        // Mark all docs as PROCESSING
        for (DocumentEntity doc : docs) {
            doc.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(doc);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", task.getId());
        result.put("document_count", docs.size());
        return result;
    }

    /**
     * Get a document, syncing status from its job if still PROCESSING.
     */
    public DocumentEntity getDocument(String tenantId, String documentId) {
        DocumentEntity doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));

        if (doc.getStatus() == DocumentStatus.PROCESSING && doc.getJobId() != null) {
            syncDocumentStatusFromJob(doc);
        }

        return doc;
    }

    /**
     * Get processing progress for a document (from its job's RUNNING result).
     * Returns null if no progress info available.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDocumentProgress(DocumentEntity doc) {
        if (doc.getStatus() != DocumentStatus.PROCESSING || doc.getJobId() == null) {
            return null;
        }
        try {
            JobEntity job = jobService.getJob(doc.getTenantId(), doc.getJobId());
            if (job.getResult() != null) {
                Map<String, Object> result = objectMapper.readValue(job.getResult(), Map.class);
                if (result.containsKey("progress")) {
                    return result;
                }
            }
        } catch (Exception e) {
            // ignore — progress is best-effort
        }
        return null;
    }

    /**
     * List documents, optionally filtered by kbId (preferred) or databaseId (legacy).
     */
    public List<DocumentEntity> listDocuments(String tenantId, String kbId, String databaseId) {
        if (kbId != null && !kbId.isBlank()) {
            return documentRepository.findAllByTenantIdAndKbIdOrderByCreatedAtDesc(tenantId, kbId);
        }
        if (databaseId != null && !databaseId.isBlank()) {
            return documentRepository.findAllByTenantIdAndDatabaseIdOrderByCreatedAtDesc(tenantId, databaseId);
        }
        return documentRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * Delete a document: remove OBS file, cancel job, delete chunks, delete entity.
     */
    @Transactional
    public DocumentEntity deleteDocument(String tenantId, String documentId) {
        DocumentEntity doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));

        // Delete OBS file (best-effort)
        if (doc.getObsKey() != null) {
            try {
                deleteObsFile(doc.getObsKey());
            } catch (Exception e) {
                log.warn("Failed to delete OBS file {}: {}", doc.getObsKey(), e.getMessage());
            }
        }

        // Delete fulltext OBS file (best-effort)
        if (doc.getKbId() != null) {
            try {
                String fulltextKey = String.format("knowledge/%s/%s/%s/fulltext.md",
                        tenantId, doc.getKbId(), documentId);
                deleteObsFile(fulltextKey);
            } catch (Exception e) {
                log.warn("Failed to delete fulltext OBS file for doc {}: {}", documentId, e.getMessage());
            }
        }

        // Cancel running job if any
        if (doc.getJobId() != null && doc.getStatus() == DocumentStatus.PROCESSING) {
            try {
                jobService.cancelJob(tenantId, doc.getJobId());
            } catch (Exception e) {
                log.warn("Failed to cancel job {} for document {}: {}", doc.getJobId(), documentId, e.getMessage());
            }
        }

        // Delete chunks via KbWriteQueue (best-effort, async)
        if (doc.getDatabaseId() != null && doc.getKbId() != null) {
            try {
                kbWriteQueue.submit(tenantId, doc.getKbId(), doc.getDatabaseId(),
                        KbWriteTaskType.DELETE_DOCUMENT_CHUNKS,
                        Map.of("document_id", documentId));
            } catch (Exception e) {
                log.warn("Failed to submit chunk deletion for document {}: {}", documentId, e.getMessage());
            }
        }

        // Delete from metadata DB
        documentRepository.delete(doc);

        return doc;
    }

    /**
     * Hybrid search: vector + full-text (zhparser tsvector) with Reciprocal Rank Fusion.
     * Accepts kbId; resolves the underlying databaseId from the KB.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(String tenantId, String kbId, String query,
                                      int topK, List<String> documentIds, List<String> tags,
                                      boolean rerank, List<Map<String, String>> conversationHistory) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("Query must not be empty");
        }
        if (topK <= 0) topK = 5;
        if (topK > 50) topK = 50;

        // Query rewrite with conversation history
        String searchQuery = query;
        String rewrittenQuery = null;
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            rewrittenQuery = queryRewriteService.rewriteQuery(query, conversationHistory);
            if (!rewrittenQuery.equals(query)) {
                searchQuery = rewrittenQuery;
            } else {
                rewrittenQuery = null; // no change, don't include in response
            }
        }

        // Tag filtering: resolve matching document IDs and merge with explicit documentIds
        List<String> filteredDocIds = documentIds;
        if (tags != null && !tags.isEmpty()) {
            List<String> tagFilteredIds = documentRepository
                .findIdsByKbIdAndTenantIdAndTagsContaining(kbId, tenantId, tags.toArray(new String[0]));
            if (filteredDocIds != null) {
                tagFilteredIds.retainAll(new HashSet<>(filteredDocIds));
            }
            filteredDocIds = tagFilteredIds;
            if (filteredDocIds.isEmpty()) {
                Map<String, Object> emptyResult = new LinkedHashMap<>();
                emptyResult.put("results", Collections.emptyList());
                if (rewrittenQuery != null) {
                    emptyResult.put("rewritten_query", rewrittenQuery);
                }
                return emptyResult;
            }
        }

        // Get query embedding from embedding service (use searchQuery which may be rewritten)
        KnowledgeBaseEntity kbEntity = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        String embModel = kbEntity.getEmbeddingModel() != null
                ? kbEntity.getEmbeddingModel() : props.getKnowledge().getEmbeddingModel();
        List<Double> embedding = getQueryEmbedding(searchQuery, embModel);
        String vectorStr = embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));

        // Resolve user PG connection via dbHelper
        String connstr = dbHelper.resolveConnstr(tenantId, kbId);
        String jdbcUrl = dbHelper.connstrToJdbc(connstr);
        log.warn("Knowledge search JDBC URL: {}", jdbcUrl);

        // Build SQL with optional document_id filter
        String docFilter = "";
        if (filteredDocIds != null && !filteredDocIds.isEmpty()) {
            docFilter = " AND document_id = ANY(?)";
        }

        // Extract user:pass from connstr via dbHelper
        String pgUser = dbHelper.extractUser(connstr);
        String pgPass = dbHelper.extractPassword(connstr);

        // Use 'chinese' text search config (zhparser) if available, fallback to 'simple'
        String tsCfg = "simple";
        try (Connection probeConn = DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
             PreparedStatement probePs = probeConn.prepareStatement(
                     "SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese'")) {
            try (ResultSet probeRs = probePs.executeQuery()) {
                if (probeRs.next()) {
                    tsCfg = "chinese";
                }
            }
        } catch (Exception e) {
            log.warn("Failed to probe text search config, using 'simple': {}", e.getMessage());
        }
        String sql = "WITH semantic AS (" +
                "  SELECT id, content, metadata, level," +
                "         1 - (embedding <=> ?::vector) AS score," +
                "         ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rank" +
                "  FROM knowledge_chunks" +
                "  WHERE 1=1 AND level IN (0, 1)" + docFilter +
                "  ORDER BY embedding <=> ?::vector" +
                "  LIMIT 20" +
                "), fts AS (" +
                "  SELECT id, content, metadata, level," +
                "         ts_rank_cd(to_tsvector('" + tsCfg + "', content), plainto_tsquery('" + tsCfg + "', ?)) AS score," +
                "         ROW_NUMBER() OVER (ORDER BY ts_rank_cd(to_tsvector('" + tsCfg + "', content), plainto_tsquery('" + tsCfg + "', ?)) DESC) AS rank" +
                "  FROM knowledge_chunks" +
                "  WHERE to_tsvector('" + tsCfg + "', content) @@ plainto_tsquery('" + tsCfg + "', ?) AND level IN (0, 1)" + docFilter +
                "  LIMIT 20" +
                ") " +
                "SELECT COALESCE(s.id, f.id) AS id," +
                "       COALESCE(s.content, f.content) AS content," +
                "       COALESCE(s.metadata, f.metadata)::text AS metadata," +
                "       COALESCE(s.level, f.level) AS level," +
                "       COALESCE(1.0/(60+s.rank), 0) + COALESCE(1.0/(60+f.rank), 0) AS rrf_score" +
                " FROM semantic s FULL OUTER JOIN fts f ON s.id = f.id" +
                " ORDER BY rrf_score DESC" +
                " LIMIT ?";

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            // semantic CTE params
            ps.setString(idx++, vectorStr); // embedding <=> ?::vector (score)
            ps.setString(idx++, vectorStr); // embedding <=> ?::vector (order/row_number)
            if (filteredDocIds != null && !filteredDocIds.isEmpty()) {
                java.sql.Array docArray = conn.createArrayOf("varchar", filteredDocIds.toArray());
                ps.setArray(idx++, docArray);
            }
            ps.setString(idx++, vectorStr); // embedding <=> ?::vector (limit order)
            // fts CTE params (zhparser tsvector)
            ps.setString(idx++, searchQuery); // ts_rank_cd score
            ps.setString(idx++, searchQuery); // ts_rank_cd in ROW_NUMBER
            ps.setString(idx++, searchQuery); // WHERE plainto_tsquery
            if (filteredDocIds != null && !filteredDocIds.isEmpty()) {
                java.sql.Array docArray = conn.createArrayOf("varchar", filteredDocIds.toArray());
                ps.setArray(idx++, docArray);
            }
            // final LIMIT
            ps.setInt(idx++, topK);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("content", rs.getString("content"));
                    row.put("score", rs.getDouble("rrf_score"));
                    row.put("level", rs.getInt("level"));
                    String metaStr = rs.getString("metadata");
                    if (metaStr != null) {
                        try {
                            row.put("metadata", objectMapper.readValue(metaStr, Map.class));
                        } catch (Exception e) {
                            row.put("metadata", metaStr);
                        }
                    }
                    results.add(row);
                }
            }
        } catch (Exception e) {
            log.error("Search failed for kb {}: {}", kbId, e.getMessage(), e);
            throw new RuntimeException("Search failed: " + e.getMessage(), e);
        }

        if (rerank && props.getKnowledge().getRerank().isEnabled() && !results.isEmpty()) {
            results = rerankResults(searchQuery, results, topK);
        }

        Map<String, Object> searchResult = new LinkedHashMap<>();
        searchResult.put("results", results);
        if (rewrittenQuery != null) {
            searchResult.put("rewritten_query", rewrittenQuery);
        }
        return searchResult;
    }

    // ── TABLE KB operations ────────────────────────────────────────

    /**
     * Search a TABLE type KB: generate SQL from natural language, execute on user compute, return results.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchTable(String tenantId, String kbId, String query, String modelId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));

        if (kb.getType() != KnowledgeBaseType.TABLE) {
            throw new BadRequestException("Knowledge base is not TABLE type");
        }
        if (kb.getSourceDatabaseId() == null) {
            throw new BadRequestException("Knowledge base has no source database");
        }

        // 1. Get schema info from source database
        String schemaInfo = buildSchemaInfo(tenantId, kb);

        // 2. Call AiSqlService to generate SQL
        Map<String, Object> aiResult = aiSqlService.generateSql(query, schemaInfo, modelId);
        if (aiResult.containsKey("error")) {
            return aiResult;
        }

        String sql = (String) aiResult.get("sql");
        if (sql == null || sql.isBlank()) {
            return Map.of("error", "AI service returned empty SQL");
        }

        // 3. Validate: only SELECT allowed
        String trimmedSql = sql.trim();
        // Remove leading comments or whitespace, check first keyword
        String sqlUpper = trimmedSql.replaceAll("^/\\*.*?\\*/\\s*", "").replaceAll("^--[^\n]*\n\\s*", "").trim().toUpperCase();
        if (!sqlUpper.startsWith("SELECT") && !sqlUpper.startsWith("WITH")) {
            return Map.of("error", "Only SELECT queries are allowed, got: " + sqlUpper.split("\\s+")[0]);
        }

        // 4. Execute SQL on user compute
        try (Connection conn = dbHelper.getComputeConnectionByDbId(tenantId, kb.getSourceDatabaseId());
             PreparedStatement ps = conn.prepareStatement(trimmedSql)) {

            // Set a query timeout to prevent long-running queries
            ps.setQueryTimeout(30);

            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<List<Object>> rows = new ArrayList<>();
                int rowLimit = 200;
                while (rs.next() && rows.size() < rowLimit) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "sql");
                result.put("sql", trimmedSql);
                result.put("columns", columns);
                result.put("rows", rows);
                result.put("row_count", rows.size());
                result.put("model", aiResult.get("model"));
                result.put("input_tokens", aiResult.get("input_tokens"));
                result.put("output_tokens", aiResult.get("output_tokens"));
                return result;
            }
        } catch (Exception e) {
            log.error("Table search SQL execution failed for kb {}: {}", kbId, e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("type", "sql");
            errorResult.put("sql", trimmedSql);
            errorResult.put("error", "SQL execution failed: " + e.getMessage());
            errorResult.put("model", aiResult.get("model"));
            errorResult.put("input_tokens", aiResult.get("input_tokens"));
            errorResult.put("output_tokens", aiResult.get("output_tokens"));
            return errorResult;
        }
    }

    /**
     * Get table schema info for a TABLE type KB.
     */
    public List<Map<String, Object>> getTableSchema(String tenantId, String kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));

        if (kb.getType() != KnowledgeBaseType.TABLE) {
            throw new BadRequestException("Knowledge base is not TABLE type");
        }
        if (kb.getSourceDatabaseId() == null) {
            throw new BadRequestException("Knowledge base has no source database");
        }

        return fetchTableSchemas(tenantId, kb.getSourceDatabaseId(), kb.getTableNames());
    }

    private String buildSchemaInfo(String tenantId, KnowledgeBaseEntity kb) {
        List<Map<String, Object>> schemas = fetchTableSchemas(tenantId, kb.getSourceDatabaseId(), kb.getTableNames());
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> table : schemas) {
            sb.append("Table: ").append(table.get("table_name")).append("\n");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> columns = (List<Map<String, String>>) table.get("columns");
            sb.append("Columns:\n");
            for (Map<String, String> col : columns) {
                sb.append("  - ").append(col.get("name")).append(" (").append(col.get("type")).append(")\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> fetchTableSchemas(String tenantId, String databaseId, List<String> tableNames) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dbHelper.getComputeConnectionByDbId(tenantId, databaseId);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT table_name, column_name, data_type FROM information_schema.columns " +
                     "WHERE table_schema = 'public' AND table_name = ANY(?) ORDER BY table_name, ordinal_position")) {
            java.sql.Array arr = conn.createArrayOf("varchar", tableNames.toArray());
            ps.setArray(1, arr);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    String colName = rs.getString("column_name");
                    String dataType = rs.getString("data_type");
                    grouped.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(Map.of("name", colName, "type", dataType));
                }
                for (Map.Entry<String, List<Map<String, String>>> entry : grouped.entrySet()) {
                    Map<String, Object> tableSchema = new LinkedHashMap<>();
                    tableSchema.put("table_name", entry.getKey());
                    tableSchema.put("columns", entry.getValue());
                    result.add(tableSchema);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch table schemas for database {}: {}", databaseId, e.getMessage(), e);
            throw new RuntimeException("Failed to fetch table schemas: " + e.getMessage(), e);
        }
        return result;
    }

    // ── Internal helpers ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rerankResults(String query,
            List<Map<String, Object>> results, int topK) {
        try {
            String url = props.getKnowledge().getRerank().getUrl();
            List<String> passages = results.stream()
                    .map(r -> (String) r.get("content")).toList();

            Map<String, Object> reqBody = Map.of("query", query, "passages", passages, "top_k", topK);
            String jsonBody = objectMapper.writeValueAsString(reqBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Rerank service returned status {}: {}", response.statusCode(), response.body());
                return results;
            }

            Map<String, Object> respBody = objectMapper.readValue(response.body(), Map.class);
            List<Integer> rankings = ((List<Number>) respBody.get("rankings")).stream()
                    .map(Number::intValue).toList();
            List<Double> scores = ((List<Number>) respBody.get("scores")).stream()
                    .map(Number::doubleValue).toList();

            List<Map<String, Object>> reranked = new ArrayList<>();
            for (int i = 0; i < rankings.size(); i++) {
                int idx = rankings.get(i);
                if (idx < 0 || idx >= results.size()) continue;
                Map<String, Object> row = new LinkedHashMap<>(results.get(idx));
                row.put("rrf_score", row.get("score"));
                row.put("score", scores.get(i));
                reranked.add(row);
            }
            log.info("Reranked {} results for query (top_k={})", reranked.size(), topK);
            return reranked;

        } catch (Exception e) {
            log.warn("Rerank failed, returning original results: {}", e.getMessage());
            return results;
        }
    }

    private void syncDocumentStatusFromJob(DocumentEntity doc) {
        try {
            JobEntity job = jobService.getJob(doc.getTenantId(), doc.getJobId());
            if (job.getStatus() == JobStatus.SUCCEEDED) {
                doc.setStatus(DocumentStatus.READY);
                // Try to extract chunks_count from job result
                if (job.getResult() != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(job.getResult(), Map.class);
                        Object chunks = result.get("chunks_count");
                        if (chunks instanceof Number) {
                            doc.setChunksCount(((Number) chunks).intValue());
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse job result for chunks_count: {}", e.getMessage());
                    }
                }
                documentRepository.save(doc);

                // Increment KB document count
                if (doc.getKbId() != null) {
                    knowledgeBaseRepository.findById(doc.getKbId()).ifPresent(kb -> {
                        kb.setDocumentCount((kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) + 1);
                        knowledgeBaseRepository.save(kb);
                    });
                }
            } else if (job.getStatus() == JobStatus.FAILED) {
                doc.setStatus(DocumentStatus.FAILED);
                doc.setError(job.getError());
                documentRepository.save(doc);
            }
        } catch (Exception e) {
            log.debug("Failed to sync document status from job: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Double> getQueryEmbedding(String query, String model) {
        String apiUrl = props.getKnowledge().getEmbeddingApiUrl();
        String apiKey = props.getKnowledge().getEmbeddingApiKey();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        // OpenAI-compatible embedding API format (硅基流动/OpenAI)
        Map<String, Object> body = Map.of(
                "model", model,
                "input", List.of(query),
                "encoding_format", "float"
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        Map<String, Object> response = restTemplate.postForObject(apiUrl, request, Map.class);
        if (response == null || !response.containsKey("data")) {
            throw new RuntimeException("Failed to get embedding: empty response from embedding API");
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("Failed to get embedding: no data returned");
        }
        return (List<Double>) data.get(0).get("embedding");
    }

    private S3Presigner buildPresigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of("cn-north-4"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
                .build();
    }

    private void deleteObsFile(String obsKey) {
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of("cn-north-4"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
                .build();
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(obsKey)
                    .build());
            log.info("Deleted OBS file: {}", obsKey);
        } finally {
            s3.close();
        }
    }

    private String detectFormat(String filename) {
        if (filename == null) return null;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".doc")) return "DOC";
        if (lower.endsWith(".xlsx")) return "XLSX";
        if (lower.endsWith(".xls")) return "XLS";
        if (lower.endsWith(".xlsm")) return "XLSM";
        if (lower.endsWith(".pptx")) return "PPTX";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MARKDOWN";
        if (lower.endsWith(".txt")) return "TEXT";
        if (lower.endsWith(".epub")) return "EPUB";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML";
        return null;
    }

    public String detectFormatPublic(String filename) {
        return detectFormat(filename);
    }

    public void deleteDocumentInternal(DocumentEntity doc) {
        if (doc.getObsKey() != null) {
            try { deleteObsFile(doc.getObsKey()); } catch (Exception e) {
                log.warn("Failed to delete OBS file {}: {}", doc.getObsKey(), e.getMessage());
            }
        }
        documentRepository.delete(doc);
    }

    /**
     * Get all READY document IDs for a KB.
     */
    public List<String> getAllReadyDocumentIds(String tenantId, String kbId) {
        return documentRepository.findAllByKbId(kbId)
                .stream()
                .filter(d -> d.getStatus() == DocumentStatus.READY)
                .map(DocumentEntity::getId)
                .toList();
    }

    /**
     * Get the compute connection string for a KB's backing database.
     */
    public String getComputeConnstr(String tenantId, String kbId) {
        return dbHelper.resolveConnstr(tenantId, kbId);
    }

    /**
     * Fetch the KB-level summary (level=2, document_id='__kb_summary__') from knowledge_chunks.
     */
    public String getKbSummary(String connstr) {
        String jdbcUrl = dbHelper.connstrToJdbc(connstr);
        String pgUser = dbHelper.extractUser(connstr);
        String pgPass = dbHelper.extractPassword(connstr);
        String sql = "SELECT content FROM knowledge_chunks " +
                "WHERE document_id = '__kb_summary__' AND level = 2 LIMIT 1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("content");
            return null;
        } catch (Exception e) {
            log.warn("Failed to get KB summary: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetch the document-level summary (level=1) for a given document from knowledge_chunks.
     */
    public String getDocumentSummary(String connstr, String docId) {
        String jdbcUrl = dbHelper.connstrToJdbc(connstr);
        String pgUser = dbHelper.extractUser(connstr);
        String pgPass = dbHelper.extractPassword(connstr);
        String sql = "SELECT content FROM knowledge_chunks WHERE document_id = ? AND level = 1 LIMIT 1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("content");
            return null;
        } catch (Exception e) {
            log.warn("Failed to get document summary for doc {}: {}", docId, e.getMessage());
            return null;
        }
    }
}
