package com.lakeon.repository;

import com.lakeon.model.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<TenantEntity, String> {
    Optional<TenantEntity> findByApiKey(String apiKey);
    Optional<TenantEntity> findByName(String name);
    Optional<TenantEntity> findByUsername(String username);
    List<TenantEntity> findByTrialTrueAndExpiresAtBefore(Instant cutoff);
}
