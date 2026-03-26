package com.lakeon.memory;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.CreateDatabaseRequest;
import com.lakeon.model.dto.DatabaseResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Service
public class MemoryService {

    private final MemoryBaseRepository repository;
    private final MemoryDbHelper dbHelper;
    private final LakeonProperties props;
    private final RestTemplate restTemplate;
    private final DatabaseService databaseService;
    private final TenantRepository tenantRepository;

    public MemoryService(MemoryBaseRepository repository,
                         MemoryDbHelper dbHelper,
                         LakeonProperties props,
                         @org.springframework.context.annotation.Lazy DatabaseService databaseService,
                         TenantRepository tenantRepository) {
        this.repository = repository;
        this.dbHelper = dbHelper;
        this.props = props;
        this.databaseService = databaseService;
        this.tenantRepository = tenantRepository;
        this.restTemplate = new RestTemplate();
    }

    public List<MemoryBaseEntity> listBases(String tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public MemoryBaseEntity getBase(String tenantId, String id) {
        var mem = repository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new NotFoundException("Memory base not found: " + id));
        // Lazy sync: if PROVISIONING, check if backing database is now ACTIVE
        if ("PROVISIONING".equals(mem.getStatus()) && mem.getDatabaseId() != null) {
            dbHelper.trySyncStatus(mem);
        }
        return mem;
    }

    public MemoryBaseEntity createBase(TenantEntity tenant, String name, String description,
                                        MemoryBaseType type, String embeddingModel, boolean oneLlmMode) {
        String tenantId = tenant.getId();
        // Use a generated slug for DB name (ASCII-safe, avoids HTTP header encoding issues with Chinese names)
        String dbSlug = "mem_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var dbRequest = new CreateDatabaseRequest(dbSlug, null, null, null);
        DatabaseResponse dbResp = databaseService.create(tenant, dbRequest);

        var entity = new MemoryBaseEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setType(type);
        entity.setEmbeddingModel(embeddingModel != null ? embeddingModel : "BAAI/bge-m3");
        entity.setOneLlmMode(oneLlmMode);
        entity.setDatabaseId(dbResp.getId());
        entity.setStatus("PROVISIONING");
        entity = repository.save(entity);
        return entity;
    }

    public void deleteBase(String tenantId, String id) {
        var entity = getBase(tenantId, id);
        repository.delete(entity);
    }

    // ── Proxy methods to Python memory microservice ──────────

    private void ensureSchemaInitialized(String connstr) {
        String url = props.getMemory().getServiceUrl() + "/init";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Database-Connstr", connstr);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(null, headers), Object.class);
        } catch (Exception e) {
            // Schema init is idempotent; log but don't fail
            log.warn("Schema init call failed (may already be initialized): {}", e.getMessage());
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemoryService.class);

    public Object proxyPost(String tenantId, String memId, String path, Object body) {
        MemoryBaseEntity mem = getBase(tenantId, memId);
        String connstr = dbHelper.resolveConnstr(tenantId, memId);
        ensureSchemaInitialized(connstr);
        String url = props.getMemory().getServiceUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Database-Connstr", connstr);
        headers.set("X-One-Llm-Mode", String.valueOf(Boolean.TRUE.equals(mem.getOneLlmMode())));
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<?> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Object> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
        return resp.getBody();
    }

    public Object proxyGet(String tenantId, String memId, String path, Map<String, String> params) {
        String connstr = dbHelper.resolveConnstr(tenantId, memId);
        ensureSchemaInitialized(connstr);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(props.getMemory().getServiceUrl() + path);
        if (params != null) {
            params.forEach((k, v) -> { if (v != null) builder.queryParam(k, v); });
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Database-Connstr", connstr);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        ResponseEntity<Object> resp = restTemplate.exchange(builder.toUriString(), HttpMethod.GET, entity, Object.class);
        return resp.getBody();
    }

    public Object proxyDelete(String tenantId, String memId, String path) {
        String connstr = dbHelper.resolveConnstr(tenantId, memId);
        String url = props.getMemory().getServiceUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Database-Connstr", connstr);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        ResponseEntity<Object> resp = restTemplate.exchange(url, HttpMethod.DELETE, entity, Object.class);
        return resp.getBody();
    }
}
