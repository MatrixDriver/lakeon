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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatasetService unit tests")
class DatasetServiceTest {

    @Mock private DatasetRepository datasetRepository;
    @Mock private ComputeLifecycleService computeLifecycleService;
    @Mock private DatabaseRepository databaseRepository;
    @Mock private DatabaseQueryService databaseQueryService;
    @Mock private JobService jobService;
    @Mock private TenantRepository tenantRepository;

    private LakeonProperties props;
    private DatasetService datasetService;

    private static final String TENANT_ID = "tn_test001";
    private static final String DB_ID = "db_test001";

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        props.getObs().setEndpoint("https://obs.cn-north-4.myhuaweicloud.com");
        props.getObs().setBucket("test-bucket");
        props.getObs().setAccessKey("test-ak");
        props.getObs().setSecretKey("test-sk");

        datasetService = new DatasetService(
                datasetRepository, computeLifecycleService, databaseRepository,
                databaseQueryService, jobService, props, tenantRepository);
    }

    // ─── create ─────────────────────────────────────────────────────

    @Test
    @DisplayName("create with TABLE_SELECT generates correct SQL")
    void create_tableSelect_generatesCorrectSql() {
        when(datasetRepository.save(any(DatasetEntity.class)))
                .thenAnswer(inv -> {
                    DatasetEntity e = inv.getArgument(0);
                    e.setId("ds_test001");
                    e.setCreatedAt(Instant.now());
                    e.setUpdatedAt(Instant.now());
                    return e;
                });

        List<Map<String, Object>> tables = List.of(
                Map.of("name", "users", "columns", List.of("id", "name", "email")));

        DatasetEntity result = datasetService.create(TENANT_ID, "My Dataset", "desc",
                DB_ID, "TABLE_SELECT", tables, null);

        assertThat(result.getSourceSql()).isEqualTo("SELECT \"id\", \"name\", \"email\" FROM \"users\"");
        assertThat(result.getStatus()).isEqualTo(DatasetStatus.DRAFT);
        assertThat(result.getSourceType()).isEqualTo(DatasetSourceType.DB_EXPORT);
        assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("create with TABLE_SELECT and no columns generates SELECT *")
    void create_tableSelect_noColumns_generatesSelectStar() {
        when(datasetRepository.save(any(DatasetEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<Map<String, Object>> tables = List.of(Map.of("name", "orders"));

        DatasetEntity result = datasetService.create(TENANT_ID, "Orders", null,
                DB_ID, "TABLE_SELECT", tables, null);

        assertThat(result.getSourceSql()).isEqualTo("SELECT * FROM \"orders\"");
    }

    @Test
    @DisplayName("create with CUSTOM_SQL validates and saves SQL")
    void create_customSql_validatesAndSaves() {
        when(datasetRepository.save(any(DatasetEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DatasetEntity result = datasetService.create(TENANT_ID, "Custom", null,
                DB_ID, "CUSTOM_SQL", null, "SELECT id, name FROM users WHERE active = true");

        assertThat(result.getSourceSql()).isEqualTo("SELECT id, name FROM users WHERE active = true");
    }

    @Test
    @DisplayName("create with CUSTOM_SQL rejects non-SELECT")
    void create_customSql_rejectsNonSelect() {
        assertThatThrownBy(() ->
                datasetService.create(TENANT_ID, "Bad", null, DB_ID, "CUSTOM_SQL", null,
                        "DELETE FROM users"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only SELECT");
    }

    @Test
    @DisplayName("create with CUSTOM_SQL rejects multiple statements")
    void create_customSql_rejectsMultipleStatements() {
        assertThatThrownBy(() ->
                datasetService.create(TENANT_ID, "Bad", null, DB_ID, "CUSTOM_SQL", null,
                        "SELECT 1; DROP TABLE users"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Multiple statements");
    }

    @Test
    @DisplayName("create with CUSTOM_SQL rejects blank SQL")
    void create_customSql_rejectsBlankSql() {
        assertThatThrownBy(() ->
                datasetService.create(TENANT_ID, "Bad", null, DB_ID, "CUSTOM_SQL", null, "  "))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SQL is required");
    }

    @Test
    @DisplayName("create rejects blank name")
    void create_rejectsBlankName() {
        assertThatThrownBy(() ->
                datasetService.create(TENANT_ID, "", null, DB_ID, "TABLE_SELECT",
                        List.of(Map.of("name", "t")), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("create rejects null name")
    void create_rejectsNullName() {
        assertThatThrownBy(() ->
                datasetService.create(TENANT_ID, null, null, DB_ID, "TABLE_SELECT",
                        List.of(Map.of("name", "t")), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("create with invalid queryMode throws BadRequestException")
    void create_invalidQueryMode() {
        assertThatThrownBy(() ->
                datasetService.create(TENANT_ID, "DS", null, DB_ID, "INVALID", null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid queryMode");
    }

    // ─── getDataset ─────────────────────────────────────────────────

    @Test
    @DisplayName("getDataset returns entity when found")
    void getDataset_returnsEntity() {
        DatasetEntity entity = new DatasetEntity();
        entity.setId("ds_found");
        entity.setTenantId(TENANT_ID);

        when(datasetRepository.findByIdAndTenantId("ds_found", TENANT_ID))
                .thenReturn(Optional.of(entity));

        DatasetEntity result = datasetService.getDataset(TENANT_ID, "ds_found");
        assertThat(result.getId()).isEqualTo("ds_found");
    }

    @Test
    @DisplayName("getDataset throws NotFoundException when missing")
    void getDataset_throwsNotFoundException() {
        when(datasetRepository.findByIdAndTenantId("ds_missing", TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> datasetService.getDataset(TENANT_ID, "ds_missing"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ds_missing");
    }

    // ─── listDatasets ───────────────────────────────────────────────

    @Test
    @DisplayName("listDatasets without status filter delegates to repository")
    void listDatasets_noFilter() {
        List<DatasetEntity> expected = List.of(new DatasetEntity());
        when(datasetRepository.findAllByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                .thenReturn(expected);

        List<DatasetEntity> result = datasetService.listDatasets(TENANT_ID, null);
        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("listDatasets with status filter delegates to repository")
    void listDatasets_withStatusFilter() {
        List<DatasetEntity> expected = List.of(new DatasetEntity());
        when(datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(TENANT_ID, DatasetStatus.READY))
                .thenReturn(expected);

        List<DatasetEntity> result = datasetService.listDatasets(TENANT_ID, DatasetStatus.READY);
        assertThat(result).isSameAs(expected);
    }

    // ─── triggerExport ──────────────────────────────────────────────

    @Test
    @DisplayName("triggerExport submits job and updates dataset")
    void triggerExport_success() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId("ds_export001");
        dataset.setTenantId(TENANT_ID);
        dataset.setDatabaseId(DB_ID);
        dataset.setSourceSql("SELECT * FROM \"orders\"");
        dataset.setStatus(DatasetStatus.DRAFT);

        when(datasetRepository.findByIdAndTenantId("ds_export001", TENANT_ID))
                .thenReturn(Optional.of(dataset));

        DatabaseEntity db = new DatabaseEntity();
        db.setId(DB_ID);
        db.setName("mydb");
        when(databaseRepository.findById(DB_ID)).thenReturn(Optional.of(db));

        when(computeLifecycleService.wakeCompute(DB_ID)).thenReturn("10.0.1.50:5432");

        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        JobEntity job = new JobEntity();
        job.setId("job_exp001");
        when(jobService.submitJob(eq(tenant), eq(JobType.EXPORT_PARQUET), any()))
                .thenReturn(job);

        when(datasetRepository.save(any(DatasetEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DatasetEntity result = datasetService.triggerExport(TENANT_ID, "ds_export001");

        assertThat(result.getStatus()).isEqualTo(DatasetStatus.EXPORTING);
        assertThat(result.getJobId()).isEqualTo("job_exp001");
        assertThat(result.getObsPath()).isEqualTo("datasets/" + TENANT_ID + "/ds_export001/data.parquet");

        // Verify job params
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jobService).submitJob(eq(tenant), eq(JobType.EXPORT_PARQUET), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertThat(params.get("database_connstr")).asString()
                .contains("cloud_admin").contains("10.0.1.50:5432").contains("mydb");
        assertThat(params.get("source_sql")).isEqualTo("SELECT * FROM \"orders\"");
        assertThat(params.get("obs_output_path")).asString().contains("ds_export001");

        // Verify db lastActiveAt was refreshed
        verify(databaseRepository).save(db);
    }

    @Test
    @DisplayName("triggerExport rejects non-DRAFT dataset")
    void triggerExport_rejectsNonDraft() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId("ds_ready");
        dataset.setTenantId(TENANT_ID);
        dataset.setStatus(DatasetStatus.READY);

        when(datasetRepository.findByIdAndTenantId("ds_ready", TENANT_ID))
                .thenReturn(Optional.of(dataset));

        assertThatThrownBy(() -> datasetService.triggerExport(TENANT_ID, "ds_ready"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DRAFT");
    }

    // ─── deleteDataset ──────────────────────────────────────────────

    @Test
    @DisplayName("deleteDataset removes entity")
    void deleteDataset_success() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId("ds_del001");
        dataset.setTenantId(TENANT_ID);
        dataset.setObsPath(null);

        when(datasetRepository.findByIdAndTenantId("ds_del001", TENANT_ID))
                .thenReturn(Optional.of(dataset));

        datasetService.deleteDataset(TENANT_ID, "ds_del001");

        verify(datasetRepository).delete(dataset);
    }

    @Test
    @DisplayName("deleteDataset throws NotFoundException for missing dataset")
    void deleteDataset_notFound() {
        when(datasetRepository.findByIdAndTenantId("ds_missing", TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> datasetService.deleteDataset(TENANT_ID, "ds_missing"))
                .isInstanceOf(NotFoundException.class);
    }

    // ─── preview ────────────────────────────────────────────────────

    @Test
    @DisplayName("preview returns columns, rows, total_count, preview_sql")
    void preview_returnsExpectedShape() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(computeLifecycleService.wakeCompute(DB_ID)).thenReturn("10.0.1.50:5432");

        QueryResult previewResult = new QueryResult(
                List.of("id", "name"), List.of(List.of(1, "Alice")), 1, 10L, true);
        QueryResult countResult = new QueryResult(
                List.of("count"), List.of(List.of(42L)), 1, 5L, true);

        when(databaseQueryService.executeQuery(eq(tenant), eq(DB_ID), contains("LIMIT 10")))
                .thenReturn(previewResult);
        when(databaseQueryService.executeQuery(eq(tenant), eq(DB_ID), contains("COUNT(*)")))
                .thenReturn(countResult);

        List<Map<String, Object>> tables = List.of(
                Map.of("name", "users", "columns", List.of("id", "name")));

        Map<String, Object> result = datasetService.preview(TENANT_ID, DB_ID, "TABLE_SELECT", tables, null);

        assertThat(result.get("columns")).isEqualTo(List.of("id", "name"));
        assertThat(result.get("total_count")).isEqualTo(42L);
        assertThat(result.get("preview_sql")).asString().contains("SELECT");
    }

    // ─── getDatasetResponse ─────────────────────────────────────────

    @Test
    @DisplayName("getDatasetResponse returns basic fields for DRAFT dataset")
    void getDatasetResponse_draft() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId("ds_resp001");
        dataset.setTenantId(TENANT_ID);
        dataset.setName("Test DS");
        dataset.setStatus(DatasetStatus.DRAFT);
        dataset.setSourceType(DatasetSourceType.DB_EXPORT);
        dataset.setCreatedAt(Instant.now());
        dataset.setUpdatedAt(Instant.now());

        when(datasetRepository.findByIdAndTenantId("ds_resp001", TENANT_ID))
                .thenReturn(Optional.of(dataset));

        Map<String, Object> result = datasetService.getDatasetResponse(TENANT_ID, "ds_resp001");

        assertThat(result.get("id")).isEqualTo("ds_resp001");
        assertThat(result.get("name")).isEqualTo("Test DS");
        assertThat(result.get("status")).isEqualTo(DatasetStatus.DRAFT);
        assertThat(result).doesNotContainKey("download_url");
        assertThat(result).doesNotContainKey("code_snippets");
    }

    @Test
    @DisplayName("getDatasetResponse includes download_url and code_snippets for READY dataset")
    void getDatasetResponse_ready_includesDownloadUrl() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId("ds_resp002");
        dataset.setTenantId(TENANT_ID);
        dataset.setName("Ready DS");
        dataset.setStatus(DatasetStatus.READY);
        dataset.setSourceType(DatasetSourceType.DB_EXPORT);
        dataset.setObsPath("datasets/tn_test001/ds_resp002/data.parquet");
        dataset.setCreatedAt(Instant.now());
        dataset.setUpdatedAt(Instant.now());

        when(datasetRepository.findByIdAndTenantId("ds_resp002", TENANT_ID))
                .thenReturn(Optional.of(dataset));

        Map<String, Object> result = datasetService.getDatasetResponse(TENANT_ID, "ds_resp002");

        // The presigned URL generation will fall back to plain URL in test (no real OBS)
        assertThat(result).containsKey("download_url");
        assertThat(result).containsKey("code_snippets");

        @SuppressWarnings("unchecked")
        Map<String, String> snippets = (Map<String, String>) result.get("code_snippets");
        assertThat(snippets).containsKeys("pandas", "ray", "duckdb");
        assertThat(snippets.get("pandas")).contains("read_parquet");
        assertThat(snippets.get("ray")).contains("ray.data.read_parquet");
        assertThat(snippets.get("duckdb")).contains("duckdb");
    }
}
