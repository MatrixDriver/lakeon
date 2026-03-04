package com.lakeon.service;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
public class TenantService {
    private final TenantRepository tenantRepository;
    private final DatabaseRepository databaseRepository;

    public TenantService(TenantRepository tenantRepository, DatabaseRepository databaseRepository) {
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
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
        int dbCount = databaseRepository.findAllByTenantId(tenantId).size();
        return TenantResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .createdAt(entity.getCreatedAt())
            .maxDatabases(entity.getMaxDatabases())
            .maxStorageGb(entity.getMaxStorageGb())
            .maxComputeCu(entity.getMaxComputeCu())
            .databaseCount(dbCount)
            .build();
    }

    @Transactional
    public TenantResponse regenerateApiKey(String tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("lk_");
        for (byte b : bytes) sb.append(String.format("%02x", b));
        tenant.setApiKey(sb.toString());
        tenant = tenantRepository.save(tenant);
        return toResponse(tenant);
    }

    public TenantEntity authenticateByApiKey(String apiKey) {
        return tenantRepository.findByApiKey(apiKey).orElse(null);
    }

    @Transactional
    public TenantResponse updateQuota(String tenantId, Integer maxDatabases, Integer maxStorageGb, Integer maxComputeCu) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        if (maxDatabases != null) entity.setMaxDatabases(maxDatabases);
        if (maxStorageGb != null) entity.setMaxStorageGb(maxStorageGb);
        if (maxComputeCu != null) entity.setMaxComputeCu(maxComputeCu);
        entity = tenantRepository.save(entity);
        int dbCount = databaseRepository.findAllByTenantId(tenantId).size();
        return TenantResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .createdAt(entity.getCreatedAt())
            .maxDatabases(entity.getMaxDatabases())
            .maxStorageGb(entity.getMaxStorageGb())
            .maxComputeCu(entity.getMaxComputeCu())
            .databaseCount(dbCount)
            .build();
    }

    public java.util.List<TenantResponse> listAll() {
        return tenantRepository.findAll().stream()
            .map(entity -> {
                int dbCount = databaseRepository.findAllByTenantId(entity.getId()).size();
                return TenantResponse.builder()
                    .id(entity.getId())
                    .name(entity.getName())
                    .createdAt(entity.getCreatedAt())
                    .maxDatabases(entity.getMaxDatabases())
                    .maxStorageGb(entity.getMaxStorageGb())
                    .maxComputeCu(entity.getMaxComputeCu())
                    .databaseCount(dbCount)
                    .build();
            })
            .toList();
    }

    private TenantResponse toResponse(TenantEntity entity) {
        return TenantResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .apiKey(entity.getApiKey())
            .createdAt(entity.getCreatedAt())
            .maxDatabases(entity.getMaxDatabases())
            .maxStorageGb(entity.getMaxStorageGb())
            .maxComputeCu(entity.getMaxComputeCu())
            .build();
    }
}
