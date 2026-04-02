package com.lakeon.obs.connection;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/obs-connections")
public class ObsConnectionController {

    private final ObsConnectionService service;
    private final LakeonProperties props;

    public ObsConnectionController(ObsConnectionService service, LakeonProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        ObsConnectionEntity conn = service.create(
                tenant.getId(),
                (String) body.get("name"),
                (String) body.get("domain_name"),
                (String) body.get("agency_name"),
                (String) body.get("obs_endpoint"),
                (String) body.get("bucket"),
                (String) body.get("base_path"));
        return toResponse(conn);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return service.list(tenant.getId()).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return toResponse(service.get(tenant.getId(), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        service.delete(tenant.getId(), id);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> testConnection(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        ObsConnectionEntity conn = service.testConnection(tenant.getId(), id);
        return toResponse(conn);
    }

    @GetMapping("/{id}/browse")
    public List<Map<String, Object>> browse(HttpServletRequest req,
                                             @PathVariable String id,
                                             @RequestParam(required = false) String path) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return service.browseFiles(tenant.getId(), id, path);
    }

    /**
     * Returns the DBay platform's Huawei Cloud account ID for agency setup guidance.
     */
    @GetMapping("/platform-info")
    public Map<String, Object> platformInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("hwcloud_account_id", props.getHwcloud().getAccountId());
        info.put("region", props.getObs().getRegion());
        return info;
    }

    private Map<String, Object> toResponse(ObsConnectionEntity conn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", conn.getId());
        m.put("name", conn.getName());
        m.put("domain_name", conn.getDomainName());
        m.put("agency_name", conn.getAgencyName());
        m.put("obs_endpoint", conn.getObsEndpoint());
        m.put("bucket", conn.getBucket());
        m.put("base_path", conn.getBasePath());
        m.put("status", conn.getStatus());
        m.put("last_tested_at", conn.getLastTestedAt() != null ? conn.getLastTestedAt().toString() : null);
        m.put("created_at", conn.getCreatedAt() != null ? conn.getCreatedAt().toString() : null);
        m.put("updated_at", conn.getUpdatedAt() != null ? conn.getUpdatedAt().toString() : null);
        return m;
    }
}
