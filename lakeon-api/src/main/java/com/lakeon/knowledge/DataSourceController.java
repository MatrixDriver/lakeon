package com.lakeon.knowledge;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.obs.ObsStsService;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/knowledge/{kbId}/datasources")
public class DataSourceController {

    private final DataSourceRepository dsRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final ObsStsService obsStsService;
    private final LakeonProperties props;
    private final DataSourceSyncService syncService;
    private final KnowledgeService knowledgeService;

    public DataSourceController(DataSourceRepository dsRepository,
                                 DocumentRepository documentRepository,
                                 KnowledgeBaseRepository kbRepository,
                                 ObsStsService obsStsService,
                                 LakeonProperties props,
                                 DataSourceSyncService syncService,
                                 KnowledgeService knowledgeService) {
        this.dsRepository = dsRepository;
        this.documentRepository = documentRepository;
        this.kbRepository = kbRepository;
        this.obsStsService = obsStsService;
        this.props = props;
        this.syncService = syncService;
        this.knowledgeService = knowledgeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest req,
                                       @PathVariable String kbId,
                                       @RequestBody Map<String, String> body) {
        TenantEntity tenant = getTenant(req);
        String name = body.get("name");
        if (name == null || name.isBlank()) throw new BadRequestException("name is required");

        kbRepository.findByIdAndTenantId(kbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));

        DataSourceEntity ds = new DataSourceEntity();
        ds.setTenantId(tenant.getId());
        ds.setKbId(kbId);
        ds.setName(name);
        ds.prePersist();
        ds.setObsPrefix("datasources/" + tenant.getId() + "/" + ds.getId() + "/");
        dsRepository.save(ds);

        return toResponse(ds);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req, @PathVariable String kbId) {
        TenantEntity tenant = getTenant(req);
        return dsRepository.findByTenantIdAndKbId(tenant.getId(), kbId).stream()
            .map(this::toResponse).toList();
    }

    @GetMapping("/{dsId}")
    public Map<String, Object> get(HttpServletRequest req,
                                    @PathVariable String kbId,
                                    @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));
        return toResponse(ds);
    }

    @DeleteMapping("/{dsId}")
    public Map<String, Object> delete(HttpServletRequest req,
                                       @PathVariable String kbId,
                                       @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));
        List<DocumentEntity> docs = documentRepository.findByDatasourceId(dsId);
        for (DocumentEntity doc : docs) {
            knowledgeService.deleteDocumentInternal(doc);
        }
        dsRepository.delete(ds);
        return Map.of("deleted", true);
    }

    @PostMapping("/{dsId}/sync")
    public Map<String, Object> sync(HttpServletRequest req,
                                     @PathVariable String kbId,
                                     @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));
        Map<String, Object> stats = syncService.sync(ds);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("datasource_id", ds.getId());
        response.put("status", ds.getStatus().name());
        response.put("sync_stats", stats);
        return response;
    }

    @GetMapping("/{dsId}/credentials")
    public Map<String, Object> getCredentials(HttpServletRequest req,
                                               @PathVariable String kbId,
                                               @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));

        ObsStsService.StsCredentials creds = obsStsService.getCredentials(tenant.getId());
        String bucket = props.getObs().getBucket();
        String endpoint = props.getObs().getEndpoint().replace("https://", "");
        String obsPath = "obs://" + bucket + "/" + ds.getObsPrefix();

        return Map.of(
            "endpoint", endpoint,
            "bucket", bucket,
            "prefix", ds.getObsPrefix(),
            "access_key", creds.accessKey(),
            "secret_key", creds.secretKey(),
            "security_token", creds.sessionToken(),
            "expires_at", creds.expiresAt().toString(),
            "upload_commands", Map.of(
                "hcloud", "hcloud obs cp ./my-docs/ " + obsPath + " -r -f -e " + endpoint
                    + " -i " + creds.accessKey() + " -k " + creds.secretKey() + " -t " + creds.sessionToken(),
                "obsutil", "obsutil cp ./my-docs/ " + obsPath + " -r -f -e " + endpoint
                    + " -i " + creds.accessKey() + " -k " + creds.secretKey() + " -t " + creds.sessionToken()
            )
        );
    }

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }

    private Map<String, Object> toResponse(DataSourceEntity ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ds.getId());
        m.put("kb_id", ds.getKbId());
        m.put("name", ds.getName());
        m.put("obs_prefix", ds.getObsPrefix());
        m.put("status", ds.getStatus().name());
        m.put("file_count", ds.getFileCount());
        m.put("last_synced_at", ds.getLastSyncedAt());
        m.put("last_sync_stats", ds.getLastSyncStats());
        m.put("error", ds.getError());
        m.put("created_at", ds.getCreatedAt());
        return m;
    }
}
