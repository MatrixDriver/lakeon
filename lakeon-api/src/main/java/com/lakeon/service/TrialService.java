package com.lakeon.service;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class TrialService {
    private static final Logger log = LoggerFactory.getLogger(TrialService.class);
    private static final int TRIAL_HOURS = 24;

    private final TenantService tenantService;
    private final DatabaseService databaseService;
    private final TenantRepository tenantRepository;
    private final DatabaseRepository databaseRepository;

    public TrialService(TenantService tenantService, DatabaseService databaseService,
                        TenantRepository tenantRepository, DatabaseRepository databaseRepository) {
        this.tenantService = tenantService;
        this.databaseService = databaseService;
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
    }

    /**
     * Create a trial tenant + database. No invite code needed.
     * Trial expires after 24 hours and is auto-cleaned.
     */
    @Transactional
    public Map<String, Object> createTrial() {
        // Generate trial credentials
        String trialId = UUID.randomUUID().toString().substring(0, 8);
        String username = "trial_" + trialId;
        String password = "Trial" + trialId + "!";

        // Create tenant (bypass invite code check by using internal method)
        TenantEntity tenant = new TenantEntity();
        tenant.setName(username);
        tenant.setUsername(username);
        tenant.setPasswordHash(""); // trial accounts don't need login password
        tenant.setTrial(true);
        tenant.setExpiresAt(Instant.now().plus(TRIAL_HOURS, ChronoUnit.HOURS));
        tenant.setMaxDatabases(1);
        tenant.setMaxStorageGb(1);
        tenant.setMaxComputeCu(1);
        tenant = tenantRepository.save(tenant);

        // Create a database for the trial
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", tenant.getId());
        result.put("api_key", tenant.getApiKey());
        result.put("username", username);
        result.put("expires_at", tenant.getExpiresAt());
        result.put("expires_in_hours", TRIAL_HOURS);

        try {
            var dbResponse = databaseService.create(tenant,
                new com.lakeon.model.dto.CreateDatabaseRequest("trial-db", null, null, null));
            result.put("database", dbResponse);
        } catch (Exception e) {
            log.warn("Failed to create trial database for {}: {}", tenant.getId(), e.getMessage());
            result.put("database_error", e.getMessage());
        }

        log.info("Created trial account: {} (expires {})", tenant.getId(), tenant.getExpiresAt());
        return result;
    }

    /**
     * Clean up expired trial tenants and their databases.
     * Runs every hour.
     */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 60_000)
    public void cleanupExpiredTrials() {
        List<TenantEntity> expired = tenantRepository.findByTrialTrueAndExpiresAtBefore(Instant.now());
        if (expired.isEmpty()) return;

        log.info("Cleaning up {} expired trial accounts", expired.size());
        for (TenantEntity tenant : expired) {
            try {
                // Delete all databases for this tenant
                List<DatabaseEntity> databases = databaseRepository.findAllByTenantId(tenant.getId());
                for (DatabaseEntity db : databases) {
                    try {
                        databaseService.delete(tenant, db.getId());
                    } catch (Exception e) {
                        log.warn("Failed to delete trial database {}: {}", db.getId(), e.getMessage());
                    }
                }
                // Delete the tenant
                tenantRepository.delete(tenant);
                log.info("Cleaned up trial tenant: {}", tenant.getId());
            } catch (Exception e) {
                log.warn("Failed to cleanup trial tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }
}
