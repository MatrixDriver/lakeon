package com.lakeon.repository;

import com.lakeon.model.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {
    Optional<ApiKeyEntity> findByApiKey(String apiKey);
    List<ApiKeyEntity> findAllByTenantIdOrderByCreatedAtAsc(String tenantId);
    long countByTenantId(String tenantId);
}
