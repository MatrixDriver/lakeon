package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.dataset.DatasetEntity;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.dataset.DatasetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiScriptService 单元测试")
class AiScriptServiceTest {

    @Mock
    private DatasetRepository datasetRepository;

    private AiScriptService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String tenantId = "tenant_test";

    @BeforeEach
    void setUp() {
        LakeonProperties props = new LakeonProperties();
        service = new AiScriptService(props, datasetRepository, objectMapper);
    }

    private DatasetEntity makeDataset(String id, String name, Long rowCount, String schemaJson) {
        DatasetEntity ds = new DatasetEntity();
        ds.setId(id);
        ds.setTenantId(tenantId);
        ds.setName(name);
        ds.setRowCount(rowCount);
        ds.setSchemaJson(schemaJson);
        ds.setStatus(DatasetStatus.READY);
        return ds;
    }

    @Nested
    @DisplayName("buildDatasetContext")
    class BuildDatasetContextTests {

        @Test
        @DisplayName("schema available — output contains dataset name, env var, and column names")
        void withSchema() {
            DatasetEntity ds = makeDataset("ds_001", "sales data", 1000L,
                "[{\"name\":\"order_id\",\"type\":\"int64\"},{\"name\":\"amount\",\"type\":\"float64\"}]");

            when(datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, DatasetStatus.READY))
                .thenReturn(List.of(ds));

            String context = service.buildDatasetContext(tenantId);

            assertThat(context).contains("sales data");
            assertThat(context).contains("DATASET_PATH_sales_data");
            assertThat(context).contains("order_id");
            assertThat(context).contains("amount");
            assertThat(context).contains("int64");
            assertThat(context).contains("float64");
            assertThat(context).contains("1000");
        }

        @Test
        @DisplayName("null schema — output contains 'unavailable' message")
        void withNullSchema() {
            DatasetEntity ds = makeDataset("ds_002", "raw_logs", null, null);

            when(datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, DatasetStatus.READY))
                .thenReturn(List.of(ds));

            String context = service.buildDatasetContext(tenantId);

            assertThat(context).contains("raw_logs");
            assertThat(context).contains("DATASET_PATH_raw_logs");
            assertThat(context).contains("unavailable");
        }

        @Test
        @DisplayName("no datasets — returns 'none available'")
        void withNoDatasets() {
            when(datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, DatasetStatus.READY))
                .thenReturn(List.of());

            String context = service.buildDatasetContext(tenantId);

            assertThat(context).isEqualTo("none available");
        }
    }

    @Nested
    @DisplayName("findUsedDatasets")
    class FindUsedDatasetsTests {

        @Test
        @DisplayName("exact env var match — correct dataset returned")
        void exactEnvVarMatch() {
            DatasetEntity ds1 = makeDataset("ds_001", "sales data", null, null);
            DatasetEntity ds2 = makeDataset("ds_002", "user events", null, null);
            List<DatasetEntity> datasets = List.of(ds1, ds2);

            String script = "import os\npath = os.environ['DATASET_PATH_sales_data']\n";

            List<String> used = service.findUsedDatasets(datasets, script);

            assertThat(used).containsExactly("ds_001");
            assertThat(used).doesNotContain("ds_002");
        }

        @Test
        @DisplayName("multiple env vars matched — all referenced datasets returned")
        void multipleMatches() {
            DatasetEntity ds1 = makeDataset("ds_001", "sales data", null, null);
            DatasetEntity ds2 = makeDataset("ds_002", "user events", null, null);
            List<DatasetEntity> datasets = List.of(ds1, ds2);

            String script = "p1 = os.environ['DATASET_PATH_sales_data']\np2 = os.environ['DATASET_PATH_user_events']\n";

            List<String> used = service.findUsedDatasets(datasets, script);

            assertThat(used).containsExactlyInAnyOrder("ds_001", "ds_002");
        }

        @Test
        @DisplayName("no specific match + single dataset + generic DATASET_PATH — fallback to that dataset")
        void fallbackToSingleDataset() {
            DatasetEntity ds = makeDataset("ds_001", "my dataset", null, null);
            List<DatasetEntity> datasets = List.of(ds);

            String script = "path = os.environ['DATASET_PATH']\n";

            List<String> used = service.findUsedDatasets(datasets, script);

            assertThat(used).containsExactly("ds_001");
        }

        @Test
        @DisplayName("no match and multiple datasets — empty list returned")
        void noMatchMultipleDatasets() {
            DatasetEntity ds1 = makeDataset("ds_001", "sales", null, null);
            DatasetEntity ds2 = makeDataset("ds_002", "events", null, null);
            List<DatasetEntity> datasets = List.of(ds1, ds2);

            String script = "# no dataset path references here\n";

            List<String> used = service.findUsedDatasets(datasets, script);

            assertThat(used).isEmpty();
        }
    }
}
