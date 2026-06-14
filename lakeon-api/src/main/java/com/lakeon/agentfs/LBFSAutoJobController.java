package com.lakeon.agentfs;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lbfs/folders/{folderId}/auto-jobs")
public class LBFSAutoJobController {

    private final LBFSAutoJobRepository jobRepository;

    public LBFSAutoJobController(LBFSAutoJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest req, @PathVariable String folderId) {
        String tenantId = getTenant(req).getId();
        return Map.of(
                "auto_jobs",
                jobRepository.findByTenantIdAndFolderIdOrderByCreatedAtDesc(tenantId, folderId)
                        .stream()
                        .map(this::toMap)
                        .toList());
    }

    private TenantEntity getTenant(HttpServletRequest req) {
        TenantEntity t = (TenantEntity) req.getAttribute("tenant");
        if (t == null) throw new BadRequestException("no authenticated tenant");
        return t;
    }

    private Map<String, Object> toMap(LBFSAutoJobEntity job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.getId());
        m.put("folder_id", job.getFolderId());
        m.put("source_path", job.getSourcePath());
        m.put("source_etag", job.getSourceEtag());
        m.put("profile", job.getProfile());
        m.put("status", job.getStatus());
        m.put("attempts", job.getAttempts());
        m.put("last_error", job.getLastError());
        m.put("started_at", job.getStartedAt());
        m.put("finished_at", job.getFinishedAt());
        m.put("created_at", job.getCreatedAt());
        m.put("updated_at", job.getUpdatedAt());
        return m;
    }
}
