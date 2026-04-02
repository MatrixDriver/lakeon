package com.lakeon.dataset;

import com.lakeon.config.LakeonProperties;
import com.lakeon.job.JobEntity;
import com.lakeon.job.JobService;
import com.lakeon.job.JobType;
import com.lakeon.model.dto.QueryResult;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.ComputeLifecycleService;
import com.lakeon.service.DatabaseQueryService;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DatasetService {
    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final DatasetRepository datasetRepository;
    private final DatasetVersionRepository datasetVersionRepository;
    private final ComputeLifecycleService computeLifecycleService;
    private final DatabaseRepository databaseRepository;
    private final DatabaseQueryService databaseQueryService;
    private final JobService jobService;
    private final LakeonProperties props;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    public DatasetService(DatasetRepository datasetRepository,
                          DatasetVersionRepository datasetVersionRepository,
                          ComputeLifecycleService computeLifecycleService,
                          DatabaseRepository databaseRepository,
                          DatabaseQueryService databaseQueryService,
                          JobService jobService,
                          LakeonProperties props,
                          TenantRepository tenantRepository,
                          ObjectMapper objectMapper) {
        this.datasetRepository = datasetRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.computeLifecycleService = computeLifecycleService;
        this.databaseRepository = databaseRepository;
        this.databaseQueryService = databaseQueryService;
        this.jobService = jobService;
        this.props = props;
        this.tenantRepository = tenantRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new dataset in DRAFT status.
     */
    @Transactional
    public DatasetEntity create(String tenantId, String name, String description,
                                String databaseId, String queryMode,
                                List<Map<String, Object>> tables, String sql) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Dataset name is required");
        }

        String sourceSql = resolveSourceSql(queryMode, tables, sql);

        DatasetEntity entity = new DatasetEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setDatabaseId(databaseId);
        entity.setSourceSql(sourceSql);
        try {
            entity.setSourceTables(tables != null ? objectMapper.writeValueAsString(tables) : null);
        } catch (Exception e) {
            entity.setSourceTables(null);
        }
        entity.setSourceType(DatasetSourceType.DB_EXPORT);
        entity.setStatus(DatasetStatus.DRAFT);

        datasetRepository.save(entity);
        log.info("Created dataset {} for tenant {}", entity.getId(), tenantId);
        return entity;
    }

    /**
     * Preview query results: returns columns, rows (limit 10), total count, and the SQL used.
     */
    public Map<String, Object> preview(String tenantId, String databaseId,
                                       String queryMode, List<Map<String, Object>> tables, String sql) {
        String sourceSql = resolveSourceSql(queryMode, tables, sql);
        TenantEntity tenant = findTenant(tenantId);

        computeLifecycleService.wakeCompute(databaseId);

        String previewSql = sourceSql + " LIMIT 10";
        QueryResult previewResult = databaseQueryService.executeQuery(tenant, databaseId, previewSql);

        String countSql = "SELECT COUNT(*) FROM (" + sourceSql + ") sub";
        QueryResult countResult = databaseQueryService.executeQuery(tenant, databaseId, countSql);

        long totalCount = 0;
        if (!countResult.rows().isEmpty() && !countResult.rows().get(0).isEmpty()) {
            Object val = countResult.rows().get(0).get(0);
            totalCount = val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", previewResult.columns());
        result.put("rows", previewResult.rows());
        result.put("total_count", totalCount);
        result.put("preview_sql", sourceSql);
        return result;
    }

    /**
     * Trigger Parquet export for a DRAFT dataset.
     */
    @Transactional
    public DatasetEntity triggerExport(String tenantId, String datasetId) {
        DatasetEntity dataset = findDataset(tenantId, datasetId);
        if (dataset.getStatus() != DatasetStatus.DRAFT) {
            throw new BadRequestException("Dataset must be in DRAFT status to export, current: " + dataset.getStatus());
        }

        DatabaseEntity db = databaseRepository.findById(dataset.getDatabaseId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dataset.getDatabaseId()));

        computeLifecycleService.wakeCompute(db.getId());

        // Re-read to get updated compute info
        db = databaseRepository.findById(db.getId()).orElseThrow();
        db.setLastActiveAt(Instant.now());
        databaseRepository.save(db);

        // Use pod IP directly — CCE overlay network ensures pod IPs are routable cluster-wide
        String computeHost = db.getComputeHost();
        int computePort = db.getComputePort() != null ? db.getComputePort() : 55433;
        String connstr = "postgresql://cloud_admin:cloud-admin-internal@" + computeHost + ":" + computePort
                + "/" + db.getName() + "?sslmode=disable";

        String obsPath = "datasets/" + tenantId + "/" + datasetId + "/data.parquet";

        // Populate schema_json for datasets with known source tables
        if (dataset.getSourceTables() != null) {
            try {
                String tableName = dataset.getSourceTables();
                if (tableName.startsWith("[")) {
                    var tables = objectMapper.readValue(tableName,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>(){});
                    if (!tables.isEmpty()) {
                        tableName = (String) tables.get(0).get("name");
                    }
                }
                String schemaQuery = "SELECT column_name, data_type FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position";
                java.util.List<java.util.Map<String, String>> columns = new java.util.ArrayList<>();
                try (var conn = java.sql.DriverManager.getConnection(
                        "jdbc:postgresql://" + computeHost + ":" + computePort + "/" + db.getName()
                                + "?sslmode=disable",
                        "cloud_admin", "cloud-admin-internal");
                     var ps = conn.prepareStatement(schemaQuery)) {
                    ps.setString(1, tableName);
                    var rs = ps.executeQuery();
                    while (rs.next()) {
                        columns.add(java.util.Map.of(
                            "name", rs.getString("column_name"),
                            "type", rs.getString("data_type")));
                    }
                }
                if (!columns.isEmpty()) {
                    dataset.setSchemaJson(objectMapper.writeValueAsString(columns));
                }
            } catch (Exception e) {
                log.warn("Failed to populate schema_json for dataset {}: {}", dataset.getId(), e.getMessage());
            }
        }

        TenantEntity tenant = findTenant(tenantId);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("database_connstr", connstr);
        params.put("source_sql", dataset.getSourceSql());
        params.put("obs_output_path", obsPath);

        JobEntity job = jobService.submitJob(tenant, JobType.EXPORT_PARQUET, params);

        dataset.setStatus(DatasetStatus.EXPORTING);
        dataset.setJobId(job.getId());
        dataset.setObsPath(obsPath);
        datasetRepository.save(dataset);

        log.info("Triggered export for dataset {}, job {}", datasetId, job.getId());
        return dataset;
    }

    /**
     * List datasets for a tenant, optionally filtered by status.
     */
    public List<DatasetEntity> listDatasets(String tenantId, DatasetStatus status) {
        if (status != null) {
            return datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
        }
        return datasetRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * Get a dataset by ID, scoped to tenant.
     */
    public DatasetEntity getDataset(String tenantId, String datasetId) {
        return findDataset(tenantId, datasetId);
    }

    /**
     * Get a detailed dataset response including download URL and code snippets when READY.
     */
    public Map<String, Object> getDatasetResponse(String tenantId, String datasetId) {
        DatasetEntity dataset = findDataset(tenantId, datasetId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", dataset.getId());
        response.put("name", dataset.getName());
        response.put("description", dataset.getDescription());
        response.put("source_type", dataset.getSourceType());
        response.put("database_id", dataset.getDatabaseId());
        response.put("source_sql", dataset.getSourceSql());
        response.put("status", dataset.getStatus());
        response.put("job_id", dataset.getJobId());
        response.put("obs_path", dataset.getObsPath());
        response.put("row_count", dataset.getRowCount());
        response.put("file_size", dataset.getFileSize());
        response.put("file_count", dataset.getFileCount());
        response.put("error", dataset.getError());
        response.put("created_at", dataset.getCreatedAt());
        response.put("updated_at", dataset.getUpdatedAt());

        // Schema (column names + types from source table)
        if (dataset.getSchemaJson() != null) {
            try {
                response.put("schema", objectMapper.readValue(dataset.getSchemaJson(), List.class));
            } catch (Exception e) {
                response.put("schema", null);
            }
        }

        if (dataset.getStatus() == DatasetStatus.READY && dataset.getObsPath() != null) {
            String downloadUrl = generatePresignedUrl(dataset.getObsPath());
            response.put("download_url", downloadUrl);
            response.put("code_snippets", buildCodeSnippets(downloadUrl, dataset.getObsPath()));
        }

        return response;
    }

    /**
     * Delete a dataset and its OBS file if present.
     */
    @Transactional
    public void deleteDataset(String tenantId, String datasetId) {
        DatasetEntity dataset = findDataset(tenantId, datasetId);

        if (dataset.getObsPath() != null) {
            deleteObsFile(dataset.getObsPath());
        }

        datasetRepository.delete(dataset);
        log.info("Deleted dataset {} for tenant {}", datasetId, tenantId);
    }

    /**
     * List versions for a dataset, verifying tenant ownership.
     */
    public List<Map<String, Object>> listVersions(String tenantId, String datasetId) {
        // Verify dataset belongs to tenant
        findDataset(tenantId, datasetId);

        List<DatasetVersionEntity> versions = datasetVersionRepository.findByDatasetIdOrderByVersionDesc(datasetId);
        return versions.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("dataset_id", v.getDatasetId());
            m.put("version", v.getVersion());
            m.put("format", v.getFormat());
            m.put("status", v.getStatus());
            m.put("row_count", v.getRowCount());
            m.put("file_size", v.getFileSize());
            m.put("obs_path", v.getObsPath());
            m.put("source_pipeline_run_id", v.getSourcePipelineRunId());
            m.put("source_job_id", v.getSourceJobId());
            m.put("created_at", v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
            return m;
        }).toList();
    }

    /**
     * Generate presigned upload URLs for a FILE_UPLOAD dataset.
     */
    @Transactional
    public Map<String, Object> generateUploadUrls(String tenantId, String name, String description,
                                                   List<String> files) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Dataset name is required");
        }
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("At least one file path is required");
        }
        if (files.size() > 200) {
            throw new BadRequestException("Maximum 200 files per upload");
        }
        for (String path : files) {
            if (path.contains("..") || path.startsWith("/")) {
                throw new BadRequestException("Invalid file path: " + path);
            }
        }

        DatasetEntity entity = new DatasetEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setSourceType(DatasetSourceType.FILE_UPLOAD);
        entity.setStatus(DatasetStatus.DRAFT);
        datasetRepository.save(entity);

        String prefix = "datasets/" + tenantId + "/" + entity.getId() + "/";
        entity.setObsPath(prefix);
        datasetRepository.save(entity);

        List<Map<String, Object>> uploads = new ArrayList<>();
        try (S3Presigner presigner = buildPresigner()) {
            for (String path : files) {
                String obsKey = prefix + path;
                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(props.getObs().getBucket())
                        .key(obsKey)
                        .build();
                PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(60))
                        .putObjectRequest(putRequest)
                        .build();
                String uploadUrl = presigner.presignPutObject(presignRequest).url().toString();

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", path);
                entry.put("obs_key", obsKey);
                entry.put("upload_url", uploadUrl);
                entry.put("expires_in", 3600);
                uploads.add(entry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset_id", entity.getId());
        result.put("uploads", uploads);
        log.info("Generated {} upload URLs for dataset {} (tenant {})", files.size(), entity.getId(), tenantId);
        return result;
    }

    /**
     * Finalize a FILE_UPLOAD dataset after files have been uploaded to OBS.
     */
    @Transactional
    public DatasetEntity finalizeUpload(String tenantId, String datasetId) {
        DatasetEntity dataset = findDataset(tenantId, datasetId);
        if (dataset.getSourceType() != DatasetSourceType.FILE_UPLOAD) {
            throw new BadRequestException("Dataset is not a FILE_UPLOAD dataset");
        }
        if (dataset.getStatus() != DatasetStatus.DRAFT) {
            throw new BadRequestException("Dataset must be in DRAFT status to finalize, current: " + dataset.getStatus());
        }

        String prefix = dataset.getObsPath();
        int fileCount = 0;
        long totalSize = 0;

        try (S3Client s3 = buildS3Client()) {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(props.getObs().getBucket())
                    .prefix(prefix)
                    .build();
            var response = s3.listObjectsV2(listRequest);
            for (S3Object obj : response.contents()) {
                fileCount++;
                totalSize += obj.size();
            }
        }

        if (fileCount == 0) {
            throw new BadRequestException("No files found under prefix: " + prefix);
        }

        dataset.setFileCount(fileCount);
        dataset.setFileSize(totalSize);
        dataset.setStatus(DatasetStatus.READY);
        datasetRepository.save(dataset);

        log.info("Finalized dataset {} with {} files, {} bytes", datasetId, fileCount, totalSize);
        return dataset;
    }

    // ─── Private helpers ────────────────────────────────────────────

    private S3Presigner buildPresigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of(props.getObs().getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
                .build();
    }

    private S3Client buildS3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of(props.getObs().getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
                .build();
    }

    private String resolveSourceSql(String queryMode, List<Map<String, Object>> tables, String sql) {
        if ("TABLE_SELECT".equals(queryMode)) {
            return generateTableSelectSql(tables);
        } else if ("CUSTOM_SQL".equals(queryMode)) {
            validateCustomSql(sql);
            return sql.trim();
        } else {
            throw new BadRequestException("Invalid queryMode: " + queryMode + ". Must be TABLE_SELECT or CUSTOM_SQL");
        }
    }

    @SuppressWarnings("unchecked")
    private String generateTableSelectSql(List<Map<String, Object>> tables) {
        if (tables == null || tables.isEmpty()) {
            throw new BadRequestException("At least one table is required for TABLE_SELECT mode");
        }
        if (tables.size() > 1) {
            throw new BadRequestException("TABLE_SELECT mode supports one table at a time");
        }

        Map<String, Object> table = tables.get(0);
        String tableName = (String) table.get("name");
        if (tableName == null || tableName.isBlank()) {
            throw new BadRequestException("Table name is required");
        }

        List<String> columns = (List<String>) table.get("columns");
        String columnsPart;
        if (columns == null || columns.isEmpty()) {
            columnsPart = "*";
        } else {
            columnsPart = columns.stream()
                    .map(c -> "\"" + c.replace("\"", "\"\"") + "\"")
                    .collect(Collectors.joining(", "));
        }

        return "SELECT " + columnsPart + " FROM \"" + tableName.replace("\"", "\"\"") + "\"";
    }

    private void validateCustomSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BadRequestException("SQL is required for CUSTOM_SQL mode");
        }
        String normalized = sql.trim().toLowerCase();
        if (!normalized.startsWith("select")) {
            throw new BadRequestException("Only SELECT statements are allowed");
        }
        if (normalized.contains(";")) {
            throw new BadRequestException("Multiple statements not allowed");
        }
    }

    private DatasetEntity findDataset(String tenantId, String datasetId) {
        return datasetRepository.findByIdAndTenantId(datasetId, tenantId)
                .orElseThrow(() -> new NotFoundException("Dataset not found: " + datasetId));
    }

    private TenantEntity findTenant(String tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
    }

    private String generatePresignedUrl(String obsPath) {
        try (S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of("cn-north-4"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
                .build()) {

            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(obsPath)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(60))
                    .getObjectRequest(getRequest)
                    .build();

            return presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            log.warn("Failed to generate presigned URL for {}, falling back to plain URL: {}", obsPath, e.getMessage());
            return "https://" + props.getObs().getBucket() + "." +
                    props.getObs().getEndpoint().replaceFirst("https?://", "") +
                    "/" + obsPath;
        }
    }

    private Map<String, String> buildCodeSnippets(String downloadUrl, String obsPath) {
        Map<String, String> snippets = new LinkedHashMap<>();

        // Local download — uses presigned URL (safe, time-limited)
        snippets.put("pandas", String.format(
                "import pandas as pd\ndf = pd.read_parquet(\"%s\")\nprint(df.head())", downloadUrl));

        // Job runtime — uses DATASET_PATH env var (no credentials exposed)
        snippets.put("job", "import os, pandas as pd\n"
                + "df = pd.read_parquet(os.environ[\"DATASET_PATH\"])\n"
                + "print(df.head())");

        return snippets;
    }

    private void deleteObsFile(String obsPath) {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of("cn-north-4"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
                .build()) {

            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(obsPath)
                    .build());

            log.info("Deleted OBS file: {}", obsPath);
        } catch (Exception e) {
            log.warn("Failed to delete OBS file {}: {}", obsPath, e.getMessage());
        }
    }
}
