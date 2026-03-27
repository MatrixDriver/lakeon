package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    private final LakeonProperties props;
    private final DatabaseService databaseService;
    private final TenantRepository tenantRepository;
    private final DatabaseRepository databaseRepository;

    public TrialService(LakeonProperties props, DatabaseService databaseService,
                        TenantRepository tenantRepository, DatabaseRepository databaseRepository) {
        this.props = props;
        this.databaseService = databaseService;
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
    }

    /**
     * Ensure the demo tenant exists on startup if LAKEON_DEMO_TENANT_ID is configured.
     * The demo tenant is shared by all trial users (read-only).
     * SRE can manage it via Admin API; it is protected from batch deletion.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureDemoTenant() {
        String demoTenantId = props.getDemo().getTenantId();
        if (demoTenantId == null || demoTenantId.isBlank()) {
            return;
        }

        Optional<TenantEntity> existing = tenantRepository.findById(demoTenantId);
        if (existing.isPresent()) {
            TenantEntity t = existing.get();
            // Fix api_key if it was created without one
            if (t.getApiKey() == null || t.getApiKey().isBlank()) {
                t.prePersist();
                tenantRepository.save(t);
                log.info("Demo tenant {} — generated missing api_key", demoTenantId);
            }
            log.info("Demo tenant {} already exists (name={}, api_key={}...)",
                    demoTenantId, t.getName(),
                    t.getApiKey() != null && t.getApiKey().length() > 10 ? t.getApiKey().substring(0, 10) : "null");
            return;
        }

        // Create the demo tenant
        TenantEntity demo = new TenantEntity();
        demo.setId(demoTenantId);
        demo.setName("demo");
        demo.setUsername("demo");
        demo.setPasswordHash("");
        demo.setTrial(false);  // not a trial tenant — it's permanent
        demo.setMaxDatabases(10);
        demo.setMaxStorageGb(10);
        demo.setMaxComputeCu(2);
        // Manually trigger prePersist since Spring Data may call merge() when id is pre-set
        demo.prePersist();
        demo = tenantRepository.save(demo);

        log.info("Created demo tenant: {} (api_key={})", demo.getId(), demo.getApiKey());
    }

    /**
     * Create a trial tenant. No invite code needed, no database created.
     * Trial expires after 24 hours and is auto-cleaned.
     */
    @Transactional
    public Map<String, Object> createTrial() {
        String trialId = UUID.randomUUID().toString().substring(0, 8);
        String username = "trial_" + trialId;

        TenantEntity tenant = new TenantEntity();
        tenant.setName(username);
        tenant.setUsername(username);
        tenant.setPasswordHash("");
        tenant.setTrial(true);
        tenant.setExpiresAt(Instant.now().plus(TRIAL_HOURS, ChronoUnit.HOURS));
        tenant.setMaxDatabases(0);
        tenant.setMaxStorageGb(0);
        tenant.setMaxComputeCu(0);
        tenant = tenantRepository.save(tenant);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", tenant.getId());
        result.put("api_key", tenant.getApiKey());
        result.put("username", username);
        result.put("trial", true);
        result.put("expires_at", tenant.getExpiresAt());
        result.put("expires_in_hours", TRIAL_HOURS);

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
