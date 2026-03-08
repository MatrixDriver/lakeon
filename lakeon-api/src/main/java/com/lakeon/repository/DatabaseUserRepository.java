package com.lakeon.repository;

import com.lakeon.model.entity.DatabaseUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DatabaseUserRepository extends JpaRepository<DatabaseUserEntity, String> {

    List<DatabaseUserEntity> findByDatabaseIdOrderByCreatedAtAsc(String databaseId);

    Optional<DatabaseUserEntity> findByDatabaseIdAndUsername(String databaseId, String username);

    Optional<DatabaseUserEntity> findByIdAndTenantId(String id, String tenantId);

    int countByDatabaseId(String databaseId);
}
