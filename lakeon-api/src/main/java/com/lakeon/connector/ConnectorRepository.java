package com.lakeon.connector;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectorRepository extends JpaRepository<ConnectorEntity, String> {
    List<ConnectorEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);

    List<ConnectorEntity> findAllByTenantIdAndTypeOrderByUpdatedAtDesc(String tenantId, ConnectorType type);

    Optional<ConnectorEntity> findByIdAndTenantId(String id, String tenantId);

    long countByTenantIdAndType(String tenantId, ConnectorType type);
}
