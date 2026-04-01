package com.lakeon.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DatasetVersionRepository extends JpaRepository<DatasetVersionEntity, String> {
    List<DatasetVersionEntity> findByDatasetIdOrderByVersionDesc(String datasetId);
    Optional<DatasetVersionEntity> findByDatasetIdAndVersion(String datasetId, Integer version);
}
