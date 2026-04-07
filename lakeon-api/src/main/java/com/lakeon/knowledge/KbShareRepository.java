package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface KbShareRepository extends JpaRepository<KbShareEntity, String> {
    List<KbShareEntity> findAllByKbId(String kbId);
    Optional<KbShareEntity> findByKbIdAndTenantId(String kbId, String tenantId);
    List<KbShareEntity> findAllByTenantId(String tenantId);
    void deleteAllByKbId(String kbId);

    @Query("SELECT s.kbId FROM KbShareEntity s WHERE s.tenantId = :tenantId")
    List<String> findKbIdsByTenantId(String tenantId);
}
