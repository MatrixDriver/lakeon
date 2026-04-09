package com.lakeon.repository;

import com.lakeon.model.entity.OAuthConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OAuthConnectionRepository extends JpaRepository<OAuthConnectionEntity, String> {
    Optional<OAuthConnectionEntity> findByProviderAndProviderUserId(String provider, String providerUserId);
    List<OAuthConnectionEntity> findAllByTenantId(String tenantId);
}
