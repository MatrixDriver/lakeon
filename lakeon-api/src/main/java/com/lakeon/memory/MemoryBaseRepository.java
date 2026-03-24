package com.lakeon.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemoryBaseRepository extends JpaRepository<MemoryBaseEntity, String> {
    List<MemoryBaseEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<MemoryBaseEntity> findByIdAndTenantId(String id, String tenantId);
}
