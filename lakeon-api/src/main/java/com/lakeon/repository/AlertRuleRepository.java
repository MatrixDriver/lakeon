package com.lakeon.repository;

import com.lakeon.model.entity.AlertRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, String> {
    List<AlertRuleEntity> findByEnabledTrue();
}
