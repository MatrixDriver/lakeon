package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DataSourceRepository extends JpaRepository<DataSourceEntity, String> {
    List<DataSourceEntity> findByTenantIdAndKbId(String tenantId, String kbId);
    Optional<DataSourceEntity> findByIdAndTenantId(String id, String tenantId);
}
