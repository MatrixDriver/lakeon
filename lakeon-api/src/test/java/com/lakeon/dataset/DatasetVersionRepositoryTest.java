package com.lakeon.dataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("DatasetVersionRepository 数据访问层测试")
class DatasetVersionRepositoryTest {

    @Autowired
    private DatasetVersionRepository datasetVersionRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Test
    @DisplayName("UT-DSV-001: save — 正常保存版本实体，ID 自动生成")
    void save_generatesId() {
        // Given
        DatasetEntity dataset = createDataset("tn_test001", "test-ds");
        dataset = datasetRepository.save(dataset);

        DatasetVersionEntity version = new DatasetVersionEntity();
        version.setDatasetId(dataset.getId());
        version.setVersion(1);
        version.setFormat("PARQUET");
        version.setStatus("READY");
        version.setObsPath("datasets/tn_test001/ds_001/v1/data.parquet");
        version.setRowCount(1000L);
        version.setFileSize(50000L);

        // When
        DatasetVersionEntity saved = datasetVersionRepository.save(version);

        // Then
        assertThat(saved.getId()).isNotNull().startsWith("dsv_");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getFormat()).isEqualTo("PARQUET");
    }

    @Test
    @DisplayName("UT-DSV-002: findByDatasetIdOrderByVersionDesc — 返回按版本降序排列的列表")
    void findByDatasetId_orderedByVersionDesc() {
        // Given
        DatasetEntity dataset = datasetRepository.save(createDataset("tn_test002", "ds-ordered"));
        String dsId = dataset.getId();

        datasetVersionRepository.save(createVersion(dsId, 1, "READY"));
        datasetVersionRepository.save(createVersion(dsId, 2, "READY"));
        datasetVersionRepository.save(createVersion(dsId, 3, "CREATING"));

        // When
        List<DatasetVersionEntity> versions = datasetVersionRepository.findByDatasetIdOrderByVersionDesc(dsId);

        // Then
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).getVersion()).isEqualTo(3);
        assertThat(versions.get(1).getVersion()).isEqualTo(2);
        assertThat(versions.get(2).getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("UT-DSV-003: findByDatasetIdAndVersion — 存在，返回匹配版本")
    void findByDatasetIdAndVersion_found() {
        // Given
        DatasetEntity dataset = datasetRepository.save(createDataset("tn_test003", "ds-find"));
        String dsId = dataset.getId();
        datasetVersionRepository.save(createVersion(dsId, 1, "READY"));
        datasetVersionRepository.save(createVersion(dsId, 2, "CREATING"));

        // When
        var result = datasetVersionRepository.findByDatasetIdAndVersion(dsId, 2);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getVersion()).isEqualTo(2);
        assertThat(result.get().getDatasetId()).isEqualTo(dsId);
    }

    @Test
    @DisplayName("UT-DSV-004: findByDatasetIdAndVersion — 不存在，返回 empty")
    void findByDatasetIdAndVersion_notFound() {
        // When
        var result = datasetVersionRepository.findByDatasetIdAndVersion("ds_nonexist", 99);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("UT-DSV-005: 不同 dataset 的版本互不干扰")
    void findByDatasetId_isolation() {
        // Given
        DatasetEntity dsA = datasetRepository.save(createDataset("tn_test005", "ds-a"));
        DatasetEntity dsB = datasetRepository.save(createDataset("tn_test005", "ds-b"));

        datasetVersionRepository.save(createVersion(dsA.getId(), 1, "READY"));
        datasetVersionRepository.save(createVersion(dsA.getId(), 2, "READY"));
        datasetVersionRepository.save(createVersion(dsB.getId(), 1, "READY"));

        // When
        List<DatasetVersionEntity> versionsA = datasetVersionRepository.findByDatasetIdOrderByVersionDesc(dsA.getId());
        List<DatasetVersionEntity> versionsB = datasetVersionRepository.findByDatasetIdOrderByVersionDesc(dsB.getId());

        // Then
        assertThat(versionsA).hasSize(2);
        assertThat(versionsB).hasSize(1);
    }

    private DatasetEntity createDataset(String tenantId, String name) {
        DatasetEntity entity = new DatasetEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setSourceType(DatasetSourceType.DB_EXPORT);
        entity.setStatus(DatasetStatus.DRAFT);
        return entity;
    }

    private DatasetVersionEntity createVersion(String datasetId, int version, String status) {
        DatasetVersionEntity entity = new DatasetVersionEntity();
        entity.setDatasetId(datasetId);
        entity.setVersion(version);
        entity.setFormat("PARQUET");
        entity.setStatus(status);
        entity.setObsPath("datasets/test/" + datasetId + "/v" + version + "/data.parquet");
        return entity;
    }
}
