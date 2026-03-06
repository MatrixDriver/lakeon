package com.lakeon.repository;

import com.lakeon.model.entity.ImportTableTaskEntity;
import com.lakeon.model.enums.ImportTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ImportTableTaskRepository extends JpaRepository<ImportTableTaskEntity, String> {
    List<ImportTableTaskEntity> findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc(String importTaskId);
    List<ImportTableTaskEntity> findAllByImportTaskIdAndStatus(String importTaskId, ImportTaskStatus status);
    long countByImportTaskIdAndStatus(String importTaskId, ImportTaskStatus status);
}
