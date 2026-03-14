package com.lakeon.repository;

import com.lakeon.model.entity.InviteCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InviteCodeRepository extends JpaRepository<InviteCodeEntity, String> {
    List<InviteCodeEntity> findAllByOrderByCreatedAtDesc();
}
