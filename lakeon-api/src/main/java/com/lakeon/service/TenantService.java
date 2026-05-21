package com.lakeon.service;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.LoginRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.ApiKeyEntity;
import com.lakeon.model.entity.InviteCodeEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.event.TenantChangedEvent;
import com.lakeon.repository.ApiKeyRepository;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TenantService {
    private static final int MAX_API_KEYS = 10;
    private final TenantRepository tenantRepository;
    private final DatabaseRepository databaseRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final LakeonProperties props;
    private final ApplicationEventPublisher events;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public TenantService(TenantRepository tenantRepository, DatabaseRepository databaseRepository,
                         ApiKeyRepository apiKeyRepository, InviteCodeRepository inviteCodeRepository,
                         LakeonProperties props, ApplicationEventPublisher events) {
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.props = props;
        this.events = events;
    }

    public boolean isUsernameAvailable(String username) {
        if (username == null || username.isBlank()) return false;
        return tenantRepository.findByUsername(username.trim()).isEmpty();
    }

    public List<Map<String, String>> searchUsers(String keyword) {
        if (keyword == null || keyword.length() < 2) return List.of();
        return tenantRepository.findByUsernameContainingIgnoreCaseOrderByUsernameAsc(keyword).stream()
                .limit(10)
                .map(t -> Map.of("username", t.getUsername(), "name", t.getName()))
                .toList();
    }

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        // Validate invite code if required
        InviteCodeEntity inviteCode = null;
        if (props.getInviteRequired()) {
            String code = request.inviteCode();
            if (code == null || code.isBlank()) {
                throw new BadRequestException("Invite code is required for registration");
            }
            inviteCode = inviteCodeRepository.findById(code.trim().toUpperCase()).orElse(null);
            if (inviteCode == null || !inviteCode.isValid()) {
                throw new BadRequestException("Invalid or expired invite code");
            }
        }

        tenantRepository.findByUsername(request.username()).ifPresent(existing -> {
            throw new ConflictException("Username '" + request.username() + "' already exists");
        });

        TenantEntity entity = new TenantEntity();
        entity.setName(request.username());
        entity.setUsername(request.username());
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity = tenantRepository.save(entity);

        // Also create an entry in api_keys table
        ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setTenantId(entity.getId());
        apiKeyEntity.setName("Default");
        apiKeyEntity.setApiKey(entity.getApiKey());
        apiKeyRepository.save(apiKeyEntity);

        // Increment invite code usage
        if (inviteCode != null) {
            inviteCode.incrementUsed();
            inviteCodeRepository.save(inviteCode);
        }

        events.publishEvent(new TenantChangedEvent(entity.getId(), TenantChangedEvent.ChangeType.CREATED));
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

    public TenantResponse getForLogin(String tenantId) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        return toResponse(entity);
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
        // Check api_keys table first (new multi-key model)
        return apiKeyRepository.findByApiKey(apiKey)
            .map(ak -> tenantRepository.findById(ak.getTenantId()).orElse(null))
            // Fallback to legacy tenants.api_key column
            .orElseGet(() -> tenantRepository.findByApiKey(apiKey).orElse(null));
    }

    // ── Multi API Key Management ──

    public List<Map<String, Object>> listApiKeys(String tenantId) {
        return apiKeyRepository.findAllByTenantIdOrderByCreatedAtAsc(tenantId).stream()
            .map(this::apiKeyToMap)
            .toList();
    }

    @Transactional
    public Map<String, Object> createApiKey(String tenantId, String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("API Key name is required");
        }
        if (name.length() > 64) {
            throw new BadRequestException("API Key name must be 64 characters or less");
        }
        long count = apiKeyRepository.countByTenantId(tenantId);
        if (count >= MAX_API_KEYS) {
            throw new BadRequestException("Maximum " + MAX_API_KEYS + " API keys per tenant");
        }
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity = apiKeyRepository.save(entity);

        // Return full key only on creation
        Map<String, Object> result = apiKeyToMap(entity);
        result.put("api_key", entity.getApiKey());
        return result;
    }

    @Transactional
    public void deleteApiKey(String tenantId, String keyId) {
        ApiKeyEntity entity = apiKeyRepository.findById(keyId)
            .orElseThrow(() -> new NotFoundException("API Key not found: " + keyId));
        if (!entity.getTenantId().equals(tenantId)) {
            throw new NotFoundException("API Key not found: " + keyId);
        }
        // Prevent deleting the last key
        long count = apiKeyRepository.countByTenantId(tenantId);
        if (count <= 1) {
            throw new BadRequestException("Cannot delete the last API key");
        }
        apiKeyRepository.delete(entity);
    }

    private Map<String, Object> apiKeyToMap(ApiKeyEntity entity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entity.getId());
        m.put("name", entity.getName());
        m.put("masked_key", maskKey(entity.getApiKey()));
        m.put("created_at", entity.getCreatedAt());
        return m;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 10) return "***";
        return key.substring(0, 6) + "..." + key.substring(key.length() - 4);
    }

    @Transactional
    public TenantResponse disableTenant(String tenantId) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        entity.setDisabled(true);
        entity.setDisabledAt(java.time.Instant.now());
        entity = tenantRepository.save(entity);
        int dbCount = databaseRepository.findAllByTenantId(tenantId).size();
        events.publishEvent(new TenantChangedEvent(tenantId, TenantChangedEvent.ChangeType.UPDATED));
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
        events.publishEvent(new TenantChangedEvent(tenantId, TenantChangedEvent.ChangeType.UPDATED));
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
        events.publishEvent(new TenantChangedEvent(tenantId, TenantChangedEvent.ChangeType.UPDATED));
        return toResponseWithDisabled(entity, dbCount);
    }

    @Transactional
    public void changePassword(String tenantId, String currentPassword, String newPassword) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        if (!passwordEncoder.matches(currentPassword, entity.getPasswordHash())) {
            throw new BadRequestException("当前密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new BadRequestException("新密码长度不能少于 6 位");
        }
        entity.setPasswordHash(passwordEncoder.encode(newPassword));
        tenantRepository.save(entity);
    }

    @Transactional
    public TenantResponse updateProfile(String tenantId, String name) {
        TenantEntity entity = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        entity = tenantRepository.save(entity);
        events.publishEvent(new TenantChangedEvent(tenantId, TenantChangedEvent.ChangeType.UPDATED));
        return toResponse(entity);
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
            .username(entity.getUsername())
            .apiKey(entity.getApiKey())
            .createdAt(entity.getCreatedAt())
            .maxDatabases(entity.getMaxDatabases())
            .maxStorageGb(entity.getMaxStorageGb())
            .maxComputeCu(entity.getMaxComputeCu())
            .disabled(entity.getDisabled())
            .disabledAt(entity.getDisabledAt())
            .trial(entity.getTrial())
            .expiresAt(entity.getExpiresAt())
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
            .trial(entity.getTrial())
            .expiresAt(entity.getExpiresAt())
            .build();
    }
}
