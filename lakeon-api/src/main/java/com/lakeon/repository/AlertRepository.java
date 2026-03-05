package com.lakeon.repository;

import com.lakeon.model.entity.AlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AlertRepository extends JpaRepository<AlertEntity, String> {
    List<AlertEntity> findAllByOrderByFiredAtDesc();
    List<AlertEntity> findByStatusOrderByFiredAtDesc(String status);
    List<AlertEntity> findByRuleNameAndStatus(String ruleName, String status);
}
