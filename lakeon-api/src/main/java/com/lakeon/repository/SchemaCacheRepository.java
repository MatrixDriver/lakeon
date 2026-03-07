package com.lakeon.repository;

import com.lakeon.model.entity.SchemaCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SchemaCacheRepository extends JpaRepository<SchemaCacheEntity, Long> {

    List<SchemaCacheEntity> findByDatabaseIdOrderBySchemaNameAscTableNameAsc(String databaseId);

    List<SchemaCacheEntity> findByDatabaseIdAndSchemaNameOrderByTableNameAsc(String databaseId, String schemaName);

    @Query("SELECT e FROM SchemaCacheEntity e WHERE e.databaseId = ?1 AND e.schemaName = ?2 AND e.tableName = ?3")
    java.util.Optional<SchemaCacheEntity> findByDatabaseIdAndSchemaNameAndTableName(String databaseId, String schemaName, String tableName);

    @Query("SELECT MAX(e.lastUpdated) FROM SchemaCacheEntity e WHERE e.databaseId = ?1")
    Instant findLatestUpdateTime(String databaseId);

    @Modifying
    @Query("DELETE FROM SchemaCacheEntity e WHERE e.databaseId = ?1")
    void deleteByDatabaseId(String databaseId);
}
