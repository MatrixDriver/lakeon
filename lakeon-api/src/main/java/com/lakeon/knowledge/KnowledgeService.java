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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    public KnowledgeService(DocumentRepository documentRepository,
                            KnowledgeBaseRepository knowledgeBaseRepository,
                            JobService jobService,
                            LakeonProperties props,
                            DatabaseRepository databaseRepository,
                            ComputePodManager computePodManager,
                            ObjectMapper objectMapper,
                            DatabaseService databaseService) {
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.jobService = jobService;
        this.props = props;
        this.databaseRepository = databaseRepository;
        this.computePodManager = computePodManager;
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.restTemplate = new RestTemplate();
    }

    // ── Knowledge Base CRUD ──────────────────────────────────────────

    /**
     * Create a KnowledgeBase and provision a hidden database for it.
     * The database name is derived from the KB id after @PrePersist.
     */
    @Transactional
    public KnowledgeBaseEntity createKnowledgeBase(TenantEntity tenant, String name, String description) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }

        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setTenantId(tenant.getId());
        kb.setName(name);
        kb.setDescription(description);
        kb.setStatus(KnowledgeBaseStatus.CREATING);
        kb.setDocumentCount(0);
        knowledgeBaseRepository.save(kb);

        // Provision a hidden database via DatabaseService asynchronously.
        // For MVP, we create the database record directly using DatabaseService.create()
        // on a separate thread so we don't block the HTTP response.
        // The KB id is available after save (generated in @PrePersist).
        String kbId = kb.getId();
        String internalDbName = kbId.replace("_", "-");  // kb_487307dad1c9 → kb-487307dad1c9

        // Spawn async provisioning in a background thread
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

            // Tag the database with kbId
            databaseRepository.findById(dbId).ifPresent(dbEntity -> {
                dbEntity.setKbId(kbId);
                databaseRepository.save(dbEntity);
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
    public Map<String, Object> generateUploadUrl(TenantEntity tenant, String kbId, String filename) {
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
            throw new BadRequestException("Unsupported file format. Supported: .pdf, .docx, .md, .txt");
        }

        // Create DocumentEntity in PENDING status
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId(tenant.getId());
        doc.setDatabaseId(databaseId);
        doc.setKbId(kbId);
        doc.setFilename(filename);
        doc.setFormat(format);
        doc.setStatus(DocumentStatus.PENDING);
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
     * Trigger document processing by submitting a DOCUMENT_PARSE job.
     */
    @Transactional
    public DocumentEntity processDocument(TenantEntity tenant, String documentId) {
        DocumentEntity doc = documentRepository.findByIdAndTenantId(documentId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));

        if (doc.getStatus() != DocumentStatus.PENDING) {
            throw new BadRequestException("Document is not in PENDING status, current: " + doc.getStatus());
        }

        // Resolve database connection string
        String connstr = resolveComputeConnstr(doc.getDatabaseId(), tenant.getId());

        // Build job params
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("document_id", doc.getId());
        params.put("tenant_id", tenant.getId());
        params.put("kb_id", doc.getKbId());
        params.put("obs_key", doc.getObsKey());
        params.put("format", doc.getFormat());
        params.put("filename", doc.getFilename());
        params.put("database_connstr", connstr);
        params.put("embedding_api_url", props.getKnowledge().getEmbeddingApiUrl());
        params.put("embedding_api_key", props.getKnowledge().getEmbeddingApiKey());
        params.put("embedding_model", props.getKnowledge().getEmbeddingModel());

        // Submit DOCUMENT_PARSE job
        JobEntity job = jobService.submitJob(tenant, JobType.DOCUMENT_PARSE, params);

        // Update doc status
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setJobId(job.getId());
        documentRepository.save(doc);

        return doc;
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

        // Cancel running job if any
        if (doc.getJobId() != null && doc.getStatus() == DocumentStatus.PROCESSING) {
            try {
                jobService.cancelJob(tenantId, doc.getJobId());
            } catch (Exception e) {
                log.warn("Failed to cancel job {} for document {}: {}", doc.getJobId(), documentId, e.getMessage());
            }
        }

        // Delete chunks from user PG (best-effort)
        if (doc.getDatabaseId() != null) {
            try {
                String dConnstr = resolveComputeConnstr(doc.getDatabaseId(), tenantId);
                String dJdbcUrl = connstrToJdbc(dConnstr);
                String dUser = "cloud_admin", dPass = "cloud-admin-internal";
                String dRaw = dConnstr.replaceFirst("^postgresql://", "");
                int dAt = dRaw.indexOf('@');
                if (dAt >= 0) { String ui = dRaw.substring(0, dAt); int c = ui.indexOf(':'); if (c >= 0) { dUser = ui.substring(0, c); dPass = ui.substring(c + 1); } }
                try (Connection conn = DriverManager.getConnection(dJdbcUrl, dUser, dPass);
                     PreparedStatement ps = conn.prepareStatement("DELETE FROM knowledge_chunks WHERE document_id = ?")) {
                    ps.setString(1, documentId);
                    int deleted = ps.executeUpdate();
                    log.info("Deleted {} chunks for document {}", deleted, documentId);
                }
            } catch (Exception e) {
                log.warn("Failed to delete chunks for document {}: {}", documentId, e.getMessage());
            }
        }

        // Delete from metadata DB
        documentRepository.delete(doc);

        return doc;
    }

    /**
     * Hybrid search: vector + BM25 with Reciprocal Rank Fusion.
     * Accepts kbId; resolves the underlying databaseId from the KB.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String tenantId, String kbId, String query,
                                            int topK, List<String> documentIds) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("Query must not be empty");
        }
        if (topK <= 0) topK = 5;
        if (topK > 50) topK = 50;

        // Resolve databaseId from KB
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        if (kb.getStatus() != KnowledgeBaseStatus.READY) {
            throw new BadRequestException("Knowledge base is not ready. Current status: " + kb.getStatus());
        }
        String databaseId = kb.getDatabaseId();
        if (databaseId == null) {
            throw new BadRequestException("Knowledge base has no backing database");
        }

        // Get query embedding from embedding service
        List<Double> embedding = getQueryEmbedding(query);
        String vectorStr = embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));

        // Resolve user PG connection (direct to compute pod, or via proxy fallback)
        String connstr = resolveComputeConnstr(databaseId, tenantId);
        String jdbcUrl = connstrToJdbc(connstr);
        log.warn("Knowledge search JDBC URL: {}", jdbcUrl);
        System.err.println("Knowledge search JDBC URL: " + jdbcUrl);

        // Build SQL with optional document_id filter
        String docFilter = "";
        if (documentIds != null && !documentIds.isEmpty()) {
            docFilter = " AND document_id = ANY(?)";
        }

        String sql = "WITH semantic AS (" +
                "  SELECT id, content, metadata," +
                "         1 - (embedding <=> ?::vector) AS score," +
                "         ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rank" +
                "  FROM knowledge_chunks" +
                "  WHERE 1=1" + docFilter +
                "  ORDER BY embedding <=> ?::vector" +
                "  LIMIT 20" +
                "), bm25 AS (" +
                "  SELECT id, content, metadata," +
                "         paradedb.score(id) AS score," +
                "         ROW_NUMBER() OVER (ORDER BY paradedb.score(id) DESC) AS rank" +
                "  FROM knowledge_chunks" +
                "  WHERE content @@@ ?" + docFilter +
                "  LIMIT 20" +
                ") " +
                "SELECT COALESCE(s.id, b.id) AS id," +
                "       COALESCE(s.content, b.content) AS content," +
                "       COALESCE(s.metadata, b.metadata)::text AS metadata," +
                "       COALESCE(1.0/(60+s.rank), 0) + COALESCE(1.0/(60+b.rank), 0) AS rrf_score" +
                " FROM semantic s FULL OUTER JOIN bm25 b ON s.id = b.id" +
                " ORDER BY rrf_score DESC" +
                " LIMIT ?";

        // Extract user:pass from connstr if present (proxy path)
        String pgUser = "cloud_admin";
        String pgPass = "cloud-admin-internal";
        String rawConnstr = connstr.replaceFirst("^postgres(ql)?://", "");
        int atIdx = rawConnstr.indexOf('@');
        if (atIdx >= 0) {
            String userInfo = rawConnstr.substring(0, atIdx);
            int colonIdx = userInfo.indexOf(':');
            if (colonIdx >= 0) {
                pgUser = userInfo.substring(0, colonIdx);
                pgPass = userInfo.substring(colonIdx + 1);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            // semantic CTE params
            ps.setString(idx++, vectorStr); // embedding <=> ?::vector (score)
            ps.setString(idx++, vectorStr); // embedding <=> ?::vector (order/row_number)
            if (documentIds != null && !documentIds.isEmpty()) {
                java.sql.Array docArray = conn.createArrayOf("varchar", documentIds.toArray());
                ps.setArray(idx++, docArray);
            }
            ps.setString(idx++, vectorStr); // embedding <=> ?::vector (limit order)
            // bm25 CTE params
            ps.setString(idx++, query);     // content @@@ ?
            if (documentIds != null && !documentIds.isEmpty()) {
                java.sql.Array docArray = conn.createArrayOf("varchar", documentIds.toArray());
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

        return results;
    }

    // ── Internal helpers ────────────────────────────────────────────

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

    /**
     * Resolve connection string for a database.
     * Priority: direct compute pod → internal proxy with credentials → connectionUri fallback.
     */
    private String resolveComputeConnstr(String databaseId, String tenantId) {
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(databaseId, tenantId)
                .orElseThrow(() -> new NotFoundException("Database not found: " + databaseId));

        log.warn("resolveComputeConnstr: db={} status={} computeHost={} dbUser={} neonTenantId={} connUri={}",
                 databaseId, db.getStatus(), db.getComputeHost(), db.getDbUser(), db.getNeonTenantId(),
                 db.getConnectionUri() != null ? db.getConnectionUri().substring(0, Math.min(80, db.getConnectionUri().length())) : null);

        // 1. Direct to compute pod (fastest, used when compute pod IP is known)
        //    Skip if host is the proxy address (proxy needs endpoint ID, different port/auth)
        String host = db.getComputeHost();
        int port = db.getComputePort() != null ? db.getComputePort() : 55433;
        boolean isProxyHost = host != null && (host.contains("dbay.cloud") || host.contains("proxy."));
        if (host != null && !host.isBlank() && !isProxyHost) {
            return "postgresql://cloud_admin@" + host + ":" + port + "/" + db.getName();
        }

        // 2. Via internal proxy with DB credentials (standard path for KB databases)
        String dbUser = db.getDbUser();
        String dbPass = db.getDbPassword();
        String neonTenantId = db.getNeonTenantId();
        if (dbUser != null && neonTenantId != null) {
            String pass = dbPass != null ? dbPass : "";
            return "postgresql://" + dbUser + ":" + pass
                    + "@proxy.lakeon.svc.cluster.local:4432/" + db.getName()
                    + "?options=endpoint=" + neonTenantId + "&sslmode=require";
        }

        // 3. Fallback: parse connectionUri (always available after DB creation)
        // Format: postgres://user_xxx@pg.dbay.cloud:4432/dbname?options=endpoint%3Dxxx
        String connUri = db.getConnectionUri();
        if (connUri != null && !connUri.isBlank()) {
            // URL-decode options: %3D → =
            connUri = connUri.replace("%3D", "=");
            // Use internal proxy instead of external
            connUri = connUri.replace("pg.dbay.cloud:4432", "proxy.lakeon.svc.cluster.local:4432");
            // Inject password into URI (proxy auth requires it)
            String dbPassword = db.getDbPassword();
            if (dbPassword != null && !connUri.contains(":" + dbPassword + "@")) {
                // postgres://user@host → postgres://user:pass@host
                connUri = connUri.replaceFirst("://([^:@]+)@", "://$1:" + dbPassword + "@");
            }
            if (!connUri.contains("sslmode=")) {
                connUri += (connUri.contains("?") ? "&" : "?") + "sslmode=require";
            }
            log.info("resolveComputeConnstr: via connectionUri for db {}", databaseId);
            return connUri;
        }

        throw new BadRequestException("Database has no connection info. id=" + databaseId + " status=" + db.getStatus());
    }

    private String connstrToJdbc(String connstr) {
        // Convert postgresql://user@host:port/dbname?params to jdbc:postgresql://host:port/dbname?params
        String withoutScheme = connstr.replaceFirst("^postgres(ql)?://", "");
        // Remove user part
        int atIdx = withoutScheme.indexOf('@');
        if (atIdx >= 0) {
            withoutScheme = withoutScheme.substring(atIdx + 1);
        }
        return "jdbc:postgresql://" + withoutScheme;
    }

    @SuppressWarnings("unchecked")
    private List<Double> getQueryEmbedding(String query) {
        String apiUrl = props.getKnowledge().getEmbeddingApiUrl();
        String apiKey = props.getKnowledge().getEmbeddingApiKey();
        String model = props.getKnowledge().getEmbeddingModel();

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
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private void deleteObsFile(String obsKey) {
        try (S3Presigner presigner = buildPresigner()) {
            // Use S3Client for deletion instead of presigner
        }
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of("cn-north-4"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
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
        if (lower.endsWith(".md")) return "MARKDOWN";
        if (lower.endsWith(".txt")) return "TEXT";
        return null;
    }
}
