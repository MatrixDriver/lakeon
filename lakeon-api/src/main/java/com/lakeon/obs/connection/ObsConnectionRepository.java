package com.lakeon.obs.connection;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ObsConnectionRepository extends JpaRepository<ObsConnectionEntity, String> {
    List<ObsConnectionEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<ObsConnectionEntity> findByIdAndTenantId(String id, String tenantId);
}
