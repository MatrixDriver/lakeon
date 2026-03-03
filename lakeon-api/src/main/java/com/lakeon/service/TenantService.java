package com.lakeon.service;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {
    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        tenantRepository.findByName(request.name()).ifPresent(existing -> {
            throw new ConflictException("Tenant '" + request.name() + "' already exists");
        });

        TenantEntity entity = new TenantEntity();
        entity.setName(request.name());
        entity = tenantRepository.save(entity);

        return toResponse(entity);
    }

    public TenantResponse get(String tenantId) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        // Don't return API key on GET - only on creation
        return TenantResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .createdAt(entity.getCreatedAt())
            .build();
    }

    public TenantEntity authenticateByApiKey(String apiKey) {
        return tenantRepository.findByApiKey(apiKey).orElse(null);
    }

    private TenantResponse toResponse(TenantEntity entity) {
        return TenantResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .apiKey(entity.getApiKey())
            .createdAt(entity.getCreatedAt())
            .build();
    }
}
