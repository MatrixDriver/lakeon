package com.lakeon.service;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.LoginRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
public class TenantService {
    private final TenantRepository tenantRepository;
    private final DatabaseRepository databaseRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public TenantService(TenantRepository tenantRepository, DatabaseRepository databaseRepository) {
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
    }

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        tenantRepository.findByUsername(request.username()).ifPresent(existing -> {
            throw new ConflictException("Username '" + request.username() + "' already exists");
        });

        TenantEntity entity = new TenantEntity();
        entity.setName(request.username());
        entity.setUsername(request.username());
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity = tenantRepository.save(entity);

        return toResponse(entity);
    }

    public TenantResponse login(LoginRequest request) {
        TenantEntity entity = tenantRepository.findByUsername(request.username()).orElse(null);
        if (entity == null || !passwordEncoder.matches(request.password(), entity.getPasswordHash())) {
            return null;
        }
        if (Boolean.TRUE.equals(entity.getDisabled())) {
            return null;
        }
        return toResponse(entity);
    }

    public TenantResponse get(String tenantId) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        int dbCount = databaseRepository.findAllByTenantId(tenantId).size();
        return toResponseWithDisabled(entity, dbCount);
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
    public TenantResponse disableTenant(String tenantId) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        entity.setDisabled(true);
        entity.setDisabledAt(java.time.Instant.now());
        entity = tenantRepository.save(entity);
        int dbCount = databaseRepository.findAllByTenantId(tenantId).size();
        return toResponseWithDisabled(entity, dbCount);
    }

    @Transactional
    public TenantResponse enableTenant(String tenantId) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        entity.setDisabled(false);
        entity.setDisabledAt(null);
        entity = tenantRepository.save(entity);
        int dbCount = databaseRepository.findAllByTenantId(tenantId).size();
        return toResponseWithDisabled(entity, dbCount);
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
        return toResponseWithDisabled(entity, dbCount);
    }

    public java.util.List<TenantResponse> listAll() {
        return tenantRepository.findAll().stream()
            .map(entity -> {
                int dbCount = databaseRepository.findAllByTenantId(entity.getId()).size();
                return toResponseWithDisabled(entity, dbCount);
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
            .disabled(entity.getDisabled())
            .disabledAt(entity.getDisabledAt())
            .build();
    }

    private TenantResponse toResponseWithDisabled(TenantEntity entity, int dbCount) {
        return TenantResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .apiKey(entity.getApiKey())
            .createdAt(entity.getCreatedAt())
            .maxDatabases(entity.getMaxDatabases())
            .maxStorageGb(entity.getMaxStorageGb())
            .maxComputeCu(entity.getMaxComputeCu())
            .databaseCount(dbCount)
            .disabled(entity.getDisabled())
            .disabledAt(entity.getDisabledAt())
            .build();
    }
}
