package com.lakeon.pageserver;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PageserverRebalanceEventRepository extends JpaRepository<PageserverRebalanceEventEntity, String> {
    List<PageserverRebalanceEventEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
