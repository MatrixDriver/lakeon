package com.lakeon.agentfs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentFileRepository
        extends JpaRepository<AgentFileEntity, AgentFileEntity.PK> {

    Optional<AgentFileEntity> findByTenantIdAndPath(String tenantId, String path);

    /** List children directly under a prefix (no recursion). */
    @Query("SELECT f FROM AgentFileEntity f " +
           "WHERE f.tenantId = :tenantId " +
           "  AND f.path LIKE CONCAT(:prefix, '%') " +
           "  AND f.path <> :prefix " +
           "ORDER BY f.path")
    List<AgentFileEntity> findByPrefix(@Param("tenantId") String tenantId,
                                       @Param("prefix") String prefix);

    void deleteByTenantIdAndPath(String tenantId, String path);

    long countByTenantId(String tenantId);

    @Query("SELECT COALESCE(SUM(f.size), 0) FROM AgentFileEntity f WHERE f.tenantId = :tenantId")
    long sumSizeByTenantId(@Param("tenantId") String tenantId);

    @Query("SELECT MAX(f.mtimeNs) FROM AgentFileEntity f WHERE f.tenantId = :tenantId")
    Long maxMtimeByTenantId(@Param("tenantId") String tenantId);
}
